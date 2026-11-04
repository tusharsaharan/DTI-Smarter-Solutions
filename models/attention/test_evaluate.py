"""Synthetic boundary tests, NOT physical-device accuracy measurements."""

import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("evaluate.py")
SPEC = importlib.util.spec_from_file_location("attention_evaluate", SCRIPT)
evaluate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evaluate)


def observation(at, label, expiry, *, observed=None, inference=None):
    value = {
        "at_elapsed_ms": at,
        "observed_elapsed_ms": at if observed is None else observed,
        "valid_until_elapsed_ms": expiry,
        "label": label,
    }
    if inference is not None:
        value["inference_ms"] = inference
    return value


def interval(start, end, label, condition="synthetic/front"):
    return {
        "start_elapsed_ms": start, "end_elapsed_ms": end,
        "label": label, "condition": condition,
    }


def document(observations=None, truth=None):
    return {
        "metadata": {
            "device": "synthetic unit test; no physical device",
            "android_version": "synthetic; not Android execution",
            "model_sha256": hashlib.sha256(b"synthetic test fixture, not a model").hexdigest(),
            "classifier_version": "synthetic-v1",
            "split": "synthetic-unit-test",
            "label_method": "hand-computed synthetic intervals; no human accuracy claim",
        },
        "observations": [] if observations is None else observations,
        "truth": [] if truth is None else truth,
    }


def run(data):
    return evaluate.evaluate_bytes(json.dumps(data, allow_nan=False).encode("utf-8"))


