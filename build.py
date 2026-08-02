"""
Clender 打包构建脚本
使用 PyInstaller 将项目打包为单个exe文件，并自动创建桌面快捷方式
"""
import os
import sys
import subprocess


def check_pyinstaller():
    """检查 pyinstaller 是否已安装"""
    try:
        import PyInstaller
        return True
    except ImportError:
        return False


def install_requirements():
    """安装所需依赖"""
    print('📦 安装项目依赖...')
    subprocess.check_call([sys.executable, '-m', 'pip', 'install', '-r', 'requirements.txt'])
    print('📦 安装 PyInstaller...')
    subprocess.check_call([sys.executable, '-m', 'pip', 'install', 'pyinstaller'])


def build_exe():
    """执行PyInstaller打包"""
    print('🔨 开始打包 Clender...')
    print('=' * 50)

    # 获取当前目录
    project_dir = os.path.dirname(os.path.abspath(__file__))

    # PyInstaller打包参数
    cmd = [
        sys.executable, '-m', 'PyInstaller',
        '--name=Clender',
        '--onefile',          # 打包为单个exe
        '--windowed',         # 无控制台窗口（GUI应用）
        '--clean',            # 清理临时文件
        '--noconfirm',        # 不确认覆盖
        '--add-data', f'requirements.txt{os.pathsep}.',
        '--add-data', f'data{os.pathsep}data',
        '--add-data', f'ui{os.pathsep}ui',
        # 主入口文件
        os.path.join(project_dir, 'main.py'),
    ]

    # 如果有图标文件则添加
    icon_path = os.path.join(project_dir, 'icon.ico')
    if os.path.exists(icon_path):
        cmd.insert(-1, f'--icon={icon_path}')

    print(f'执行命令: {" ".join(cmd)}')
    subprocess.check_call(cmd, cwd=project_dir)

    print('=' * 50)
    print('✅ 打包完成！')

    # 输出位置
    dist_dir = os.path.join(project_dir, 'dist')
    exe_path = os.path.join(dist_dir, 'Clender.exe')
    if os.path.exists(exe_path):
        print(f'📁 输出文件: {exe_path}')
        return exe_path
    else:
        print('❌ 未找到生成的exe文件，请检查打包日志')
        return None


def create_shortcut(exe_path):
    """
    在桌面创建快捷方式
    使用PowerShell脚本（Windows）
    """
    if not exe_path or not os.path.exists(exe_path):
        print('❌ exe文件不存在，无法创建快捷方式')
        return

    # 获取桌面路径
    desktop = os.path.join(os.path.expanduser('~'), 'Desktop')

    # 替代方案：使用Python直接创建.ps1脚本然后执行
    ps_script = f'''
$TargetFile = "{exe_path}"
$ShortcutFile = "{os.path.join(desktop, 'Clender.lnk')}"
$WScriptShell = New-Object -ComObject WScript.Shell
$Shortcut = $WScriptShell.CreateShortcut($ShortcutFile)
$Shortcut.TargetPath = $TargetFile
$Shortcut.WorkingDirectory = "{os.path.dirname(exe_path)}"
$Shortcut.Description = "Clender - 智能日程管理"
$Shortcut.Save()
Write-Host "桌面快捷方式已创建: $ShortcutFile"
'''

    ps_file = os.path.join(os.path.dirname(exe_path), 'create_shortcut.ps1')
    with open(ps_file, 'w', encoding='utf-8') as f:
        f.write(ps_script)

    try:
        print('🔗 创建桌面快捷方式...')
        result = subprocess.run(
            ['powershell', '-ExecutionPolicy', 'Bypass', '-File', ps_file],
            capture_output=True, text=True, timeout=30
        )
        if result.returncode == 0:
            print('✅ 桌面快捷方式创建成功！')
            print(result.stdout)
        else:
            print(f'⚠️ 快捷方式创建可能失败: {result.stderr}')
            print('你可以手动将 dist/Clender.exe 发送到桌面快捷方式')
    except Exception as e:
        print(f'⚠️ 无法自动创建快捷方式: {e}')
        print(f'你可以手动将 {exe_path} 的快捷方式复制到桌面')
    finally:
        # 清理临时脚本
        if os.path.exists(ps_file):
            os.remove(ps_file)


def main():
    print('╔══════════════════════════════════════╗')
    print('║   Clender 打包构建工具              ║')
    print('╚══════════════════════════════════════╝')
    print()

    # 检查pyinstaller
    if not check_pyinstaller():
        print('🔧 首次运行，正在安装构建依赖...')
        install_requirements()
        print()

    # 构建
    exe_path = build_exe()

    if exe_path:
        # 创建快捷方式
        create_shortcut(exe_path)

        print()
        print('╔══════════════════════════════════════╗')
        print('║   🎉 打包构建全部完成！            ║')
        print('╚══════════════════════════════════════╝')
        print()
        print(f'可执行文件: {exe_path}')
        print('桌面快捷方式已创建')
        print()
        print('提示：首次运行时会在exe所在目录创建data文件夹存储数据')
    else:
        print('构建失败，请检查错误信息并重试')


if __name__ == '__main__':
    main()