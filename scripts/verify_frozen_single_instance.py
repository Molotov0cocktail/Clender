"""Safely verify frozen PyInstaller onefile single-instance handoff on Windows."""
from __future__ import annotations

import argparse
import ctypes
import getpass
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import time
from ctypes import wintypes
from typing import NamedTuple, Sequence, TextIO


APP_NAME = "Clender"
PROCESS_NAME = "clender.exe"
PROBE_MESSAGE = b"probe\n"
STABLE_SAMPLES = 3
SAMPLE_INTERVAL_SECONDS = 0.2


class HarnessError(RuntimeError):
    pass


class HarnessSafetyError(HarnessError):
    pass


class EnvironmentalBlocker(HarnessSafetyError):
    pass


class ScenarioFailure(HarnessError):
    pass


class ProcessRecord(NamedTuple):
    pid: int
    parent_pid: int
    executable_path: str
    creation_time: int


class ReadinessSignals(NamedTuple):
    owner_alive: bool
    cohort_alive: bool
    database_exists: bool
    endpoint_reachable: bool

    @property
    def ready(self) -> bool:
        return all(self)


def normalize_executable_path(path: os.PathLike[str] | str) -> str:
    """Return an absolute, resolved, case-insensitive Windows path identity."""
    return os.path.normcase(os.path.realpath(os.path.abspath(os.fspath(path)))).casefold()


def _basename(path: str) -> str:
    return pathlib.PureWindowsPath(path).name.casefold()


def find_exact_path_cohort(
    records: Sequence[ProcessRecord], executable: os.PathLike[str] | str
) -> list[ProcessRecord]:
    target = normalize_executable_path(executable)
    return sorted(
        (
            record
            for record in records
            if normalize_executable_path(record.executable_path) == target
        ),
        key=lambda record: record.pid,
    )


def validate_clean_preflight(
    records: Sequence[ProcessRecord],
    endpoint_reachable: bool,
    *,
    terminate=None,
) -> None:
    del terminate  # Preflight is deliberately observation-only.
    clender = [record for record in records if _basename(record.executable_path) == PROCESS_NAME]
    if clender or endpoint_reachable:
        raise EnvironmentalBlocker("existing Clender process or local endpoint")


def _is_within(path: os.PathLike[str] | str, root: os.PathLike[str] | str) -> bool:
    normalized_path = normalize_executable_path(path)
    normalized_root = normalize_executable_path(root)
    try:
        return os.path.commonpath([normalized_path, normalized_root]) == normalized_root
    except ValueError:
        return False


def prepare_scenario_workspace(
    source_executable: os.PathLike[str] | str,
    scenario_directory: os.PathLike[str] | str,
) -> pathlib.Path:
    source = pathlib.Path(source_executable)
    scenario = pathlib.Path(scenario_directory)
    if scenario.exists():
        raise HarnessSafetyError("scenario directory already exists")
    scenario.mkdir(parents=True)
    destination = scenario / "Clender.exe"
    shutil.copy2(source, destination)
    return destination


def has_stable_readiness(
    samples: Sequence[ReadinessSignals], required_samples: int = STABLE_SAMPLES
) -> bool:
    if required_samples <= 0 or len(samples) < required_samples:
        return False
    return all(sample.ready for sample in samples[-required_samples:])


def launch_arguments(
    executable: os.PathLike[str] | str, *, silent: bool
) -> list[str]:
    arguments = [os.fspath(executable)]
    if silent:
        arguments.append("--silent")
    return arguments


def scenario_directions(
    executable: os.PathLike[str] | str,
) -> list[tuple[list[str], list[str]]]:
    return [
        (
            launch_arguments(executable, silent=False),
            launch_arguments(executable, silent=True),
        ),
        (
            launch_arguments(executable, silent=True),
            launch_arguments(executable, silent=False),
        ),
    ]


def wait_for_secondary(process: subprocess.Popen, timeout_seconds: float) -> int:
    try:
        exit_code = process.wait(timeout=timeout_seconds)
    except subprocess.TimeoutExpired as error:
        raise ScenarioFailure("secondary did not exit before timeout") from error
    if exit_code != 0:
        raise ScenarioFailure(f"secondary exited nonzero: {exit_code}")
    return exit_code