class DurationTests(unittest.TestCase):
    def assert_partition(self, summary):
        confusion = summary["confusion_duration_ms"]
        self.assertEqual(sum(sum(row.values()) for row in confusion.values()), summary["truth_duration_ms"])
        self.assertEqual(summary["known_duration_ms"] + summary["unknown_duration_ms"], summary["truth_duration_ms"])
        self.assertEqual(summary["correct_duration_ms"] + summary["incorrect_duration_ms"], summary["known_duration_ms"])
        self.assertTrue(all(value >= 0 for row in confusion.values() for value in row.values()))

    def test_hand_computed_confusion_conditions_rates_and_latency(self):
        data = document([
            observation(0, "ATTENDED", 400, inference=10),
            observation(200, "PASSIVE", 500, inference=20),
            observation(500, "ATTENDED", 900, inference=30),
            observation(800, "UNKNOWN", 1000, inference=40),
        ], [
            interval(0, 400, "ATTENDED", "synthetic/front"),
            interval(400, 700, "PASSIVE", "synthetic/front"),
            interval(700, 1000, "PASSIVE", "synthetic/reclined"),
        ])
        report = run(data)
        overall = report["overall"]
        self.assertEqual(overall["confusion_duration_ms"], {
            "ATTENDED": {"ATTENDED": 200, "PASSIVE": 200, "UNKNOWN": 0},
            "PASSIVE": {"ATTENDED": 300, "PASSIVE": 100, "UNKNOWN": 200},
        })
        self.assertEqual(overall["truth_duration_ms"], 1000)
        self.assertEqual(overall["coverage"]["value"], 0.8)
        self.assertEqual(overall["accuracy_over_all_truth"]["value"], 0.3)
        self.assertEqual(overall["accuracy_when_known"]["value"], 0.375)
        self.assertEqual(overall["false_attended"]["duration_ms"], 300)
        self.assertEqual(overall["false_passive"]["duration_ms"], 200)
        self.assertEqual(overall["false_attended"]["rate_given_truth"]["denominator_ms"], 600)
        self.assertEqual(overall["false_attended"]["rate_given_truth"]["value"], 0.5)
        self.assertEqual(overall["false_attended"]["error_rate_given_prediction"]["value"], 0.6)
        self.assertEqual(overall["false_passive"]["rate_given_truth"]["value"], 0.5)
        self.assertAlmostEqual(overall["false_passive"]["error_rate_given_prediction"]["value"], 2 / 3)
        self.assertEqual(overall["attended_duration_error_ms"], {
            "truth": 400, "predicted": 500, "signed": 100, "absolute": 100,
        })
        self.assertEqual(overall["inference"], {"count": 4, "p50_ms": 25.0, "p95_ms": 38.5})
        front = report["per_condition"]["synthetic/front"]
        reclined = report["per_condition"]["synthetic/reclined"]
        self.assertEqual(front["confusion_duration_ms"], {
            "ATTENDED": {"ATTENDED": 200, "PASSIVE": 200, "UNKNOWN": 0},
            "PASSIVE": {"ATTENDED": 200, "PASSIVE": 100, "UNKNOWN": 0},
        })
        self.assertEqual(reclined["confusion_duration_ms"]["PASSIVE"], {
            "ATTENDED": 100, "PASSIVE": 0, "UNKNOWN": 200,
        })
        self.assertEqual(front["inference"], {"count": 3, "p50_ms": 20.0, "p95_ms": 29.0})
        self.assertEqual(reclined["inference"], {"count": 1, "p50_ms": 40.0, "p95_ms": 40.0})
        for summary in (overall, front, reclined):
            self.assert_partition(summary)

    def test_initial_gap_expiry_and_final_gap_are_unknown(self):
        result = run(document([
            observation(100, "ATTENDED", 200),
            observation(400, "PASSIVE", 500),
        ], [interval(0, 600, "ATTENDED")]))["overall"]
        self.assertEqual(result["confusion_duration_ms"]["ATTENDED"], {
            "ATTENDED": 100, "PASSIVE": 100, "UNKNOWN": 400,
        })
        self.assertEqual(result["attended_duration_error_ms"]["signed"], -500)
        self.assert_partition(result)

    def test_delayed_inference_is_never_backdated_or_given_a_new_expiry(self):
        result = run(document([
            observation(300, "ATTENDED", 500, observed=0, inference=300),
        ], [interval(0, 1000, "ATTENDED")]))["overall"]
        self.assertEqual(result["confusion_duration_ms"]["ATTENDED"], {
            "ATTENDED": 200, "PASSIVE": 0, "UNKNOWN": 800,
        })

    def test_next_arrival_truncates_even_when_future_or_expired(self):
        for rejected in (
            observation(100, "PASSIVE", 400, observed=200),
            observation(100, "PASSIVE", 100, observed=50),
        ):
            with self.subTest(rejected=rejected):
                report = run(document([
                    observation(0, "ATTENDED", 1000), rejected,
                    observation(500, "PASSIVE", 700),
                ], [interval(0, 1000, "ATTENDED")]))
                self.assertEqual(report["overall"]["confusion_duration_ms"]["ATTENDED"], {
                    "ATTENDED": 100, "PASSIVE": 200, "UNKNOWN": 700,
                })
                self.assertEqual(report["observation_diagnostics"]["timing_excluded_count"], 1)

    def test_superseded_or_replayed_capture_cannot_restore_old_evidence(self):
        report = run(document([
            observation(100, "ATTENDED", 500, observed=90),
            observation(200, "PASSIVE", 500, observed=80),
            observation(300, "ATTENDED", 800, observed=90),
            observation(400, "PASSIVE", 500, observed=350),
        ], [interval(0, 600, "ATTENDED")]))
        self.assertEqual(report["overall"]["confusion_duration_ms"]["ATTENDED"], {
            "ATTENDED": 100, "PASSIVE": 100, "UNKNOWN": 400,
        })
        self.assertEqual(report["observation_diagnostics"]["timing_exclusions"]["superseded_observed"], 2)

    def test_future_capture_does_not_poison_later_valid_capture(self):
        report = run(document([
            observation(0, "ATTENDED", 1000, observed=900),
            observation(100, "PASSIVE", 200),
        ], [interval(0, 200, "PASSIVE")]))
        self.assertEqual(report["overall"]["correct_duration_ms"], 100)
        self.assertEqual(report["observation_diagnostics"]["timing_exclusions"]["future_observed"], 1)

    def test_truth_gaps_are_unscored_and_spanning_segment_is_clipped(self):
        report = run(document([
            observation(0, "ATTENDED", 1000, inference=1),
            observation(300, "PASSIVE", 1000, inference=999),
        ], [
            interval(600, 700, "PASSIVE", "synthetic/second"),
            interval(0, 100, "ATTENDED", "synthetic/first"),
            interval(150, 200, "ATTENDED", "synthetic/first"),
        ]))
        self.assertEqual(report["overall"]["truth_duration_ms"], 250)
        self.assertEqual(report["overall"]["correct_duration_ms"], 250)
        self.assertEqual(report["overall"]["inference"], {"count": 1, "p50_ms": 1.0, "p95_ms": 1.0})
        self.assertEqual(report["observation_diagnostics"]["arrival_outside_truth_count"], 1)
        self.assert_partition(report["overall"])

    def test_half_open_boundaries_and_latency_assignment(self):
        report = run(document([
            observation(0, "ATTENDED", 100, inference=0),
            observation(100, "PASSIVE", 200, inference=4),
            observation(200, "ATTENDED", 300, inference=99),
        ], [
            interval(0, 100, "ATTENDED", "synthetic/first"),
            interval(100, 200, "PASSIVE", "synthetic/second"),
        ]))
        self.assertEqual(report["overall"]["correct_duration_ms"], 200)
        self.assertEqual(report["overall"]["inference"]["p50_ms"], 2)
        self.assertAlmostEqual(report["overall"]["inference"]["p95_ms"], 3.8)
        self.assertEqual(report["per_condition"]["synthetic/second"]["inference"]["count"], 1)

    def test_latency_includes_rejected_and_unknown_arrivals_not_missing_values(self):
        report = run(document([
            observation(0, "UNKNOWN", 100, inference=10.5),
            observation(100, "ATTENDED", 100, observed=50, inference=40.5),
            observation(200, "PASSIVE", 300),
        ], [interval(0, 300, "PASSIVE")]))
        self.assertEqual(report["overall"]["inference"]["count"], 2)
        self.assertEqual(report["overall"]["inference"]["p50_ms"], 25.5)

    def test_unknown_never_inflates_accuracy(self):
        for observations in ([], [observation(0, "UNKNOWN", 100)]):
            result = run(document(observations, [interval(0, 100, "ATTENDED")]))["overall"]
            self.assertEqual(result["unknown_duration_ms"], 100)
            self.assertEqual(result["coverage"]["value"], 0)
            self.assertEqual(result["accuracy_over_all_truth"]["value"], 0)
            self.assertIsNone(result["accuracy_when_known"]["value"])
            self.assertIsNone(result["false_attended"]["rate_given_truth"]["value"])
            self.assertEqual(result["false_passive"]["rate_given_truth"]["value"], 0)
            self.assertIsNone(result["false_passive"]["error_rate_given_prediction"]["value"])
            self.assertEqual(result["attended_duration_error_ms"]["absolute"], 100)

    def test_empty_truth_has_null_rates_and_no_latency_or_scored_duration(self):
        report = run(document([observation(0, "ATTENDED", 100, inference=5)]))
        result = report["overall"]
        self.assertEqual(result["truth_duration_ms"], 0)
        self.assertEqual(result["attended_duration_error_ms"]["absolute"], 0)
        for metric in ("coverage", "accuracy_over_all_truth", "accuracy_when_known"):
            self.assertIsNone(result[metric]["value"])
        for error in ("false_attended", "false_passive"):
            for metric in ("rate_given_truth", "error_rate_given_prediction"):
                self.assertIsNone(result[error][metric]["value"])
        self.assertEqual(result["inference"], {"count": 0, "p50_ms": None, "p95_ms": None})
        self.assertEqual(report["per_condition"], {})
        self.assert_partition(result)

    def test_extreme_allowed_timestamp_uses_exact_integer_duration(self):
        end = evaluate.MAX_NUMBER
        result = run(document([observation(end - 3, "ATTENDED", end)], [
            interval(end - 5, end, "ATTENDED"),
        ]))["overall"]
        self.assertEqual(result["correct_duration_ms"], 3)
        self.assertEqual(result["unknown_duration_ms"], 2)


