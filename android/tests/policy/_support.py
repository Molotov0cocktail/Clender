"""Standard-library helpers shared by Android foundation policy tests."""

from __future__ import annotations

import os
import re
import subprocess
import sys
import tomllib
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Iterable


ANDROID_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = ANDROID_ROOT.parent
ANDROID_NS = "http://schemas.android.com/apk/res/android"
A = f"{{{ANDROID_NS}}}"

TEXT_SUFFIXES = {
    ".gradle",
    ".kts",
    ".kt",
    ".properties",
    ".toml",
    ".xml",
    ".yml",
    ".yaml",
    ".ps1",
    ".md",
    ".txt",
}
GENERATED_PARTS = {
    ".gradle",
    ".toolchain",
    ".android",
    ".sdk",
    ".tmp",
    ".idea",
    ".kotlin",
    "build",
    "release",
    "reports",
}
SIGNING_ENV_NAMES = (
    "CLENDER_ANDROID_KEYSTORE_FILE",
    "CLENDER_ANDROID_KEYSTORE_PASSWORD",
    "CLENDER_ANDROID_KEY_ALIAS",
    "CLENDER_ANDROID_KEY_PASSWORD",
)


def rel(path: Path) -> str:
    """Return a stable, non-sensitive repository-relative path."""
    try:
        return path.resolve().relative_to(REPO_ROOT.resolve()).as_posix()
    except (OSError, ValueError):
        return "<outside-repository>"


def sanitize_output(value: object) -> str:
    """Remove local roots and any configured signing values from diagnostics."""
    text = str(value)
    replacements = {
        str(REPO_ROOT): "<repo>",
        str(REPO_ROOT).replace("\\", "/"): "<repo>",
        str(ANDROID_ROOT): "<repo>/android",
        str(ANDROID_ROOT).replace("\\", "/"): "<repo>/android",
        str(Path.home()): "<user-home>",
        str(Path.home()).replace("\\", "/"): "<user-home>",
        str(Path(sys.prefix)): "<python-runtime>",
        str(Path(sys.prefix)).replace("\\", "/"): "<python-runtime>",
    }
    for name in SIGNING_ENV_NAMES:
        secret = os.environ.get(name)
        if secret:
            replacements[secret] = f"<{name.lower()}>"
    for source in sorted(replacements, key=len, reverse=True):
        if source:
            text = text.replace(source, replacements[source])
    text = re.sub(r"(?i)(storePassword|keyPassword)\s*[=:]\s*\S+", r"\1=<redacted>", text)
    return text


class PolicyTestCase(unittest.TestCase):
    """Base class whose failures refer only to repository-relative paths."""

    maxDiff = 4_000

    def path(self, relative: str) -> Path:
        return ANDROID_ROOT / Path(relative)

    def require_file(self, relative: str) -> Path:
        path = self.path(relative)
        if not path.is_file():
            self.fail(f"required file missing: android/{Path(relative).as_posix()}")
        return path

    def require_directory(self, relative: str) -> Path:
        path = self.path(relative)
        if not path.is_dir():
            self.fail(f"required directory missing: android/{Path(relative).as_posix()}")
        return path

    def read_text(self, relative: str) -> str:
        path = self.require_file(relative)
        try:
            return path.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as exc:
            self.fail(f"cannot read UTF-8 policy file android/{Path(relative).as_posix()}: {type(exc).__name__}")
        raise AssertionError("unreachable")

    def read_toml(self, relative: str) -> dict:
        text = self.read_text(relative)
        try:
            return tomllib.loads(text)
        except tomllib.TOMLDecodeError as exc:
            self.fail(f"invalid TOML in android/{Path(relative).as_posix()}: {sanitize_output(exc)}")
        raise AssertionError("unreachable")

    def read_xml(self, relative: str) -> ET.Element:
        text = self.read_text(relative)
        try:
            return ET.fromstring(text)
        except ET.ParseError as exc:
            self.fail(f"invalid XML in android/{Path(relative).as_posix()}: {sanitize_output(exc)}")
        raise AssertionError("unreachable")

    def gradle_sources(self) -> list[Path]:
        files: list[Path] = []
        for current, directories, names in os.walk(ANDROID_ROOT):
            directories[:] = [item for item in directories if item not in GENERATED_PARTS]
            current_path = Path(current)
            for name in names:
                path = current_path / name
                if not (name.endswith(".gradle.kts") or name.endswith(".gradle") or name.endswith(".kt")):
                    continue
                parts = path.relative_to(ANDROID_ROOT).parts
                if parts[:2] == ("tests", "policy") or any(part in {"test", "androidTest"} for part in parts):
                    continue
                files.append(path)
        return sorted(set(files))

    def production_text_files(self) -> list[Path]:
        files: list[Path] = []
        if not ANDROID_ROOT.exists():
            return files
        for current, directories, names in os.walk(ANDROID_ROOT):
            directories[:] = [item for item in directories if item not in GENERATED_PARTS]
            current_path = Path(current)
            for name in names:
                path = current_path / name
                if path.suffix.lower() not in TEXT_SUFFIXES:
                    continue
                relative_parts = path.relative_to(ANDROID_ROOT).parts
                if relative_parts[:2] == ("tests", "policy"):
                    continue
                if relative_parts in {("keystore.properties",), ("local.properties",)}:
                    repository_path = path.relative_to(REPO_ROOT).as_posix()
                    tracked = self.run_git(["ls-files", "--error-unmatch", "--", repository_path])
                    if tracked.returncode not in (0, 1):
                        self.fail("git ls-files local configuration check failed")
                    if tracked.returncode == 1:
                        ignored = self.run_git(["check-ignore", "-q", "--no-index", "--", repository_path])
                        if ignored.returncode not in (0, 1):
                            self.fail("git check-ignore local configuration check failed")
                        if ignored.returncode == 0:
                            continue
                files.append(path)
        return sorted(files)

    def combined_text(self, paths: Iterable[Path]) -> str:
        chunks = []
        for path in paths:
            try:
                chunks.append(path.read_text(encoding="utf-8"))
            except (OSError, UnicodeError) as exc:
                self.fail(f"cannot inspect {rel(path)}: {type(exc).__name__}")
        return "\n".join(chunks)

    def run_git(self, args: list[str], *, stdin: str | None = None) -> subprocess.CompletedProcess[str]:
        try:
            return subprocess.run(
                ["git", *args],
                cwd=REPO_ROOT,
                input=stdin,
                text=True,
                encoding="utf-8",
                errors="replace",
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=30,
                check=False,
            )
        except (OSError, subprocess.SubprocessError) as exc:
            self.fail(f"git policy check could not run: {type(exc).__name__}")
        raise AssertionError("unreachable")
