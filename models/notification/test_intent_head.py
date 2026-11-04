"""CPU-only exported artifact and numerical contract checks; no model training."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import unittest

import numpy as np

from train_intent_head import ASSETS, CLASSES, DIRECTORY, predict, softmax


class IntentHeadExportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.head = json.loads((ASSETS / "intent_head.json").read_text(encoding="utf-8"))

    def test_manifest_and_golden_bind_the_actual_export(self):
        manifest = json.loads((ASSETS / "manifest.json").read_text(encoding="utf-8"))
        raw = (ASSETS / "intent_head.json").read_bytes()
        checksum = hashlib.sha256(raw).hexdigest()
        self.assertEqual(manifest["files"]["intent_head.json"], {"sha256": checksum, "bytes": len(raw)})
        goldens = json.loads((DIRECTORY / "intent_head_golden.json").read_text(encoding="utf-8"))
        self.assertEqual(goldens["head_sha256"], checksum)
        cases = goldens["cases"]
        predicted, probabilities, margins, similarities = predict(np.asarray([case["vector"] for case in cases]), self.head)
        for case, intent, probs, margin, similarity in zip(cases, predicted, probabilities, margins, similarities):
            self.assertEqual(intent, case["expectedIntent"], case["id"])
            self.assertAlmostEqual(float(probs.max()), case["confidence"], places=5)
            self.assertAlmostEqual(float(margin), case["margin"], places=5)
            self.assertAlmostEqual(float(similarity), case["oodSimilarity"], places=5)

    def test_export_shape_is_finite_and_supports_are_unit_vectors(self):
        self.assertEqual(self.head["classes"], CLASSES)
        weights = np.asarray(self.head["weights"])
        self.assertEqual(weights.shape, (6, 512))
        self.assertTrue(np.isfinite(weights).all())
        self.assertEqual(np.asarray(self.head["bias"]).shape, (6,))
        supports = np.asarray(self.head["supportVectors"])
        self.assertEqual(supports.shape, (48, 512))
        self.assertTrue(np.isfinite(supports).all())
        np.testing.assert_allclose(np.linalg.norm(supports, axis=1), np.ones(48), atol=1e-5)

    def test_scenario_families_are_disjoint_and_no_frozen_text_is_training_data(self):
        dataset = json.loads((DIRECTORY / "synthetic_intent_cases.json").read_text(encoding="utf-8"))["cases"]
        families = {split: {case["family"] for case in dataset if case["split"] == split} for split in ("train", "temperature", "gates")}
        self.assertGreaterEqual(sum(case["split"] == "train" for case in dataset), 300)
        for a in families:
            for b in families:
                if a != b:
                    self.assertFalse(families[a] & families[b])
        heldout = json.loads((DIRECTORY / "evaluation/teacher_cases.json").read_text(encoding="utf-8"))["cases"]
        training_text = {case["text"].strip().lower() for case in dataset}
        self.assertFalse(training_text & {case["text"].strip().lower() for case in heldout})

    def test_softmax_is_stable_under_large_shared_offsets(self):
        logits = np.asarray([[10.0, 7.0, 0.0, -3.0, 1.0, 2.0]])
        probabilities = softmax(logits, .8)
        np.testing.assert_allclose(probabilities, softmax(logits + 10000, .8), atol=1e-12)
        self.assertTrue(np.isfinite(probabilities).all())
        self.assertAlmostEqual(float(probabilities.sum()), 1.0)

    def test_confidence_margin_and_ood_are_independent_gates(self):
        weights = np.zeros((6, 512))
        weights[0, 0] = 3
        vector = np.zeros((1, 512))
        vector[0, 0] = 1
        head = {"weights": weights.tolist(), "bias": [0] * 6, "temperature": 1, "supportVectors": vector.tolist(), "minimumConfidence": 0, "minimumMargin": 0, "minimumOODSimilarity": 0}
        self.assertEqual(predict(vector, head)[0], [CLASSES[0]])
        for field, value in (("minimumConfidence", .99), ("minimumMargin", .99)):
            self.assertEqual(predict(vector, {**head, field: value})[0], ["UNCERTAIN"])
        opposite_support = (-vector).tolist()
        self.assertEqual(predict(vector, {**head, "supportVectors": opposite_support})[0], ["UNCERTAIN"])
        # Equality is accepted, matching the Kotlin >= threshold contract.
        prediction, probs, margins, similarities = predict(vector, head)
        boundary = {**head, "minimumConfidence": float(probs.max()), "minimumMargin": float(margins[0]), "minimumOODSimilarity": float(similarities[0])}
        self.assertEqual(predict(vector, boundary)[0], prediction)


if __name__ == "__main__":
    unittest.main()
