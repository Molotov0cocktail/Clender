"""
AI 对话主组件 - 聊天界面、对话管理、消息收发
从 ai_chat.py 提取为独立 UI 组件
"""
import re
from datetime import datetime

from PyQt5.QtWidgets import (QWidget, QVBoxLayout, QHBoxLayout, QPushButton,
                             QLabel, QTextEdit, QTextBrowser, QLineEdit, QFrame,
                             QProgressBar, QMessageBox, QApplication)
from PyQt5.QtCore import Qt, pyqtSignal
from PyQt5.QtGui import QFontMetrics, QTextCursor

import config as cfg_mod
import theme_manager
from ai_client import AICallThread
from ai_service import AIService
from conversation_store import load_conversations, save_conversations
from models import Conversation
from ui.sidebar import ConversationSidebar
from logger import get_logger
from typography import app_scale_from_config, qfont_for

_log = get_logger(__name__)
from ui.ai_settings import SettingsDialog

class AIChatWidget(QFrame):
    data_changed = pyqtSignal()
    external_request_status = pyqtSignal(str)

    _EXTERNAL_STATUS_LIMIT = 200

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setFrameStyle(QFrame.StyledPanel | QFrame.Raised)
        self._convs = load_conversations()
        self._active_conv = None
        self._ai_thread = None
        self._sidebar_visible = False
        self._think_expanded = {}
        self._pending_conv_id = None
        self._pending_source = None

        if not self._convs:
            c = Conversation.new(title="默认对话")
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

        title_row = QHBoxLayout()
        self._btn_toggle_sidebar = QPushButton('☰')
        self._btn_toggle_sidebar.setToolTip('显示/隐藏对话列表')
        self._btn_toggle_sidebar.clicked.connect(self._toggle_sidebar)

        self._lbl_conv_title = QLabel('🤖 AI 智能助手')
        title_row.addWidget(self._btn_toggle_sidebar)
        title_row.addWidget(self._lbl_conv_title)
        title_row.addStretch()
        self._btn_settings = QPushButton('⚙')
        self._btn_settings.setToolTip('AI设置')
        self._btn_settings.clicked.connect(self._open_settings)
        title_row.addWidget(self._btn_settings)
        self._btn_clear = QPushButton('🗑')
        self._btn_clear.setToolTip('清空当前对话')
        self._btn_clear.clicked.connect(self._clear_chat)
        title_row.addWidget(self._btn_clear)
        right.addLayout(title_row)

        self._chat_display = QTextBrowser()
        self._chat_display.setOpenExternalLinks(False)
        self._chat_display.setReadOnly(True)
        right.addWidget(self._chat_display, 1)

        # Token 进度条
        token_row = QHBoxLayout()
        token_row.setSpacing(4)
        self._token_bar = QProgressBar()
        self._token_bar.setRange(0, cfg_mod.load_config().get('context_window', 128000))
        self._token_bar.setValue(0)
        self._token_bar.setTextVisible(True)
        self._token_bar.setFormat('%v / %m')
        token_row.addWidget(QLabel('📊'), 0)
        token_row.addWidget(self._token_bar, 1)
        self._lbl_usage = QLabel('0/0')
        token_row.addWidget(self._lbl_usage)
        right.addLayout(token_row)

        self._lbl_status = QLabel('')
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
        self._update_send_state()
        self.apply_theme()

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
        c = Conversation.new(title=f'对话 {len(self._convs)+1}')
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

    def _render_conv_messages(self, scroll_mode="bottom", anchor=None):
        scroll_bar = self._chat_display.verticalScrollBar()
        previous_scroll = scroll_bar.value() if scroll_bar is not None else 0
        previous_anchor_y = None
        if scroll_mode == "preserve" and anchor:
            previous_anchor_y = self._anchor_document_y(anchor)
        try:
            self._chat_display.anchorClicked.disconnect(self._on_think_toggle)
        except TypeError:
            pass  # 信号尚未连接
        self._chat_display.clear()
        if self._active_conv is None:
            self._chat_display.anchorClicked.connect(self._on_think_toggle)
            return
        t = self._t()
        scale = app_scale_from_config(cfg_mod.load_config())
        for i, msg in enumerate(self._active_conv.messages):
            role = msg.role
            content = msg.content
            ts = msg.timestamp
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
                think_idx = sum(1 for m in self._active_conv.messages[:i] if m.role == 'think')
                expansion_key = (self._active_conv.id, think_idx)
                is_expanded = self._think_expanded.get(expansion_key, False)
                anchor_name = self._think_anchor_name(think_idx)
                if is_expanded:
                    header = f'<p><b style="color:{t["warning_text"]}">💭 思考 {time_str}</b> '
                    header += f'<a name="{anchor_name}" href="toggle_think_{think_idx}" style="color:{t["primary"]};text-decoration:none;font-size:{scale.caption_px}px;">收起 ▲</a></p>'
                    body = f'<p style="color:{t["subtitle_color"]}; margin-left:10px; font-style:italic;">{self._esc(content)}</p>'
                else:
                    short_preview = content[:100].replace('\n', ' ') + ('…' if len(content) > 100 else '')
                    header = f'<p><b style="color:{t["warning_text"]}">💭 思考 {time_str} ({len(content)}字)</b> '
                    header += f'<a name="{anchor_name}" href="toggle_think_{think_idx}" style="color:{t["primary"]};text-decoration:none;font-size:{scale.caption_px}px;">展开 ▼</a></p>'
                    body = f'<p style="color:{t["muted_color"]}; margin-left:10px; font-size:{scale.caption_px}px;">💡 {self._esc(short_preview)}</p>'
            else:
                continue
            self._chat_display.insertHtml(header + body + '<hr style="border:0;height:1px;background:#30363d;">')
        if scroll_mode == "preserve" and scroll_bar is not None:
            current_anchor_y = self._anchor_document_y(anchor) if anchor else None
            if previous_anchor_y is None or current_anchor_y is None:
                target_scroll = previous_scroll
            else:
                target_scroll = previous_scroll + current_anchor_y - previous_anchor_y
            target_scroll = max(
                scroll_bar.minimum(), min(scroll_bar.maximum(), int(target_scroll))
            )
            scroll_bar.setValue(target_scroll)
        else:
            self._chat_display.moveCursor(QTextCursor.End)
            if scroll_bar is not None:
                scroll_bar.setValue(scroll_bar.maximum())
        self._chat_display.anchorClicked.connect(self._on_think_toggle)

    @staticmethod
    def _think_anchor_name(think_idx):
        return f'think_anchor_{think_idx}'

    def _anchor_document_y(self, anchor):
        document = self._chat_display.document()
        layout = document.documentLayout()
        block = document.begin()
        while block.isValid():
            iterator = block.begin()
            while not iterator.atEnd():
                fragment = iterator.fragment()
                if fragment.isValid() and anchor in fragment.charFormat().anchorNames():
                    return layout.blockBoundingRect(block).top()
                iterator += 1
            block = block.next()
        return None

    def _on_think_toggle(self, url):
        if self._active_conv is None:
            return
        href = url.toString()
        match = re.fullmatch(r'toggle_think_([0-9]+)', href)
        if match is None:
            return
        think_idx = int(match.group(1))
        think_count = sum(
            1 for message in self._active_conv.messages if message.role == 'think'
        )
        if think_idx >= think_count:
            return
        expansion_key = (self._active_conv.id, think_idx)
        if self._think_expanded.get(expansion_key, False):
            self._think_expanded.pop(expansion_key, None)
        else:
            self._think_expanded[expansion_key] = True
        self._render_conv_messages(
            scroll_mode="preserve",
            anchor=self._think_anchor_name(think_idx),
        )

    def _esc(self, text: str) -> str:
        """安全转义HTML特殊字符, 使用chr()避免XML实体冲突"""
        a = chr(38)
        return text.replace('&', a + 'amp;').replace('<', a + 'lt;').replace('>', a + 'gt;').replace('\n', '<br>')

    def submit_external_message(self, text: str) -> bool:
        """Submit text through the active main conversation without duplicating AI state."""
        return self._submit_message(text, source="external")

    def _send_message(self):
        return self._submit_message(self._edit_input.text(), source="main")

    def _request_in_flight(self) -> bool:
        if self._pending_conv_id is not None:
            return True
        thread = self._ai_thread
        is_running = getattr(thread, "isRunning", None) if thread is not None else None
        if callable(is_running):
            try:
                return bool(is_running())
            except (RuntimeError, TypeError):
                return False
        return False

    @classmethod
    def _bounded_external_error(cls, error) -> str:
        text = str(error).replace("\r", " ").replace("\n", " ").strip()
        if not text:
            text = "AI 请求失败"
        http_error = re.match(r"API错误\(([0-9]{3})\)", text)
        if http_error is not None:
            text = http_error.group(0)
        elif text.startswith("AI调用异常"):
            text = "AI 调用失败"
        prefix = "error:"
        return prefix + text[:cls._EXTERNAL_STATUS_LIMIT - len(prefix)]

    def _reject_submission(self, source: str, message: str) -> bool:
        if source == "external":
            self.external_request_status.emit(self._bounded_external_error(message))
        return False

    def _submit_message(self, text: str, source: str) -> bool:
        if source not in ("main", "external"):
            raise ValueError("未知的 AI 请求来源")
        if not isinstance(text, str) or not text.strip():
            return self._reject_submission(source, "请输入内容")
        if self._active_conv is None:
            return self._reject_submission(source, "当前没有可用对话")
        if self._request_in_flight():
            return self._reject_submission(source, "AI 请求正在处理中")
        if not cfg_mod.is_api_configured():
            if source == "main":
                QMessageBox.warning(self, '未配置', '请先在设置中配置API地址和密钥')
            return self._reject_submission(source, "请先配置 API 地址和密钥")

        txt = text.strip()
        if source == "main":
            self._edit_input.clear()
        self._edit_input.setEnabled(False)
        self._btn_send.setEnabled(False)
        self._lbl_status.setText('⏳ AI思考中...')

        conversation = self._active_conv
        conversation.add_message('user', txt)
        self._recalculate_token_count(conversation)
        self._chat_display.clear()
        self._render_conv_messages()
        save_conversations(self._convs)
        self._update_token_bar()

        cfg = cfg_mod.load_config()
        messages = AIService.build_request_messages(
            conversation,
            context_window=cfg.get('context_window', 128000),
            max_output_tokens=cfg.get('max_tokens', 4096),
        )
        self._pending_conv_id = conversation.id
        self._pending_source = source
        self._ai_thread = AICallThread(messages=messages)
        self._ai_thread.result_ready.connect(self._on_result)
        self._ai_thread.error_occurred.connect(self._on_error)
        self.external_request_status.emit("working")
        self._ai_thread.start()
        return True

    def _finish_request(self):
        self._pending_conv_id = None
        self._pending_source = None
        configured = cfg_mod.is_api_configured()
        self._edit_input.setEnabled(configured)
        self._btn_send.setEnabled(configured)
        if configured:
            self._edit_input.setFocus()

    def _on_result(self, parsed: dict):
        if self._pending_conv_id is None:
            return
        self._lbl_status.setText('')
        conv = self._convs.get(self._pending_conv_id)
        if conv is None:
            self._on_error('对应对话已不存在')
            return
        think = parsed.get('think', '')
        ops = parsed.get('operations', [])
        reply_text = parsed.get('reply_text', '')

        if think:
            conv.add_message('think', think)

        has_change = False
        if ops:
            op_results = AIService.execute_operations(ops)
            replies = [
                op.get('message', '') for op in ops
                if isinstance(op, dict) and op.get('action') == 'reply'
                and isinstance(op.get('message'), str)
            ]
            if op_results:
                conv.add_message('assistant', '\n'.join(op_results))
            if replies:
                conv.add_message('assistant', '\n'.join(replies))
            has_change = any(
                result.startswith(('✅ 已添加', '✅ 已更新', '✅ 已删除'))
                for result in op_results
            )
        elif reply_text:
            conv.add_message('assistant', reply_text)
        else:
            raw = parsed.get('raw', '')
            if raw:
                conv.add_message('assistant', raw)

        self._recalculate_token_count(conv)
        usage_total = parsed.get('usage', {}).get('total_tokens')
        if isinstance(usage_total, int) and usage_total >= 0:
            conv.token_count = max(conv.token_count, usage_total)

        if self._active_conv is conv:
            self._chat_display.clear()
            self._render_conv_messages()

        save_conversations(self._convs)
        self._update_token_bar()

        if conv.title.startswith('对话') or conv.title == '新对话':
            msgs = conv.messages
            if msgs:
                first = msgs[0].content
                conv.title = first[:30] + ('…' if len(first) > 30 else '')
                save_conversations(self._convs)
                self._sidebar.refresh(self._convs, self._active_conv.id if self._active_conv else None)
                if self._active_conv is conv:
                    self._lbl_conv_title.setText(f'💬 {conv.title}')

        if has_change:
            self.data_changed.emit()
        self.external_request_status.emit("changed" if has_change else "unchanged")
        self._finish_request()

    def _on_error(self, err: str):
        if self._pending_conv_id is None:
            return
        self._lbl_status.setText('')
        conv = self._convs.get(self._pending_conv_id)
        if conv is not None:
            conv.add_message('assistant', f'❌ 错误: {err}')
            self._recalculate_token_count(conv)
        if self._active_conv is conv:
            self._chat_display.clear()
            self._render_conv_messages()
        save_conversations(self._convs)
        self.external_request_status.emit(self._bounded_external_error(err))
        self._finish_request()

    @staticmethod
    def _recalculate_token_count(conv: Conversation) -> None:
        messages = [
            {"role": message.role, "content": message.content}
            for message in conv.messages
        ]
        conv.token_count = AIService.count_messages_tokens(messages)

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
        scale = app_scale_from_config(cfg_mod.load_config())
        ratio = used / max(total, 1)
        if ratio > 0.8:
            chunk = '#e74c3c'
        elif ratio > 0.5:
            chunk = '#d29922'
        else:
            chunk = '#3fb950'
        token_height = max(
            14,
            QFontMetrics(qfont_for(scale, 'caption')).height() + 4,
        )
        self._token_bar.setFixedHeight(token_height)
        self._token_bar.setStyleSheet(f"""
            QProgressBar{{border:1px solid {t["frame_border"]};border-radius:3px;background:{t["list_bg"]};text-align:center;font-size:{scale.caption_px}px;color:{t["text_color"]};}}
            QProgressBar::chunk{{background:{chunk};border-radius:2px;}}
        """)

    def _update_send_state(self):
        ok = cfg_mod.is_api_configured()
        busy = self._request_in_flight()
        self._btn_send.setEnabled(ok and not busy)
        self._edit_input.setEnabled(ok and not busy)
        if not ok:
            self._lbl_status.setText('⚠️ 请先配置API')
        elif busy:
            self._lbl_status.setText('⏳ AI思考中...')
        else:
            self._lbl_status.setText('✅ 可以开始对话')

    def _open_settings(self):
        dlg = SettingsDialog(self)
        dlg.config_saved.connect(self._update_send_state)
        dlg.config_saved.connect(self._update_token_bar)
        dlg.exec_()

    def _clear_chat(self):
        if self._active_conv is None:
            return
        active_conversation_id = self._active_conv.id
        self._think_expanded = {
            key: value
            for key, value in self._think_expanded.items()
            if key[0] != active_conversation_id
        }
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
        scale = app_scale_from_config(cfg_mod.load_config())
        self.setStyleSheet(f'AIChatWidget{{background:{t["frame_bg"]};border-radius:8px;}}')
        self._lbl_conv_title.setStyleSheet(
            f'font-size:{scale.section_title_px}px;font-weight:bold;'
            f'color:{t["title_color"]};'
        )
        self._chat_display.setStyleSheet(f"""
            QTextEdit{{border:1px solid {t["frame_border"]};border-radius:6px;background:{t["list_bg"]};font-size:{scale.body_px}px;padding:6px;color:{t["text_color"]};}}
        """)
        self._lbl_usage.setStyleSheet(
            f'font-size:{scale.caption_px}px;color:{t["subtitle_color"]};'
        )
        self._lbl_status.setStyleSheet(
            f'color:{t["subtitle_color"]};font-size:{scale.secondary_px}px;'
        )
        self._edit_input.setStyleSheet(f'''
            QLineEdit{{padding:6px;font-size:{scale.control_px}px;border:1px solid {t["input_border"]};border-radius:6px;background:{t["input_bg"]};color:{t["text_color"]};}}
        ''')
        self._btn_send.setStyleSheet(f"""
            QPushButton{{background:{t["primary"]};color:{t["primary_text"]};border:none;border-radius:5px;padding:6px 16px;font-size:{scale.control_px}px;font-weight:bold;}}
            QPushButton:hover{{background:{t["primary_hover"]};}} QPushButton:disabled{{background:{t["muted_color"]};}}
        """)
        control_extent = max(
            26,
            QFontMetrics(qfont_for(scale, 'control')).height() + 8,
        )
        for btn in (self._btn_settings, self._btn_clear, self._btn_toggle_sidebar):
            btn.setFixedSize(control_extent, control_extent)
            btn.setStyleSheet(f"""
                QPushButton{{border:1px solid {t["input_border"]};border-radius:4px;padding:2px 4px;font-size:{scale.control_px}px;background:{t["frame_bg"]};color:{t["text_color"]};}}
                QPushButton:hover{{background:{t["list_item_hover"]};}}
            """)
        self._sidebar.apply_theme()
        self._update_token_bar()
        if self._active_conv:
            self._chat_display.clear()
            self._render_conv_messages()
