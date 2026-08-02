"""
对话侧栏组件 - 可折叠对话列表（右键重命名/删除）
从 ai_chat.py 提取为独立 UI 组件
"""
from PyQt5.QtWidgets import (QWidget, QVBoxLayout, QHBoxLayout, QPushButton,
                             QLabel, QFrame, QListWidget, QListWidgetItem,
                             QMenu, QInputDialog)
from PyQt5.QtCore import Qt, pyqtSignal
from PyQt5.QtGui import QColor, QFontMetrics

import config as cfg_mod
import theme_manager
from typography import app_scale_from_config, qfont_for


class ConversationSidebar(QFrame):
    conversation_selected = pyqtSignal(str)
    new_conversation = pyqtSignal()
    delete_conversation = pyqtSignal(str)
    rename_conversation = pyqtSignal(str, str)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setMinimumWidth(130)
        self.setMaximumWidth(200)
        self._init_ui()

    def _init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(4, 4, 4, 4)
        layout.setSpacing(4)

        hdr = QHBoxLayout()
        self._lbl_title = QLabel('💬 对话')
        self._lbl_title.setObjectName('conversationSidebarTitle')
        hdr.addWidget(self._lbl_title)
        hdr.addStretch()
        self._btn_new = QPushButton('＋')
        self._btn_new.setToolTip('新建对话')
        self._btn_new.clicked.connect(self.new_conversation.emit)
        hdr.addWidget(self._btn_new)
        layout.addLayout(hdr)

        self._list = QListWidget()
        self._list.itemClicked.connect(self._on_click)
        self._list.setContextMenuPolicy(Qt.CustomContextMenu)
        self._list.customContextMenuRequested.connect(self._show_menu)
        layout.addWidget(self._list, 1)
        self.apply_theme()

    def _on_click(self, item):
        cid = item.data(Qt.UserRole)
        if cid:
            self.conversation_selected.emit(cid)

    def _show_menu(self, pos):
        item = self._list.itemAt(pos)
        if not item:
            return
        cid = item.data(Qt.UserRole)
        if not cid:
            return
        menu = QMenu(self)
        rename_action = menu.addAction('✏️ 重命名')
        del_action = menu.addAction('🗑 删除此对话')
        action = menu.exec_(self._list.viewport().mapToGlobal(pos))
        if action == del_action:
            self.delete_conversation.emit(cid)
        elif action == rename_action:
            new_title, ok = QInputDialog.getText(
                self, '重命名对话', '新名称:',
                text=item.text().replace('● ', '').replace('  ', '').strip()
            )
            if ok and new_title.strip():
                self.rename_conversation.emit(cid, new_title.strip())

    def refresh(self, convs: dict, active_id=None):
        self._list.clear()
        for cid, conv in sorted(convs.items(), key=lambda x: x[1].created_at, reverse=True):
            mark = '● ' if cid == active_id else '  '
            item = QListWidgetItem(f'{mark}{conv.title[:25]}')
            item.setData(Qt.UserRole, cid)
            if cid == active_id:
                t = theme_manager.get_current_theme()
                item.setForeground(QColor(t['primary']))
            self._list.addItem(item)

    def apply_theme(self):
        t = theme_manager.get_current_theme()
        scale = app_scale_from_config(cfg_mod.load_config())
        self._lbl_title.setStyleSheet(
            f'font-weight:bold;color:{t["title_color"]};'
            f'font-size:{scale.section_title_px}px;'
        )
        self._btn_new.setStyleSheet(
            f'QPushButton{{border:1px solid {t["input_border"]};border-radius:4px;'
            f'background:{t["frame_bg"]};color:{t["text_color"]};font-weight:bold;'
            f'font-size:{scale.control_px}px;}}'
            f'QPushButton:hover{{background:{t["list_item_hover"]};}}'
        )
        control_extent = max(
            24,
            QFontMetrics(qfont_for(scale, 'control')).height() + 8,
        )
        self._btn_new.setFixedSize(control_extent, control_extent)
        self._list.setStyleSheet(f"""
            QListWidget{{border:1px solid {t["frame_border"]};border-radius:4px;background:{t["list_bg"]};color:{t["text_color"]};font-size:{scale.secondary_px}px;}}
            QListWidget::item{{padding:5px 4px;border-bottom:1px solid {t["frame_border"]};}}
            QListWidget::item:hover{{background:{t["list_item_hover"]};}}
        """)