def validate_cleanup_process(
    expected: ProcessRecord,
    current: ProcessRecord,
    smoke_root: os.PathLike[str] | str,
) -> None:
    if expected.creation_time <= 0 or current.creation_time <= 0:
        raise HarnessSafetyError("process creation time is unavailable")
    if expected.pid != current.pid or expected.creation_time != current.creation_time:
        raise HarnessSafetyError("PID identity changed before cleanup")
    if normalize_executable_path(expected.executable_path) != normalize_executable_path(
        current.executable_path
    ):
        raise HarnessSafetyError("executable path changed before cleanup")
    if not _is_within(current.executable_path, smoke_root):
        raise HarnessSafetyError("cleanup target is outside smoke root")


def is_quiescent(
    exact_path_cohort: Sequence[object],
    endpoint_reachable: bool,
    clender_processes: Sequence[object],
) -> bool:
    return not exact_path_cohort and not endpoint_reachable and not clender_processes


def combine_failures(
    original: BaseException | None, cleanup_failures: Sequence[BaseException]
) -> BaseException:
    cleanup_text = "; ".join(str(error) for error in cleanup_failures)
    if original is None:
        return HarnessError(f"cleanup failed: {cleanup_text}")
    combined = HarnessError(f"{original}; cleanup failed: {cleanup_text}")
    combined.__cause__ = original
    return combined


def emit_summary(summary: dict, output: TextIO = sys.stdout) -> None:
    allowed = {
        "scenario",
        "primary_pid",
        "secondary_pid",
        "cohort",
        "readiness",
        "secondary_exit_code",
        "primary_survival",
        "cleanup",
        "quiescence",
    }
    bounded = {key: summary[key] for key in summary if key in allowed}
    output.write(json.dumps(bounded, sort_keys=True, separators=(",", ":")) + "\n")
    output.flush()


def _default_service_name() -> str:
    username = (getpass.getuser() or "unknown").strip().casefold()
    digest = hashlib.sha256(username.encode("utf-8")).hexdigest()[:16]
    return f"{APP_NAME}-{digest}"


def probe_default_endpoint(timeout_ms: int = 250) -> bool:
    from PyQt5.QtNetwork import QLocalSocket

    socket = QLocalSocket()
    socket.connectToServer(_default_service_name())
    if not socket.waitForConnected(timeout_ms):
        socket.abort()
        return False
    socket.write(PROBE_MESSAGE)
    socket.flush()
    socket.waitForBytesWritten(timeout_ms)
    socket.disconnectFromServer()
    if socket.state() != QLocalSocket.UnconnectedState:
        socket.waitForDisconnected(timeout_ms)
    return True


# Windows Toolhelp and process APIs. No WMI or pywin32 dependency is used.
TH32CS_SNAPPROCESS = 0x00000002
PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
PROCESS_TERMINATE = 0x0001
INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value
ERROR_NO_MORE_FILES = 18


class PROCESSENTRY32W(ctypes.Structure):
    _fields_ = [
        ("dwSize", wintypes.DWORD),
        ("cntUsage", wintypes.DWORD),
        ("th32ProcessID", wintypes.DWORD),
        ("th32DefaultHeapID", ctypes.c_size_t),
        ("th32ModuleID", wintypes.DWORD),
        ("cntThreads", wintypes.DWORD),
        ("th32ParentProcessID", wintypes.DWORD),
        ("pcPriClassBase", wintypes.LONG),
        ("dwFlags", wintypes.DWORD),
        ("szExeFile", wintypes.WCHAR * 260),
    ]


