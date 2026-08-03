"""
Clender - 智能日程管理桌面应用 入口点
支持日间/夜间主题动态切换、系统托盘最小化
"""
import sys
from PyQt5.QtWidgets import QApplication
import database
import config
import theme_manager
from logger import configure_logging
from single_instance import SingleInstanceCoordinator
from ui.main_window import MainWindow


def main(argv=None) -> int:
    raw_args = list(sys.argv if argv is None else argv)
    silent = "--silent" in raw_args[1:]
    qt_args = [arg for arg in raw_args if arg != "--silent"]
    app = QApplication(qt_args)
    app.setApplicationName('Clender')
    app.setOrganizationName('ClenderApp')
    app.setQuitOnLastWindowClosed(False)

    coordinator = SingleInstanceCoordinator(parent=app)
    if not coordinator.acquire(activate_existing=not silent):
        return 0

    config.ensure_app_data_dir()
    configure_logging(config.APP_DATA_DIR)
    database.init_db()
    theme_manager.apply_theme(app)
    window = MainWindow(app)
    coordinator.activation_requested.connect(window.activate_existing_instance)
    app.aboutToQuit.connect(coordinator.close)
    if not silent or not window.has_system_tray():
        window.show()
    return app.exec_()


if __name__ == '__main__':
    sys.exit(main())
