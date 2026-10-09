"""Validate the exact approved risk scope, then use Trivy's native gate filter."""

import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

POLICY = Path(__file__).resolve().parents[1] / "security/trivy-gate-ignore.yaml"
APPROVED = {
    "CVE-2026-47884": ("pkg:maven/org.springframework/spring-webmvc@6.2.19",),
    "CVE-2026-47890": ("pkg:maven/org.springframework/spring-webmvc@6.2.19",
                       "pkg:maven/org.springframework/spring-webflux@6.2.19"),
    "CVE-2026-47892": ("pkg:maven/org.springframework/spring-webflux@6.2.19",),
}
OWNER = "abdullahsayed30"
EXPIRES_AT = "2027-07-01T00:00:00Z"


def unique_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def read_json(path):
    return json.loads(Path(path).read_text(), object_pairs_hook=unique_keys)


def validate_policy(policy, now=None):
    # JSON is a YAML subset: one dependency-free parser and Trivy's native YAML parser
    # read the same policy. Freeze the authorized scope/cutoff, not arbitrary rules.
    if not isinstance(policy, dict) or set(policy) != {"vulnerabilities"}:
        raise ValueError("Only the approved vulnerability exceptions are permitted")
    entries = policy["vulnerabilities"]
    if not isinstance(entries, list) or len(entries) != len(APPROVED):
        raise ValueError("Exactly the three approved CVE entries are required")
    fields = {"id", "purls", "expired_at", "owner", "statement"}
    seen = set()
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != fields:
            raise ValueError("Missing or unexpected risk-acceptance fields")
        cve = entry["id"]
        if (not isinstance(cve, str) or cve not in APPROVED or cve in seen
                or (entry["purls"], entry["owner"], entry["expired_at"]) != (
                    list(APPROVED[cve]), OWNER, EXPIRES_AT)):
            raise ValueError("Exception scope, owner or cutoff differs from the approved decision")
        if not isinstance(entry["statement"], str) or not entry["statement"].strip():
            raise ValueError("An explicit accepted-risk statement is required")
        seen.add(cve)
    cutoff = datetime.fromisoformat(EXPIRES_AT.replace("Z", "+00:00"))
    if (now or datetime.now(timezone.utc)) >= cutoff:
        raise ValueError(f"Risk acceptance expired at {EXPIRES_AT}; no automatic renewal")


def validate_report(report):
    if (not isinstance(report, dict) or report.get("SchemaVersion") != 2
            or report.get("Trivy", {}).get("Version") != "0.74.0"
            or not report.get("ArtifactName") or not report.get("ArtifactType")):
        raise ValueError("Expected native Trivy 0.74.0 schema-2 scan evidence")
    results = report.get("Results", [])
    if not isinstance(results, list):
        raise ValueError("Invalid scan results")
    for result in results:
        if not isinstance(result, dict):
            raise ValueError("Invalid scan result")
        vulnerabilities = result.get("Vulnerabilities", [])
        if not isinstance(vulnerabilities, list):
            raise ValueError("Invalid vulnerability records")
        for finding in vulnerabilities:
            if not isinstance(finding, dict):
                raise ValueError("Invalid vulnerability record")
            cve = finding.get("VulnerabilityID")
            if cve not in APPROVED:
                continue
            identifier = finding.get("PkgIdentifier", {})
            purl = identifier.get("PURL") if isinstance(identifier, dict) else None
            # Trivy's ignore matcher accepts a nil PURL. Its compatibility conversion
            # can replace identifiers without UIDs. Reject either ambiguous case.
            if (not isinstance(purl, str) or not purl or "%" in purl
                    or not isinstance(identifier.get("UID"), str) or not identifier["UID"]):
                raise ValueError("Accepted-risk CVE requires an explicit PURL and UID")
            base_purl = purl.partition("?")[0].partition("#")[0]
            if base_purl in APPROVED[cve]:
                package = base_purl.removeprefix("pkg:maven/").partition("@")[0].replace("/", ":")
                if (purl not in (base_purl, base_purl + "?type=jar")
                        or finding.get("PkgName") != package
                        or finding.get("InstalledVersion") != "6.2.19"):
                    raise ValueError("Accepted-risk package identity is inconsistent or unrecognized")


def is_accepted_finding(finding):
    """For summaries only; native Trivy remains responsible for gate filtering."""
    for purl in APPROVED.get(finding.get("VulnerabilityID"), ()):
        package = purl.removeprefix("pkg:maven/").partition("@")[0].replace("/", ":")
        if (finding.get("PkgIdentifier", {}).get("PURL") in (purl, purl + "?type=jar")
                and finding.get("PkgName") == package
                and finding.get("InstalledVersion") == "6.2.19"):
            return True
    return False


def main(arguments):
    try:
        if len(arguments) != 1:
            raise ValueError("Usage: trivy_gate.py NATIVE_REPORT_JSON")
        validate_policy(read_json(POLICY))
        validate_report(read_json(arguments[0]))
        for cve, purls in APPROVED.items():
            print(f"Gate-only accepted risk: {cve}, {', '.join(purls)}, owner {OWNER}, expires {EXPIRES_AT}", flush=True)
        return subprocess.run([
            os.environ.get("TRIVY", "trivy"), "--config", os.devnull, "convert",
            "--ignorefile", str(POLICY), "--ignore-policy", "",
            "--format", "table", "--scanners", "vuln,secret,misconfig",
            "--severity", "HIGH,CRITICAL", "--exit-code", "1", arguments[0],
        ], check=False).returncode
    except (AttributeError, OSError, TypeError, ValueError) as error:
        print(f"Invalid security gate evidence: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
