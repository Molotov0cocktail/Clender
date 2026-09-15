"""
AI 设置对话框 - API配置、模型选择、人格设定
从 ai_chat.py 提取为独立 UI 组件
"""
from PyQt5.QtWidgets import (QWidget, QVBoxLayout, QHBoxLayout, QPushButton,
                             QLabel, QLineEdit, QTextEdit, QComboBox,
                             QSpinBox, QDoubleSpinBox, QFormLayout,
                             QDialogButtonBox, QDialog, QMessageBox, QCheckBox)
from PyQt5.QtCore import pyqtSignal

import config as cfg_mod
import theme_manager
from ai_client import fetch_models_list
from ai_service import AIService
from model_capabilities import TOKEN_LIMIT_MAX, resolve_model_capabilities
from typography import app_scale_from_config


class SettingsDialog(QDialog):
    config_saved = pyqtSignal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle('⚙️ AI 设置')
        self.setMinimumWidth(540)

        layout = QFormLayout(self)
        layout.setSpacing(10)
        layout.setContentsMargins(20, 14, 20, 14)

        connection_section = QLabel('接口与模型')
        connection_section.setObjectName('aiSettingsSection')
        layout.addRow(connection_section)

        self._edit_ep = QLineEdit()
        self._edit_ep.setPlaceholderText('https://api.openai.com')
        layout.addRow('API地址:', self._edit_ep)

        self._edit_key = QLineEdit()
        self._edit_key.setEchoMode(QLineEdit.Password)
        self._edit_key.setPlaceholderText('sk-...')
        layout.addRow('API密钥:', self._edit_key)

        model_row = QHBoxLayout()
        self._cmb_model = QComboBox()
        self._cmb_model.setEditable(True)
        self._cmb_model.setMinimumWidth(300)
        model_row.addWidget(self._cmb_model, 1)
        self._btn_fetch = QPushButton('🔄 获取模型')
        self._btn_fetch.setToolTip('获取模型列表；按 API 元数据或官方文档填充已知上限，未知项可手动填写')
        self._btn_fetch.clicked.connect(self._fetch_models)
        model_row.addWidget(self._btn_fetch)
        layout.addRow('模型:', model_row)

        self._spin_temp = QDoubleSpinBox()
        self._spin_temp.setRange(0, 2)
        self._spin_temp.setSingleStep(0.1)
        self._spin_temp.setValue(0.7)
        layout.addRow('温度:', self._spin_temp)

        self._spin_max_tok = QSpinBox()
        self._spin_max_tok.setRange(1, TOKEN_LIMIT_MAX)
        self._spin_max_tok.setSingleStep(256)
        self._spin_max_tok.setValue(4096)
        layout.addRow('最大输出Token:', self._spin_max_tok)

        self._spin_ctx = QSpinBox()
        self._spin_ctx.setRange(1, TOKEN_LIMIT_MAX)
        self._spin_ctx.setSingleStep(1024)
        self._spin_ctx.setValue(128000)
        self._spin_ctx.setToolTip('上下文窗口大小（token），控制发送给AI的历史长度')
        layout.addRow('上下文窗口:', self._spin_ctx)

        # ── Thinking 模式开关（仅 DeepSeek）──
        think_header = QHBoxLayout()
        self._chk_think = QCheckBox('启用思考模式')
        self._chk_think.setToolTip('关闭则返回直接回复，无推理过程')
        think_header.addWidget(self._chk_think)
        layout.addRow('💭 Thinking:', think_header)

        self._cmb_think = QComboBox()
        self._cmb_think.addItems(['max', 'xhigh', 'high', 'medium', 'low'])
        layout.addRow('   思考强度:', self._cmb_think)

        self._lbl_info = QLabel('')
        self._lbl_info.setObjectName('aiSettingsInfo')
        self._lbl_info.setWordWrap(True)
        layout.addRow(self._lbl_info)

        persona_header = QHBoxLayout()
        self._lbl_personality = QLabel('🎭 AI 人格设定')
        self._lbl_personality.setObjectName('aiSettingsPersonalityTitle')
        persona_header.addWidget(self._lbl_personality)
        persona_header.addStretch()
        layout.addRow(persona_header)

        self._personality_hint = QLabel(
            '内置日程指令自动生效；\n'
            '此处可设置语气与人格，留空清除自定义内容。'
        )
        self._personality_hint.setObjectName('aiSettingsHint')
        self._personality_hint.setWordWrap(True)
        layout.addRow(self._personality_hint)
        self._edit_personality = QTextEdit()
        self._edit_personality.setPlaceholderText('如："你是一个严谨的时间管理专家，说话简洁专业"\n或 "你是一个亲切的私人助理，用轻松幽默的语气回复"\n\n留空则不添加额外人格设定')
        self._edit_personality.setMaximumHeight(110)
        self._edit_personality.setMinimumHeight(110)
        layout.addRow(self._edit_personality)

        btn = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
        btn.button(QDialogButtonBox.Save).setText('保存')
        btn.button(QDialogButtonBox.Save).setProperty('btnClass', 'primary')
        btn.button(QDialogButtonBox.Cancel).setText('取消')
        btn.accepted.connect(self._save)
        btn.rejected.connect(self.reject)
        layout.addRow(btn)

        self._load()
        self._cmb_model.currentTextChanged.connect(self._model_changed)
        self._edit_ep.textChanged.connect(self._endpoint_changed)
        self._show_capabilities()
        self.apply_theme()

    def apply_theme(self):
        """Apply the current application typography and theme colors."""
        t = theme_manager.get_current_theme()
        scale = app_scale_from_config(cfg_mod.load_config())
        self.setStyleSheet(f'''
            QDialog {{ background: {t["frame_bg"]}; }}
            QLabel {{ color: {t["text_color"]}; background: transparent; }}
            QLabel#aiSettingsSection, QLabel#aiSettingsPersonalityTitle {{
                font-size: {scale.section_title_px}px; font-weight: bold;
                color: {t["primary"]};
                border-left: 3px solid {t["primary"]};
                padding: 2px 8px;
            }}
            QLabel#aiSettingsInfo, QLabel#aiSettingsHint {{
                color: {t["subtitle_color"]};
                font-size: {scale.secondary_px}px;
            }}
            QLineEdit, QComboBox, QSpinBox, QDoubleSpinBox {{
                background: {t.get("input_bg", t["list_bg"])}; color: {t["text_color"]};
                border: 1px solid {t.get("border_soft", t["input_border"])};
                border-radius: 6px; padding: 6px;
            }}
            QTextEdit {{
                background: {t.get("input_bg", t["list_bg"])}; color: {t["text_color"]};
                border: 1px solid {t.get("border_soft", t["input_border"])};
                border-radius: 6px; padding: 4px;
            }}
            QLineEdit:focus, QTextEdit:focus, QComboBox:focus,
            QSpinBox:focus, QDoubleSpinBox:focus {{
                border: 1px solid {t["primary"]};
            }}
        ''')

    def _load(self):
        cfg = cfg_mod.load_config()
        self._metadata_endpoint = cfg.get('available_models_endpoint', '')
        self._model_metadata = cfg.get('model_capabilities', {})
        self._edit_ep.setText(cfg.get('api_endpoint', ''))
        self._edit_key.setText(cfg.get('api_key', ''))
        self._edit_personality.setPlainText(AIService.merge_personality_settings(cfg))
        cur_model = cfg.get('model', 'deepseek-v4-pro')
        models = cfg.get('available_models', [])
        self._cmb_model.clear()
        if models:
            self._cmb_model.addItems(models)
            idx = self._cmb_model.findText(cur_model)
            if idx >= 0:
                self._cmb_model.setCurrentIndex(idx)
            else:
                self._cmb_model.setEditText(cur_model)
        else:
            self._cmb_model.addItem(cur_model)
            self._cmb_model.setCurrentIndex(0)
        self._spin_temp.setValue(cfg.get('temperature', 0.7))
        self._spin_max_tok.setValue(cfg.get('max_tokens', 4096))
        self._spin_ctx.setValue(cfg.get('context_window', 128000))
        self._chk_think.setChecked(cfg.get('thinking_enabled', True))
        idx = self._cmb_think.findText(cfg.get('think_effort', 'high'))
        if idx >= 0:
            self._cmb_think.setCurrentIndex(idx)

    def _capabilities(self):
        from ai_client import normalize_endpoint
        endpoint = self._edit_ep.text().strip()
        model = self._cmb_model.currentText().strip()
        metadata = None
        if (self._metadata_endpoint and
                normalize_endpoint(endpoint) == normalize_endpoint(self._metadata_endpoint) and
                isinstance(self._model_metadata, dict)):
            metadata = self._model_metadata.get(model)
        return resolve_model_capabilities(model, endpoint, metadata)

    def _show_capabilities(self, prefix=''):
        caps = self._capabilities()
        source = {'metadata': 'API 元数据', 'official': '官方文档',
                  'metadata+official': 'API 元数据 / 官方文档', 'unknown': '未提供能力'}.get(caps.source, '未提供能力')
        context = f'{caps.context_window:,}' if caps.context_window else '未知'
        output = f'{caps.max_output_tokens:,}' if caps.max_output_tokens else '未知'
        self._lbl_info.setText(f'{prefix}{source} | 上下文窗口: {context} | 最大输出: {output}')
        self._lbl_info.setToolTip(caps.source_url or (
            '服务商模型列表返回的能力；未知项可按服务商文档手动填写。'
            if caps.source == 'metadata' else
            '未知项可按服务商文档手动填写；当前输入不会被猜测值覆盖。'))
        return caps

    def _model_changed(self):
        caps = self._show_capabilities()
        self._apply_capabilities(caps)

    def _apply_capabilities(self, caps, fill_maximum=False):
        output = self._spin_max_tok.value()
        if caps.context_window:
            self._spin_ctx.setValue(caps.context_window)
            # Match the request budget's safety margin. Independent input/output
            # ceilings do not mean they can both be consumed in one request.
            available = max(1, caps.context_window -
                            max(32, int(caps.context_window * 0.1)) - 1)
            if fill_maximum and caps.max_output_tokens and caps.max_output_tokens <= available:
                output = caps.max_output_tokens
            output = min(output, available)
        if caps.max_output_tokens:
            output = min(output, caps.max_output_tokens)
        self._spin_max_tok.setValue(output)

    def _endpoint_changed(self):
        self._metadata_endpoint = ''
        self._model_metadata = {}
        self._model_changed()

    def _fetch_models(self):
        ep = self._edit_ep.text().strip()
        key = self._edit_key.text().strip()
        if not ep or not key:
            self._lbl_info.setText('❌ 请填写 API 地址和密钥后再获取模型')
            return
        selected = self._cmb_model.currentText().strip()
        current = cfg_mod.load_config()
        current['api_endpoint'] = ep
        current['api_key'] = key
        try:
            cfg_mod.save_config(current)
        except OSError as exc:
            self._lbl_info.setText(f'❌ 无法保存 API 配置: {exc}')
            return
        models, err = fetch_models_list()
        if err:
            self._lbl_info.setText(f'❌ {err}')
        else:
            saved = cfg_mod.load_config()
            self._metadata_endpoint = saved.get('available_models_endpoint', '')
            self._model_metadata = saved.get('model_capabilities', {})
            self._cmb_model.blockSignals(True)
            self._cmb_model.clear()
            self._cmb_model.addItems(models)
            idx = self._cmb_model.findText(selected)
            if idx >= 0:
                self._cmb_model.setCurrentIndex(idx)
            elif not models:
                self._cmb_model.setEditText(selected)
            self._cmb_model.blockSignals(False)
            caps = self._show_capabilities(f'✅ 获取到 {len(models)} 个模型\n')
            self._apply_capabilities(caps, fill_maximum=True)

    def _save(self):
        caps = self._capabilities()
        context = self._spin_ctx.value()
        output = self._spin_max_tok.value()
        error = ''
        if caps.context_window and context > caps.context_window:
            error = f'上下文窗口不能超过当前模型上限 {caps.context_window:,} tokens。'
        elif caps.max_output_tokens and output > caps.max_output_tokens:
            error = f'最大输出不能超过当前模型上限 {caps.max_output_tokens:,} tokens。'
        elif output >= context:
            error = '最大输出必须小于上下文窗口，为输入消息保留空间。'
        if error:
            QMessageBox.warning(self, 'Token 设置无效', error)
            return
        current = dict(cfg_mod.load_config())
        current.update({
            'api_endpoint': self._edit_ep.text().strip(),
            'api_key': self._edit_key.text().strip(),
            'model': self._cmb_model.currentText().strip(),
            'temperature': self._spin_temp.value(),
            'max_tokens': self._spin_max_tok.value(),
            'context_window': self._spin_ctx.value(),
            'thinking_enabled': self._chk_think.isChecked(),
            'think_effort': self._cmb_think.currentText(),
            'system_prompt': '',
            'ai_personality': self._edit_personality.toPlainText().strip(),
        })
        try:
            cfg_mod.save_config(current)
        except OSError as exc:
            QMessageBox.critical(self, '保存失败', f'设置未保存：{exc}')
            return
        QMessageBox.information(self, '成功', '设置已保存！')
        self.config_saved.emit()
        self.accept()
