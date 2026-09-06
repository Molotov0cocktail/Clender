"""UI-only wrapping chat editor; emits submission without managing AI state."""
import math

from PyQt5.QtCore import QEvent, Qt, pyqtSignal
from PyQt5.QtGui import QTextOption
from PyQt5.QtWidgets import QTextEdit


class ChatInput(QTextEdit):
    """Enter submits, Shift+Enter inserts a newline; grow up to six rows."""

    returnPressed = pyqtSignal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self._composing = False
        self.setAcceptRichText(False)
        self.setLineWrapMode(QTextEdit.WidgetWidth)
        self.setWordWrapMode(QTextOption.WrapAtWordBoundaryOrAnywhere)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self.document().documentLayout().documentSizeChanged.connect(self._resize_to_content)
        self.textChanged.connect(self._resize_to_content)
        self._resize_to_content()

    def text(self) -> str:
        """Compatibility accessor for the shared main/external send pipeline."""
        return self.toPlainText()

    def setText(self, text: str) -> None:
        self.setPlainText(text)

    def _resize_to_content(self, *_args):
        row = self.fontMetrics().lineSpacing()
        padding = 22
        minimum = row + padding
        maximum = row * 6 + padding
        content = math.ceil(self.document().size().height()) + 14
        self.setFixedHeight(max(minimum, min(maximum, content)))

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self._resize_to_content()

    def changeEvent(self, event):
        super().changeEvent(event)
        if event.type() in (QEvent.FontChange, QEvent.StyleChange):
            self._resize_to_content()

    def inputMethodEvent(self, event):
        self._composing = bool(event.preeditString())
        super().inputMethodEvent(event)

    def keyPressEvent(self, event):
        if event.key() in (Qt.Key_Return, Qt.Key_Enter) and not event.modifiers() & Qt.ShiftModifier:
            if self.isEnabled() and not self._composing:
                self.returnPressed.emit()
            event.accept()
            return
        super().keyPressEvent(event)
