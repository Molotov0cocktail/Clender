import importlib
import os
import re
import unittest
from dataclasses import fields
from pathlib import Path
from unittest import mock


os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PyQt5.QtWidgets import QApplication

import theme_manager
from constants import DEFAULT_CONFIG
from floating_window_logic import FloatingWindowSettings
from ui.app_settings import AppSettingsDialog
from ui.daily_floating_window import DailyFloatingWindow


REPO_ROOT = Path(__file__).resolve().parents[1]
FONT_ROLE_NAMES = (
    "caption_px",
    "secondary_px",
    "body_px",
    "control_px",
    "section_title_px",
    "page_title_px",
)


def load_typography():
    """Import lazily so the pre-implementation run reports focused test names."""
    return importlib.import_module("typography")


def base_config(**overrides):
    config = dict(DEFAULT_CONFIG)
    config.update({
        "theme": "light",
        "app_font_size_px": 13,
        "floating_font_size_px": 13,
        "test_preserved_value": "保留",
    })
    config.update(overrides)
    return config


class TypographyContractTests(unittest.TestCase):
    def test_default_config_has_two_independent_pixel_sizes(self):
        self.assertEqual(DEFAULT_CONFIG["app_font_size_px"], 13)
        self.assertEqual(DEFAULT_CONFIG["floating_font_size_px"], 13)

    def test_validate_font_size_accepts_boundaries_and_rejects_bool_and_invalid_values(self):
        typography = load_typography()

        for valid in (8, 13, 20):
            with self.subTest(valid=valid):
                self.assertEqual(typography.validate_font_size(valid), valid)

        for invalid in (True, False, 7, 21, "13", 13.0, None):
            with self.subTest(invalid=invalid):
                self.assertEqual(
                    typography.validate_font_size(invalid),
                    typography.DEFAULT_FONT_PX,
                )

    def test_semantic_roles_are_named_once_and_clamped_to_supported_range(self):
        typography = load_typography()
        small = typography.build_scale(8)
        large = typography.build_scale(20)

        self.assertEqual(tuple(field.name for field in fields(small)), FONT_ROLE_NAMES)
        self.assertEqual(
            tuple(getattr(small, name) for name in FONT_ROLE_NAMES),
            (8, 8, 8, 8, 10, 12),
        )
        self.assertEqual(
            tuple(getattr(large, name) for name in FONT_ROLE_NAMES),
            (18, 19, 20, 20, 20, 20),
        )
        for scale in (small, large):
            self.assertTrue(all(
                typography.MIN_FONT_PX <= getattr(scale, name) <= typography.MAX_FONT_PX
                for name in FONT_ROLE_NAMES
            ))

    def test_application_and_floating_scales_do_not_share_their_base_value(self):
        typography = load_typography()

        config = base_config(app_font_size_px=8, floating_font_size_px=20)
        app_scale = typography.app_scale_from_config(config)
        floating_scale = typography.floating_scale_from_config(config)
        self.assertEqual(app_scale.body_px, 8)
        self.assertEqual(floating_scale.body_px, 20)

        reversed_config = base_config(app_font_size_px=20, floating_font_size_px=8)
        reversed_app = typography.app_scale_from_config(reversed_config)
        reversed_floating = typography.floating_scale_from_config(reversed_config)
        self.assertEqual(reversed_app.body_px, 20)
        self.assertEqual(reversed_floating.body_px, 8)

        invalid = base_config(app_font_size_px=True, floating_font_size_px="20")
        self.assertEqual(
            typography.app_scale_from_config(invalid).body_px,
            typography.DEFAULT_FONT_PX,
        )
        self.assertEqual(
            typography.floating_scale_from_config(invalid).body_px,
            typography.DEFAULT_FONT_PX,
        )

    def test_qfont_for_uses_pixel_size_instead_of_point_size(self):
        typography = load_typography()
        scale = typography.build_scale(13)

        font = typography.qfont_for(scale, "page_title")

        self.assertEqual(font.pixelSize(), scale.page_title_px)
        self.assertEqual(font.pointSize(), -1)


class AppSettingsTypographyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def make_dialog(self, config):
        load_patcher = mock.patch(
            "ui.app_settings.cfg_mod.load_config",
            return_value=dict(config),
        )
        save_patcher = mock.patch("ui.app_settings.cfg_mod.save_config")
        load_patcher.start()
        save_config = save_patcher.start()
        self.addCleanup(load_patcher.stop)
        self.addCleanup(save_patcher.stop)

        dialog = AppSettingsDialog()
        self.addCleanup(dialog.close)
        return dialog, save_config

    def test_two_font_control_groups_are_independent_synchronized_and_named(self):
        dialog, _ = self.make_dialog(
            base_config(app_font_size_px=8, floating_font_size_px=20)
        )
        controls = (
            dialog._app_font_slider,
            dialog._app_font_spin,
            dialog._floating_font_slider,
            dialog._floating_font_spin,
        )

        self.assertTrue(all(control.objectName() for control in controls))
        self.assertEqual(len({control.objectName() for control in controls}), len(controls))
        for control in controls:
            self.assertEqual((control.minimum(), control.maximum()), (8, 20))
            self.assertEqual(control.singleStep(), 1)

        self.assertEqual(dialog._app_font_slider.value(), 8)
        self.assertEqual(dialog._app_font_spin.value(), 8)
        self.assertEqual(dialog._floating_font_slider.value(), 20)
        self.assertEqual(dialog._floating_font_spin.value(), 20)

        dialog._app_font_slider.setValue(17)
        self.assertEqual(dialog._app_font_spin.value(), 17)
        self.assertEqual(dialog._floating_font_spin.value(), 20)
        dialog._app_font_spin.setValue(9)
        self.assertEqual(dialog._app_font_slider.value(), 9)

        dialog._floating_font_slider.setValue(11)
        self.assertEqual(dialog._floating_font_spin.value(), 11)
        self.assertEqual(dialog._app_font_spin.value(), 9)
        dialog._floating_font_spin.setValue(19)
        self.assertEqual(dialog._floating_font_slider.value(), 19)

    def test_save_emits_both_exact_sizes_and_preserves_complete_config(self):
        original = base_config(app_font_size_px=13, floating_font_size_px=13)
        dialog, save_config = self.make_dialog(original)
        emitted = []
        dialog.config_saved.connect(emitted.append)
        dialog._app_font_spin.setValue(8)
        dialog._floating_font_spin.setValue(20)

        dialog._save()

        self.assertEqual(len(emitted), 1)
        saved = emitted[0]
        self.assertEqual(saved["app_font_size_px"], 8)
        self.assertEqual(saved["floating_font_size_px"], 20)
        self.assertEqual(saved["test_preserved_value"], "保留")
        save_config.assert_called_once_with(saved)

    def test_save_failure_does_not_emit_or_close_dialog(self):
        dialog, save_config = self.make_dialog(base_config())
        dialog.show()
        emitted = []
        dialog.config_saved.connect(emitted.append)
        dialog._app_font_spin.setValue(8)
        dialog._floating_font_spin.setValue(20)
        save_config.side_effect = OSError("disk full")

        with mock.patch("ui.app_settings.QMessageBox.critical") as critical:
            dialog._save()

        critical.assert_called_once()
        self.assertEqual(emitted, [])
        self.assertTrue(dialog.isVisible())


class FloatingWindowTypographyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def test_global_app_qss_does_not_override_independent_floating_fonts(self):
        typography = load_typography()
        for app_size, floating_size in ((8, 20), (20, 8)):
            with self.subTest(app_size=app_size, floating_size=floating_size):
                config = base_config(
                    app_font_size_px=app_size,
                    floating_font_size_px=floating_size,
                    floating_window_enabled=True,
                )
                scale = typography.floating_scale_from_config(config)
                with mock.patch(
                    "config.load_config",
                    return_value=dict(config),
                ), mock.patch(
                    "ui.daily_floating_window.EventService.get_events_overlapping_range",
                    return_value=[],
                ):
                    theme_manager.apply_theme(self.app, config)
                    window = DailyFloatingWindow()
                    self.addCleanup(window.shutdown)
                    window.apply_settings(FloatingWindowSettings.from_config(config))
                    self.app.processEvents()

                    self.assertEqual(window._event_list.font().pixelSize(), scale.body_px)
                    self.assertEqual(window._ai_status.font().pixelSize(), scale.caption_px)
                    self.assertEqual(window._ai_input.font().pixelSize(), scale.control_px)
                    self.assertEqual(window._btn_pin.font().pixelSize(), scale.control_px)
                    self.assertTrue(window._ai_input.objectName())
                    self.assertEqual(
                        len(window.findChildren(type(window._ai_input), window._ai_input.objectName())),
                        1,
                    )


class TypographyStaticAuditTests(unittest.TestCase):
    @staticmethod
    def production_sources():
        root_modules = sorted(REPO_ROOT.glob("*.py"))
        ui_modules = sorted((REPO_ROOT / "ui").rglob("*.py"))
        allowed = {
            REPO_ROOT / "constants.py",
            REPO_ROOT / "typography.py",
        }
        return [path for path in root_modules + ui_modules if path not in allowed]

    def matching_locations(self, pattern):
        matches = []
        for path in self.production_sources():
            source = path.read_text(encoding="utf-8")
            for line_number, line in enumerate(source.splitlines(), 1):
                if pattern.search(line):
                    matches.append(f"{path.relative_to(REPO_ROOT)}:{line_number}: {line.strip()}")
        return matches

    def test_production_stylesheets_have_no_literal_font_size(self):
        pattern = re.compile(
            r"font-size\s*:\s*[+-]?(?:\d+(?:\.\d*)?|\.\d+)\s*(?:px|pt)\b",
            re.IGNORECASE,
        )
        matches = self.matching_locations(pattern)
        self.assertEqual(matches, [], "发现裸 QSS/HTML 字号：\n" + "\n".join(matches))

    def test_production_qfont_apis_have_no_numeric_size(self):
        qfont_constructor = re.compile(
            r"\bQFont\s*\([^\n)]*,\s*[+-]?(?:\d+(?:\.\d*)?|\.\d+)\s*(?:,|\))"
        )
        numeric_setter = re.compile(
            r"\.set(?:Point|Pixel)Size(?:F)?\s*\(\s*[+-]?(?:\d+(?:\.\d*)?|\.\d+)"
        )
        matches = self.matching_locations(qfont_constructor)
        matches.extend(self.matching_locations(numeric_setter))
        self.assertEqual(matches, [], "发现数字 QFont 字号 API：\n" + "\n".join(matches))


if __name__ == "__main__":
    unittest.main()