def _kernel32():
    if os.name != "nt":
        raise EnvironmentalBlocker("frozen smoke harness requires Windows")
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.CreateToolhelp32Snapshot.argtypes = [wintypes.DWORD, wintypes.DWORD]
    kernel32.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
    kernel32.Process32FirstW.argtypes = [wintypes.HANDLE, ctypes.POINTER(PROCESSENTRY32W)]
    kernel32.Process32FirstW.restype = wintypes.BOOL
    kernel32.Process32NextW.argtypes = [wintypes.HANDLE, ctypes.POINTER(PROCESSENTRY32W)]
    kernel32.Process32NextW.restype = wintypes.BOOL
    kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel32.OpenProcess.restype = wintypes.HANDLE
    kernel32.QueryFullProcessImageNameW.argtypes = [
        wintypes.HANDLE,
        wintypes.DWORD,
        wintypes.LPWSTR,
        ctypes.POINTER(wintypes.DWORD),
    ]
    kernel32.QueryFullProcessImageNameW.restype = wintypes.BOOL
    kernel32.GetProcessTimes.argtypes = [
        wintypes.HANDLE,
        ctypes.POINTER(wintypes.FILETIME),
        ctypes.POINTER(wintypes.FILETIME),
        ctypes.POINTER(wintypes.FILETIME),
        ctypes.POINTER(wintypes.FILETIME),
    ]
    kernel32.GetProcessTimes.restype = wintypes.BOOL
    kernel32.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]
    kernel32.TerminateProcess.restype = wintypes.BOOL
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel32.CloseHandle.restype = wintypes.BOOL
    return kernel32


def _filetime_value(value: wintypes.FILETIME) -> int:
    return (int(value.dwHighDateTime) << 32) | int(value.dwLowDateTime)


def _query_process_record(
    kernel32, pid: int, parent_pid: int, fallback_name: str
) -> ProcessRecord:
    handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not handle:
        return ProcessRecord(pid, parent_pid, fallback_name, 0)
    try:
        path, creation_time = _query_identity_from_handle(
            kernel32, handle, fallback_name
        )
        return ProcessRecord(pid, parent_pid, path, creation_time)
    finally:
        kernel32.CloseHandle(handle)


def _query_identity_from_handle(kernel32, handle, fallback_name: str) -> tuple[str, int]:
    buffer = ctypes.create_unicode_buffer(32768)
    length = wintypes.DWORD(len(buffer))
    if not kernel32.QueryFullProcessImageNameW(handle, 0, buffer, ctypes.byref(length)):
        path = fallback_name
    else:
        path = buffer.value
    created = wintypes.FILETIME()
    exited = wintypes.FILETIME()
    kernel = wintypes.FILETIME()
    user = wintypes.FILETIME()
    creation_time = 0
    if kernel32.GetProcessTimes(
        handle,
        ctypes.byref(created),
        ctypes.byref(exited),
        ctypes.byref(kernel),
        ctypes.byref(user),
    ):
        creation_time = _filetime_value(created)
    return path, creation_time


def enumerate_processes() -> list[ProcessRecord]:
    kernel32 = _kernel32()
    snapshot = kernel32.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0)
    if snapshot == INVALID_HANDLE_VALUE:
        raise HarnessSafetyError("CreateToolhelp32Snapshot failed")
    records: list[ProcessRecord] = []
    try:
        entry = PROCESSENTRY32W()
        entry.dwSize = ctypes.sizeof(PROCESSENTRY32W)
        if not kernel32.Process32FirstW(snapshot, ctypes.byref(entry)):
            raise HarnessSafetyError("Process32FirstW failed")
        while True:
            records.append(
                _query_process_record(
                    kernel32,
                    int(entry.th32ProcessID),
                    int(entry.th32ParentProcessID),
                    entry.szExeFile,
                )
            )
            if not kernel32.Process32NextW(snapshot, ctypes.byref(entry)):
                if ctypes.get_last_error() not in (0, ERROR_NO_MORE_FILES):
                    raise HarnessSafetyError("Process32NextW failed")
                break
    finally:
        kernel32.CloseHandle(snapshot)
    return records


def _clender_processes(records: Sequence[ProcessRecord]) -> list[ProcessRecord]:
    return [record for record in records if _basename(record.executable_path) == PROCESS_NAME]


