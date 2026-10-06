"""Regression tests for false-green aggregation and reused SBOM provenance."""

import os
import copy
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import check_ci_results
import ci_source
import merge_trivy_results


class RequiredCheckTest(unittest.TestCase):
    def results(self):
        return {name: {"result": "success"} for name in check_ci_results.REQUIRED_JOBS}

    def test_all_required_jobs_succeeded(self):
        self.assertEqual([], check_ci_results.failures(self.results(), "true"))

    def test_every_non_success_state_fails_each_required_job(self):
        for name in check_ci_results.REQUIRED_JOBS:
            for outcome in ("failure", "skipped", "cancelled", None, "neutral"):
                with self.subTest(job=name, outcome=outcome):
                    results = self.results()
                    results[name]["result"] = outcome
                    self.assertTrue(check_ci_results.failures(results, "true"))

    def test_missing_or_extra_job_fails(self):
        for results in ({}, [], {"quality": {"result": "success"}}, {**self.results(), "extra": {}}):
            self.assertTrue(check_ci_results.failures(results, "true"))

    def test_missing_result_fails(self):
        results = self.results()
        results["quality"] = {}
        self.assertTrue(check_ci_results.failures(results, "true"))

    def test_manual_fast_run_cannot_pass_required_check(self):
        for value in ("false", "", None):
            self.assertTrue(check_ci_results.failures(self.results(), value))

    def test_cli_rejects_malformed_evidence_and_emits_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            summary = Path(directory) / "summary.md"
            result = subprocess.run(
                [sys.executable, str(Path(check_ci_results.__file__))],
                env={**os.environ, "CI_RESULTS": "{", "GITHUB_STEP_SUMMARY": str(summary)},
                capture_output=True, text=True,
            )
            self.assertEqual(1, result.returncode)
            self.assertIn("Invalid required-job evidence", summary.read_text())


class SourceProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        (self.directory / "polaris-sbom.json").write_text('{"components": []}')
        self.env = patch.dict(os.environ, {"GITHUB_SHA": "tested-sha", "GITHUB_RUN_ID": "123"})
        self.env.start()
        self.addCleanup(self.env.stop)
        ci_source.process("write", self.directory)

    def test_same_source_and_run_passes(self):
        ci_source.process("verify", self.directory)

    def test_other_source_or_run_fails(self):
        for key in ("GITHUB_SHA", "GITHUB_RUN_ID"):
            with self.subTest(key=key), patch.dict(os.environ, {key: "different"}):
                with self.assertRaises(ValueError):
                    ci_source.process("verify", self.directory)

    def test_modified_sbom_fails(self):
        (self.directory / "polaris-sbom.json").write_text('{"components": ["altered"]}')
        with self.assertRaises(ValueError):
            ci_source.process("verify", self.directory)

    def test_missing_or_malformed_provenance_fails(self):
        manifest = self.directory / "ci-source.json"
        manifest.write_text("{")
        with self.assertRaises(ValueError):
            ci_source.process("verify", self.directory)
        manifest.unlink()
        with self.assertRaises(OSError):
            ci_source.process("verify", self.directory)

    def test_missing_sbom_fails(self):
        (self.directory / "polaris-sbom.json").unlink()
        with self.assertRaises(OSError):
            ci_source.process("verify", self.directory)


class RepositoryEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.source = {"SchemaVersion": 2, "Trivy": {"Version": "0.74.0"},
                       "Results": [{"Secrets": [{"Severity": "HIGH"}]}]}
        self.dependencies = {"SchemaVersion": 2, "Trivy": {"Version": "0.74.0"},
                             "Results": [{"Packages": [{"Name": "fixture"}],
                                          "Vulnerabilities": [{"Severity": "MEDIUM"}]}]}

    def test_complete_native_records_and_severities_are_preserved(self):
        original = copy.deepcopy((self.source, self.dependencies))
        combined = merge_trivy_results.merge(self.source, self.dependencies)
        self.assertEqual(self.source["Results"] + self.dependencies["Results"], combined["Results"])
        self.assertEqual(original, (self.source, self.dependencies))

    def test_clean_source_report_can_have_no_results(self):
        self.source.pop("Results")
        self.assertEqual(self.dependencies["Results"], merge_trivy_results.merge(self.source, self.dependencies)["Results"])

    def test_mismatched_schema_or_scanner_version_fails(self):
        for key, value in (("SchemaVersion", 1), ("Trivy", {"Version": "different"})):
            with self.subTest(key=key):
                changed = {**self.dependencies, key: value}
                with self.assertRaises(ValueError):
                    merge_trivy_results.merge(self.source, changed)

    def test_missing_dependency_inventory_fails(self):
        self.dependencies["Results"] = [{"Vulnerabilities": []}]
        with self.assertRaises(ValueError):
            merge_trivy_results.merge(self.source, self.dependencies)


if __name__ == "__main__":
    unittest.main()