class ValidationTests(unittest.TestCase):
    def setUp(self):
        self.valid = document([observation(10, "ATTENDED", 30)], [interval(0, 50, "ATTENDED")])

    def assert_invalid(self, data):
        with self.assertRaises(evaluate.ValidationError):
            run(data)

    def test_missing_and_unknown_fields_at_every_object_level(self):
        for level in ("root", "metadata", "observation", "truth"):
            for mutation in ("missing", "extra"):
                with self.subTest(level=level, mutation=mutation):
                    data = copy.deepcopy(self.valid)
                    target = {
                        "root": data, "metadata": data["metadata"],
                        "observation": data["observations"][0], "truth": data["truth"][0],
                    }[level]
                    if mutation == "missing":
                        target.pop(next(iter(target)))
                    else:
                        target["raw_camera_data"] = "forbidden"
                    self.assert_invalid(data)

    def test_invalid_numbers_in_all_numeric_fields(self):
        for field in ("at_elapsed_ms", "observed_elapsed_ms", "valid_until_elapsed_ms", "inference_ms"):
            for value in (-1, True, False, None, "12", [], {}, evaluate.MAX_NUMBER + 1):
                with self.subTest(field=field, value=value):
                    data = copy.deepcopy(self.valid)
                    data["observations"][0][field] = value
                    self.assert_invalid(data)
        for field in ("start_elapsed_ms", "end_elapsed_ms"):
            for value in (-1, True, None, "12", 1.5, evaluate.MAX_NUMBER + 1):
                with self.subTest(field=field, value=value):
                    data = copy.deepcopy(self.valid)
                    data["truth"][0][field] = value
                    self.assert_invalid(data)
        for field in ("at_elapsed_ms", "observed_elapsed_ms", "valid_until_elapsed_ms"):
            data = copy.deepcopy(self.valid)
            data["observations"][0][field] = 10.0
            self.assert_invalid(data)

    def test_duplicate_json_keys_and_nonfinite_values_are_rejected(self):
        for raw in (
            b'{"metadata": {}, "metadata": {}, "observations": [], "truth": []}',
            json.dumps(self.valid).replace('"device":', '"device": "duplicate", "device":').encode(),
        ):
            with self.subTest(raw=raw), self.assertRaises(evaluate.ValidationError):
                evaluate.evaluate_bytes(raw)
        for constant in ("NaN", "Infinity", "-Infinity", "1e400"):
            data = copy.deepcopy(self.valid)
            data["observations"][0]["inference_ms"] = "NONFINITE"
            raw = json.dumps(data).replace('"NONFINITE"', constant).encode()
            with self.subTest(constant=constant), self.assertRaises(evaluate.ValidationError):
                evaluate.evaluate_bytes(raw)

    def test_bad_container_shapes_and_malformed_json(self):
        for value in (None, [], 5, "input"):
            self.assert_invalid(value)
        for field in ("metadata", "observations", "truth"):
            data = copy.deepcopy(self.valid)
            data[field] = None
            self.assert_invalid(data)
        for field in ("observations", "truth"):
            data = copy.deepcopy(self.valid)
            data[field] = [None]
            self.assert_invalid(data)
        for raw in (b"\xff", b"{} {}", b"{", b"\xef\xbb\xbf{}"):
            with self.subTest(raw=raw), self.assertRaises(evaluate.ValidationError):
                evaluate.evaluate_bytes(raw)

    def test_metadata_and_sha256_must_be_present_and_well_formed(self):
        for field in evaluate.METADATA_FIELDS:
            for value in ("", "  \n", None, 14):
                data = copy.deepcopy(self.valid)
                data["metadata"][field] = value
                self.assert_invalid(data)
        for digest in ("a" * 63, "a" * 65, "g" * 64, "sha256:" + "a" * 64):
            data = copy.deepcopy(self.valid)
            data["metadata"]["model_sha256"] = digest
            self.assert_invalid(data)

    def test_reordered_or_duplicate_arrivals_invalid_and_truth_overlap_invalid(self):
        for arrivals in ((10, 10), (20, 10)):
            self.assert_invalid(document([observation(at, "ATTENDED", 100) for at in arrivals]))
        for truth in (
            [interval(0, 20, "ATTENDED"), interval(19, 30, "PASSIVE", "different")],
            [interval(0, 20, "ATTENDED"), interval(0, 20, "ATTENDED")],
            [interval(10, 10, "ATTENDED")],
            [interval(20, 10, "ATTENDED")],
        ):
            self.assert_invalid(document(truth=truth))

    def test_expiry_before_capture_invalid_and_zero_lifetime_is_excluded(self):
        self.assert_invalid(document([observation(10, "ATTENDED", 8, observed=9)]))
        report = run(document([observation(10, "ATTENDED", 10)]))
        self.assertEqual(report["observation_diagnostics"]["timing_exclusions"]["expired_at_arrival"], 1)

    def test_labels_and_condition_are_strict(self):
        for label in ("attended", "", None, {}, [], True):
            self.assert_invalid(document([observation(0, label, 10)]))
            self.assert_invalid(document(truth=[interval(0, 10, label)]))
        self.assert_invalid(document(truth=[interval(0, 10, "UNKNOWN")]))
        for condition in (None, "", " \n", 5):
            self.assert_invalid(document(truth=[interval(0, 10, "ATTENDED", condition)]))


