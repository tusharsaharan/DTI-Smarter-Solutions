"""CPU-only frozen-encoder linear intent head, with family-disjoint calibration.

Run with models/.venv/Scripts/python.exe. Reads public, local assets only; there
are no network, teacher, GPU, device-message, or training-on-held-out paths.
The six-way model is intentionally small. UNCERTAIN comes from abstention gates,
not an intent class. Synthetic calibration cannot establish real-message accuracy.
"""
from __future__ import annotations

import argparse
from collections import Counter
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import time

os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"
os.environ["CUDA_VISIBLE_DEVICES"] = ""
os.environ["OMP_NUM_THREADS"] = "2"
os.environ["OPENBLAS_NUM_THREADS"] = "2"

import numpy as np
import onnxruntime as ort
from scipy.optimize import minimize_scalar
from sklearn.linear_model import LogisticRegression
from transformers import AutoTokenizer

CLASSES = ["ACTION_REQUIRED", "SAFETY_CRITICAL", "TIME_SENSITIVE", "CANCELLED_OR_RESOLVED", "INFORMATIONAL", "PROMOTION"]
DIRECTORY = Path(__file__).resolve().parent
ROOT = DIRECTORY.parents[1]
ASSETS = ROOT / "apps/mobile/modules/attune-native/android/src/main/assets/notification_semantics"


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def softmax(logits, temperature=1.0):
    scaled = np.asarray(logits, np.float64) / temperature
    scaled -= scaled.max(axis=1, keepdims=True)
    exps = np.exp(scaled)
    return exps / exps.sum(axis=1, keepdims=True)


def confidence_metrics(probabilities, labels):
    maximum = probabilities.max(axis=1)
    correct = probabilities.argmax(axis=1) == labels
    ece = 0.0
    for lower in np.arange(0.0, 1.0, 0.1):
        selected = (maximum >= lower) & (maximum < lower + .1 + (1e-10 if lower > .89 else 0))
        if selected.any():
            ece += selected.mean() * abs(correct[selected].mean() - maximum[selected].mean())
    target = np.eye(len(CLASSES))[labels]
    return {"count": len(labels), "accuracy": float(correct.mean()), "nll": float(-np.log(np.maximum(probabilities[np.arange(len(labels)), labels], 1e-12)).mean()), "brier": float(np.square(probabilities - target).sum(axis=1).mean()), "ece_10_bins": float(ece)}


class Encoder:
    def __init__(self, manifest):
        cache = DIRECTORY / ".cache" / ("models--" + manifest["model"].replace("/", "--"))
        self.tokenizer = AutoTokenizer.from_pretrained(cache / "snapshots" / manifest["revision"], local_files_only=True)
        self.max_length = manifest["maxLength"]
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        options.log_severity_level = 3
        self.session = ort.InferenceSession(str(ASSETS / "encoder.onnx"), sess_options=options, providers=["CPUExecutionProvider"])
        assert self.session.get_providers() == ["CPUExecutionProvider"]

    def encode(self, cases):
        results = []
        counts = []
        total_ms = 0
        for start in range(0, len(cases), 16):
            texts = [case["text"] for case in cases[start:start + 16]]
            for text in texts:
                if len(text) > 2048:
                    raise ValueError("Text exceeds Android input limit")
                count = len(self.tokenizer(text)["input_ids"])
                if count >= self.max_length:
                    raise ValueError("Text reaches Android conservative truncation boundary")
                counts.append(count)
            values = self.tokenizer(texts, padding=True, truncation=True, max_length=self.max_length, return_tensors="np")
            started = time.perf_counter()
            encoded = self.session.run(["sentence_embedding"], {i.name: values[i.name].astype(np.int64) for i in self.session.get_inputs()})[0]
            total_ms += (time.perf_counter() - started) * 1000
            if encoded.shape != (len(texts), 512) or not np.isfinite(encoded).all() or not np.allclose(np.linalg.norm(encoded, axis=1), 1.0, atol=1e-4):
                raise ValueError("Encoder did not emit finite normalized 512-dimensional embeddings")
            results.append(encoded)
        return np.vstack(results), counts, total_ms


