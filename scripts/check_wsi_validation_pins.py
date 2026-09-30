#!/usr/bin/env python3
"""Check the WSI validation manifest, workflow pins and fixture layout.

Fails (exit 1) when

* any pin in ``wsi-validation-manifest.json`` is not a full 40-character
  commit SHA -- in particular the ``TODO-successor`` placeholders used while a
  frontend successor branch is still being ported.  A placeholder is a hard
  failure so the acceptance gate can never pass on an unresolved tuple;
* the workflow ``env`` pins, repositories or matrix refs disagree with the
  manifest, or the frontend matrices do not list exactly the expected
  variants;
* the fixture layout is wrong: the importable fixture studies must hold the
  generated resource files and no legacy ``meta_wsi``/``data_wsi`` pair, while
  ``legacy_wsi_source/`` holds that pair as converter input only.

Set WSI_FRONTEND_GIT_DIR to a frontend clone to also check the frontend
ancestry locally.  Every problem is reported before exiting.  Requires PyYAML.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "wsi-validation-manifest.json"
WORKFLOW = ROOT / ".github/workflows/wsi-validation.yml"
FIXTURE_DIR = ROOT / "src/e2e/js/test/WsiHierarchyController"
PLACEHOLDER = "TODO-successor"
SHA = re.compile(r"^[0-9a-f]{40}$")

# workflow env name -> manifest path
ENV_PINS = {
    "BACKEND_REPOSITORY": ("backend", "repository"),
    "BACKEND_REF": ("backend", "ref"),
    "CORE_REPOSITORY": ("core", "repository"),
    "CORE_REF": ("core", "ref"),
    "TILE_REPOSITORY": ("tile", "repository"),
    "TILE_REF": ("tile", "ref"),
    "TEST_STACK_REPOSITORY": ("testStack", "repository"),
    "TEST_STACK_REF": ("testStack", "ref"),
    "COMPOSE_REPOSITORY": ("compose", "repository"),
    "COMPOSE_REF": ("compose", "ref"),
    "FRONTEND_REPOSITORY": ("frontend", "repository"),
    "FRONTEND_PACKAGE_REF": ("frontend", "package"),
    "FRONTEND_STUDY_REF": ("frontend", "study"),
    "FRONTEND_PATIENT_REF": ("frontend", "patient"),
    "FRONTEND_MOLECULAR_REF": ("frontend", "molecular"),
    "FRONTEND_ANNOTATIONS_REF": ("frontend", "annotations"),
    "FRONTEND_AGENT_REF": ("frontend", "agent"),
    "FRONTEND_INTEGRATION_REF": ("frontend", "integration"),
}
COMMIT_PINS = [path for name, path in ENV_PINS.items() if name.endswith("_REF")]
# matrix variant -> (manifest key of its checked-out ref, manifest key of an
# additional ancestor it must contain, or None)
VARIANTS = {
    "package": ("package", None),
    "study": ("study", None),
    "patient": ("patient", None),
    "molecular": ("molecular", None),
    "annotations-agent": ("agent", "annotations"),
    "integration": ("integration", None),
}
FIXTURES = ("wsi_loader_fixture", "wsi_loader_control_fixture")
GENERATED_FILES = {
    "meta_resource_definition.txt",
    "data_resource_definition.txt",
    "meta_resource_sample.txt",
    "data_resource_sample.txt",
    "meta_resource_patient.txt",
    "data_resource_patient.txt",
    "meta_clinical_samples.txt",
    "data_clinical_samples.txt",
    "meta_clinical_patients.txt",
    "data_clinical_patients.txt",
}
LEGACY_FILES = {"meta_wsi.txt", "data_wsi.txt"}


def lookup(document: dict, path: tuple[str, ...]):
    value = document
    for key in path:
        if not isinstance(value, dict) or key not in value:
            return None
        value = value[key]
    return value


def main() -> int:
    errors: list[str] = []
    placeholders: list[str] = []
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    workflow = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
    env = workflow.get("env", {})

    # 1. Every manifest commit pin is a full SHA; placeholders are failures.
    for path in COMMIT_PINS:
        value = lookup(manifest, path)
        label = ".".join(path)
        if value == PLACEHOLDER:
            placeholders.append(label)
        elif not isinstance(value, str) or not SHA.match(value):
            errors.append(f"manifest {label} is not a full commit SHA: {value!r}")

    # 2. Workflow env mirrors the manifest.
    for name, path in ENV_PINS.items():
        expected = lookup(manifest, path)
        if env.get(name) != expected:
            errors.append(
                f"workflow env {name}={env.get(name)!r} does not match manifest "
                f"{'.'.join(path)}={expected!r}"
            )

    # 3. Frontend matrices list exactly the expected variants, with manifest refs.
    for job_name, key in (("frontend-children", "name"), ("full-stack-wsi-validation", "variant")):
        job = workflow.get("jobs", {}).get(job_name)
        if job is None:
            errors.append(f"workflow has no {job_name} job")
            continue
        rows = job.get("strategy", {}).get("matrix", {}).get("include", [])
        seen = [row.get(key) for row in rows]
        if sorted(seen) != sorted(VARIANTS):
            errors.append(f"{job_name} matrix variants {seen} != {sorted(VARIANTS)}")
        for row in rows:
            variant = row.get(key)
            if variant not in VARIANTS:
                continue
            ref_key, ancestor_key = VARIANTS[variant]
            expected = manifest["frontend"].get(ref_key)
            if row.get("ref") != expected:
                errors.append(
                    f"{job_name}[{variant}] ref {row.get('ref')!r} != manifest frontend.{ref_key} {expected!r}"
                )
            if ancestor_key is not None:
                expected_ancestor = manifest["frontend"].get(ancestor_key)
                if row.get("ancestor") != expected_ancestor:
                    errors.append(
                        f"{job_name}[{variant}] ancestor {row.get('ancestor')!r} != manifest "
                        f"frontend.{ancestor_key} {expected_ancestor!r}"
                    )
            for field, value in row.items():
                if isinstance(value, str) and PLACEHOLDER in value and f"{job_name}[{variant}].{field}" not in placeholders:
                    placeholders.append(f"{job_name}[{variant}].{field}")

    # Job-level env (e.g. the combined browser test list) may hold placeholders too.
    for job_name, job in workflow.get("jobs", {}).items():
        for name, value in (job.get("env") or {}).items():
            if isinstance(value, str) and PLACEHOLDER in value:
                placeholders.append(f"{job_name}.env.{name}")

    # 3b. Optional: with WSI_FRONTEND_GIT_DIR pointing at a frontend clone that
    # has the pinned objects, check the ancestry the CI jobs enforce.
    frontend_git = os.environ.get("WSI_FRONTEND_GIT_DIR")
    frontend = manifest.get("frontend", {})
    if frontend_git and not placeholders:
        edges = [(frontend["package"], frontend[k], f"{k} contains package")
                 for k in ("study", "patient", "molecular", "annotations", "agent", "integration")]
        edges.append((frontend["annotations"], frontend["agent"], "agent contains annotations"))
        edges += [(frontend[k], frontend["integration"], f"integration contains {k}")
                  for k in ("study", "patient", "molecular", "annotations", "agent")]
        for ancestor, descendant, label in edges:
            result = subprocess.run(
                ["git", "-C", frontend_git, "merge-base", "--is-ancestor", ancestor, descendant],
                capture_output=True,
            )
            if result.returncode != 0:
                errors.append(f"frontend ancestry check failed: {label} ({ancestor[:9]} -> {descendant[:9]})")
            else:
                print(f"ok frontend ancestry: {label}")

    # 4. Fixture authorization metadata and resource-data layout.
    allowed = (FIXTURE_DIR / "wsi_loader_fixture/meta_study.txt").read_text(encoding="utf-8")
    denied = (FIXTURE_DIR / "wsi_loader_control_fixture/meta_study.txt").read_text(encoding="utf-8")
    if "groups: PUBLIC_STUDIES" not in allowed:
        errors.append("wsi_loader_fixture is not in the PUBLIC_STUDIES group")
    if "groups: WSI_DENIED" not in denied:
        errors.append("wsi_loader_control_fixture is not in the WSI_DENIED group")
    for name in FIXTURES:
        study = {p.name for p in (FIXTURE_DIR / name).iterdir()}
        legacy = {p.name for p in (FIXTURE_DIR / "legacy_wsi_source" / name).iterdir()}
        if study & LEGACY_FILES:
            errors.append(f"{name} contains legacy WSI input {sorted(study & LEGACY_FILES)}")
        missing = GENERATED_FILES - study
        # A study without unmatched slides legitimately has no patient resource pair.
        if missing - {"meta_resource_patient.txt", "data_resource_patient.txt"}:
            errors.append(f"{name} lacks generated resource files {sorted(missing)}")
        if not LEGACY_FILES <= legacy:
            errors.append(f"legacy_wsi_source/{name} lacks the converter input pair")
        for data_file in ("data_resource_sample.txt", "data_resource_patient.txt"):
            path = FIXTURE_DIR / name / data_file
            if path.is_file() and "WHOLE_SLIDE_IMAGE" not in path.read_text(encoding="utf-8"):
                errors.append(f"{name}/{data_file} has no WHOLE_SLIDE_IMAGE rows")
    base_url = lookup(manifest, ("fixtures", "portalBaseUrl"))
    generator = (ROOT / "scripts/wsi_resource_fixtures.py").read_text(encoding="utf-8")
    if not base_url or f'DEFAULT_PORTAL_BASE_URL = "{base_url}"' not in generator:
        errors.append(f"manifest fixtures.portalBaseUrl {base_url!r} does not match the generator default")

    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    if placeholders:
        print(
            "ERROR: unresolved successor pins (placeholders are a hard failure until the "
            "successor branches are ready): " + ", ".join(placeholders),
            file=sys.stderr,
        )
    if errors or placeholders:
        return 1
    print("WSI validation manifest, workflow pins and fixture layout are consistent")
    return 0


if __name__ == "__main__":
    sys.exit(main())
