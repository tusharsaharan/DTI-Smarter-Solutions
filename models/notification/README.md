# Offline notification intent head

`train_intent_head.py` trains only a six-class linear classifier on the existing
frozen, normalized 512-dimensional multilingual encoder. Every operation uses
the ONNX Runtime CPU provider and local files. No teacher, network, GPU, or
phone messages are used. `requirements-intent-head.txt` records the tested
Python package versions.

`synthetic_intent_cases.json` contains **agent-authored synthetic labels without
independent human review**. Scenario families are disjoint across the splits:

| Split | Examples | Families | Purpose |
|---|---:|---:|---|
| Training | 384 | 48 | Fixed L2-regularized multinomial classifier |
| Temperature | 48 | 12 | Scalar temperature fitting |
| Gate selection | 80 | 16 | Confidence, margin, OOD gate selection; includes 32 OOD examples |
| Frozen benchmark | 32 | Separate existing file | Evaluation only, read after head export |

The head uses normalized family centroids for the OOD similarity gate. Runtime
requires all three gates: softmax confidence **0.55**, top-two probability
margin **0.25**, maximum support cosine similarity **0.30**. Temperature is
**0.5334432114**. These were fixed before the frozen benchmark was read. OOD
recognition remains incomplete; short references and quoted or adversarial text
can defeat a six-class sentence classifier.

The CPU run in `evaluation/results/intent_head_cpu_20261029.json` found:

| Metric | Synthetic gate calibration | Frozen benchmark |
|---|---:|---:|
| Exact intent agreement, including abstentions | 73/80 (91.25%) | 20/32 (62.5%) |
| Accepted | 44/80 | 22/32 |
| Correct among accepted | 42/44 (95.45%) | 17/22 (77.27%) |
| In-domain coverage | 43/48 (89.58%) | 21/28 (75%) |
| OOD rejected | 31/32 | 3/4 |

Four of five benchmark safety cases were accepted correctly; the contextual
safety follow-up abstained. Five accepted benchmark predictions were wrong,
including confident false urgency and a false safety classification. The
held-out in-domain ECE was **0.206**. Softmax confidence must not be presented
as established real-notification accuracy. The benchmark also excludes Android
context/rule fusion, sender response history, final notification actions and
physical-device performance.

After evaluation, a metadata correction clarified the dataset's agent-authored
provenance. No examples, labels, numerical model values, gates, or predictions
changed. The initial report is preserved. The hash mapping and posthoc
confidence audit are in
`evaluation/results/intent_head_export_audit_20261029.json`. The current exported
head SHA-256 is
`4f44b36523eb24bc2a33a2bffe47e1e5126a4346c7fafe20e313446aa1a258ac`.

To reproduce from existing local encoder/tokenizer assets with a new report:

```powershell
./models/.venv/Scripts/python.exe ./models/notification/build_synthetic_intents.py
./models/.venv/Scripts/python.exe ./models/notification/train_intent_head.py --report ./models/notification/evaluation/results/intent_head_cpu_new.json --evaluate-heldout
./models/.venv/Scripts/python.exe ./models/notification/test_intent_head.py
```

The exporter writes `intent_head.json`, updates its manifest hash, and emits
`intent_head_golden.json` with eight public synthetic embedding vectors for
Kotlin numerical parity tests. It refuses to overwrite reports. The held-out
file must never be used to tune examples, weights, gates or calibration.
If `prepare_model.py` regenerates the base encoder assets, run head training
afterward so the manifest and head bind to that encoder.