def predict(vectors, head):
    normalized = vectors / np.linalg.norm(vectors, axis=1, keepdims=True)
    logits = normalized @ np.asarray(head["weights"], np.float64).T + np.asarray(head["bias"], np.float64)
    probabilities = softmax(logits, head["temperature"])
    order = np.argsort(-probabilities, axis=1)
    top = probabilities[np.arange(len(vectors)), order[:, 0]]
    margin = top - probabilities[np.arange(len(vectors)), order[:, 1]]
    ood = normalized @ np.asarray(head["supportVectors"], np.float64).T
    accepted = (top >= head["minimumConfidence"]) & (margin >= head["minimumMargin"]) & (ood.max(axis=1) >= head["minimumOODSimilarity"])
    predictions = [CLASSES[index] if valid else "UNCERTAIN" for index, valid in zip(order[:, 0], accepted)]
    return predictions, probabilities, margin, ood.max(axis=1)


def evaluate(cases, vectors, head):
    predictions, probabilities, margins, similarities = predict(vectors, head)
    labels = CLASSES + ["UNCERTAIN"]
    confusion = {gold: {predicted: 0 for predicted in labels} for gold in labels}
    rows = []
    for case, predicted, probs, margin, similarity in zip(cases, predictions, probabilities, margins, similarities):
        gold = case["expected"]
        confusion[gold][predicted] += 1
        rows.append({**case, "predicted": predicted, "raw_prediction": CLASSES[int(probs.argmax())], "correct": predicted == gold, "accepted": predicted != "UNCERTAIN", "confidence": float(probs.max()), "margin": float(margin), "ood_similarity": float(similarity), "probabilities": dict(zip(CLASSES, probs.tolist()))})
    accepted = [row for row in rows if row["accepted"]]
    in_domain = [row for row in rows if row["expected"] != "UNCERTAIN"]
    ood = [row for row in rows if row["expected"] == "UNCERTAIN"]
    known_indices = [i for i, case in enumerate(cases) if case["expected"] != "UNCERTAIN"]
    known_labels = np.asarray([CLASSES.index(cases[i]["expected"]) for i in known_indices])
    return {"count": len(rows), "correct": sum(row["correct"] for row in rows), "accuracy": sum(row["correct"] for row in rows) / len(rows), "accepted": len(accepted), "coverage": len(accepted) / len(rows), "accepted_accuracy": sum(row["correct"] for row in accepted) / len(accepted) if accepted else None, "in_domain_count": len(in_domain), "in_domain_raw_accuracy": sum(row["raw_prediction"] == row["expected"] for row in in_domain) / len(in_domain) if in_domain else None, "in_domain_coverage": sum(row["accepted"] for row in in_domain) / len(in_domain) if in_domain else None, "in_domain_confidence_metrics": confidence_metrics(probabilities[known_indices], known_labels) if known_indices else None, "ood_count": len(ood), "ood_rejection_rate": sum(not row["accepted"] for row in ood) / len(ood) if ood else None, "confusion": confusion, "failures": [row["id"] for row in rows if not row["correct"]], "cases": rows}


