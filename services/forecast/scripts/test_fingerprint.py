"""Tests for the optional cross-language fingerprint helper."""
import copy
import json
import pathlib
import unittest
from fingerprint import canonical, fingerprint


class FingerprintTest(unittest.TestCase):
    def test_matches_fixed_java_and_python_fixture_vector(self):
        path = pathlib.Path(__file__).parent.parent / "src/test/resources/fixtures/calculate.json"
        snapshot = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(fingerprint(snapshot), "34c6b5b45ae8c8ebeec17c8319e2a60f064f906cc2e5f6ab4cbc202aca6edf77")
        altered = copy.deepcopy(snapshot)
        altered["payments"].reverse()
        altered["snapshotId"] = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        self.assertEqual(fingerprint(altered), snapshot["fingerprint"])

    def test_money_negative_zero_is_canonical_zero(self):
        self.assertEqual(canonical("-0.00", "startBalance"), "0.00")

    def test_knowledge_cutoff_is_normalized_to_utc(self):
        self.assertEqual(canonical("2026-10-25T12:00:00+03:00", "knowledgeCutoff"), "2026-10-25T09:00:00Z")

    def test_instant_keeps_nanoseconds_and_java_grouped_fraction_digits(self):
        self.assertEqual(canonical("2026-10-20T13:00:00.123456789+03:00", "knownAt"), "2026-10-20T10:00:00.123456789Z")
        self.assertEqual(canonical("2026-10-20T10:00:00.1Z", "knownAt"), "2026-10-20T10:00:00.100Z")
        self.assertEqual(canonical("2026-10-20T10:00:00.000Z", "knownAt"), "2026-10-20T10:00:00Z")


if __name__ == "__main__":
    unittest.main()
