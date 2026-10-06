"""Fail the overall check unless every required CI job succeeded."""

import json
import os
import sys
from pathlib import Path

REQUIRED_JOBS = {
    "quality", "repository-security", "container-security", "static-analysis"
}


def failures(results, integration_enabled):
    issues = []
    if integration_enabled != "true":
        issues.append("Testcontainers integration tests were disabled")
    if not isinstance(results, dict) or set(results) != REQUIRED_JOBS:
        issues.append("Required job results are missing or unexpected")
        return issues
    for name in sorted(REQUIRED_JOBS):
        job = results[name]
        outcome = job.get("result") if isinstance(job, dict) else None
        if outcome != "success":
            issues.append(f"{name}: {outcome or 'missing result'}")
    return issues


def main():
    try:
        results = json.loads(os.environ["CI_RESULTS"])
        issues = failures(results, os.environ.get("CI_INTEGRATION_ENABLED"))
    except (KeyError, ValueError) as error:
        issues = [f"Invalid required-job evidence: {error}"]
    message = "\n".join(issues) if issues else "All required CI gates succeeded."
    print(message)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as summary:
            summary.write("## CI required\n\n" + message + "\n")
    return 1 if issues else 0


if __name__ == "__main__":
    sys.exit(main())
