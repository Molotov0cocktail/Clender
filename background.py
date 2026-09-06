"""Local-only background rendering with bounded decoding and safe theme masks."""
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping

from PyQt5.QtCore import Qt, QRectF, QSize
from PyQt5.QtGui import QColor, QImage, QImageReader, QPainter
from PyQt5.QtWidgets import QWidget


@dataclass(frozen=True)
class BackgroundSettings:
    path: str = ''
    strength: int = 60

    @classmethod
    def from_config(cls, config: Mapping[str, object]) -> 'BackgroundSettings':
        path = config.get('background_image', '')
        strength = config.get('background_strength', 60)
        if type(strength) is not int or not 0 <= strength <= 100:
            strength = 60
        return cls(path if isinstance(path, str) else '', strength)

    def mask_opacity(self, theme: str) -> float:
        # At maximum visibility, white/black artwork remains behind a safe veil.
        return 1.0 - self.strength / 100 * (0.40 if theme == 'dark' else 0.45)


def load_background(path: str) -> QImage:
    """Reject oversized inputs before decode; retain at most a 1920px image."""
    if not path:
        return QImage()
    try:
        source = Path(path)
        if not source.is_file() or source.stat().st_size > 32 * 1024 * 1024:
            return QImage()
        reader = QImageReader(str(source))
        reader.setAutoTransform(True)
        size = reader.size()
        if not size.isValid() or size.width() * size.height() > 24_000_000:
            return QImage()
        reader.setScaledSize(size.scaled(QSize(1920, 1920), Qt.KeepAspectRatio))
        return reader.read()
    except (OSError, ValueError):
        return QImage()


class BackgroundWidget(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.settings = BackgroundSettings()
        self._image = QImage()
        self._cache_key = None
        self._theme = 'light'

    @property
    def has_background(self) -> bool:
        return not self._image.isNull()

    def apply_config(self, config: Mapping[str, object]) -> None:
        self.settings = BackgroundSettings.from_config(config)
        self._theme = config.get('theme', 'light')
        try:
            stat = Path(self.settings.path).stat() if self.settings.path else None
            key = (self.settings.path, stat.st_mtime_ns, stat.st_size) if stat else None
        except (OSError, ValueError):
            key = (self.settings.path, None)
        if key != self._cache_key:
            self._image = load_background(self.settings.path)
            self._cache_key = key
        self.update()

    def paintEvent(self, event):
        painter = QPainter(self)
        color = QColor('#101522' if self._theme == 'dark' else '#f0f3fa')
        painter.fillRect(self.rect(), color)
        if self.has_background:
            painter.setRenderHint(QPainter.SmoothPixmapTransform)
            width, height = self._image.width(), self._image.height()
            ratio = max(self.width() / width, self.height() / height)
            target = QRectF((self.width() - width * ratio) / 2,
                            (self.height() - height * ratio) / 2,
                            width * ratio, height * ratio)
            painter.drawImage(target, self._image)
            color.setAlphaF(self.settings.mask_opacity(self._theme))
            painter.fillRect(self.rect(), color)
        painter.end()
