"""
AI 对话主组件 - 聊天界面、对话管理、消息收发
从 ai_chat.py 提取为独立 UI 组件
"""
import hashlib
import time
from datetime import datetime

from PyQt5.QtWidgets import (QWidget, QVBoxLayout, QHBoxLayout, QPushButton,
                             QLabel, QTextEdit, QTextBrowser, QLineEdit, QFrame,
                             QProgressBar, QMessageBox, QApplication)
from PyQt5.QtCore import Qt, pyqtSignal
from PyQt5.QtGui import QTextCursor

import config as cfg_mod
import theme_manager
from ai_client import AICallThread
from ai_service import AIService
from conversation_store import load_conversations as _load_convs, save_conversations as _save_convs
from ui.sidebar import ConversationSidebar
from logger import get_logger

_log = get_logger(__name__)
from ui.ai_settings import SettingsDialog

# ── 兼容别名（与 ai_chat.py 保持一致）──
get_current_date_context = AIService.get_current_date_context
get_effective_system_prompt = AIService.get_effective_system_prompt
build_context_messages = AIService.build_context_messages
estimate_tokens = AIService.estimate_tokens
count_messages_tokens = AIService.count_messages_tokens
guess_model_capabilities = AIService.guess_model_capabilities
parse_ai_response = AIService.parse_ai_response
execute_operations = AIService.execute_operations


class Conversation:
    """对话模型 - dict-based（兼容旧版 AICallThread 和渲染逻辑）"""

    def __init__(self, cid=None, title="新对话"):
        self.id = cid or hashlib.md5(str(time.time()).encode()).hexdigest()[:12]
        self.title = title
        self.messages = []
        self.created_at = datetime.now().isoformat()
        self.token_count = 0

    def add_message(self, role, content):
        self.messages.append({"role": role, "content": content, "timestamp": datetime.now().isoformat()})
        self.token_count = count_messages_tokens(self.messages)

    def to_dict(self):
        return {"id": self.id, "title": self.title, "messages": self.messages, "created_at": self.created_at}

    @staticmethod
    def from_dict(d):
        c = Conversation(d["id"], d["title"])
        c.messages = d.get("messages", [])
        c.created_at = d.get("created_at", "")
        c.token_count = count_messages_tokens(c.messages)
        return c


def load_conversations() -> dict:
    """加载对话 - 转换 models.Conversation → 本地 Conversation（兼容层）"""
    saved = _load_convs()
    result = {}
    for cid, conv in saved.items():
        c = Conversation(conv.id, conv.title)
        c.messages = [{"role": m.role, "content": m.content, "timestamp": m.timestamp}
                      for m in conv.messages]
        c.created_at = conv.created_at
        c.token_count = count_messages_tokens(c.messages)
        result[cid] = c
    return result


def save_conversations(convs: dict):
    """保存对话 - 转换本地 Conversation → models.Conversation → JSON"""
    from models import Conversation as MConv, Message as MMsg
    saved = {}
    for cid, c in convs.items():
        mc = MConv(id=c.id, title=c.title, created_at=c.created_at)
        mc.messages = [MMsg(role=m["role"], content=m["content"], timestamp=m.get("timestamp", ""))
                       for m in c.messages]
        mc.token_count = c.token_count
        saved[cid] = mc
    _save_convs(saved)


