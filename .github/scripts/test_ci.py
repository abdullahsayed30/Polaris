"""Regression tests for false-green aggregation and reused SBOM provenance."""

import os
import copy
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

import check_ci_results
import ci_source
import merge_trivy_results
import trivy_gate
import trivy_summary


class TrivySummaryTest(unittest.TestCase):
    def setUp(self):
        self.policy = trivy_gate.read_json(trivy_gate.POLICY)
        self.now = datetime(2026, 10, 10, tzinfo=timezone.utc)
        self.report = {"SchemaVersion": 2, "Trivy": {"Version": "0.74.0"},
                       "ArtifactName": "fixture", "ArtifactType": "filesystem", "Results": []}
        self.accepted = {"VulnerabilityID": trivy_gate.CVE,
                         "PkgIdentifier": {"PURL": trivy_gate.PURL, "UID": "fixture"},
                         "PkgName": "org.springframework:spring-webmvc", "InstalledVersion": "6.2.19",
                         "FixedVersion": "7.0.9", "Severity": "CRITICAL",
                         "PrimaryURL": "https://spring.io/security/cve-2026-47884/"}

    def render(self, target="repository"):
        return trivy_summary.render(target, self.report, self.policy, now=self.now)

    def test_raw_and_effective_counts_keep_the_accepted_risk_visible(self):
        self.report["Results"] = [{
            "Vulnerabilities": [self.accepted, {"VulnerabilityID": "CVE-2099-0001", "Severity": "HIGH"},
                                {"VulnerabilityID": "CVE-2099-0002", "Severity": "MEDIUM"}],
            "Secrets": [{"Severity": "LOW", "Match": "NEVER-PRINT-SECRET"}],
            "Misconfigurations": [{"Severity": "UNKNOWN", "Status": "FAIL"},
                                  {"Severity": "CRITICAL", "Status": "PASS"}],
        }]
        original = copy.deepcopy(self.report)
        markdown, valid = self.render()
        self.assertTrue(valid)
        self.assertIn("| 1 | 1 | 1 | 1 | 1 |", markdown)
        self.assertIn("| Vulnerabilities | 3 | 2 | 1 |", markdown)
        self.assertIn(trivy_gate.CVE, markdown)
        self.assertIn("Accepted / suppressed for gate only", markdown)
        self.assertIn("owner abdullahsayed30", markdown)
        self.assertIn("June 30, 2027 UTC", markdown)
        self.assertIn("vulnerable version remains installed", markdown)
        self.assertNotIn("NEVER-PRINT-SECRET", markdown)
        self.assertEqual(original, self.report)

    def test_clean_scan_has_explicit_zero_counts_and_distinct_artifacts(self):
        for target in trivy_summary.TARGETS:
            with self.subTest(target=target):
                markdown, valid = self.render(target)
                self.assertTrue(valid)
                self.assertIn("| 0 | 0 | 0 | 0 | 0 |", markdown)
                self.assertIn("No vulnerability findings", markdown)
                self.assertIn("polaris-" + target + "-security", markdown)
                self.assertIn(target, markdown)

    def test_expired_or_missing_policy_cannot_claim_effective_zero(self):
        self.report["Results"] = [{"Vulnerabilities": [self.accepted]}]
        for policy, now in ((None, self.now), (self.policy, datetime(2027, 7, 1, tzinfo=timezone.utc))):
            with self.subTest(policy=policy):
                markdown, valid = trivy_summary.render("repository", self.report, policy, now=now)
                self.assertFalse(valid)
                self.assertIn("| Vulnerabilities | 1 | 1 | unavailable |", markdown)
                self.assertIn("gate cannot pass", markdown)
                self.assertIn("Blocking", markdown)
                self.assertNotIn("Accepted / suppressed", markdown)

    def test_malformed_evidence_never_looks_clean(self):
        for report in ({}, {**self.report, "Results": None},
                       {**self.report, "Results": [{"Secrets": ["invalid"]}]},
                       {**self.report, "Results": [{"Secrets": [{"Severity": "INVALID"}]}]}):
            with self.subTest(report=report):
                markdown, valid = trivy_summary.render("repository", report, self.policy, now=self.now)
                self.assertFalse(valid)
                self.assertIn("evidence unavailable or invalid", markdown)
                self.assertNotIn("| 0 | 0 | 0 | 0 | 0 |", markdown)

    def test_missing_report_publishes_failure_explanation(self):
        with tempfile.TemporaryDirectory() as directory:
            summary = Path(directory) / "summary.md"
            result = subprocess.run(
                [sys.executable, str(Path(trivy_summary.__file__)), "repository", str(Path(directory) / "missing.json")],
                env={**os.environ, "GITHUB_STEP_SUMMARY": str(summary)}, capture_output=True, text=True,
            )
            self.assertEqual(1, result.returncode)
            self.assertIn("No zero findings or passing gate", summary.read_text())

    def test_unusual_text_and_advisories_are_safely_bounded(self):
        finding = {"VulnerabilityID": "CVE|[bad]`\n<script>", "PkgName": "package" * 100,
                   "InstalledVersion": "1", "Severity": "HIGH", "PrimaryURL": "javascript:alert(1)"}
        self.report["Results"] = [{"Vulnerabilities": [finding] * (trivy_summary.ROW_LIMIT + 3)}]
        markdown, valid = self.render()
        self.assertTrue(valid)
        self.assertIn("CVE\\|\\[bad\\]\\` &lt;script&gt;", markdown)
        self.assertNotIn("<script>", markdown)
        self.assertNotIn("javascript:", markdown)
        self.assertIn("Showing 25 of 28", markdown)
        self.assertLess(len(markdown), 8000)
        for url in ("https://[broken", "https://example.com/with space", "https://user:pass@example.com"):
            self.assertEqual("—", trivy_summary.advisory({"PrimaryURL": url}))
        self.assertIn("%28", trivy_summary.advisory({"PrimaryURL": "https://example.com/a(b)|c"}))