class ReproducibilityAndCliTests(unittest.TestCase):
    def test_exact_byte_hash_metadata_and_deterministic_output(self):
        data = document()
        data["metadata"]["model_sha256"] = data["metadata"]["model_sha256"].upper()
        first = json.dumps(data).encode()
        second = json.dumps(data, indent=2).encode()
        report = evaluate.evaluate_bytes(first)
        self.assertEqual(report["input_sha256"], hashlib.sha256(first).hexdigest())
        self.assertNotEqual(report["input_sha256"], evaluate.evaluate_bytes(second)["input_sha256"])
        self.assertEqual(report["metadata"], data["metadata"])
        self.assertEqual(report["evaluator"]["source_sha256"], hashlib.sha256(SCRIPT.read_bytes()).hexdigest())
        self.assertEqual(report, evaluate.evaluate_bytes(first))

    def test_cli_writes_valid_json_and_rejects_invalid_without_overwrite(self):
        with tempfile.TemporaryDirectory(prefix="attention-synthetic-test-") as directory:
            source = Path(directory) / "synthetic.json"
            output = Path(directory) / "synthetic-report.json"
            source.write_text(json.dumps(document()), encoding="utf-8")
            command = [sys.executable, str(SCRIPT), str(source), "--output", str(output)]
            completed = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertEqual(json.loads(output.read_text())["metadata"]["split"], "synthetic-unit-test")
            original_output = output.read_bytes()
            source.write_text("{}", encoding="utf-8")
            completed = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(completed.returncode, 2)
            self.assertIn("missing fields", completed.stderr)
            self.assertEqual(output.read_bytes(), original_output)

    def test_cli_rejects_same_input_output_and_missing_input(self):
        with tempfile.TemporaryDirectory(prefix="attention-synthetic-test-") as directory:
            source = Path(directory) / "synthetic.json"
            source.write_text(json.dumps(document()), encoding="utf-8")
            original = source.read_bytes()
            completed = subprocess.run(
                [sys.executable, str(SCRIPT), str(source), "--output", str(source)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(completed.returncode, 2)
            self.assertEqual(source.read_bytes(), original)
            missing = Path(directory) / "missing.json"
            output = Path(directory) / "never-created.json"
            completed = subprocess.run(
                [sys.executable, str(SCRIPT), str(missing), "--output", str(output)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(completed.returncode, 2)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
