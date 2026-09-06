"""Install the exact locked Android toolchain into ignored project directories."""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tomllib
import urllib.request
import uuid
import zipfile
from pathlib import Path


ANDROID_ROOT = Path(__file__).resolve().parents[1]
LOCK_PATH = ANDROID_ROOT / "toolchain.lock.toml"
TOOLCHAIN_DIR = ANDROID_ROOT / ".toolchain"
DOWNLOAD_DIR = TOOLCHAIN_DIR / "downloads"
SDK_DIR = ANDROID_ROOT / ".sdk"
TEMP_DIR = ANDROID_ROOT / ".tmp"
JDK_TARGETS = {
    "jdk17": TOOLCHAIN_DIR / "jdk-17.0.20+8",
    "jdk21_tests": TOOLCHAIN_DIR / "jdk-21.0.12+8",
}


def confined(path: Path) -> Path:
    resolved = path.resolve()
    resolved.relative_to(ANDROID_ROOT.resolve())
    return resolved


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_archive(name: str, filename: str, url: str, expected: str) -> Path:
    DOWNLOAD_DIR.mkdir(parents=True, exist_ok=True)
    archive = confined(DOWNLOAD_DIR / filename)
    if archive.is_file() and file_sha256(archive) == expected:
        return archive
    archive.unlink(missing_ok=True)
    partial = archive.with_suffix(".zip.part")
    partial.unlink(missing_ok=True)
    print(f"Downloading locked archive: {name}")
    with urllib.request.urlopen(url, timeout=180) as response, partial.open("wb") as output:
        shutil.copyfileobj(response, output)
    if file_sha256(partial) != expected:
        partial.unlink(missing_ok=True)
        raise RuntimeError(f"SHA-256 verification failed for {name}")
    partial.replace(archive)
    return archive


def safe_extract(archive: Path, destination: Path) -> None:
    destination = confined(destination)
    destination.mkdir(parents=True, exist_ok=False)
    with zipfile.ZipFile(archive) as bundle:
        for member in bundle.infolist():
            target = (destination / member.filename).resolve()
            target.relative_to(destination)
        bundle.extractall(destination)


def install_zip(archive: Path, target: Path, marker: str) -> None:
    target = confined(target)
    if (target / marker).is_file():
        return
    staging = confined(TEMP_DIR / f"extract-{uuid.uuid4().hex}")
    TEMP_DIR.mkdir(parents=True, exist_ok=True)
    safe_extract(archive, staging)
    matches = list(staging.rglob(marker))
    if len(matches) != 1:
        shutil.rmtree(staging)
        raise RuntimeError("Locked archive layout was not the expected single tool root")
    source = matches[0]
    for _part in Path(marker).parts:
        source = source.parent
    if target.exists():
        shutil.rmtree(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(source), str(target))
    shutil.rmtree(staging, ignore_errors=True)


def properties(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def verify_jdk(path: Path, version: str) -> None:
    release = properties(path / "release")
    actual = release.get("JAVA_VERSION", "").strip('"')
    if actual != version:
        raise RuntimeError(f"Locked JDK {version} is unavailable")


def sdk_environment() -> dict[str, str]:
    environment = os.environ.copy()
    android_home = confined(ANDROID_ROOT / ".android")
    gradle_home = confined(ANDROID_ROOT / ".gradle")
    for directory in (android_home, gradle_home, TEMP_DIR):
        directory.mkdir(parents=True, exist_ok=True)
    environment.update(
        {
            "JAVA_HOME": str(JDK_TARGETS["jdk17"]),
            "ANDROID_HOME": str(SDK_DIR),
            "ANDROID_SDK_ROOT": str(SDK_DIR),
            "ANDROID_USER_HOME": str(android_home),
            "ANDROID_EMULATOR_HOME": str(android_home),
            "ANDROID_AVD_HOME": str(android_home / "avd"),
            "GRADLE_USER_HOME": str(gradle_home),
            "TEMP": str(TEMP_DIR),
            "TMP": str(TEMP_DIR),
        }
    )
    return environment


def install_sdk(packages: dict[str, str]) -> None:
    executable = SDK_DIR / "cmdline-tools" / "latest" / "bin" / "sdkmanager.bat"
    coordinates = [
        f"platforms;android-{packages['platform']}",
        f"build-tools;{packages['build_tools']}",
    ]
    result = subprocess.run(
        [str(executable), f"--sdk_root={SDK_DIR}", *coordinates],
        cwd=ANDROID_ROOT,
        env=sdk_environment(),
        input="y\n" * 20,
        text=True,
        timeout=900,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError("sdkmanager could not install the locked Android packages")


def verify_sdk(packages: dict[str, str]) -> None:
    checks = {
        SDK_DIR / "cmdline-tools" / "latest" / "source.properties": packages["command_line_tools"],
        SDK_DIR / "platforms" / f"android-{packages['platform']}" / "source.properties": packages["platform_revision"],
        SDK_DIR / "build-tools" / packages["build_tools"] / "source.properties": packages["build_tools"],
    }
    for path, expected in checks.items():
        if not path.is_file() or properties(path).get("Pkg.Revision") != expected:
            raise RuntimeError(f"Android SDK package revision mismatch: {path.name}")
    platform = properties(SDK_DIR / "platforms" / f"android-{packages['platform']}" / "source.properties")
    if platform.get("AndroidVersion.ExtensionLevel") != packages["platform_extension"]:
        raise RuntimeError("Android platform extension revision mismatch")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify-only", action="store_true")
    arguments = parser.parse_args()
    lock = tomllib.loads(LOCK_PATH.read_text(encoding="utf-8"))
    archives = lock["archives"]
    if not arguments.verify_only:
        downloaded = {
            name: download_archive(name, item["filename"], item["url"], item["sha256"])
            for name, item in archives.items()
        }
        install_zip(downloaded["jdk17"], JDK_TARGETS["jdk17"], "bin/java.exe")
        install_zip(downloaded["jdk21_tests"], JDK_TARGETS["jdk21_tests"], "bin/java.exe")
        install_zip(
            downloaded["android_command_line_tools"],
            SDK_DIR / "cmdline-tools" / "latest",
            "bin/sdkmanager.bat",
        )
        install_sdk(lock["android_sdk_packages"])
    for name, item in archives.items():
        matching = DOWNLOAD_DIR / item["filename"]
        if not matching.is_file() or file_sha256(matching) != item["sha256"]:
            raise RuntimeError(f"Locked archive is missing or invalid: {name}")
    verify_jdk(JDK_TARGETS["jdk17"], "17.0.20")
    verify_jdk(JDK_TARGETS["jdk21_tests"], "21.0.12")
    verify_sdk(lock["android_sdk_packages"])
    print("Locked Android toolchain verified")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        print(f"Toolchain bootstrap failed: {error}", file=sys.stderr)
        raise SystemExit(1)
