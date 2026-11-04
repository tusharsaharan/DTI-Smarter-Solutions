"""Offline, duration-weighted evaluation of derived camera attention events.

Only Python's standard library is used. See README.md for the strict input
contract, timing rules, ground-truth protocol, and metric denominators.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import sys


VERSION = "1.0.0"
MAX_NUMBER = 2**53 - 1
TRUTH_LABELS = ("ATTENDED", "PASSIVE")
PREDICTION_LABELS = (*TRUTH_LABELS, "UNKNOWN")
METADATA_FIELDS = {
    "device", "android_version", "model_sha256", "classifier_version",
    "split", "label_method",
}


class ValidationError(ValueError):
    """Input does not conform to the evaluation contract."""


def _object(value, required, optional, where):
    if not isinstance(value, dict):
        raise ValidationError(f"{where}: expected an object")
    missing = required - value.keys()
    extra = value.keys() - required - optional
    if missing:
        raise ValidationError(f"{where}: missing fields: {', '.join(sorted(missing))}")
    if extra:
        raise ValidationError(f"{where}: unknown fields: {', '.join(sorted(extra))}")


def _number(value, where, *, integer=False):
    allowed = (int,) if integer else (int, float)
    if type(value) not in allowed or not 0 <= value <= MAX_NUMBER:
        kind = "integer" if integer else "finite number"
        raise ValidationError(f"{where}: expected a {kind} in [0, {MAX_NUMBER}]")
    if isinstance(value, float) and not math.isfinite(value):
        raise ValidationError(f"{where}: expected a finite number")


def _string(value, where):
    if not isinstance(value, str) or not value.strip():
        raise ValidationError(f"{where}: expected a nonblank string")


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValidationError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_constant(value):
    raise ValidationError(f"nonfinite JSON constant: {value}")


def _load_and_validate(payload):
    try:
        data = json.loads(
            payload.decode("utf-8"),
            object_pairs_hook=_unique_object,
            parse_constant=_reject_constant,
        )
    except (UnicodeDecodeError, ValueError, RecursionError) as error:
        raise ValidationError(f"invalid JSON: {error}") from error
    _object(data, {"metadata", "observations", "truth"}, set(), "input")
    metadata = data["metadata"]
    _object(metadata, METADATA_FIELDS, set(), "metadata")
    for field in METADATA_FIELDS:
        _string(metadata[field], f"metadata.{field}")
    if re.fullmatch(r"[0-9a-fA-F]{64}", metadata["model_sha256"]) is None:
        raise ValidationError("metadata.model_sha256: expected 64 hexadecimal characters")

    for field in ("observations", "truth"):
        if not isinstance(data[field], list):
            raise ValidationError(f"{field}: expected an array")
    previous_arrival = -1
    for index, observation in enumerate(data["observations"]):
        where = f"observations[{index}]"
        _object(observation, {
            "at_elapsed_ms", "observed_elapsed_ms", "valid_until_elapsed_ms", "label",
        }, {"inference_ms"}, where)
        for field in ("at_elapsed_ms", "observed_elapsed_ms", "valid_until_elapsed_ms"):
            _number(observation[field], f"{where}.{field}", integer=True)
        if observation["at_elapsed_ms"] <= previous_arrival:
            raise ValidationError(f"{where}: arrival times must be strictly increasing")
        previous_arrival = observation["at_elapsed_ms"]
        if observation["valid_until_elapsed_ms"] < observation["observed_elapsed_ms"]:
            raise ValidationError(f"{where}: expiry precedes observation time")
        if observation["label"] not in PREDICTION_LABELS:
            raise ValidationError(f"{where}.label: expected ATTENDED, PASSIVE, or UNKNOWN")
        if "inference_ms" in observation:
            _number(observation["inference_ms"], f"{where}.inference_ms")

    for index, interval in enumerate(data["truth"]):
        where = f"truth[{index}]"
        _object(interval, {
            "start_elapsed_ms", "end_elapsed_ms", "label", "condition",
        }, set(), where)
        for field in ("start_elapsed_ms", "end_elapsed_ms"):
            _number(interval[field], f"{where}.{field}", integer=True)
        if interval["start_elapsed_ms"] >= interval["end_elapsed_ms"]:
            raise ValidationError(f"{where}: start must precede end")
        if interval["label"] not in TRUTH_LABELS:
            raise ValidationError(f"{where}.label: expected ATTENDED or PASSIVE")
        _string(interval["condition"], f"{where}.condition")
    truth = sorted(data["truth"], key=lambda item: item["start_elapsed_ms"])
    for previous, current in zip(truth, truth[1:]):
        if current["start_elapsed_ms"] < previous["end_elapsed_ms"]:
            raise ValidationError("truth: intervals overlap (including across conditions)")
    return metadata, data["observations"], truth


def _segments(observations):
    """Every arrival terminates the previous event, even when it is unusable."""
    segments = []
    exclusions = {"future_observed": 0, "expired_at_arrival": 0, "superseded_observed": 0}
    newest_observed = -1
    for index, observation in enumerate(observations):
        arrival = observation["at_elapsed_ms"]
        observed = observation["observed_elapsed_ms"]
        expiry = observation["valid_until_elapsed_ms"]
        reason = None
        if observed > arrival:
            reason = "future_observed"
        else:
            if expiry <= arrival:
                reason = "expired_at_arrival"
            elif observed <= newest_observed:
                reason = "superseded_observed"
            newest_observed = max(newest_observed, observed)
        if reason:
            exclusions[reason] += 1
            continue
        end = expiry
        if index + 1 < len(observations):
            end = min(end, observations[index + 1]["at_elapsed_ms"])
        if observation["label"] != "UNKNOWN":
            segments.append((arrival, end, observation["label"]))
    return segments, exclusions


def _confusion():
    return {truth: {prediction: 0 for prediction in PREDICTION_LABELS} for truth in TRUTH_LABELS}


def _rate(numerator, denominator):
    return {
        "numerator_ms": numerator,
        "denominator_ms": denominator,
        "value": numerator / denominator if denominator else None,
    }


def _latency(values):
    values = sorted(values)

    def percentile(quantile):
        if not values:
            return None
        position = (len(values) - 1) * quantile
        lower = math.floor(position)
        upper = math.ceil(position)
        return values[lower] + (values[upper] - values[lower]) * (position - lower)

    return {"count": len(values), "p50_ms": percentile(0.50), "p95_ms": percentile(0.95)}


def _summarize(confusion, latencies):
    attended_truth = sum(confusion["ATTENDED"].values())
    passive_truth = sum(confusion["PASSIVE"].values())
    total = attended_truth + passive_truth
    unknown = sum(row["UNKNOWN"] for row in confusion.values())
    predicted_attended = sum(row["ATTENDED"] for row in confusion.values())
    predicted_passive = sum(row["PASSIVE"] for row in confusion.values())
    correct = confusion["ATTENDED"]["ATTENDED"] + confusion["PASSIVE"]["PASSIVE"]
    false_attended = confusion["PASSIVE"]["ATTENDED"]
    false_passive = confusion["ATTENDED"]["PASSIVE"]
    signed_error = predicted_attended - attended_truth
    return {
        "confusion_duration_ms": confusion,
        "truth_duration_ms": total,
        "known_duration_ms": total - unknown,
        "unknown_duration_ms": unknown,
        "correct_duration_ms": correct,
        "incorrect_duration_ms": false_attended + false_passive,
        "coverage": _rate(total - unknown, total),
        "accuracy_over_all_truth": _rate(correct, total),
        "accuracy_when_known": _rate(correct, total - unknown),
        "false_attended": {
            "duration_ms": false_attended,
            "rate_given_truth": _rate(false_attended, passive_truth),
            "error_rate_given_prediction": _rate(false_attended, predicted_attended),
        },
        "false_passive": {
            "duration_ms": false_passive,
            "rate_given_truth": _rate(false_passive, attended_truth),
            "error_rate_given_prediction": _rate(false_passive, predicted_passive),
        },
        "attended_duration_error_ms": {
            "truth": attended_truth,
            "predicted": predicted_attended,
            "signed": signed_error,
            "absolute": abs(signed_error),
        },
        "inference": _latency(latencies),
    }


def evaluate_bytes(payload: bytes) -> dict:
    """Validate exact UTF-8 input bytes, hash them, and evaluate labelled time."""
    metadata, observations, truth = _load_and_validate(payload)
    segments, exclusions = _segments(observations)
    conditions = {interval["condition"] for interval in truth}
    by_condition = {condition: _confusion() for condition in sorted(conditions)}
    latencies = {condition: [] for condition in by_condition}
    segment_index = 0
    for interval in truth:
        start, end = interval["start_elapsed_ms"], interval["end_elapsed_ms"]
        row = by_condition[interval["condition"]][interval["label"]]
        row["UNKNOWN"] += end - start
        while segment_index < len(segments) and segments[segment_index][1] <= start:
            segment_index += 1
        cursor = segment_index
        while cursor < len(segments) and segments[cursor][0] < end:
            segment_start, segment_end, prediction = segments[cursor]
            duration = max(0, min(end, segment_end) - max(start, segment_start))
            row[prediction] += duration
            row["UNKNOWN"] -= duration
            if segment_end > end:
                break
            cursor += 1
        segment_index = cursor

    # Latencies are sample-weighted, assigned by ARRIVAL into labelled truth.
    # Rejected/UNKNOWN predictions still took inference time and are included.
    truth_index = 0
    outside_truth = 0
    for observation in observations:
        arrival = observation["at_elapsed_ms"]
        while truth_index < len(truth) and truth[truth_index]["end_elapsed_ms"] <= arrival:
            truth_index += 1
        if truth_index == len(truth) or arrival < truth[truth_index]["start_elapsed_ms"]:
            outside_truth += 1
        elif "inference_ms" in observation:
            latencies[truth[truth_index]["condition"]].append(observation["inference_ms"])

    overall = _confusion()
    for confusion in by_condition.values():
        for truth_label, row in confusion.items():
            for prediction, duration in row.items():
                overall[truth_label][prediction] += duration
    all_latencies = [value for values in latencies.values() for value in values]
    return {
        "schema_version": 1,
        "evaluator": {
            "version": VERSION,
            "source_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        },
        "input_sha256": hashlib.sha256(payload).hexdigest(),
        "metadata": metadata,
        "semantics": {
            "intervals": "half-open elapsed-realtime milliseconds; only labelled truth is scored",
            "prediction_start": "arrival, never capture; next arrival always supersedes",
            "prediction_end": "earlier of expiry and next arrival",
            "unknown": "uncovered or rejected evidence; never counted as correct",
            "undefined_rates": "null when denominator is zero",
            "latency_scope": "provided inference_ms with arrival inside truth, including rejected/UNKNOWN events",
            "latency_percentiles": "sample-weighted linear interpolation at (count - 1) * quantile",
            "provenance": "metadata is supplied by the collector, not independently verified",
        },
        "observation_diagnostics": {
            "count": len(observations),
            "timing_usable_count": len(observations) - sum(exclusions.values()),
            "timing_excluded_count": sum(exclusions.values()),
            "timing_exclusions": exclusions,
            "arrival_outside_truth_count": outside_truth,
        },
        "overall": _summarize(overall, all_latencies),
        "per_condition": {
            condition: _summarize(confusion, latencies[condition])
            for condition, confusion in by_condition.items()
        },
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input_json", type=Path)
    parser.add_argument("--output", type=Path, required=True, metavar="OUTPUT_JSON")
    args = parser.parse_args(argv)
    try:
        if args.input_json.resolve() == args.output.resolve() or (
            args.output.exists() and args.input_json.samefile(args.output)
        ):
            raise ValidationError("input and output must be different files")
        report = evaluate_bytes(args.input_json.read_bytes())
        args.output.write_text(
            json.dumps(report, indent=2, ensure_ascii=True, allow_nan=False) + "\n",
            encoding="utf-8",
        )
    except (OSError, ValidationError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    print(f"Wrote evaluation to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
