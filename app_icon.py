"""Clender's original calendar/check mark, shared by all icon exports.

Runtime rendering uses only Qt primitives: no font, file or network dependency.
Coordinates use the Android adaptive icon's 108-unit viewport and safe zone.
"""
from PyQt5.QtCore import Qt, QRectF
from PyQt5.QtGui import QColor, QIcon, QImage, QLinearGradient, QPainter, QPainterPath, QPen, QPixmap

ICON_SIZES = (16, 24, 32, 48, 64, 128, 256)
BLUE = "#4968E8"
PURPLE = "#6851CB"
WHITE = "#FFFFFF"
MINT = "#70F0D5"
MARK_SCALE = 0.9
# (color, stroke width, path commands); kept inside the adaptive safe circle.
MARK_PATHS = (
    (WHITE, 4.5, (("M", 72, 33), ("L", 36, 33), ("Q", 29, 33, 29, 40),
                  ("L", 29, 72), ("Q", 29, 79, 36, 79), ("L", 72, 79),
                  ("Q", 79, 79, 79, 72), ("L", 79, 40), ("Q", 79, 33, 72, 33))),
    (WHITE, 4.5, (("M", 41, 27), ("L", 41, 39), ("M", 67, 27), ("L", 67, 39))),
    (WHITE, 3.0, (("M", 30, 47), ("L", 78, 47))),
    (MINT, 5.5, (("M", 42, 62), ("L", 51, 70), ("L", 67, 55))),
)


def render_icon(size: int) -> QImage:
    """Render a transparent-corner square; reject unsafe allocation requests."""
    if type(size) is not int or not 1 <= size <= 4096:
        raise ValueError("Icon size must be an integer from 1 to 4096")
    image = QImage(size, size, QImage.Format_ARGB32_Premultiplied)
    image.fill(Qt.transparent)
    painter = QPainter(image)
    try:
        painter.setRenderHint(QPainter.Antialiasing)
        painter.scale(size / 108, size / 108)
        gradient = QLinearGradient(10, 0, 98, 108)
        gradient.setColorAt(0, QColor(BLUE))
        gradient.setColorAt(1, QColor(PURPLE))
        painter.setPen(Qt.NoPen)
        painter.setBrush(gradient)
        painter.drawRoundedRect(QRectF(2, 2, 104, 104), 25, 25)
        painter.setBrush(Qt.NoBrush)
        painter.translate(54 * (1 - MARK_SCALE), 54 * (1 - MARK_SCALE))
        painter.scale(MARK_SCALE, MARK_SCALE)
        for color, width, commands in MARK_PATHS:
            path = QPainterPath()
            for command, *points in commands:
                if command == "M":
                    path.moveTo(*points)
                elif command == "L":
                    path.lineTo(*points)
                else:
                    path.quadTo(*points)
            painter.setPen(QPen(QColor(color), width, Qt.SolidLine, Qt.RoundCap, Qt.RoundJoin))
            painter.drawPath(path)
    finally:
        painter.end()
    return image


def create_app_icon() -> QIcon:
    """Return native-resolution window/tray icons after QApplication exists."""
    icon = QIcon()
    for size in ICON_SIZES:
        icon.addPixmap(QPixmap.fromImage(render_icon(size)))
    return icon
