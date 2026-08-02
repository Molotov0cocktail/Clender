"""
Clender Win7 兼容打包脚本
==========================

使用项目内置的 .conda 环境 (Python 3.8.20 + PyInstaller 4.10) 构建
可在 Windows 7 上运行的 Clender.exe。

所有产物输出到独立子目录，与标准 build.py（dist/Clender.exe）完全隔离：
  - 输出目录: dist/win7/
  - 构建缓存: build_win7/
  - Spec 文件: Clender_win7.spec
  - 桌面快捷方式放在: 桌面/Clender (Win7)/

用法:
  python build_win7.py              # 完整构建
  python build_win7.py --check      # 仅检查环境
  python build_win7.py --clean      # 清理 Win7 构建产物
"""

import os
import sys
import subprocess
import shutil
import struct
import platform
import time


# ── 路径配置 ────────────────────────────────────────────────

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
CONDA_PYTHON = os.path.join(PROJECT_DIR, ".conda", "python.exe")

# Win7 专属输出路径（与标准构建隔离）
DIST_WIN7_DIR = os.path.join(PROJECT_DIR, "dist", "win7")
WORK_WIN7_DIR = os.path.join(PROJECT_DIR, "build_win7")
SPEC_WIN7 = os.path.join(PROJECT_DIR, "Clender_win7.spec")

# 桌面快捷方式文件夹名
SHORTCUT_FOLDER_NAME = "Clender (Win7)"


# ── 工具函数 ────────────────────────────────────────────────

def print_banner():
    print("╔" + "═" * 58 + "╗")
    print("║" + "   Clender Win7 兼容打包工具       ".center(58) + "║")
    print("╠" + "═" * 58 + "╣")
    print("║" + "   环境: Python 3.8.20 + PyInstaller 4.10  ".center(58) + "║")
    print("║" + "   目标: Windows 7 / Server 2008 R2       ".center(58) + "║")
    print("║" + "   输出: dist/win7/  (与标准构建隔离)     ".center(58) + "║")
    print("╚" + "═" * 58 + "╝")
    print()


def get_arch():
    return "x64" if struct.calcsize("P") == 8 else "x86"


def run(cmd, cwd=None, check=True, capture=True):
    """执行命令并打印输出，返回 CompletedProcess"""
    if isinstance(cmd, list):
        display = " ".join(cmd)
    else:
        display = cmd
    print(f"  → {display}")
    result = subprocess.run(
        cmd, cwd=cwd or PROJECT_DIR,
        capture_output=capture, text=True,
        check=False,
    )
    if capture and result.stdout:
        print(result.stdout.strip())
    if capture and result.stderr:
        stderr = result.stderr.strip()
        if stderr:
            print(f"  [stderr] {stderr}", file=sys.stderr)
    if check and result.returncode != 0:
        print(f"  ❌ 命令失败，返回码: {result.returncode}")
        sys.exit(1)
    return result


def file_exists(path):
    return os.path.exists(path)


# ── 环境检查 ────────────────────────────────────────────────

def check_environment():
    """检查 .conda 环境是否就绪"""
    print("🔍 检查构建环境...")
    print("-" * 50)

    all_ok = True

    # 1. .conda/python.exe
    if file_exists(CONDA_PYTHON):
        ver = subprocess.run(
            [CONDA_PYTHON, "--version"],
            capture_output=True, text=True
        )
        print(f"  ✅ .conda 环境: {ver.stdout.strip()}  ({get_arch()})")
    else:
        print(f"  ❌ 未找到 .conda 环境: {CONDA_PYTHON}")
        all_ok = False

    # 2. PyInstaller
    if file_exists(CONDA_PYTHON):
        pi_check = subprocess.run(
            [CONDA_PYTHON, "-c", "import PyInstaller; print(PyInstaller.__version__)"],
            capture_output=True, text=True
        )
        if pi_check.returncode == 0:
            print(f"  ✅ PyInstaller: {pi_check.stdout.strip()}")
        else:
            print(f"  ❌ PyInstaller 未安装或导入失败")
            all_ok = False

    # 3. 项目文件
    for f in ["main.py", "requirements.txt"]:
        fp = os.path.join(PROJECT_DIR, f)
        if file_exists(fp):
            print(f"  ✅ 找到 {f}")
        else:
            print(f"  ❌ 未找到 {f}")
            all_ok = False

    # 4. 操作系统
    win_ver = platform.win32_ver()
    print(f"  ℹ️  当前系统: Windows {win_ver[0]} {win_ver[1]}")

    print()
    if all_ok:
        print("✅ 环境检查通过 — Python 3.8 无需补丁 DLL")
    else:
        print("❌ 环境检查未通过")
    return all_ok


