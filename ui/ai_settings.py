"""
AI 设置对话框 - API配置、模型选择、系统提示词编辑
从 ai_chat.py 提取为独立 UI 组件
"""
from PyQt5.QtWidgets import (QWidget, QVBoxLayout, QHBoxLayout, QPushButton,
                             QLabel, QLineEdit, QTextEdit, QComboBox,
                             QSpinBox, QDoubleSpinBox, QFormLayout,
                             QDialogButtonBox, QDialog, QMessageBox, QCheckBox)
from PyQt5.QtCore import pyqtSignal

import config as cfg_mod
import theme_manager
from constants import SYSTEM_PROMPT
from ai_client import fetch_models_list
from ai_service import AIService


class SettingsDialog(QDialog):
    config_saved = pyqtSignal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle('⚙️ AI 设置')
        self.setMinimumWidth(540)

        layout = QFormLayout(self)
        layout.setSpacing(10)

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
        self._btn_fetch.setToolTip('从API获取模型列表，并自动填充上下文窗口和最大输出Token')
        self._btn_fetch.clicked.connect(self._fetch_models)
        model_row.addWidget(self._btn_fetch)
        layout.addRow('模型:', model_row)

        self._spin_temp = QDoubleSpinBox()
        self._spin_temp.setRange(0, 2)
        self._spin_temp.setSingleStep(0.1)
        self._spin_temp.setValue(0.7)
        layout.addRow('温度:', self._spin_temp)

        self._spin_max_tok = QSpinBox()
        self._spin_max_tok.setRange(100, 65536)
        self._spin_max_tok.setSingleStep(256)
        self._spin_max_tok.setValue(4096)
        layout.addRow('最大输出Token:', self._spin_max_tok)

        self._spin_ctx = QSpinBox()
        self._spin_ctx.setRange(1024, 1048576)
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
        t = theme_manager.get_current_theme()
        self._lbl_info.setStyleSheet(f'color:{t["subtitle_color"]}; font-size:11px;')
        layout.addRow(self._lbl_info)

        # ── 系统提示词编辑 ──
        layout.addRow(QLabel(''))
        prompt_header = QHBoxLayout()
        prompt_lbl = QLabel('📝 系统提示词')
        prompt_lbl.setStyleSheet(f'font-weight:bold; color:{t["title_color"]}; font-size:13px;')
        prompt_header.addWidget(prompt_lbl)
        prompt_header.addStretch()
        self._btn_reset_prompt = QPushButton('恢复默认')
        self._btn_reset_prompt.setFixedHeight(24)
        self._btn_reset_prompt.setToolTip('重置为默认系统提示词')
        self._btn_reset_prompt.clicked.connect(self._reset_prompt)
        prompt_header.addWidget(self._btn_reset_prompt)
        layout.addRow(prompt_header)

        self._edit_prompt = QTextEdit()
        self._edit_prompt.setPlaceholderText('留空则使用默认提示词\n\n提示：在此编写自定义的系统级指令...')
        self._edit_prompt.setMaximumHeight(180)
        layout.addRow(self._edit_prompt)

        # ── AI人格描述 ──
        layout.addRow(QLabel(''))
        persona_header = QHBoxLayout()
        persona_lbl = QLabel('🎭 AI 人格设定')
        persona_lbl.setStyleSheet(f'font-weight:bold; color:{t["title_color"]}; font-size:13px;')
        persona_header.addWidget(persona_lbl)
        persona_header.addStretch()
        layout.addRow(persona_header)

        self._edit_personality = QTextEdit()
        self._edit_personality.setPlaceholderText('如："你是一个严谨的时间管理专家，说话简洁专业"\n或 "你是一个亲切的私人助理，用轻松幽默的语气回复"\n\n留空则不添加额外人格设定')
        self._edit_personality.setMaximumHeight(100)
        layout.addRow(self._edit_personality)

        btn = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
        btn.button(QDialogButtonBox.Save).setText('保存')
        btn.button(QDialogButtonBox.Cancel).setText('取消')
        btn.accepted.connect(self._save)
        btn.rejected.connect(self.reject)
        layout.addRow(btn)

        self._load()

    def _load(self):
        cfg = cfg_mod.load_config()
        self._edit_ep.setText(cfg.get('api_endpoint', ''))
        self._edit_key.setText(cfg.get('api_key', ''))
        self._edit_prompt.setPlainText(cfg.get('system_prompt', ''))
        self._edit_personality.setPlainText(cfg.get('ai_personality', ''))
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

    def _fetch_models(self):
        ep = self._edit_ep.text().strip()
        key = self._edit_key.text().strip()
        if not ep or not key:
            self._lbl_info.setText('❌ 请填写 API 地址和密钥后再获取模型')
            return
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
            self._cmb_model.clear()
            self._cmb_model.addItems(models)
            cur = cfg_mod.load_config().get('model', 'deepseek-v4-pro')
            idx = self._cmb_model.findText(cur)
            if idx >= 0:
                self._cmb_model.setCurrentIndex(idx)
            ctx_win, max_out = AIService.guess_model_capabilities(cur)
            self._spin_ctx.setValue(ctx_win)
            self._spin_max_tok.setValue(max_out)
            self._lbl_info.setText(f'✅ 获取到 {len(models)} 个模型 | 上下文窗口: {ctx_win//1024}K | 最大输出: {max_out}')

    def _reset_prompt(self):
        self._edit_prompt.setPlainText(SYSTEM_PROMPT)

    def _save(self):
        current = cfg_mod.load_config()
        current.update({
            'api_endpoint': self._edit_ep.text().strip(),
            'api_key': self._edit_key.text().strip(),
            'model': self._cmb_model.currentText().strip(),
            'temperature': self._spin_temp.value(),
            'max_tokens': self._spin_max_tok.value(),
            'context_window': self._spin_ctx.value(),
            'thinking_enabled': self._chk_think.isChecked(),
            'think_effort': self._cmb_think.currentText(),
            'system_prompt': self._edit_prompt.toPlainText().strip(),
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
