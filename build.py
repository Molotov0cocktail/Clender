"""Standard Clender build for the Miniconda base Python 3.12.4 environment."""
from __future__ import annotations

import argparse
import importlib.metadata
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


EXPECTED_PYTHON = (3, 12, 4)
REQUIRED_PACKAGES = {
    "PyQt5": "5.15.11",
    "requests": "2.32.5",
    "pywin32": "311",
    "pyinstaller": "6.21.0",
}


@dataclass(frozen=True)
class EnvironmentCheck:
    ok: bool
    message: str
    python_version: tuple[int, int, int]
    executable: str


def check_environment() -> EnvironmentCheck:
    """Validate the only supported development/build environment."""
    version = sys.version_info[:3]
    executable = str(Path(sys.executable).resolve())
    errors: list[str] = []
    if version != EXPECTED_PYTHON:
        errors.append(f"需要 Python {'.'.join(map(str, EXPECTED_PYTHON))}，当前为 {'.'.join(map(str, version))}")
    prefix = Path(sys.prefix).resolve()
    if sys.prefix != sys.base_prefix or not (prefix / "conda-meta").is_dir():
        errors.append(f"必须使用 Conda base 解释器，当前为 {executable}")
    for package, expected in REQUIRED_PACKAGES.items():
        try:
            actual = importlib.metadata.version(package)
        except importlib.metadata.PackageNotFoundError:
            errors.append(f"缺少依赖 {package}=={expected}")
        else:
            if actual != expected:
                errors.append(f"{package} 需要 {expected}，当前为 {actual}")
    return EnvironmentCheck(
        ok=not errors,
        message="环境检查通过" if not errors else "；".join(errors),
        python_version=version,
        executable=executable,
    )


def build_command(project_dir: Path | None = None) -> list[str]:
    """Return a staging PyInstaller command without runtime user data."""
    project = (project_dir or Path(__file__).resolve().parent).resolve()
    build_root = project / "build"
    command = [
        sys.executable,
        "-m",
        "PyInstaller",
        "--name=Clender",
        "--onefile",
        "--windowed",
        "--clean",
        "--noconfirm",
        f"--distpath={build_root / 'release'}",
        f"--workpath={build_root / 'pyinstaller'}",
        f"--specpath={build_root / 'spec'}",
        str(project / "main.py"),
    ]
    icon = project / "icon.ico"
    if icon.exists():
        command.insert(-1, f"--icon={icon}")
    return command


def build_environment(project_dir: Path | None = None) -> dict[str, str]:
    """Return an isolated subprocess environment for PyInstaller."""
    project = (project_dir or Path(__file__).resolve().parent).resolve()
    environment = os.environ.copy()
    environment["PYTHONNOUSERSITE"] = "1"
    # PyInstaller probes site.getusersitepackages() even when user-site loading
    # is disabled. Redirect that probe to an accessible, ignored build path.
    environment["PYTHONUSERBASE"] = str(project / "build" / "userbase")
    prefix = Path(sys.prefix).resolve()
    conda_paths = [
        prefix,
        prefix / "Scripts",
        prefix / "Library" / "bin",
        prefix / "Library" / "usr" / "bin",
        prefix / "Library" / "mingw-w64" / "bin",
    ]
    environment["PATH"] = os.pathsep.join(
        [str(path) for path in conda_paths] + [environment.get("PATH", "")]
    )
    return environment


def build_exe(project_dir: Path | None = None) -> Path:
    """Build in an ignored staging area and publish only the executable.

    Existing runtime data under ``dist/data`` is never passed to PyInstaller or
    removed.  The final replace targets only ``dist/Clender.exe``.
    """
    project = (project_dir or Path(__file__).resolve().parent).resolve()
    check = check_environment()
    if not check.ok:
        raise RuntimeError(check.message)
    (project / "build" / "spec").mkdir(parents=True, exist_ok=True)
    subprocess.run(
        build_command(project),
        cwd=project,
        check=True,
        env=build_environment(project),
    )
    staged_exe = project / "build" / "release" / "Clender.exe"
    if not staged_exe.is_file():
        raise FileNotFoundError(f"构建完成但未找到 {staged_exe}")
    dist_dir = project / "dist"
    dist_dir.mkdir(parents=True, exist_ok=True)
    exe_path = dist_dir / "Clender.exe"
    os.replace(staged_exe, exe_path)
    return exe_path


def create_shortcut(exe_path: Path) -> Path:
    """Create a desktop shortcut when explicitly requested."""
    import win32com.client

    desktop = Path.home() / "Desktop"
    shortcut_path = desktop / "Clender.lnk"
    shell = win32com.client.Dispatch("WScript.Shell")
    shortcut = shell.CreateShortCut(str(shortcut_path))
    shortcut.Targetpath = str(exe_path)
    shortcut.WorkingDirectory = str(exe_path.parent)
    shortcut.Description = "Clender - 智能日程管理"
    shortcut.save()
    return shortcut_path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="仅验证 Python 与依赖")
    parser.add_argument("--shortcut", action="store_true", help="构建后创建桌面快捷方式")
    args = parser.parse_args(argv)

    check = check_environment()
    print(check.message)
    print(f"解释器: {check.executable}")
    if not check.ok:
        return 1
    if args.check:
        return 0

    try:
        exe_path = build_exe()
        print(f"构建完成: {exe_path}")
        if args.shortcut:
            print(f"快捷方式: {create_shortcut(exe_path)}")
    except (OSError, RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"构建失败: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
