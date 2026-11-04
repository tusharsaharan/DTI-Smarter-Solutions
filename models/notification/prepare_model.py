"""Prepare a pinned, offline Android semantic encoder; uses public model assets only.

Install onnxruntime==1.23.2, onnx==1.19.1, transformers and safetensors in an isolated
environment. No device notifications, conversation history or credentials are accepted.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import shutil

import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto
import onnxruntime as ort
from huggingface_hub import hf_hub_download
from safetensors.numpy import load_file
from transformers import AutoTokenizer

REPO = "sentence-transformers/distiluse-base-multilingual-cased-v2"
REVISION = "bfe45d0732ca50787611c0fe107ba278c7f3f889"
ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "apps/mobile/modules/attune-native/android/src/main/assets/notification_semantics"
CACHE = Path(__file__).resolve().parent / ".cache"
MAX_LENGTH = 128

# Training/reference phrases, deliberately separate from held-out evaluation examples.
REFERENCES = {
    "SAFETY_CRITICAL": [
        "I cannot breathe. Please get medical help.", "Someone is badly hurt and needs an ambulance.",
        "There is a fire in the building. Leave immediately.", "I am being followed and I am scared. Please help.",
        "Saans nahi aa rahi, ambulance bulao.", "Accident ho gaya hai, khoon nikal raha hai, help chahiye.",
    ],
    "TIME_SENSITIVE": [
        "The room for today's exam has changed. Go to room 204.", "The train is leaving in five minutes.",
        "Submit your application before the deadline tonight.", "Your meeting starts in ten minutes. Join now.",
        "Aaj viva dus minute mein shuru hoga, lab mein aa jao.", "Submission ki last date aaj hai, form abhi jama karo.",
    ],
    "ACTION_REQUIRED": [
        "Please review the document and send your approval.", "Can you pick me up from the station?",
        "Please bring your identity card to class.", "Could you send the notes I need for studying?",
        "Mujhe station se lene aa sakte ho?", "Kal class mein apni file lekar aana.",
    ],
    "CANCELLED_OR_RESOLVED": [
        "The meeting has been cancelled. No action is needed.", "The deadline was extended; do not submit today.",
        "I found my keys, you do not need to come.", "The issue has been fixed and everything is safe now.",
        "Exam cancel ho gaya, aaj college aane ki zaroorat nahi.", "Sab theek hai ab, call karne ki zaroorat nahi hai.",
    ],
    "INFORMATIONAL": [
        "Here are some photos from yesterday's party.", "I watched a nice movie over the weekend.",
        "Thanks for sending the document. I received it.", "The assignment was submitted last week.",
        "Kal ke function ki photos bhej raha hoon.", "Haan mil gaya, shukriya bhai.",
    ],
    "PROMOTION": [
        "Limited time sale: buy our products at fifty percent off.", "Shop now for exclusive offers and discounts.",
        "Congratulations, claim your prize by clicking this shopping link.", "Subscribe today for our premium marketing offer.",
        "Jaldi khareedo, sirf aaj pachaas pratishat discount!", "Aaj ki sale mein shopping karo aur cashback pao.",
    ],
}


def download(name: str) -> Path:
    return Path(hf_hub_download(REPO, name, revision=REVISION, cache_dir=CACHE))


def prepare() -> None:
    ASSETS.mkdir(parents=True, exist_ok=True)
    source = download("onnx/model_qint8_arm64.onnx")
    model = onnx.load(source)
    print("Encoder outputs:", [o.name for o in model.graph.output], flush=True)
    hidden = model.graph.output[0].name
    dense = load_file(download("2_Dense/model.safetensors"))
    print("Dense parameters:", {k: list(v.shape) for k, v in dense.items()}, flush=True)
    weight = dense["linear.weight"].astype(np.float32)
    bias = dense["linear.bias"].astype(np.float32)
    # SentenceTransformers' ONNX export contains the transformer only. Reproduce the
    # published mean pooling -> Dense -> tanh -> L2 normalization in the same graph.
    if len(model.graph.output[0].type.tensor_type.shape.dim) != 3:
        raise ValueError("Unexpected upstream graph; inspect before changing the export")
    initializers = {
        "attune_axis2": np.array([2], np.int64), "attune_axis1": np.array([1], np.int64),
        "attune_eps": np.array([1e-9], np.float32), "attune_dense_weight": weight.T.copy(),
        "attune_dense_bias": bias,
    }
    model.graph.initializer.extend(numpy_helper.from_array(v, k) for k, v in initializers.items())
    opset = next(o.version for o in model.opset_import if not o.domain)
    nodes = [
        helper.make_node("Cast", ["attention_mask"], ["attune_mask_float"], to=TensorProto.FLOAT),
        (helper.make_node("Unsqueeze", ["attune_mask_float", "attune_axis2"], ["attune_mask"]) if opset >= 13
         else helper.make_node("Unsqueeze", ["attune_mask_float"], ["attune_mask"], axes=[2])),
        helper.make_node("Mul", [hidden, "attune_mask"], ["attune_masked"]),
    ]
    for src, target in [("attune_masked", "attune_sum"), ("attune_mask", "attune_count")]:
        nodes.append(helper.make_node("ReduceSum", [src, "attune_axis1"], [target], keepdims=0) if opset >= 13
                     else helper.make_node("ReduceSum", [src], [target], keepdims=0, axes=[1]))
    nodes += [
        helper.make_node("Max", ["attune_count", "attune_eps"], ["attune_denominator"]),
        helper.make_node("Div", ["attune_sum", "attune_denominator"], ["attune_mean"]),
        helper.make_node("MatMul", ["attune_mean", "attune_dense_weight"], ["attune_linear"]),
        helper.make_node("Add", ["attune_linear", "attune_dense_bias"], ["attune_biased"]),
        helper.make_node("Tanh", ["attune_biased"], ["attune_dense"]),
        helper.make_node("LpNormalization", ["attune_dense"], ["sentence_embedding"], axis=1, p=2),
    ]
    if opset < 11:
        raise ValueError("Unsupported upstream ONNX opset")
    model.graph.node.extend(nodes)
    del model.graph.output[:]
    model.graph.output.append(helper.make_tensor_value_info("sentence_embedding", TensorProto.FLOAT, ["batch", weight.shape[0]]))
    onnx.checker.check_model(model)
    output = ASSETS / "encoder.onnx"
    onnx.save(model, output)
    vocab = download("vocab.txt")
    shutil.copyfile(vocab, ASSETS / "vocab.txt")
    tokenizer = AutoTokenizer.from_pretrained(REPO, revision=REVISION, cache_dir=CACHE)
    session = ort.InferenceSession(str(output), providers=["CPUExecutionProvider"])
    def encode(texts):
        values = tokenizer(texts, padding=True, truncation=True, max_length=MAX_LENGTH, return_tensors="np")
        return session.run(None, {i.name: values[i.name].astype(np.int64) for i in session.get_inputs()})[0]
    reference = [(label, phrase) for label, phrases in REFERENCES.items() for phrase in phrases]
    vectors = encode([p for _, p in reference])
    (ASSETS / "references.json").write_text(json.dumps([
        {"intent": label, "vector": vector.tolist()} for (label, _), vector in zip(reference, vectors)
    ], separators=(",", ":")), encoding="utf-8")
    goldens = ["Urgent, call me now!", "Kal exam cancel ho gaya.", "मुझे अभी मदद चाहिए।", "CAFE café中文", "No need to submit today.", "café cafe\u0301", "a\ue000b\u0001c\u200bd", "[CLS]hello [MASK] [SEP] [PAD] [UNK]", "🚨अभी मदद𠀀中文!"]
    (Path(__file__).parent / "tokenizer_golden.json").write_text(json.dumps([
        {"text": text, "ids": tokenizer(text, truncation=True, max_length=MAX_LENGTH)["input_ids"]} for text in goldens
    ], ensure_ascii=False, indent=2), encoding="utf-8")
    manifest = {"model": REPO, "revision": REVISION, "license": "Apache-2.0", "maxLength": MAX_LENGTH,
        "dimensions": int(weight.shape[0]), "minimumSimilarity": .60, "minimumMargin": .08,
        "note": "Semantic reference similarity, not a calibrated accuracy probability. Abstain outside reference coverage.",
        "files": {p.name: {"sha256": hashlib.sha256(p.read_bytes()).hexdigest(), "bytes": p.stat().st_size}
                  for p in ASSETS.iterdir() if p.name in ("encoder.onnx", "vocab.txt", "references.json")}}
    (ASSETS / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2), flush=True)


if __name__ == "__main__":
    prepare()
