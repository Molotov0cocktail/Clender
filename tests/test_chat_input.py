import os
import unittest
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
from PyQt5.QtCore import Qt
from PyQt5.QtGui import QInputMethodEvent
from PyQt5.QtTest import QTest
from PyQt5.QtWidgets import QApplication


class ChatInputTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        from ui.chat_input import ChatInput
        self.edit = ChatInput()
        self.edit.resize(240, 60)
        self.edit.show()
        self.addCleanup(self.edit.close)
        self.sent = []
        self.edit.returnPressed.connect(lambda: self.sent.append(self.edit.text()))

    def test_enter_sends_shift_enter_inserts_newline(self):
        self.edit.setText('first')
        QTest.keyClick(self.edit, Qt.Key_End)
        QTest.keyClick(self.edit, Qt.Key_Return, Qt.ShiftModifier)
        QTest.keyClicks(self.edit, 'second')
        QTest.keyClick(self.edit, Qt.Key_Return)
        self.assertEqual(self.sent, ['first\nsecond'])

    def test_wrapping_grows_with_bounded_height_and_shrinks(self):
        small = self.edit.height()
        self.edit.setText('long text ' * 200)
        self.app.processEvents()
        self.assertGreater(self.edit.height(), small)
        self.assertLessEqual(self.edit.height(), self.edit.fontMetrics().lineSpacing() * 6 + 22)
        self.assertEqual(self.edit.horizontalScrollBar().maximum(), 0)
        self.edit.clear()
        self.assertEqual(self.edit.height(), small)

    def test_preedit_enter_does_not_send_and_disabled_does_not_send(self):
        QApplication.sendEvent(self.edit, QInputMethodEvent('拼音', []))
        QTest.keyClick(self.edit, Qt.Key_Return)
        self.assertEqual(self.sent, [])
        QApplication.sendEvent(self.edit, QInputMethodEvent('', []))
        self.edit.setEnabled(False)
        QTest.keyClick(self.edit, Qt.Key_Return)
        self.assertEqual(self.sent, [])
