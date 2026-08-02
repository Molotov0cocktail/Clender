"""
Clender - 智能日程管理桌面应用 入口点
支持日间/夜间主题动态切换、系统托盘最小化
"""
import sys
from PyQt5.QtWidgets import QApplication
from PyQt5.QtGui import QFont
import database
import config
from logger import configure_logging
from ui.main_window import MainWindow


def main():
    config.ensure_app_data_dir()
    configure_logging(config.APP_DATA_DIR)
    app = QApplication(sys.argv)
    app.setApplicationName('Clender')
    app.setOrganizationName('ClenderApp')
    app.setQuitOnLastWindowClosed(False)
    database.init_db()
    font = QFont('Microsoft YaHei', 10)
    app.setFont(font)
    window = MainWindow(app)
    window.show()
    sys.exit(app.exec_())


if __name__ == '__main__':
    main()