# ── 清理 ────────────────────────────────────────────────────

def kill_locked_exe():
    """强制结束可能占用 dist/win7/Clender.exe 的进程"""
    exe_path = os.path.join(DIST_WIN7_DIR, "Clender.exe")
    if not file_exists(exe_path):
        return
    try:
        subprocess.run(
            ["taskkill", "/F", "/IM", "Clender.exe"],
            capture_output=True, text=True, timeout=10,
        )
    except Exception:
        pass
    for _ in range(10):
        try:
            os.remove(exe_path)
            print("  ℹ️  已删除被占用的 dist/win7/Clender.exe")
            return
        except PermissionError:
            time.sleep(0.5)


def clean_build():
    """清理 Win7 构建产物（不影响标准构建的 dist/Clender.exe）"""
    print("🧹 清理 Win7 构建产物...")

    # 仅清理 Win7 专属目录
    dirs_to_clean = [
        DIST_WIN7_DIR,
        WORK_WIN7_DIR,
    ]
    for d in dirs_to_clean:
        if os.path.isdir(d):
            shutil.rmtree(d, ignore_errors=True)
            print(f"  已删除: {d}")

    # 清理 Win7 专属 spec 文件
    if file_exists(SPEC_WIN7):
        os.remove(SPEC_WIN7)
        print(f"  已删除: {SPEC_WIN7}")

    print("✅ 清理完成")


# ── 构建 ────────────────────────────────────────────────────

def build_exe():
    """使用 .conda 环境的 PyInstaller 打包，输出到 dist/win7/"""
    print("🔨 开始打包 Clender (Win7 兼容)...")
    print("=" * 50)

    # 先杀掉可能锁定的进程
    kill_locked_exe()

    # 确保 Win7 专属目录存在
    os.makedirs(DIST_WIN7_DIR, exist_ok=True)

    # 直接在命令行指定所有参数，不经过 spec 文件
    cmd = [
        CONDA_PYTHON, "-m", "PyInstaller",
        "--name=Clender",
        "--distpath", DIST_WIN7_DIR,
        "--workpath", WORK_WIN7_DIR,
        "--specpath", PROJECT_DIR,
        "--onefile",
        "--windowed",
        "--clean",
        "--noconfirm",
    ]

    # ── 添加数据文件 ──
    cmd.extend(["--add-data", f"requirements.txt{os.pathsep}."])
    if os.path.isdir(os.path.join(PROJECT_DIR, "ui")):
        cmd.extend(["--add-data", f"ui{os.pathsep}ui"])
    if os.path.isdir(os.path.join(PROJECT_DIR, "agents")):
        cmd.extend(["--add-data", f"agents{os.pathsep}agents"])
    if os.path.isdir(os.path.join(PROJECT_DIR, "data")):
        cmd.extend(["--add-data", f"data{os.pathsep}data"])

    # ── 隐藏导入 ──
    for hi in ["PyQt5", "PyQt5.QtCore", "PyQt5.QtGui", "PyQt5.QtWidgets",
               "PyQt5.sip", "json", "sqlite3", "urllib", "logging"]:
        cmd.extend(["--hidden-import", hi])

    # ── 排除模块 ──
    for ex in ["tkinter", "unittest", "test", "pdb", "distutils", "setuptools",
               "pip", "email", "html", "xmlrpc", "pydoc"]:
        cmd.extend(["--exclude-module", ex])

    # ── 图标 ──
    icon_path = os.path.join(PROJECT_DIR, "icon.ico")
    if file_exists(icon_path):
        cmd.extend(["--icon", icon_path])

    # ── 主入口 ──
    cmd.append(os.path.join(PROJECT_DIR, "main.py"))

    print()
    print(f"执行: {' '.join(cmd)}")
    print()

    run(cmd, cwd=PROJECT_DIR, capture=False)

    # 将自动生成的 Clender.spec 重命名为 Clender_win7.spec
    default_spec = os.path.join(PROJECT_DIR, "Clender.spec")
    if file_exists(default_spec):
        if file_exists(SPEC_WIN7):
            os.remove(SPEC_WIN7)
        os.rename(default_spec, SPEC_WIN7)

    print("=" * 50)
    print("✅ 打包完成！")

    exe_path = os.path.join(DIST_WIN7_DIR, "Clender.exe")
    if file_exists(exe_path):
        size_mb = os.path.getsize(exe_path) / (1024 * 1024)
        print(f"📁 输出文件: {exe_path}")
        print(f"📏 文件大小: {size_mb:.1f} MB")
        return exe_path
    else:
        print("❌ 未找到生成的 exe 文件，请检查打包日志")
        return None


