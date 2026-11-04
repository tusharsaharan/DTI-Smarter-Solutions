"""CPU-only held-out evaluation of the exported encoder/reference matcher, not app policy.

Uses the frozen synthetic teacher cases and existing local files only. Does not tune,
download, train, call a GPU/teacher, or claim to reproduce Android rules/context merging.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import statistics
import time

os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"

import numpy as np
import onnxruntime as ort
from transformers import AutoTokenizer


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[3]
    directory = Path(__file__).resolve().parent
    assets = project / "apps/mobile/modules/attune-native/android/src/main/assets/notification_semantics"
    cases_path = directory / "teacher_cases.json"
    manifest = json.loads((assets / "manifest.json").read_text(encoding="utf-8"))
    cases_bytes = cases_path.read_bytes()
    cases = json.loads(cases_bytes)["cases"]
    for name, expected in manifest["files"].items():
        raw = (assets / name).read_bytes()
        if len(raw) != expected["bytes"] or hashlib.sha256(raw).hexdigest() != expected["sha256"]:
            raise ValueError("Asset checksum mismatch: " + name)
    cache = directory.parent / ".cache" / ("models--" + manifest["model"].replace("/", "--"))
    tokenizer = AutoTokenizer.from_pretrained(cache / "snapshots" / manifest["revision"], local_files_only=True)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(assets / "encoder.onnx"), sess_options=options, providers=["CPUExecutionProvider"])
    references = json.loads((assets / "references.json").read_text(encoding="utf-8"))
    vectors = np.asarray([r["vector"] for r in references], dtype=np.float32)
    if vectors.shape[1] != manifest["dimensions"] or not np.isfinite(vectors).all():
        raise ValueError("Invalid reference vectors")
    norms = np.linalg.norm(vectors, axis=1)
    if not np.allclose(norms, 1, atol=1e-4):
        raise ValueError("References must be unit-normalized, as required by Kotlin matcher")
    labels = list(dict.fromkeys(r["intent"] for r in references))
    indices = {label: [i for i, r in enumerate(references) if r["intent"] == label] for label in labels}
    minimum = manifest["minimumSimilarity"]
    margin_minimum = manifest["minimumMargin"]
    rows = []
    for case in cases:
        # Android model infer() encodes the latest bounded text only. Earlier context
        # is deliberately not added here: doing so would change the runtime input.
        text = case["text"][:2048]
        tokenized = tokenizer(text, truncation=True, max_length=manifest["maxLength"], return_tensors="np")
        started = time.perf_counter()
        vector = session.run(["sentence_embedding"], {i.name: tokenized[i.name].astype(np.int64) for i in session.get_inputs()})[0][0]
        elapsed_ms = (time.perf_counter() - started) * 1000
        if not np.isfinite(vector).all() or not np.isclose(np.linalg.norm(vector), 1, atol=1e-4):
            raise ValueError("Exported embedding is invalid or not normalized")
        similarity = vectors @ (vector / np.linalg.norm(vector))
        scores = sorted(((label, float(np.clip(similarity[selected].max(), -1, 1))) for label, selected in indices.items()), key=lambda item: item[1], reverse=True)
        best, runner_up = scores[:2]
        margin = best[1] - runner_up[1]
        predicted = best[0] if best[1] >= minimum and margin >= margin_minimum else "UNCERTAIN"
        rows.append({**case, "predicted": predicted, "correct": predicted == case["expected"], "accepted": predicted != "UNCERTAIN", "similarity": best[1], "margin": margin, "scores": dict(scores), "token_count": int(tokenized["input_ids"].shape[1]), "inference_ms": round(elapsed_ms, 3)})
    labels_with_unknown = labels + ["UNCERTAIN"]
    confusion = {gold: {predicted: 0 for predicted in labels_with_unknown} for gold in labels_with_unknown}
    for row in rows:
        confusion[row["expected"]][row["predicted"]] += 1
    accepted = [r for r in rows if r["accepted"]]
    report = {
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "scope": "CPU-only exported encoder/reference matcher. Latest text only; excludes Android rule composition, prior context resolution and final notification action.",
        "held_out": True, "threshold_tuning": False, "cpu_providers": session.get_providers(),
        "cases_sha256": hashlib.sha256(cases_bytes).hexdigest(), "manifest": manifest,
        "count": len(rows), "correct": sum(r["correct"] for r in rows),
        "accuracy": sum(r["correct"] for r in rows) / len(rows),
        "accepted": len(accepted), "coverage": len(accepted) / len(rows),
        "accepted_accuracy": sum(r["correct"] for r in accepted) / len(accepted) if accepted else None,
        "confusion": confusion,
        "inference_ms": {"median": statistics.median(r["inference_ms"] for r in rows), "max": max(r["inference_ms"] for r in rows)},
        "failures": [r["id"] for r in rows if not r["correct"]], "cases": rows,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(report, indent=2, ensure_ascii=False))
    print(json.dumps({k:report[k] for k in ["count", "correct", "accuracy", "accepted", "coverage", "accepted_accuracy", "failures", "inference_ms"]}))


if __name__ == "__main__":
    main()
