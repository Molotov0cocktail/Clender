"""Central pixel-based typography scales for application-owned UI."""
from __future__ import annotations

from dataclasses import dataclass

from PyQt5.QtGui import QFont


MIN_FONT_PX = 8
MAX_FONT_PX = 20
DEFAULT_FONT_PX = 13
DEFAULT_FONT_FAMILY = "Microsoft YaHei"


@dataclass(frozen=True)
class TypographyScale:
    """Named semantic font roles derived from one validated base size."""

    caption_px: int
    secondary_px: int
    body_px: int
    control_px: int
    section_title_px: int
    page_title_px: int


def validate_font_size(value, default: int = DEFAULT_FONT_PX) -> int:
    """Return a strict 8–20px integer, falling back for invalid input."""
    fallback = (
        default
        if isinstance(default, int)
        and not isinstance(default, bool)
        and MIN_FONT_PX <= default <= MAX_FONT_PX
        else DEFAULT_FONT_PX
    )
    if (
        isinstance(value, bool)
        or not isinstance(value, int)
        or not MIN_FONT_PX <= value <= MAX_FONT_PX
    ):
        return fallback
    return value


def _clamp_role(value: int) -> int:
    return min(MAX_FONT_PX, max(MIN_FONT_PX, value))


def build_scale(base_px: int) -> TypographyScale:
    """Build the semantic role scale, clamping every final role to range."""
    base = validate_font_size(base_px)
    return TypographyScale(
        caption_px=_clamp_role(base - 2),
        secondary_px=_clamp_role(base - 1),
        body_px=base,
        control_px=base,
        section_title_px=_clamp_role(base + 2),
        page_title_px=_clamp_role(base + 4),
    )


def _scale_from_config(config: dict, key: str) -> TypographyScale:
    values = config if isinstance(config, dict) else {}
    return build_scale(validate_font_size(values.get(key)))


def app_scale_from_config(config: dict) -> TypographyScale:
    """Return the application typography scale from complete config."""
    return _scale_from_config(config, "app_font_size_px")


def floating_scale_from_config(config: dict) -> TypographyScale:
    """Return the independent floating-window scale from complete config."""
    return _scale_from_config(config, "floating_font_size_px")


def qfont_for(scale: TypographyScale, role: str) -> QFont:
    """Create a QFont using the named role's pixel size, never point size."""
    if not isinstance(scale, TypographyScale):
        raise TypeError("scale 必须是 TypographyScale")
    if not isinstance(role, str):
        raise TypeError("role 必须是字符串")
    attribute = role if role.endswith("_px") else f"{role}_px"
    if attribute not in TypographyScale.__dataclass_fields__:
        raise ValueError(f"未知字号角色: {role}")
    font = QFont(DEFAULT_FONT_FAMILY)
    font.setPixelSize(getattr(scale, attribute))
    return font


def apply_application_font(app, scale: TypographyScale) -> None:
    """Apply the application's body role to QApplication."""
    if app is None or not hasattr(app, "setFont"):
        raise TypeError("app 必须提供 setFont")
    app.setFont(qfont_for(scale, "body"))
