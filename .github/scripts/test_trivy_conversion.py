"""Test the pinned Trivy CLI's severity gate without scanning or downloading a DB."""

import json
import os
import copy
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import trivy_gate


class TrivyConversionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.report = self.directory / "report.json"
        self.trivy = os.environ.get("TRIVY", "trivy")

    def fixture(self, field, severity):
        items = {
            "Vulnerabilities": {"VulnerabilityID": "CVE-2099-0001", "PkgName": "fixture", "InstalledVersion": "1", "Title": "Synthetic vulnerability"},
            "Secrets": {"RuleID": "fixture-secret", "Title": "Synthetic secret", "Match": "REDACTED"},
            "Misconfigurations": {"ID": "fixture-config", "Title": "Synthetic misconfiguration", "Status": "FAIL"},
        }
        item = {**items[field], "Severity": severity}
        self.report.write_text(json.dumps({
            "SchemaVersion": 2, "ArtifactName": "ci-fixture", "ArtifactType": "filesystem",
            "Results": [{"Target": "fixture", "Class": "lang-pkgs", "Type": "jar", field: [item]}],
        }))

    def convert(self, *arguments):
        if "--ignorefile" not in arguments:
            arguments = ("--ignorefile", os.devnull, *arguments)
        return subprocess.run(
            [self.trivy, "convert", *arguments, str(self.report)],
            capture_output=True, text=True, cwd=self.directory,
        )

    def risk_fixture(self, purl=trivy_gate.PURL):
        self.fixture("Vulnerabilities", "CRITICAL")
        report = json.loads(self.report.read_text())
        report["Trivy"] = {"Version": "0.74.0"}
        finding = report["Results"][0]["Vulnerabilities"][0]
        finding.update({"VulnerabilityID": trivy_gate.CVE,
                        "PkgName": "org.springframework:spring-webmvc",
                        "InstalledVersion": "6.2.19",
                        "PkgIdentifier": {"PURL": purl, "UID": "fixture-uid"}})
        report["Results"][0]["Packages"] = [{
            "ID": "org.springframework:spring-webmvc:6.2.19",
            "Name": "org.springframework:spring-webmvc", "Version": "6.2.19",
            "Identifier": {"PURL": purl, "UID": "fixture-uid"},
        }]
        self.report.write_text(json.dumps(report))
        return report

    def gate(self):
        return subprocess.run(
            [sys.executable, str(self.gate_script), str(self.report)],
            env={**os.environ, "TRIVY": self.trivy}, capture_output=True, text=True,
        )

    def copy_gate(self):
        scripts = self.directory / ".github/scripts"
        scripts.mkdir(parents=True)
        self.gate_script = scripts / "trivy_gate.py"
        shutil.copyfile(trivy_gate.__file__, self.gate_script)
        self.policy = scripts.parent / "security/trivy-gate-ignore.yaml"
        self.policy.parent.mkdir()
        shutil.copyfile(trivy_gate.POLICY, self.policy)

    def test_pinned_convert_applies_approved_purl_to_repository_and_image_shapes(self):
        self.copy_gate()
        for purl in (trivy_gate.PURL, trivy_gate.PURL + "?type=jar"):
            with self.subTest(purl=purl):
                self.risk_fixture(purl)
                original = self.report.read_bytes()
                unexcepted = self.convert("--format", "table", "--severity", "HIGH,CRITICAL", "--exit-code", "1")
                self.assertEqual(1, unexcepted.returncode, unexcepted.stderr)
                accepted = self.gate()
                self.assertEqual(0, accepted.returncode, accepted.stderr)
                self.assertIn("Gate-only accepted risk", accepted.stdout)
                self.assertEqual(original, self.report.read_bytes())

    def test_other_cve_package_version_and_namespace_remain_blocking(self):
        self.copy_gate()
        changes = [
            {"VulnerabilityID": "CVE-2099-0001"},
            {"PkgIdentifier": {"PURL": "pkg:maven/org.springframework/spring-webmvc@6.2.18", "UID": "fixture-uid"}, "InstalledVersion": "6.2.18"},
            {"PkgIdentifier": {"PURL": "pkg:maven/org.springframework/spring-core@6.2.19", "UID": "fixture-uid"}, "PkgName": "org.springframework:spring-core"},
            {"PkgIdentifier": {"PURL": "pkg:maven/example/spring-webmvc@6.2.19", "UID": "fixture-uid"}, "PkgName": "example:spring-webmvc"},
        ]
        for change in changes:
            with self.subTest(change=change):
                report = self.risk_fixture()
                report["Results"][0]["Vulnerabilities"][0].update(change)
                self.report.write_text(json.dumps(report))
                result = self.gate()
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn("Gate-only accepted risk", result.stdout)  # Native convert ran.

    def test_missing_purl_uid_or_inconsistent_identity_fails_closed(self):
        self.copy_gate()
        for change in ({"PkgIdentifier": {}}, {"PkgIdentifier": {"PURL": trivy_gate.PURL}},
                       {"InstalledVersion": "6.2.18"}, {"PkgName": "example:other"},
                       {"PkgIdentifier": {"PURL": trivy_gate.PURL + "?type=war", "UID": "fixture-uid"}}):
            with self.subTest(change=change):
                report = self.risk_fixture()
                report["Results"][0]["Vulnerabilities"][0].update(change)
                self.report.write_text(json.dumps(report))
                result = self.gate()
                self.assertEqual(1, result.returncode)
                self.assertIn("Invalid security gate evidence", result.stderr)

    def test_native_missing_purl_hazard_and_missing_policy_are_guarded(self):
        self.copy_gate()
        report = self.risk_fixture()
        report["Results"][0]["Vulnerabilities"][0]["PkgIdentifier"].pop("PURL")
        self.report.write_text(json.dumps(report))
        native = self.convert("--ignorefile", str(self.policy), "--format", "table",
                              "--severity", "HIGH,CRITICAL", "--exit-code", "1")
        self.assertEqual(0, native.returncode, native.stderr)  # Native nil-PURL match is unsafe.
        self.assertEqual(1, self.gate().returncode)
        report["Results"] = []
        self.report.write_text(json.dumps(report))
        self.policy.unlink()
        native = self.convert("--ignorefile", str(self.policy), "--format", "table",
                              "--severity", "HIGH,CRITICAL", "--exit-code", "1")
        self.assertEqual(0, native.returncode, native.stderr)  # Native missing file is optional.
        self.assertEqual(1, self.gate().returncode)

    def test_secrets_misconfigurations_and_other_vulnerabilities_still_fail(self):
        self.copy_gate()
        for field in ("Secrets", "Misconfigurations", "Vulnerabilities"):
            with self.subTest(scanner=field):
                self.fixture(field, "HIGH")
                extra = json.loads(self.report.read_text())["Results"][0][field][0]
                report = self.risk_fixture()
                report["Results"][0].setdefault(field, []).append(extra)
                self.report.write_text(json.dumps(report))
                result = self.gate()
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn("Gate-only accepted risk", result.stdout)

    def test_native_expiration_uses_timestamp_instead_of_last_accepted_date(self):
        self.risk_fixture()
        policy = trivy_gate.read_json(trivy_gate.POLICY)
        path = self.directory / "native-ignore.yaml"
        for timestamp, expected in (("2000-07-01T00:00:00Z", 1), ("2100-07-01T00:00:00Z", 0)):
            with self.subTest(timestamp=timestamp):
                policy["vulnerabilities"][0]["expired_at"] = timestamp
                path.write_text(json.dumps(policy))
                result = self.convert("--ignorefile", str(path), "--format", "table", "--severity", "HIGH,CRITICAL", "--exit-code", "1")
                self.assertEqual(expected, result.returncode, result.stderr)

    def test_absent_malformed_broadened_or_renewed_policy_never_passes(self):
        self.copy_gate()
        self.risk_fixture()
        valid = self.policy.read_text()
        payloads = [None, "{", "{}"]
        for field, value in (("owner", "other-owner"), ("expired_at", "2028-07-01T00:00:00Z"),
                             ("expired_at", "2000-07-01T00:00:00Z"), ("purls", []),
                             ("id", "CVE-2099-0001")):
            policy = json.loads(valid)
            policy["vulnerabilities"][0][field] = value
            payloads.append(json.dumps(policy))
        for payload in payloads:
            with self.subTest(payload=payload):
                if payload is None:
                    self.policy.unlink()
                else:
                    self.policy.write_text(payload)
                result = self.gate()
                self.assertEqual(1, result.returncode)
                self.assertIn("Invalid security gate evidence", result.stderr)

    def test_gate_rejects_missing_malformed_or_wrong_version_report(self):
        self.copy_gate()
        for payload in (None, "{", "{}", '{"SchemaVersion": 2, "Trivy": null}'):
            with self.subTest(payload=payload):
                if payload is not None:
                    self.report.write_text(payload)
                result = self.gate()
                self.assertEqual(1, result.returncode)
                self.assertIn("Invalid security gate evidence", result.stderr)
        report = self.risk_fixture()
        report["Trivy"]["Version"] = "0.73.0"
        self.report.write_text(json.dumps(report))
        self.assertEqual(1, self.gate().returncode)

    def test_full_json_sarif_and_sbom_keep_accepted_risk_finding(self):
        self.copy_gate()
        self.risk_fixture()
        original = self.report.read_bytes()
        # A broad default ignore must not influence full report conversion.
        (self.directory / ".trivyignore").write_text(trivy_gate.CVE)
        self.assertEqual(0, self.gate().returncode)
        for format_name in ("sarif", "cyclonedx"):
            with self.subTest(format=format_name):
                output = self.directory / ("full." + format_name)
                result = self.convert("--format", format_name, "--output", str(output))
                self.assertEqual(0, result.returncode, result.stderr)
                converted = json.loads(output.read_text())
                if format_name == "sarif":
                    self.assertTrue(any(trivy_gate.CVE in item["ruleId"] for item in converted["runs"][0]["results"]))
                else:
                    self.assertTrue(any(item["id"] == trivy_gate.CVE for item in converted["vulnerabilities"]))
                    self.assertTrue(any(item["purl"] == trivy_gate.PURL for item in converted["components"]))
        self.assertEqual(original, self.report.read_bytes())

    def test_high_and_critical_findings_fail_for_every_scanner(self):
        for field in ("Vulnerabilities", "Secrets", "Misconfigurations"):
            for severity in ("HIGH", "CRITICAL"):
                with self.subTest(scanner=field, severity=severity):
                    self.fixture(field, severity)
                    result = self.convert("--format", "table", "--scanners", "vuln,secret,misconfig", "--severity", "HIGH,CRITICAL", "--exit-code", "1")
                    self.assertEqual(1, result.returncode, result.stderr)

    def test_reported_medium_findings_do_not_fail_gate(self):
        for field in ("Vulnerabilities", "Secrets", "Misconfigurations"):
            with self.subTest(scanner=field):
                self.fixture(field, "MEDIUM")
                result = self.convert("--format", "table", "--severity", "HIGH,CRITICAL", "--exit-code", "1")
                self.assertEqual(0, result.returncode, result.stderr)

    def test_full_sarif_preserves_medium_findings(self):
        self.fixture("Vulnerabilities", "MEDIUM")
        sarif = self.directory / "report.sarif"
        result = self.convert("--format", "sarif", "--output", str(sarif))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, len(json.loads(sarif.read_text())["runs"][0]["results"]))

    def test_cyclonedx_conversion_keeps_package_inventory(self):
        self.fixture("Vulnerabilities", "MEDIUM")
        report = json.loads(self.report.read_text())
        report["Results"][0]["Packages"] = [{"ID": "fixture@1", "Name": "fixture", "Version": "1", "Identifier": {"PURL": "pkg:maven/example/fixture@1"}}]
        self.report.write_text(json.dumps(report))
        sbom = self.directory / "image.cdx.json"
        result = self.convert("--format", "cyclonedx", "--output", str(sbom))
        self.assertEqual(0, result.returncode, result.stderr)
        packages = json.loads(sbom.read_text())["components"]
        self.assertTrue(any(package.get("name") == "fixture" for package in packages))

    def test_sbom_scan_reads_resolved_cyclonedx_inventory(self):
        sbom = self.directory / "polaris-sbom.json"
        sbom.write_text(json.dumps({
            "bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
            "components": [{"type": "library", "group": "example", "name": "fixture",
                            "version": "1", "purl": "pkg:maven/example/fixture@1"}],
        }))
        result = subprocess.run(
            [self.trivy, "sbom", "--scanners", "license", "--format", "json",
             "--list-all-pkgs", str(sbom)],
            capture_output=True, text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        packages = [package for entry in json.loads(result.stdout)["Results"]
                    for package in entry.get("Packages", [])]
        self.assertTrue(any(package.get("Name") == "example:fixture" for package in packages), result.stdout)

    def test_source_scan_keeps_maven_resolution_out_of_secret_analysis(self):
        (self.directory / "pom.xml").write_text(
            '<project><modelVersion>4.0.0</modelVersion><parent>'
            '<groupId>example.invalid</groupId><artifactId>unresolvable-parent</artifactId>'
            '<version>1</version></parent><artifactId>fixture</artifactId></project>'
        )
        result = subprocess.run(
            [self.trivy, "fs", "--scanners", "secret", "--pkg-types", "os",
             "--offline-scan", "--skip-version-check", "--format", "json", str(self.directory)],
            capture_output=True, text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], json.loads(result.stdout).get("Results", []))
        self.assertNotIn("[pom]", result.stderr)

    def test_missing_or_malformed_report_fails(self):
        for content in (None, "{"):
            with self.subTest(content=content):
                if content is not None:
                    self.report.write_text(content)
                result = self.convert("--format", "table", "--severity", "HIGH,CRITICAL", "--exit-code", "1")
                self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
