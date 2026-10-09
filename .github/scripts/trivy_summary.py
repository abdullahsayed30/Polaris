"""Publish bounded, unsuppressed native findings to the Actions run Summary."""

import html
import os
import sys
from pathlib import Path
from urllib.parse import quote, urlsplit

import trivy_gate

SEVERITIES = ("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN")
TARGETS = {"repository", "gateway", "order-service", "inventory-service", "notification-service"}
ROW_LIMIT = 25


def text(value, limit=100):
    value = " ".join(str(value or "—").split())
    value = "".join(character for character in value if character.isprintable())
    if len(value) > limit:
        value = value[:limit - 1] + "…"
    value = html.escape(value, quote=False)
    for character in ("\\", "|", "`", "[", "]", "*", "_"):
        value = value.replace(character, "\\" + character)
    return value


def advisory(finding):
    url = finding.get("PrimaryURL", "")
    if not isinstance(url, str) or any(character.isspace() for character in url):
        return "—"
    try:
        parsed = urlsplit(url)
    except ValueError:
        return "—"
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        return "—"
    return "[Advisory](" + quote(url, safe="/:?=&%#@+,-._~") + ")"


def findings(report):
    trivy_gate.validate_report(report)
    groups = {"Vulnerabilities": [], "Secrets": [], "Misconfigurations": []}
    for result in report.get("Results", []):
        for field in groups:
            records = result.get(field, [])
            if not isinstance(records, list):
                raise ValueError(f"Invalid {field} records")
            for finding in records:
                if not isinstance(finding, dict):
                    raise ValueError(f"Invalid {field} record")
                severity = finding.get("Severity") or "UNKNOWN"
                if severity not in SEVERITIES:
                    raise ValueError("Unrecognized finding severity")
                # Native convert counts only failing misconfigurations as blockers.
                if field == "Misconfigurations" and finding.get("Status") != "FAIL":
                    continue
                groups[field].append({**finding, "Severity": severity})
    return groups


def render(target, report, policy, prefix="polaris", now=None):
    heading = f"## Trivy — {text(target)}\n\n"
    try:
        groups = findings(report)
    except (AttributeError, TypeError, ValueError) as error:
        return heading + f"**Scan evidence unavailable or invalid:** {text(error)}. No zero findings or passing gate is claimed.\n", False
    policy_error = None
    try:
        trivy_gate.validate_policy(policy, now)
    except (AttributeError, TypeError, ValueError) as error:
        policy_error = str(error)
    all_findings = sum(groups.values(), [])
    counts = [sum(finding["Severity"] == severity for finding in all_findings) for severity in SEVERITIES]
    lines = [heading, "Raw findings include vulnerabilities, secrets and failing misconfigurations.\n",
             "| Critical | High | Medium | Low | Unknown |",
             "| ---: | ---: | ---: | ---: | ---: |",
             "| " + " | ".join(map(str, counts)) + " |\n",
             "| Finding kind | Raw total | Raw HIGH/CRITICAL | Effective blocking |",
             "| --- | ---: | ---: | ---: |"]
    accepted = [finding for finding in groups["Vulnerabilities"]
                if trivy_gate.is_accepted_finding(finding)] if not policy_error else []
    for field, label in (("Vulnerabilities", "Vulnerabilities"), ("Secrets", "Secrets"),
                         ("Misconfigurations", "Failing misconfigurations")):
        raw = sum(finding["Severity"] in ("HIGH", "CRITICAL") for finding in groups[field])
        removed = sum(finding["Severity"] in ("HIGH", "CRITICAL") for finding in accepted) if field == "Vulnerabilities" else 0
        effective = "unavailable" if policy_error else str(raw - removed)
        lines.append(f"| {label} | {len(groups[field])} | {raw} | {effective} |")
    if policy_error:
        lines.append(f"\n**Risk-acceptance policy invalid; gate cannot pass:** {text(policy_error)}.\n")
    else:
        lines.append(f"\n**Gate-only accepted risk:** {trivy_gate.CVE}, {text(trivy_gate.PURL)}, "
                     f"owner {trivy_gate.OWNER}; expires after **June 30, 2027 UTC**, "
                     f"enforced from **{trivy_gate.EXPIRES_AT}**. Matching raw findings: **{len(accepted)}**. "
                     "The vulnerable version remains installed; runtime applicability is unproven. "
                     "This is accepted risk, not a patch or a VEX non-applicability assertion.\n")
    lines.extend(["| Vulnerability | Package | Installed | Fixed | Severity | Gate status | Source |",
                  "| --- | --- | --- | --- | --- | --- | --- |"])
    vulnerabilities = sorted(groups["Vulnerabilities"], key=lambda finding: (
        SEVERITIES.index(finding["Severity"]), str(finding.get("VulnerabilityID", "")),
        str(finding.get("PkgName", ""))))
    for finding in vulnerabilities[:ROW_LIMIT]:
        if finding in accepted:
            status = "Accepted / suppressed for gate only"
        elif finding["Severity"] in ("HIGH", "CRITICAL"):
            status = "Blocking"
        else:
            status = "Below blocking threshold"
        cells = [text(finding.get(field)) for field in (
            "VulnerabilityID", "PkgName", "InstalledVersion", "FixedVersion", "Severity")]
        lines.append("| " + " | ".join([*cells, status, advisory(finding)]) + " |")
    if not vulnerabilities:
        lines.append("\nNo vulnerability findings in the available scan evidence.")
    elif len(vulnerabilities) > ROW_LIMIT:
        lines.append(f"\nShowing {ROW_LIMIT} of {len(vulnerabilities)} vulnerability rows; see full artifacts.")
    report_name = f"{prefix}-{target}-security"
    sbom_name = f"{prefix}-dependency-sbom" if target == "repository" else f"{prefix}-{target}-sbom"
    lines.append(f"\nFull unsuppressed JSON/SARIF: artifact **{text(report_name)}**. "
                 f"SBOM: artifact **{text(sbom_name)}** in this run. Secret values are never included in this summary. "
                 "Counts explain findings; the native severity gate and final CI check determine success.\n")
    return "\n".join(lines), not policy_error


def main(arguments):
    try:
        if len(arguments) != 2 or arguments[0] not in TARGETS:
            raise ValueError("Usage: trivy_summary.py TARGET NATIVE_REPORT_JSON")
        target, report_path = arguments
        report = trivy_gate.read_json(report_path)
        try:
            policy = trivy_gate.read_json(trivy_gate.POLICY)
        except (OSError, ValueError):
            policy = None
        markdown, valid = render(target, report, policy, os.environ.get("CI_ARTIFACT_PREFIX", "polaris"))
    except (AttributeError, OSError, TypeError, ValueError) as error:
        target = arguments[0] if arguments else "unavailable target"
        markdown = (f"## Trivy — {text(target)}\n\n**Scan evidence unavailable or invalid:** "
                    f"{text(error)}. No zero findings or passing gate is claimed. "
                    "Inspect the scan step logs and any reports uploaded in this run.\n")
        valid = False
    print(markdown)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as summary:
            summary.write(markdown)
    return 0 if valid else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