def choose_gates(cases, vectors, head):
    """Select only on reserved gate families, never frozen benchmark cases.

    Fixed objective: maximize ID coverage among gates with >=95% accepted
    accuracy and >=93.75% OOD rejection on this small synthetic split. Ties
    favor accuracy and stricter gates. These are empirical constraints, not a
    claimed population guarantee. If infeasible, abstain conservatively.
    """
    _, probabilities, margins, similarity = predict(vectors, head)
    confidence = probabilities.max(axis=1)
    raw = np.asarray([CLASSES[i] for i in probabilities.argmax(axis=1)])
    gold = np.asarray([case["expected"] for case in cases])
    known = gold != "UNCERTAIN"
    options = []
    for minimum_confidence in (.35, .4, .45, .5, .55, .6, .65, .7, .75, .8, .85, .9, .95):
        for minimum_margin in (.05, .1, .15, .2, .25, .3, .4):
            for minimum_ood in (.15, .2, .25, .3, .35, .4, .45, .5, .55, .6):
                accepted = (confidence >= minimum_confidence) & (margins >= minimum_margin) & (similarity >= minimum_ood)
                count = int(accepted.sum())
                if not count:
                    continue
                accepted_accuracy = float((raw[accepted] == gold[accepted]).mean())
                ood_rejection = float((~accepted[~known]).mean())
                if accepted_accuracy >= .95 and ood_rejection >= .9375:
                    options.append((int(accepted[known].sum()), accepted_accuracy, minimum_confidence, minimum_margin, minimum_ood))
    if options:
        chosen = max(options)
        head.update(minimumConfidence=chosen[2], minimumMargin=chosen[3], minimumOODSimilarity=chosen[4])
    else:
        head.update(minimumConfidence=.95, minimumMargin=.4, minimumOODSimilarity=.6)
    return {"search_candidates": 13 * 7 * 10, "feasible_candidates": len(options), "target_accepted_accuracy": .95, "target_ood_rejection": .9375, "objective": "Maximum in-domain coverage, then accepted accuracy, then stricter gates. Fixed grid; no benchmark tuning."}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--evaluate-heldout", action="store_true", help="Only read frozen cases after all training/calibration/export are complete")
    args = parser.parse_args()
    if args.report.exists():
        raise FileExistsError("Refusing to overwrite an evaluation report")
    dataset_path = DIRECTORY / "synthetic_intent_cases.json"
    dataset = json.loads(dataset_path.read_text(encoding="utf-8"))
    cases = dataset["cases"]
    assert len({case["id"] for case in cases}) == len(cases)
    assert len({case["text"].strip().lower() for case in cases}) == len(cases)
    split_families = {split: {case["family"] for case in cases if case["split"] == split} for split in ("train", "temperature", "gates")}
    assert all(not split_families[a] & split_families[b] for a in split_families for b in split_families if a != b)
    manifest_path = ASSETS / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    for name in ("encoder.onnx", "vocab.txt"):
        if sha(ASSETS / name) != manifest["files"][name]["sha256"]:
            raise ValueError("Asset checksum mismatch: " + name)
    encoder = Encoder(manifest)
    vectors, token_counts, encode_ms = encoder.encode(cases)
    train_indices = np.asarray([i for i, case in enumerate(cases) if case["split"] == "train"])
    temperature_indices = np.asarray([i for i, case in enumerate(cases) if case["split"] == "temperature"])
    gate_indices = np.asarray([i for i, case in enumerate(cases) if case["split"] == "gates"])
    train_labels = np.asarray([CLASSES.index(cases[i]["expected"]) for i in train_indices])
    assert len(train_indices) >= 300
    classifier = LogisticRegression(C=10.0, max_iter=1000, solver="lbfgs", random_state=1729)
    classifier.fit(vectors[train_indices], train_labels)
    assert list(classifier.classes_) == list(range(6))
    weights = classifier.coef_.astype(np.float32)
    bias = classifier.intercept_.astype(np.float32)
    supports = []
    for family in sorted(split_families["train"]):
        selected = [i for i in train_indices if cases[i]["family"] == family]
        centroid = vectors[selected].mean(axis=0)
        supports.append((centroid / np.linalg.norm(centroid)).astype(np.float32).tolist())
    calibration_labels = np.asarray([CLASSES.index(cases[i]["expected"]) for i in temperature_indices])
    calibration_logits = vectors[temperature_indices] @ weights.T + bias
    def objective(temperature):
        probabilities = softmax(calibration_logits, temperature)
        return -np.log(np.maximum(probabilities[np.arange(len(calibration_labels)), calibration_labels], 1e-12)).mean()
    temperature_fit = minimize_scalar(objective, bounds=(.5, 5), method="bounded")
    temperature = float(temperature_fit.x)
    head = {"version": 1, "classes": CLASSES, "weights": weights.tolist(), "bias": bias.tolist(), "temperature": temperature, "minimumConfidence": 0.0, "minimumMargin": 0.0, "minimumOODSimilarity": -1.0, "supportVectors": supports, "provenance": {"algorithm": "L2-regularized multinomial logistic regression; frozen 512-dimensional encoder", "trainingC": 10.0, "trainingSeed": 1729, "dataset_sha256": sha(dataset_path), "trainingExamples": len(train_indices), "trainingFamilies": len(split_families["train"]), "temperatureExamples": len(temperature_indices), "gateExamples": len(gate_indices), "encoder_sha256": sha(ASSETS / "encoder.onnx"), "scope": "Public synthetic calibration only; softmax confidence is not established real-notification accuracy."}}
    gate_selection = choose_gates([cases[i] for i in gate_indices], vectors[gate_indices], head)
    head["provenance"]["labelReview"] = "Agent-authored public synthetic labels; not independently human-reviewed."
    head_path = ASSETS / "intent_head.json"
    head_path.write_text(json.dumps(head, separators=(",", ":"), allow_nan=False) + "\n", encoding="utf-8")
    manifest["files"][head_path.name] = {"sha256": sha(head_path), "bytes": head_path.stat().st_size}
    manifest["note"] = "Frozen multilingual encoder with six-class local linear head. Temperature/confidence/margin/OOD gates calibrated on disjoint synthetic families; abstain outside coverage. Confidence is not established real-notification accuracy."
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    report = {"created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(), "scope": "CPU-only exported encoder + six-class head, latest text only; excludes Android rules, context fusion, sender history, notification actions and hardware behavior.", "limitations": ["Synthetic hand-authored examples are limited and can share author/style biases despite scenario-family splits.", "Neither calibration confidence nor accepted accuracy is a population guarantee; no real-notification accuracy or Android performance claim.", "OOD gates cannot guarantee recognition of all adversarial or unsupported input.", "Earlier conversation context is deliberately excluded because Android encodes the latest message only."], "cpu_providers": encoder.session.get_providers(), "dataset_sha256": sha(dataset_path), "encoder_sha256": sha(ASSETS / "encoder.onnx"), "head_sha256": sha(head_path), "manifest_sha256": sha(manifest_path), "splits": {split: {"examples": sum(case["split"] == split for case in cases), "families": len(families), "labels": dict(Counter(case["expected"] for case in cases if case["split"] == split))} for split, families in split_families.items()}, "temperature": {"value": temperature, "before": confidence_metrics(softmax(calibration_logits), calibration_labels), "after": confidence_metrics(softmax(calibration_logits, temperature), calibration_labels)}, "gate_selection": gate_selection, "gates": {key: head[key] for key in ("minimumConfidence", "minimumMargin", "minimumOODSimilarity")}, "training": evaluate([cases[i] for i in train_indices], vectors[train_indices], head), "gate_calibration": evaluate([cases[i] for i in gate_indices], vectors[gate_indices], head), "training_embedding_ms_batched_cpu": encode_ms, "maximum_token_count": max(token_counts)}
    # Export deterministic public synthetic vector/head goldens for cross-language
    # Kotlin float-tolerance parity; no raw phone text is ever accepted here.
    selected_golden = [int(temperature_indices[i]) for i in (0, 8, 16, 24, 32, 40)] + [int(gate_indices[-1]), int(gate_indices[-9])]
    predictions, probabilities, margins, similarities = predict(vectors[selected_golden], head)
    goldens = [{"id": cases[i]["id"], "vector": vectors[i].tolist(), "expectedIntent": prediction, "confidence": float(probs.max()), "margin": float(margin), "oodSimilarity": float(similarity)} for i, prediction, probs, margin, similarity in zip(selected_golden, predictions, probabilities, margins, similarities)]
    (DIRECTORY / "intent_head_golden.json").write_text(json.dumps({"head_sha256": sha(head_path), "cases": goldens}, separators=(",", ":")) + "\n", encoding="utf-8")
    # This is the only held-out file access, after the model and gates are locked.
    if args.evaluate_heldout:
        heldout_path = DIRECTORY / "evaluation/teacher_cases.json"
        heldout_cases = json.loads(heldout_path.read_text(encoding="utf-8"))["cases"]
        heldout_vectors, _, heldout_ms = encoder.encode(heldout_cases)
        report["held_out"] = {"cases_sha256": sha(heldout_path), "training_or_threshold_tuning": False, "head_locked_before_read": True, "embedding_ms_batched_cpu": heldout_ms, **evaluate(heldout_cases, heldout_vectors, head)}
        assert sha(head_path) == report["head_sha256"]
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(report, indent=2, ensure_ascii=False, allow_nan=False) + "\n")
    concise = {"temperature": report["temperature"], "gates": report["gates"], "head_sha256": report["head_sha256"]}
    for split in ("training", "gate_calibration", "held_out"):
        if split in report:
            concise[split] = {key: report[split][key] for key in ("count", "accuracy", "accepted", "accepted_accuracy", "coverage", "in_domain_raw_accuracy", "in_domain_coverage", "ood_rejection_rate", "failures")}
    print(json.dumps(concise))


if __name__ == "__main__":
    main()
