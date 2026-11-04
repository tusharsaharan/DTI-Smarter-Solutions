# Synthetic GPU teacher evaluation

The 2026-10-29 run classified **31 of 32** hand-labelled synthetic cases correctly
(96.875% exact intent agreement). This small, deliberately chosen set is an engineering
contrast check. It does not measure real-notification accuracy or validate the Android
encoder. No real notification text, contacts, conversation history or phone data was sent.

The frozen cases are in `teacher_cases.json`. They must remain separate from training
examples and reference prototypes. `run_gpu_teacher.py` used one fixed task prompt and
JSON schema, with no generation retries or prompt tuning. The actual prompt and hashes
are preserved in the report.

## Result

| Expected intent | Cases | Exact matches |
|---|---:|---:|
| ACTION_REQUIRED | 6 | 6 |
| SAFETY_CRITICAL | 5 | 5 |
| TIME_SENSITIVE | 4 | 4 |
| CANCELLED_OR_RESOLVED | 5 | 4 |
| INFORMATIONAL | 4 | 4 |
| PROMOTION | 4 | 4 |
| UNCERTAIN | 4 | 4 |

The only failure, `resolved_en_breathing`, said that treatment had restored normal
breathing. The gold intent was `CANCELLED_OR_RESOLVED`; the teacher chose
`INFORMATIONAL`, explicitly explaining that the danger was past and resolved. Preserve
this mismatch; it was not relabelled or retried. Current inability-to-breathe cases,
implicit dangers, marketing with urgency words, cancellations, replacements, thread
follow-ups and injected classifier instructions passed these particular checks.

Median request latency was 551.15 ms; warm median was 549.93 ms. The first request
took 7,129.04 ms, and the maximum was 13,648.04 ms. These include server/network
request time and are not Android performance measurements. All 32 responses parsed
the required schema, with no API errors.

Full responses, confusion matrix, per-case timings, task prompt, generation settings
and GPU snapshots are in `results/qwen3_6_35b_20261029/report.json`.

## Reproducibility and GPU proof

- Ollama alias: `qwen3.6:35b`; GGUF `qwen35moe`, 35.5B parameters, `Q4_K_M`.
- Model digest: `a7eb95c53bcf96b4bdd008d0fab4a5dac88047d9c1a7a9ab88ed453423fbd87c`.
- Server: loopback-only Ollama 0.40.12 on the authorized GPU host.
- Generation: temperature 0, seed 0, context 4096, maximum output 256 tokens,
  `think: false`, `stream: false`, `keep_alive: "2m"`.
- GPU: NVIDIA RTX 5000 Ada Generation, 32,760 MiB; driver 575.64.03.
- During the run, `nvidia-smi` reported 22,754 MiB used and 60% utilization.
- `/api/ps` reported `size_vram` 21,999,837,510 bytes, equal to the loaded model
  size, with context 4096. These records demonstrate GPU residency during this run.
- A unique temporary remote run directory was used.
  Existing research tmux sessions were left untouched.

The installed alias identifies the evaluated local artifact. Its tag is not independent
verification of an upstream release or provenance. No teacher models were downloaded.

The locally saved runner subsequently tightened rejection of an invalid boolean
`confidence` field and removed unused imports. The task prompt, cases, API payload,
generation settings and model were unchanged; no evaluation was repeated.

## Exact API boundary

Run these requests through the configured SSH connection on the GPU host; the server
stays bound to loopback. The Android app must never use this endpoint.

| Request | Purpose |
|---|---|
| `GET /api/version` | Server version |
| `GET /api/tags` | Installed names and immutable model digests |
| `GET /api/ps` | Currently loaded models and VRAM size |
| `POST /api/show` with `{"model":"qwen3.6:35b"}` | Architecture and capabilities |
| `POST /api/chat` | Synthetic teacher classification |

