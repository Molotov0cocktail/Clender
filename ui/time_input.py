"""UI-only HHmm entry with a muted, unchanged original time."""
import re
from PyQt5.QtCore import QTime
from PyQt5.QtWidgets import QLineEdit


class TimeInput(QLineEdit):
    """Accept four digits or HH:mm; empty input retains the original QTime."""

    def __init__(self, time: QTime, parent=None):
        super().__init__(parent)
        self.setMaxLength(5)
        self.setTime(time)
        self.setToolTip('输入四位数字，例如 0930 → 09:30；留空保留原时间')
        self.textEdited.connect(self._format_digits)

    def setTime(self, time: QTime) -> None:
        if not time.isValid():
            raise ValueError('时间无效')
        self._original_time = QTime(time)
        self.setPlaceholderText(time.toString('HH:mm'))
        self.clear()

    def time(self) -> QTime:
        text = self.text().strip()
        if not text:
            return QTime(self._original_time)
        if re.fullmatch(r'[0-9]{4}', text):
            text = text[:2] + ':' + text[2:]
        if not re.fullmatch(r'[0-9]{2}:[0-9]{2}', text):
            raise ValueError('请输入完整的四位时间，例如 0930（09:30）')
        result = QTime.fromString(text, 'HH:mm')
        if not result.isValid():
            raise ValueError('时间须在 00:00 到 23:59 之间')
        return result

    def _format_digits(self, text: str) -> None:
        if re.fullmatch(r'[0-9]{4}', text):
            self.setText(text[:2] + ':' + text[2:])
            self.setCursorPosition(5)
