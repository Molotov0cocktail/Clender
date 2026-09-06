"""Run source and generated-manifest policy tests after Gradle manifest generation."""

from __future__ import annotations

import os
import re
import sys
import unittest
from pathlib import Path


ANDROID_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = ANDROID_ROOT.parent
POLICY_DIR = ANDROID_ROOT / "tests" / "policy"
SIGNING_ENV_NAMES = (
    "CLENDER_ANDROID_KEYSTORE_FILE",
    "CLENDER_ANDROID_KEYSTORE_PASSWORD",
    "CLENDER_ANDROID_KEY_ALIAS",
    "CLENDER_ANDROID_KEY_PASSWORD",
)


def _sanitize(value: object) -> str:
    text = str(value)
    replacements = {
        str(REPO_ROOT): "<repo>",
        str(REPO_ROOT).replace("\\", "/"): "<repo>",
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
    return re.sub(
        r"(?i)(storePassword|keyPassword)\s*[=:]\s*\S+",
        r"\1=<redacted>",
        text,
    )


class SanitizingTextResult(unittest.TextTestResult):
    """Keep failure evidence useful without exposing local or signing values."""

    def _exc_info_to_string(self, err, test):  # noqa: ANN001 - unittest API
        return _sanitize(super()._exc_info_to_string(err, test))


def main() -> int:
    sys.path.insert(0, str(POLICY_DIR))
    suite = unittest.defaultTestLoader.discover(
        start_dir=str(POLICY_DIR),
        pattern="test_*.py",
        top_level_dir=str(POLICY_DIR),
    )
    runner = unittest.TextTestRunner(
        verbosity=2,
        resultclass=SanitizingTextResult,
    )
    result = runner.run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
