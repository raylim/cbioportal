#!/usr/bin/env python3
"""Generate, check, and validate the WSI resource-data E2E fixtures.

The committed importable fixture studies under
``src/e2e/js/test/WsiHierarchyController/<fixture>/`` are *generated*: the
legacy ``meta_wsi.txt``/``data_wsi.txt`` pairs in ``legacy_wsi_source/`` are
converter input only, and the pinned cBioPortal Core
``scripts/importer/convertWsiToResources.py`` turns them into standard
resource files (``WSI_SAMPLE``/``WSI_PATIENT`` resource rows of type
``WHOLE_SLIDE_IMAGE``) plus clinical files carrying the ``WSI_*`` slide-count
attributes.  Everything else in the legacy source directory (meta_study,
timeline files, ...) is copied through unchanged, and the legacy pair itself
is never part of an importable study.

Modes:

``--write``     regenerate the committed fixture directories in place.
``--check``     regenerate into a temporary directory and fail with a diff if
                the committed fixtures differ (used by CI against the pinned
                Core revision).
``--validate``  run Core ``validateData.py`` on every generated study (with
                ``--no_portal_checks``) and require zero errors.

``--check`` and ``--validate`` may be combined; ``--validate`` then runs on the
freshly generated copies, not on the committed files.
"""

from __future__ import annotations

import argparse
import difflib
import filecmp
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURE_DIR = ROOT / "src/e2e/js/test/WsiHierarchyController"
LEGACY_DIR = FIXTURE_DIR / "legacy_wsi_source"
FIXTURES = ("wsi_loader_fixture", "wsi_loader_control_fixture")
LEGACY_FILES = {"meta_wsi.txt", "data_wsi.txt"}
# The URL embedded in every generated resource row.  It is the backend origin
# the full-stack validation stack exposes; the standalone viewer route is
# served by the backend itself.
DEFAULT_PORTAL_BASE_URL = "http://localhost:8080"


def core_dir(value: str | None) -> Path:
    path = Path(value or os.environ.get("CORE_DIR", "")).resolve()
    converter = path / "scripts/importer/convertWsiToResources.py"
    if not converter.is_file():
        raise SystemExit(
            f"--core-dir/CORE_DIR must point at a cBioPortal Core checkout "
            f"(missing {converter})"
        )
    return path


def generate(core: Path, portal_base_url: str, target: Path) -> None:
    converter = core / "scripts/importer/convertWsiToResources.py"
    for name in FIXTURES:
        source = LEGACY_DIR / name
        study = target / name
        if study.exists():
            shutil.rmtree(study)
        study.mkdir(parents=True)
        with tempfile.TemporaryDirectory(prefix=f"wsi-convert-{name}-") as tmp:
            converted = Path(tmp) / "out"
            subprocess.run(
                [
                    sys.executable,
                    str(converter),
                    "--meta-wsi", str(source / "meta_wsi.txt"),
                    "--study-dir", str(source),
                    "--output-dir", str(converted),
                    "--portal-base-url", portal_base_url,
                ],
                check=True,
                stdout=subprocess.DEVNULL,
            )
            for item in sorted(source.iterdir()):
                if item.name not in LEGACY_FILES:
                    shutil.copy2(item, study / item.name)
            # Converter output wins: it replaces the clinical files it merged
            # the WSI counts into and adds the resource meta/data pairs.
            for item in sorted(converted.iterdir()):
                shutil.copy2(item, study / item.name)
        leaked = sorted(LEGACY_FILES & {p.name for p in study.iterdir()})
        if leaked:
            raise SystemExit(f"{study} still contains legacy WSI files: {leaked}")


def compare(expected_root: Path, actual_root: Path) -> list[str]:
    problems: list[str] = []
    for name in FIXTURES:
        expected, actual = expected_root / name, actual_root / name
        expected_files = {p.name for p in expected.iterdir()} if expected.is_dir() else set()
        actual_files = {p.name for p in actual.iterdir()}
        for missing in sorted(actual_files - expected_files):
            problems.append(f"{name}/{missing}: generated but not committed")
        for extra in sorted(expected_files - actual_files):
            problems.append(f"{name}/{extra}: committed but not generated")
        for common in sorted(expected_files & actual_files):
            if filecmp.cmp(expected / common, actual / common, shallow=False):
                continue
            diff = difflib.unified_diff(
                (expected / common).read_text(encoding="utf-8").splitlines(True),
                (actual / common).read_text(encoding="utf-8").splitlines(True),
                fromfile=f"committed/{name}/{common}",
                tofile=f"generated/{name}/{common}",
            )
            problems.append("".join(diff))
    return problems


def validate(core: Path, root: Path) -> None:
    validator = core / "scripts/importer/validateData.py"
    env = dict(os.environ)
    env["PYTHONPATH"] = os.pathsep.join(
        filter(None, [str(core / "scripts"), env.get("PYTHONPATH")])
    )
    for name in FIXTURES:
        study = root / name
        result = subprocess.run(
            [sys.executable, str(validator), "-s", str(study), "-n"],
            env=env,
            capture_output=True,
            text=True,
        )
        output = result.stdout + result.stderr
        print(f"--- validateData.py {name} (exit {result.returncode}) ---")
        print(output.rstrip())
        # validateData.py exits 0 on success and 3 when only warnings were
        # reported; 1/2 mean errors or an aborted validation.
        errors = [line for line in output.splitlines() if re.search(r"\bERROR\b", line)]
        if result.returncode not in (0, 3) or errors:
            raise SystemExit(f"validateData.py reported errors for {name}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n", 1)[0])
    parser.add_argument("--core-dir", help="cBioPortal Core checkout (default: $CORE_DIR)")
    parser.add_argument("--portal-base-url", default=DEFAULT_PORTAL_BASE_URL)
    mode = parser.add_argument_group("modes")
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--validate", action="store_true")
    args = parser.parse_args()
    if args.write and args.check:
        parser.error("--write and --check are mutually exclusive")
    if not (args.write or args.check or args.validate):
        parser.error("choose at least one of --write, --check, --validate")
    core = core_dir(args.core_dir)

    if args.write:
        generate(core, args.portal_base_url, FIXTURE_DIR)
        print(f"regenerated {', '.join(FIXTURES)} from Core {core}")
        if args.validate:
            validate(core, FIXTURE_DIR)
        return 0

    with tempfile.TemporaryDirectory(prefix="wsi-fixtures-") as tmp:
        generated = Path(tmp)
        generate(core, args.portal_base_url, generated)
        if args.check:
            problems = compare(FIXTURE_DIR, generated)
            if problems:
                print("\n".join(problems), file=sys.stderr)
                print(
                    "committed WSI resource fixtures differ from the pinned Core "
                    "converter output; rerun scripts/wsi_resource_fixtures.py --write",
                    file=sys.stderr,
                )
                return 1
            print("committed WSI resource fixtures match the pinned Core converter output")
        if args.validate:
            validate(core, generated)
    return 0


if __name__ == "__main__":
    sys.exit(main())