def _terminate_record(record: ProcessRecord) -> None:
    kernel32 = _kernel32()
    handle = kernel32.OpenProcess(
        PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_TERMINATE, False, record.pid
    )
    if not handle:
        raise HarnessSafetyError("unable to open owned process for termination")
    try:
        path, creation_time = _query_identity_from_handle(kernel32, handle, "")
        current = ProcessRecord(record.pid, record.parent_pid, path, creation_time)
        validate_cleanup_process(record, current, pathlib.Path(record.executable_path).parent)
        if not kernel32.TerminateProcess(handle, 1):
            raise HarnessSafetyError("TerminateProcess failed")
    finally:
        kernel32.CloseHandle(handle)


def _observe_owned(
    observed: dict[int, ProcessRecord],
    cohort: Sequence[ProcessRecord],
    owner_pid: int,
) -> None:
    pending = list(cohort)
    while pending:
        progressed = False
        for record in list(pending):
            if record.creation_time <= 0:
                raise HarnessSafetyError("owned process creation time is unavailable")
            known_parent = record.parent_pid == owner_pid or record.parent_pid in observed
            if record.pid == owner_pid or known_parent:
                previous = observed.get(record.pid)
                if previous and previous.creation_time != record.creation_time:
                    raise HarnessSafetyError("PID reuse detected while observing cohort")
                observed[record.pid] = record
                pending.remove(record)
                progressed = True
        if not progressed:
            raise HarnessSafetyError("unknown exact-path process in cohort")


def _launched_lineage(
    cohort: Sequence[ProcessRecord], owner_pid: int
) -> list[ProcessRecord]:
    lineage_pids = {owner_pid}
    lineage: list[ProcessRecord] = []
    pending = list(cohort)
    while pending:
        progressed = False
        for record in list(pending):
            if record.pid == owner_pid or record.parent_pid in lineage_pids:
                lineage_pids.add(record.pid)
                lineage.append(record)
                pending.remove(record)
                progressed = True
        if not progressed:
            break
    return lineage


def _capture_launched_cohort(
    process: subprocess.Popen,
    executable: pathlib.Path,
    observed: dict[int, ProcessRecord],
    deadline: float,
) -> list[ProcessRecord]:
    """Register a Popen-anchored onefile lineage before waiting on its exit."""
    previous_identity: tuple[tuple[int, int], ...] | None = None
    stable = 0
    latest: list[ProcessRecord] = []
    while time.monotonic() < deadline:
        cohort = find_exact_path_cohort(enumerate_processes(), executable)
        latest = _launched_lineage(cohort, process.pid)
        if latest:
            for record in latest:
                if record.creation_time <= 0:
                    raise HarnessSafetyError(
                        "launched process creation time is unavailable"
                    )
                previous = observed.get(record.pid)
                if previous and previous.creation_time != record.creation_time:
                    raise HarnessSafetyError(
                        "PID reuse detected while capturing launched cohort"
                    )
                observed[record.pid] = record
            identity = tuple(sorted((item.pid, item.creation_time) for item in latest))
            stable = stable + 1 if identity == previous_identity else 1
            previous_identity = identity
            if stable >= 2 or process.poll() is not None:
                return latest
        elif process.poll() is not None:
            return []
        time.sleep(SAMPLE_INTERVAL_SECONDS)
    raise ScenarioFailure("launched process ownership capture timeout")


def _wait_for_readiness(
    owner: subprocess.Popen,
    executable: pathlib.Path,
    database: pathlib.Path,
    deadline: float,
    observed: dict[int, ProcessRecord],
) -> list[ProcessRecord]:
    samples: list[ReadinessSignals] = []
    last_cohort: list[ProcessRecord] = []
    while time.monotonic() < deadline:
        records = enumerate_processes()
        last_cohort = find_exact_path_cohort(records, executable)
        if last_cohort:
            _observe_owned(observed, last_cohort, owner.pid)
        signals = ReadinessSignals(
            owner.poll() is None,
            bool(last_cohort),
            database.is_file(),
            probe_default_endpoint(),
        )
        samples.append(signals)
        if has_stable_readiness(samples):
            return last_cohort
        if owner.poll() is not None:
            raise ScenarioFailure(f"primary exited before readiness: {owner.returncode}")
        time.sleep(SAMPLE_INTERVAL_SECONDS)
    raise ScenarioFailure("primary readiness timeout")


