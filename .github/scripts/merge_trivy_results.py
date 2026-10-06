"""Combine native source and SBOM finding records for one repository analysis."""

import json
import sys
from pathlib import Path


def merge(source, dependencies):
    if any(report.get("SchemaVersion") != 2 for report in (source, dependencies)):
        raise ValueError("Expected Trivy native JSON schema 2")
    version = source.get("Trivy", {}).get("Version")
    if not version or version != dependencies.get("Trivy", {}).get("Version"):
        raise ValueError("Source and dependency results must use the same Trivy version")
    source_results = source.get("Results", [])
    dependency_results = dependencies.get("Results", [])
    if not isinstance(source_results, list) or not isinstance(dependency_results, list):
        raise ValueError("Invalid native finding records")
    if not any(result.get("Packages") for result in dependency_results):
        raise ValueError("Dependency scan did not discover the resolved SBOM package inventory")
    # Keep complete native finding records; severity filtering belongs to Trivy convert.
    return {**source, "Results": source_results + dependency_results}


if __name__ == "__main__":
    try:
        if len(sys.argv) != 4:
            raise ValueError("Usage: merge_trivy_results.py SOURCE_JSON DEPENDENCIES_JSON OUTPUT_JSON")
        source, dependencies = [json.loads(Path(path).read_text()) for path in sys.argv[1:3]]
        Path(sys.argv[3]).write_text(json.dumps(merge(source, dependencies)) + "\n")
    except (AttributeError, OSError, TypeError, ValueError) as error:
        sys.exit(f"Invalid repository scan evidence: {error}")