class AIChatWidget(QFrame):
    data_changed = pyqtSignal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setFrameStyle(QFrame.StyledPanel | QFrame.Raised)
        self._convs = load_conversations()
        self._active_conv = None
        self._ai_thread = None
        self._sidebar_visible = False
        self._think_expanded = {}

        if not self._convs:
            c = Conversation(title="默认对话")
            self._convs[c.id] = c
            save_conversations(self._convs)
        self._active_conv = next(iter(self._convs.values()))
        self._init_ui()

    def _t(self):
        return theme_manager.get_current_theme()

    def _init_ui(self):
        layout = QHBoxLayout(self)
        layout.setContentsMargins(6, 6, 6, 6)
        layout.setSpacing(4)

        # ── 可折叠侧栏 ──
        self._sidebar = ConversationSidebar()
        self._sidebar.conversation_selected.connect(self._switch_conv)
        self._sidebar.new_conversation.connect(self._new_conv)
        self._sidebar.delete_conversation.connect(self._delete_conv)
        self._sidebar.rename_conversation.connect(self._rename_conv)
        self._sidebar.hide()
        layout.addWidget(self._sidebar)

        # ── 右侧主对话区 ──
        right = QVBoxLayout()
        right.setContentsMargins(0, 0, 0, 0)
        right.setSpacing(3)
        t = self._t()

        title_row = QHBoxLayout()
        self._btn_toggle_sidebar = QPushButton('☰')
        self._btn_toggle_sidebar.setFixedSize(26, 26)
        self._btn_toggle_sidebar.setToolTip('显示/隐藏对话列表')
        self._btn_toggle_sidebar.clicked.connect(self._toggle_sidebar)
        self._btn_toggle_sidebar.setStyleSheet(f"""
            QPushButton{{border:1px solid {t["input_border"]};border-radius:4px;padding:2px 4px;font-size:14px;background:{t["frame_bg"]};color:{t["text_color"]};}}
            QPushButton:hover{{background:{t["list_item_hover"]};}}
        """)

        self._lbl_conv_title = QLabel('🤖 AI 智能助手')
        self._lbl_conv_title.setStyleSheet(f'font-size:15px;font-weight:bold;color:{t["title_color"]};')
        title_row.addWidget(self._btn_toggle_sidebar)
        title_row.addWidget(self._lbl_conv_title)
        title_row.addStretch()
        self._btn_settings = QPushButton('⚙')
        self._btn_settings.setFixedSize(26, 26)
        self._btn_settings.setToolTip('AI设置')
        self._btn_settings.clicked.connect(self._open_settings)
        title_row.addWidget(self._btn_settings)
        self._btn_clear = QPushButton('🗑')
        self._btn_clear.setFixedSize(26, 26)
        self._btn_clear.setToolTip('清空当前对话')
        self._btn_clear.clicked.connect(self._clear_chat)
        title_row.addWidget(self._btn_clear)
        right.addLayout(title_row)

        self._chat_display = QTextBrowser()
        self._chat_display.setOpenExternalLinks(False)
        self._chat_display.setReadOnly(True)
        self._chat_display.setStyleSheet(f"""
            QTextEdit{{border:1px solid {t["frame_border"]};border-radius:6px;background:{t["list_bg"]};font-size:12px;padding:6px;color:{t["text_color"]};}}
        """)
        right.addWidget(self._chat_display, 1)

        # Token 进度条
        token_row = QHBoxLayout()
        token_row.setSpacing(4)
        self._token_bar = QProgressBar()
        self._token_bar.setRange(0, cfg_mod.load_config().get('context_window', 128000))
        self._token_bar.setValue(0)
        self._token_bar.setTextVisible(True)
        self._token_bar.setMaximumHeight(14)
        self._token_bar.setFormat('%v / %m')
        token_row.addWidget(QLabel('📊'), 0)
        token_row.addWidget(self._token_bar, 1)
        self._lbl_usage = QLabel('0/0')
        self._lbl_usage.setStyleSheet(f'font-size:10px; color:{t["subtitle_color"]};')
        token_row.addWidget(self._lbl_usage)
        right.addLayout(token_row)

        self._lbl_status = QLabel('')
        self._lbl_status.setStyleSheet(f'color:{t["subtitle_color"]};font-size:11px;')
        right.addWidget(self._lbl_status)

        input_row = QHBoxLayout()
        input_row.setSpacing(4)
        self._edit_input = QLineEdit()
        self._edit_input.setPlaceholderText('输入需求，如"帮我安排明天下午3点开会"...')
        self._edit_input.returnPressed.connect(self._send_message)
        input_row.addWidget(self._edit_input, 1)
        self._btn_send = QPushButton('发送')
        self._btn_send.clicked.connect(self._send_message)
        input_row.addWidget(self._btn_send)
        right.addLayout(input_row)

        layout.addLayout(right, 1)

        self._sidebar.refresh(self._convs, self._active_conv.id)
        self._chat_display.anchorClicked.connect(self._on_think_toggle)
        self._render_conv_messages()
        self._update_send_state()
        self._update_token_bar()

    # ──────── 侧栏折叠 ────────
    def _toggle_sidebar(self):
        self._sidebar_visible = not self._sidebar_visible
        self._sidebar.setVisible(self._sidebar_visible)

    # ──────── 对话管理 ────────
    def _switch_conv(self, cid):
        if cid in self._convs:
            self._active_conv = self._convs[cid]
            self._sidebar.refresh(self._convs, cid)
            self._chat_display.clear()
            self._render_conv_messages()
            self._update_token_bar()

    def _new_conv(self):
        c = Conversation(title=f'对话 {len(self._convs)+1}')
        self._convs[c.id] = c
        save_conversations(self._convs)
        self._active_conv = c
        self._sidebar.refresh(self._convs, c.id)
        self._chat_display.clear()
        self._update_token_bar()

    def _delete_conv(self, cid):
        if len(self._convs) <= 1:
            QMessageBox.information(self, '提示', '至少保留一个对话')
            return
        if cid in self._convs:
            del self._convs[cid]
            save_conversations(self._convs)
            if self._active_conv and self._active_conv.id == cid:
                self._active_conv = next(iter(self._convs.values()))
            self._sidebar.refresh(self._convs, self._active_conv.id)
            self._chat_display.clear()
            self._render_conv_messages()
            self._update_token_bar()

    def _rename_conv(self, cid, new_title):
        if cid in self._convs:
            self._convs[cid].title = new_title
            save_conversations(self._convs)
            self._sidebar.refresh(self._convs, self._active_conv.id if self._active_conv else None)

    def _render_conv_messages(self):
        try:
            self._chat_display.anchorClicked.disconnect(self._on_think_toggle)
        except TypeError:
            pass  # 信号尚未连接
        self._chat_display.clear()
        if self._active_conv is None:
            self._chat_display.anchorClicked.connect(self._on_think_toggle)
            return
        t = self._t()
        for i, msg in enumerate(self._active_conv.messages):
            role = msg['role']
            content = msg['content']
            ts = msg.get('timestamp', '')
            time_str = ''
            if ts:
                try:
                    time_str = datetime.fromisoformat(ts).strftime('%H:%M')
                except (ValueError, TypeError):
                    pass  # 无效的时间戳格式

            if role == 'user':
                header = f'<p><b style="color:{t["chat_user_color"]}">👤 你 {time_str}</b></p>'
                body = f'<p style="color:{t["text_color"]}; margin-left:10px;">{self._esc(content)}</p>'
            elif role == 'assistant':
                header = f'<p><b style="color:{t["chat_ai_color"]}">🤖 AI {time_str}</b></p>'
                body = f'<p style="color:{t["text_color"]}; margin-left:10px;">{self._esc(content)}</p>'
            elif role == 'think':
                think_idx = sum(1 for m in self._active_conv.messages[:i] if m['role'] == 'think')
                is_expanded = self._think_expanded.get(think_idx, False)
                if is_expanded:
                    header = f'<p><b style="color:{t["warning_text"]}">💭 思考 {time_str}</b> '
                    header += f'<a href="toggle_think_{think_idx}" style="color:{t["primary"]};text-decoration:none;font-size:11px;">收起 ▲</a></p>'
                    body = f'<p style="color:{t["subtitle_color"]}; margin-left:10px; font-style:italic;">{self._esc(content)}</p>'
                else:
                    short_preview = content[:100].replace('\n', ' ') + ('…' if len(content) > 100 else '')
                    header = f'<p><b style="color:{t["warning_text"]}">💭 思考 {time_str} ({len(content)}字)</b> '
                    header += f'<a href="toggle_think_{think_idx}" style="color:{t["primary"]};text-decoration:none;font-size:11px;">展开 ▼</a></p>'
                    body = f'<p style="color:{t["muted_color"]}; margin-left:10px; font-size:11px;">💡 {self._esc(short_preview)}</p>'
            else:
                continue
            self._chat_display.insertHtml(header + body + '<hr style="border:0;height:1px;background:#30363d;">')
        self._chat_display.moveCursor(QTextCursor.End)
        sb = self._chat_display.verticalScrollBar()
        if sb:
            sb.setValue(sb.maximum())
        self._chat_display.anchorClicked.connect(self._on_think_toggle)

    def _on_think_toggle(self, url):
        href = url.toString()
        if href.startswith('toggle_think_'):
            think_idx = int(href.split('_')[-1])
            self._think_expanded[think_idx] = not self._think_expanded.get(think_idx, False)
            self._chat_display.clear()
            self._render_conv_messages()

    def _esc(self, text: str) -> str:
        """安全转义HTML特殊字符, 使用chr()避免XML实体冲突"""
        a = chr(38)
        return text.replace('&', a + 'amp;').replace('<', a + 'lt;').replace('>', a + 'gt;').replace('\n', '<br>')

    def _send_message(self):
        txt = self._edit_input.text().strip()
        if not txt or self._active_conv is None:
            return
        if not cfg_mod.is_api_configured():
            QMessageBox.warning(self, '未配置', '请先在设置中配置API地址和密钥')
            return
        self._edit_input.clear()
        self._edit_input.setEnabled(False)
        self._btn_send.setEnabled(False)
        self._lbl_status.setText('⏳ AI思考中...')

        self._active_conv.add_message('user', txt)
        self._chat_display.clear()
        self._render_conv_messages()
        save_conversations(self._convs)
        self._update_token_bar()

        self._ai_thread = AICallThread(txt, self._active_conv)
        self._ai_thread.result_ready.connect(self._on_result)
        self._ai_thread.error_occurred.connect(self._on_error)
        self._ai_thread.start()

    def _on_result(self, parsed: dict):
        self._lbl_status.setText('')
        think = parsed.get('think', '')
        ops = parsed.get('operations', [])
        reply_text = parsed.get('reply_text', '')

        if think:
            self._active_conv.add_message('think', think)
            self._chat_display.clear()
            self._render_conv_messages()

        has_change = False
        if ops:
            op_results = execute_operations(ops)
            replies = [op.get('message', '') for op in ops if op.get('action') == 'reply']
            if op_results:
                self._active_conv.add_message('assistant', '\n'.join(op_results))
                self._chat_display.clear()
                self._render_conv_messages()
            if replies:
                self._active_conv.add_message('assistant', '\n'.join(replies))
                self._chat_display.clear()
                self._render_conv_messages()
            if any(op['action'] in ('add', 'update', 'delete') for op in ops):
                has_change = True
        elif reply_text:
            self._active_conv.add_message('assistant', reply_text)
            self._chat_display.clear()
            self._render_conv_messages()
        else:
            raw = parsed.get('raw', '')
            if raw:
                self._active_conv.add_message('assistant', raw)
                self._chat_display.clear()
                self._render_conv_messages()

        save_conversations(self._convs)
        self._update_token_bar()

        if self._active_conv.title.startswith('对话') or self._active_conv.title == '新对话':
            msgs = self._active_conv.messages
            if msgs:
                first = msgs[0]['content']
                self._active_conv.title = first[:30] + ('…' if len(first) > 30 else '')
                save_conversations(self._convs)
                self._sidebar.refresh(self._convs, self._active_conv.id)
                self._lbl_conv_title.setText(f'💬 {self._active_conv.title}')

        if has_change:
            self.data_changed.emit()
        self._edit_input.setEnabled(True)
        self._btn_send.setEnabled(True)
        self._edit_input.setFocus()

    def _on_error(self, err: str):
        self._lbl_status.setText('')
        self._active_conv.add_message('assistant', f'❌ 错误: {err}')
        self._chat_display.clear()
        self._render_conv_messages()
        save_conversations(self._convs)
        self._edit_input.setEnabled(True)
        self._btn_send.setEnabled(True)

    def _update_token_bar(self):
        if self._active_conv is None:
            return
        used = self._active_conv.token_count
        total = cfg_mod.load_config().get('context_window', 128000)
        self._token_bar.setMaximum(total)
        self._token_bar.setValue(min(used, total))
        pct = f'{used/total*100:.1f}%' if total else '0%'
        self._lbl_usage.setText(f'{used}/{total} ({pct})')
        t = self._t()
        ratio = used / max(total, 1)
        if ratio > 0.8:
            chunk = '#e74c3c'
        elif ratio > 0.5:
            chunk = '#d29922'
        else:
            chunk = '#3fb950'
        self._token_bar.setStyleSheet(f"""
            QProgressBar{{border:1px solid {t["frame_border"]};border-radius:3px;background:{t["list_bg"]};text-align:center;font-size:10px;color:{t["text_color"]};}}
            QProgressBar::chunk{{background:{chunk};border-radius:2px;}}
        """)

    def _update_send_state(self):
        ok = cfg_mod.is_api_configured()
        self._btn_send.setEnabled(ok)
        self._edit_input.setEnabled(ok)
        self._lbl_status.setText('⚠️ 请先配置API' if not ok else '✅ 可以开始对话')

    def _open_settings(self):
        dlg = SettingsDialog(self)
        dlg.config_saved.connect(self._update_send_state)
        dlg.config_saved.connect(self._update_token_bar)
        dlg.exec_()

    def _clear_chat(self):
        if self._active_conv is None:
            return
        self._active_conv.messages.clear()
        self._active_conv.token_count = 0
        self._active_conv.title = '新对话'
        save_conversations(self._convs)
        self._chat_display.clear()
        self._sidebar.refresh(self._convs, self._active_conv.id)
        self._update_token_bar()
        self._lbl_conv_title.setText('🤖 AI 智能助手')

    def refresh_api_state(self):
        self._update_send_state()

    def apply_theme(self):
        t = self._t()
        self.setStyleSheet(f'AIChatWidget{{background:{t["frame_bg"]};border-radius:8px;}}')
        self._lbl_conv_title.setStyleSheet(f'font-size:15px;font-weight:bold;color:{t["title_color"]};')
        self._chat_display.setStyleSheet(f"""
            QTextEdit{{border:1px solid {t["frame_border"]};border-radius:6px;background:{t["list_bg"]};font-size:12px;padding:6px;color:{t["text_color"]};}}
        """)
        self._lbl_status.setStyleSheet(f'color:{t["subtitle_color"]};font-size:11px;')
        self._edit_input.setStyleSheet(f'''
            QLineEdit{{padding:6px;font-size:12px;border:1px solid {t["input_border"]};border-radius:6px;background:{t["input_bg"]};color:{t["text_color"]};}}
        ''')
        self._btn_send.setStyleSheet(f"""
            QPushButton{{background:{t["primary"]};color:{t["primary_text"]};border:none;border-radius:5px;padding:6px 16px;font-size:12px;font-weight:bold;}}
            QPushButton:hover{{background:{t["primary_hover"]};}} QPushButton:disabled{{background:{t["muted_color"]};}}
        """)
        for btn in (self._btn_settings, self._btn_clear, self._btn_toggle_sidebar):
            btn.setStyleSheet(f"""
                QPushButton{{border:1px solid {t["input_border"]};border-radius:4px;padding:2px 4px;font-size:13px;background:{t["frame_bg"]};color:{t["text_color"]};}}
                QPushButton:hover{{background:{t["list_item_hover"]};}}
            """)
        self._sidebar.apply_theme()
        self._update_token_bar()
        if self._active_conv:
            self._chat_display.clear()
            self._render_conv_messages()