# ── 快捷方式 ────────────────────────────────────────────────

def create_shortcut(exe_path):
    """在桌面创建 Clender (Win7) 文件夹并放置快捷方式"""
    if not exe_path or not file_exists(exe_path):
        print("❌ exe 文件不存在，无法创建快捷方式")
        return

    desktop = os.path.join(os.path.expanduser("~"), "Desktop")
    shortcut_folder = os.path.join(desktop, SHORTCUT_FOLDER_NAME)

    # 创建桌面文件夹
    os.makedirs(shortcut_folder, exist_ok=True)

    ps_script = f'''
$TargetFile = "{exe_path}"
$ShortcutFile = "{os.path.join(shortcut_folder, 'Clender.lnk')}"
$WScriptShell = New-Object -ComObject WScript.Shell
$Shortcut = $WScriptShell.CreateShortcut($ShortcutFile)
$Shortcut.TargetPath = $TargetFile
$Shortcut.WorkingDirectory = "{os.path.dirname(exe_path)}"
$Shortcut.Description = "Clender - 智能日程管理 (Win7 兼容版)"
$Shortcut.Save()
Write-Host "桌面快捷方式已创建: $ShortcutFile"
'''
    # 脚本放在 dist/win7/ 下
    ps_file = os.path.join(DIST_WIN7_DIR, "create_shortcut.ps1")
    with open(ps_file, "w", encoding="utf-8") as f:
        f.write(ps_script)

    try:
        print("🔗 创建桌面快捷方式...")
        result = subprocess.run(
            ["powershell", "-ExecutionPolicy", "Bypass", "-File", ps_file],
            capture_output=True, text=True, timeout=30
        )
        if result.returncode == 0:
            print(f"✅ 桌面快捷方式已创建在: {shortcut_folder}")
            if result.stdout:
                print(result.stdout.strip())
        else:
            print(f"⚠️  快捷方式创建失败: {result.stderr}")
            print(f"  你可以手动创建快捷方式到: {exe_path}")
    except Exception as e:
        print(f"⚠️  无法自动创建快捷方式: {e}")
        print(f"  你可以手动创建快捷方式到: {exe_path}")
    finally:
        if file_exists(ps_file):
            os.remove(ps_file)


# ── 部署说明 ────────────────────────────────────────────────

def print_deploy_instructions():
    print()
    print("╔" + "═" * 58 + "╗")
    print("║" + "   📋 Windows 7 部署说明".center(58) + "║")
    print("╚" + "═" * 58 + "╝")
    print()
    print(f"  Win7 版本输出目录: {DIST_WIN7_DIR}")
    print(f"  桌面快捷方式位于: 桌面\\{SHORTCUT_FOLDER_NAME}\\")
    print()
    print("  部署到 Windows 7 时，请确保：")
    print()
    print("  1. ✅ 已安装所有 Windows 更新 (KB2533623)")
    print("  2. ✅ 已安装 Visual C++ Redistributable 2015-2022")
    print("     下载: https://aka.ms/vs/17/release/vc_redist.x64.exe")
    print()
    print("  3. ✅ 本项目使用 Python 3.8.20 构建，无需 DLL 补丁")
    print()
    print("  4. 🔧 首次运行会在 exe 所在目录创建 data/ 文件夹")
    print()
    print("=" * 52)


# ── 主入口 ──────────────────────────────────────────────────

def main():
    print_banner()

    args = sys.argv[1:]

    if "--check" in args:
        ok = check_environment()
        sys.exit(0 if ok else 1)

    if "--clean" in args:
        clean_build()
        return

    # ── 完整构建流程 ──

    # 步骤 1: 环境检查
    if not check_environment():
        print("❌ 环境检查未通过，请解决上述问题后重试")
        sys.exit(1)

    # 步骤 2: 清理旧 Win7 产物
    clean_build()

    # 步骤 3: 构建
    exe_path = build_exe()

    if exe_path:
        # 步骤 4: 创建快捷方式
        create_shortcut(exe_path)

        # 步骤 5: 部署说明
        print_deploy_instructions()

        print()
        print("╔" + "═" * 58 + "╗")
        print("║" + "   🎉 Win7 兼容打包全部完成！   ".center(58) + "║")
        print("╚" + "═" * 58 + "╝")
        print()
        print(f"  可执行文件: {exe_path}")
        print(f"  桌面快捷方式: 桌面\\{SHORTCUT_FOLDER_NAME}\\Clender.lnk")
        print()
    else:
        print("\n❌ 构建失败，请检查错误信息并重试")
        sys.exit(1)


if __name__ == "__main__":
    main()