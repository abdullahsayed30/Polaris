"""Bind the dependency SBOM to the tested checkout and current workflow run."""

import hashlib
import json
import os
import sys
from pathlib import Path


def identity(directory):
    return {
        "sha": os.environ["GITHUB_SHA"],
        "run_id": os.environ["GITHUB_RUN_ID"],
        "sbom_sha256": hashlib.sha256((directory / "polaris-sbom.json").read_bytes()).hexdigest(),
    }


def process(mode, directory):
    expected = identity(directory)
    manifest = directory / "ci-source.json"
    if mode == "write":
        manifest.write_text(json.dumps(expected, indent=2) + "\n", encoding="utf-8")
    elif mode == "verify":
        actual = json.loads(manifest.read_text(encoding="utf-8"))
        if actual != expected:
            raise ValueError("SBOM source, workflow run or checksum does not match")
    else:
        raise ValueError("Expected write or verify")


if __name__ == "__main__":
    try:
        if len(sys.argv) != 3:
            raise ValueError("Usage: ci_source.py write|verify DIRECTORY")
        process(sys.argv[1], Path(sys.argv[2]))
    except (KeyError, OSError, ValueError) as error:
        sys.exit(f"Invalid SBOM provenance: {error}")