def _wait_until_quiescent(executable: pathlib.Path, deadline: float) -> bool:
    while time.monotonic() < deadline:
        records = enumerate_processes()
        if is_quiescent(
            find_exact_path_cohort(records, executable),
            probe_default_endpoint(),
            _clender_processes(records),
        ):
            return True
        time.sleep(SAMPLE_INTERVAL_SECONDS)
    return False


def _cleanup_owned(
    observed: dict[int, ProcessRecord],
    executable: pathlib.Path,
    smoke_root: pathlib.Path,
    deadline: float,
) -> None:
    records = enumerate_processes()
    cohort = find_exact_path_cohort(records, executable)
    validated_pids: set[int] = set()
    unresolved: list[ProcessRecord] = []
    for current in cohort:
        expected = observed.get(current.pid)
        if expected is None:
            unresolved.append(current)
            continue
        validate_cleanup_process(expected, current, smoke_root)
        validated_pids.add(current.pid)
    while unresolved:
        progressed = False
        for current in list(unresolved):
            if current.parent_pid not in validated_pids:
                continue
            if current.creation_time <= 0:
                raise HarnessSafetyError(
                    "late descendant creation time is unavailable"
                )
            if not _is_within(current.executable_path, smoke_root):
                raise HarnessSafetyError("late descendant is outside smoke root")
            observed[current.pid] = current
            validated_pids.add(current.pid)
            unresolved.remove(current)
            progressed = True
        if not progressed:
            raise HarnessSafetyError("unknown exact-path process at cleanup")
    by_pid = {record.pid: record for record in cohort}

    def depth(record: ProcessRecord) -> int:
        result = 0
        seen = {record.pid}
        parent_pid = record.parent_pid
        while parent_pid in by_pid and parent_pid not in seen:
            seen.add(parent_pid)
            result += 1
            parent_pid = by_pid[parent_pid].parent_pid
        return result

    for current in sorted(cohort, key=lambda item: (depth(item), item.pid), reverse=True):
        _terminate_record(current)
    if not _wait_until_quiescent(executable, deadline):
        raise HarnessSafetyError("quiescence barrier timed out")


def _safe_remove_scenario(scenario: pathlib.Path, smoke_root: pathlib.Path) -> None:
    if not _is_within(scenario, smoke_root) or scenario == smoke_root:
        raise HarnessSafetyError("scenario directory is outside smoke root")
    shutil.rmtree(scenario)


