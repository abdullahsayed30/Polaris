"""Test the pinned Trivy CLI's severity gate without scanning or downloading a DB."""

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


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
        return subprocess.run(
            [self.trivy, "convert", *arguments, str(self.report)],
            capture_output=True, text=True,
        )

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