class RiskAcceptancePolicyTest(unittest.TestCase):
    def setUp(self):
        self.policy = trivy_gate.read_json(trivy_gate.POLICY)
        self.before = datetime(2027, 6, 30, 23, 59, 59, tzinfo=timezone.utc)

    def test_last_accepted_day_and_exact_utc_cutoff(self):
        trivy_gate.validate_policy(self.policy, self.before)
        for now in (datetime(2027, 7, 1, tzinfo=timezone.utc),
                    datetime(2027, 7, 2, tzinfo=timezone.utc)):
            with self.subTest(now=now), self.assertRaisesRegex(ValueError, "expired"):
                trivy_gate.validate_policy(self.policy, now)

    def test_policy_cannot_broaden_or_renew_itself(self):
        changes = {"id": "CVE-2099-0001", "purls": [],
                   "owner": "another-owner", "expired_at": "2028-07-01T00:00:00Z",
                   "statement": ""}
        for key, value in changes.items():
            policy = copy.deepcopy(self.policy)
            policy["vulnerabilities"][0][key] = value
            with self.subTest(field=key), self.assertRaises(ValueError):
                trivy_gate.validate_policy(policy, self.before)
        for policy in ({}, [], {**self.policy, "secrets": []},
                       {"vulnerabilities": self.policy["vulnerabilities"] * 2}):
            with self.subTest(policy=policy), self.assertRaises(ValueError):
                trivy_gate.validate_policy(policy, self.before)

    def test_missing_fields_and_wildcard_package_are_rejected(self):
        for field in self.policy["vulnerabilities"][0]:
            policy = copy.deepcopy(self.policy)
            del policy["vulnerabilities"][0][field]
            with self.subTest(field=field), self.assertRaises(ValueError):
                trivy_gate.validate_policy(policy, self.before)
        for purls in (["pkg:maven/org.springframework/spring-webmvc"],
                      [trivy_gate.PURL, "pkg:maven/example/other@1"]):
            policy = copy.deepcopy(self.policy)
            policy["vulnerabilities"][0]["purls"] = purls
            with self.subTest(purls=purls), self.assertRaises(ValueError):
                trivy_gate.validate_policy(policy, self.before)

    def test_duplicate_policy_keys_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "policy.yaml"
            path.write_text('{"vulnerabilities": [], "vulnerabilities": []}')
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                trivy_gate.read_json(path)


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