def run_scenario(
    source_executable: pathlib.Path,
    smoke_root: pathlib.Path,
    scenario_name: str,
    primary_silent: bool,
    timeout_seconds: float,
    output: TextIO,
) -> None:
    scenario = smoke_root / scenario_name
    executable = scenario / "Clender.exe"
    database = scenario / "data" / "clender.db"
    primary: subprocess.Popen | None = None
    secondary: subprocess.Popen | None = None
    observed: dict[int, ProcessRecord] = {}
    original: Exception | None = None
    cleanup_failures: list[Exception] = []
    summary = {
        "scenario": scenario_name,
        "primary_pid": None,
        "secondary_pid": None,
        "cohort": [],
        "readiness": False,
        "secondary_exit_code": None,
        "primary_survival": False,
        "cleanup": False,
        "quiescence": False,
    }
    try:
        deadline = time.monotonic() + timeout_seconds
        executable = prepare_scenario_workspace(source_executable, scenario)
        primary = subprocess.Popen(
            launch_arguments(executable, silent=primary_silent), cwd=scenario
        )
        summary["primary_pid"] = primary.pid
        cohort = _wait_for_readiness(primary, executable, database, deadline, observed)
        summary["cohort"] = [
            {"pid": record.pid, "parent_pid": record.parent_pid} for record in cohort
        ]
        summary["readiness"] = True
        secondary = subprocess.Popen(
            launch_arguments(executable, silent=not primary_silent), cwd=scenario
        )
        summary["secondary_pid"] = secondary.pid
        _capture_launched_cohort(secondary, executable, observed, deadline)
        summary["secondary_exit_code"] = wait_for_secondary(
            secondary, max(0.1, deadline - time.monotonic())
        )
        records = enumerate_processes()
        surviving = find_exact_path_cohort(records, executable)
        if surviving:
            _observe_owned(observed, surviving, primary.pid)
        summary["primary_survival"] = (
            primary.poll() is None and bool(surviving) and probe_default_endpoint()
        )
        if not summary["primary_survival"]:
            raise ScenarioFailure("primary did not survive secondary handoff")
    except Exception as error:
        original = error
    finally:
        try:
            _cleanup_owned(
                observed,
                executable,
                smoke_root,
                time.monotonic() + timeout_seconds,
            )
            summary["cleanup"] = True
            summary["quiescence"] = True
        except Exception as error:
            cleanup_failures.append(error)
        if summary["quiescence"] and scenario.exists():
            try:
                _safe_remove_scenario(scenario, smoke_root)
            except Exception as error:
                cleanup_failures.append(error)
                summary["cleanup"] = False
        emit_summary(summary, output)
    if cleanup_failures:
        raise combine_failures(original, cleanup_failures)
    if original is not None:
        raise original


def run(
    executable: pathlib.Path,
    cycles: int,
    timeout_seconds: float,
    output: TextIO = sys.stdout,
) -> None:
    source = executable.resolve(strict=True)
    if source.suffix.casefold() != ".exe":
        raise HarnessSafetyError("executable must be an .exe file")
    validate_clean_preflight(enumerate_processes(), probe_default_endpoint())
    smoke_root = pathlib.Path(tempfile.mkdtemp(prefix="clender-frozen-smoke-"))
    original: Exception | None = None
    cleanup_failures: list[Exception] = []
    try:
        for cycle in range(1, cycles + 1):
            run_scenario(
                source,
                smoke_root,
                f"cycle-{cycle:02d}-normal-to-silent",
                False,
                timeout_seconds,
                output,
            )
            run_scenario(
                source,
                smoke_root,
                f"cycle-{cycle:02d}-silent-to-normal",
                True,
                timeout_seconds,
                output,
            )
    except Exception as error:
        original = error
    finally:
        try:
            if smoke_root.exists():
                if any(smoke_root.iterdir()):
                    raise HarnessSafetyError("smoke root is not empty after cleanup")
                smoke_root.rmdir()
        except Exception as error:
            cleanup_failures.append(error)
    if cleanup_failures:
        raise combine_failures(original, cleanup_failures)
    if original is not None:
        raise original
    validate_clean_preflight(enumerate_processes(), probe_default_endpoint())


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    root = pathlib.Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--executable", type=pathlib.Path, default=root / "dist" / "Clender.exe")
    parser.add_argument("--cycles", type=int, default=1)
    parser.add_argument("--timeout-seconds", type=float, default=20.0)
    args = parser.parse_args(argv)
    if args.cycles <= 0 or args.timeout_seconds <= 0:
        parser.error("cycles and timeout must be positive")
    return args


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        run(args.executable, args.cycles, args.timeout_seconds)
    except Exception as error:
        if isinstance(error, EnvironmentalBlocker):
            error_code = "ENVIRONMENTAL_BLOCKER"
        elif isinstance(error, HarnessSafetyError):
            error_code = "SAFETY_FAILURE"
        elif isinstance(error, ScenarioFailure):
            error_code = "SCENARIO_FAILURE"
        elif isinstance(error, HarnessError):
            error_code = "HARNESS_FAILURE"
        else:
            error_code = "UNEXPECTED_FAILURE"
        sys.stderr.write(
            json.dumps(
                {"result": "failed", "error": error_code},
                sort_keys=True,
                separators=(",", ":"),
            )
            + "\n"
        )
        sys.stderr.flush()
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
