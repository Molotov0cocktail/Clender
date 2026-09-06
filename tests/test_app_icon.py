"""Original icon assets must work without runtime resource files."""
import importlib
import os
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
from PyQt5.QtWidgets import QApplication
from PyQt5.QtCore import QSize
from PyQt5.QtGui import QImage

ROOT = Path(__file__).resolve().parents[1]


class AppIconTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def test_runtime_icon_without_assets_or_working_directory(self):
        module = importlib.import_module("app_icon")
        with tempfile.TemporaryDirectory() as directory, patch("pathlib.Path.read_bytes", side_effect=OSError):
            old = os.getcwd()
            try:
                os.chdir(directory)
                icon = module.create_app_icon()
            finally:
                os.chdir(old)
        for size in (16, 24, 32, 48, 64, 128, 256):
            self.assertIn(QSize(size, size), icon.availableSizes())
            pixels = icon.pixmap(size, size).toImage()
            self.assertFalse(pixels.isNull())
            self.assertEqual(pixels.pixelColor(0, 0).alpha(), 0)
            self.assertEqual(pixels.pixelColor(size // 2, size // 2).alpha(), 255)

    def test_multiresolution_windows_icon_is_decodable(self):
        raw = (ROOT / "icon.ico").read_bytes()
        self.assertEqual(struct.unpack_from("<HHH", raw), (0, 1, 7))
        dimensions = []
        for index in range(7):
            width, height, _, _, _, _, length, offset = struct.unpack_from("<BBBBHHII", raw, 6 + 16 * index)
            dimensions.append(width or 256)
            frame = QImage.fromData(raw[offset:offset + length], "PNG")
            self.assertEqual(frame.size(), QSize(width or 256, height or 256))
        self.assertEqual(dimensions, [16, 24, 32, 48, 64, 128, 256])

    def test_adaptive_and_monochrome_resources(self):
        res = ROOT / "android/app/src/main/res"
        node = ET.parse(res / "mipmap-anydpi/ic_launcher.xml").getroot()
        self.assertEqual(node.tag, "adaptive-icon")
        self.assertIsNotNone(node.find("background"))
        self.assertIsNotNone(node.find("foreground"))
        self.assertIsNotNone(node.find("monochrome"))
        for obsolete in ("mipmap-anydpi-v26/ic_launcher.xml", "mipmap-anydpi-v33/ic_launcher.xml", "drawable/ic_launcher.xml"):
            self.assertFalse((res / obsolete).exists())
        manifest = ET.parse(ROOT / "android/app/src/main/AndroidManifest.xml")
        attr = "{http://schemas.android.com/apk/res/android}"
        self.assertEqual(manifest.find("application").get(attr + "icon"), "@mipmap/ic_launcher")
        self.assertEqual(manifest.find("application").get(attr + "roundIcon"), "@mipmap/ic_launcher")

    def test_size_contract_rejects_bad_input(self):
        module = importlib.import_module("app_icon")
        for size in (0, -1, 4097, True, "32", None):
            with self.assertRaises(ValueError):
                module.render_icon(size)


if __name__ == "__main__":
    unittest.main()