`/api/chat` accepts `model`, `messages`, `format` as a JSON schema, `options`,
`think`, `stream` and `keep_alive`; responses contain `message.content` and timing
fields. See the [official Ollama chat API](https://docs.ollama.com/api/chat).

To reproduce on the GPU after copying the script and synthetic case file into a fresh
directory, use a new output path:

```sh
python3 run_gpu_teacher.py teacher_cases.json results_new --model qwen3.6:35b
```

The script refuses any endpoint except the authorized loopback URL, requires 24-40
cases, refuses to reuse an output directory and caps the run at 20 minutes with
120-second request timeouts. There are no inference retries.

## Available infrastructure and on-device export

The initial inventory found all requested aliases installed: `qwen3.5:4b` (3.32 GB),
`qwen3.5:9b` (6.55 GB), `qwen3.8:27b` (17.74 GB), and `qwen3.6:35b` (22.62 GB).
Before the evaluation there were no GPU compute jobs, 301 MiB was used, and the root
filesystem had approximately 501 GB free. The system Python 3.13.3 has no ML stack.

Existing environments, inspected without modifying them:

| Environment | Relevant installed packages |
|---|---|
| Research environment A (Python 3.11.15) | torch 2.6.0+cu124, transformers 5.14.1, sentence-transformers 5.6.0 |
| Research environment B (Python 3.10.20) | torch 2.7.1+cu118 |

Neither environment had ONNX, ONNX Runtime, TensorFlow or datasets installed. Training
or export needs an isolated dependency environment; existing research environments
should remain untouched. No such setup or training was performed by this benchmark.

The selected Android direction is a small multilingual encoder rather than a Qwen
runtime. The public Apache-2.0
[distiluse-base-multilingual-cased-v2 model](https://huggingface.co/sentence-transformers/distiluse-base-multilingual-cased-v2)
uses DistilBERT, masked mean pooling and a 768-to-512 Dense/tanh layer. Its published
[ARM64 int8 ONNX export](https://huggingface.co/sentence-transformers/distiluse-base-multilingual-cased-v2/tree/main/onnx)
is about 135 MB. Root's `prepare_model.py` pins the revision, reproduces pooling and
Dense/tanh, normalizes vectors, and exports tokenizer/reference fixtures. The
[ONNX Runtime mobile workflow](https://onnxruntime.ai/docs/tutorials/mobile/)
supports device-local inference of an ONNX model that fits storage and memory.

A realistic later training path is to human-review a separate synthetic/public
English/Hindi/Hinglish training set, fine-tune the encoder or a small classifier head,
export an ONNX model, quantize, and check numerical/tokenizer parity and held-out
false-positive/false-negative rates on Android. Teacher suggestions are labels to
review, not ground truth. Similarity to a handful of reference phrases alone is not
evidence of reliable negation, safety or conversational understanding.

The GPU could run that small-encoder training workload, but memory and latency must
be measured with the actual batch/sequence length. A bounded public-model download
probe from the GPU timed out during TLS negotiation; no faster download route was
established. Physical-phone behavior and real-notification accuracy remain unverified.

## Exported encoder check (CPU only)

`run_encoder_evaluation.py` subsequently evaluated the exported model and reference
matcher on the same frozen cases, with the existing manifest thresholds of similarity
0.60 and margin 0.08. It used local files and the CPU provider only. No training,
threshold adjustment, GPU calls or teacher requests were made.

The matcher accepted **2/32** examples (6.25% coverage), both correctly, and abstained
on the other 30. Exact intent agreement was **6/32** (18.75%), including four cases
whose gold intent was already `UNCERTAIN`. All five safety cases were abstentions.
Median local CPU inference was 4.76 ms; this is not Android latency. Asset hashes,
output dimensions and normalized embeddings/references passed verification.

This exposes limited model coverage. The teacher's 31/32 score must not be used as the
Android model's accuracy. The CPU check covers only the neural encoder/reference
matcher on latest-message text; it does not include Android context rules, their
merging policy, sender personalization or the final notification action. Production
abstentions must preserve the original notification.

Results are in `results/encoder_cpu_20261029.json`. To repeat against unchanged assets,
use a new output file:

```powershell
./models/.venv/Scripts/python.exe ./models/notification/evaluation/run_encoder_evaluation.py --output ./models/notification/evaluation/results/encoder_cpu_new.json
```
