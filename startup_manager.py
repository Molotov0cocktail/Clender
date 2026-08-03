"""Manage current-user Windows startup for the frozen Clender executable."""
from __future__ import annotations

import os
import subprocess
import sys

import winreg


RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
VALUE_NAME = "Clender"


class StartupError(RuntimeError):
    """A safe, user-facing startup registration error."""


def is_supported_runtime() -> bool:
    """Return whether this process can register the packaged application."""
    return sys.platform == "win32" and bool(getattr(sys, "frozen", False))


def build_startup_command(executable: str | None = None) -> str:
    """Return a safely quoted Windows command for silent startup."""
    path = os.path.abspath(executable or sys.executable)
    if not path.lower().endswith(".exe"):
        raise StartupError("开机自启动仅支持 Clender.exe")
    return subprocess.list2cmdline([path, "--silent"])


def is_startup_enabled() -> bool:
    """Return whether the current-user Run value matches this executable."""
    try:
        expected = build_startup_command()
        with winreg.OpenKey(
            winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_QUERY_VALUE
        ) as key:
            value, value_type = winreg.QueryValueEx(key, VALUE_NAME)
        return value_type == winreg.REG_SZ and value == expected
    except (FileNotFoundError, OSError, StartupError):
        return False


def set_startup_enabled(enabled: bool) -> None:
    """Create or remove the current-user Run value without elevation."""
    if not isinstance(enabled, bool):
        raise StartupError("开机自启动设置无效")
    if enabled and not is_supported_runtime():
        raise StartupError("开机自启动仅在打包版 Clender.exe 中可用")
    try:
        access = winreg.KEY_QUERY_VALUE | winreg.KEY_SET_VALUE
        with winreg.OpenKey(
            winreg.HKEY_CURRENT_USER, RUN_KEY, 0, access
        ) as key:
            if enabled:
                winreg.SetValueEx(
                    key, VALUE_NAME, 0, winreg.REG_SZ, build_startup_command()
                )
            else:
                try:
                    winreg.DeleteValue(key, VALUE_NAME)
                except FileNotFoundError:
                    pass
    except StartupError:
        raise
    except OSError as exc:
        raise StartupError("无法更新当前用户的开机自启动设置") from exc
