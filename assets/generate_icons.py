"""Regenerate original SVG, PNG-in-ICO and Android vectors without extra packages."""
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from PyQt5.QtCore import QByteArray, QBuffer, QIODevice
from app_icon import BLUE, PURPLE, ICON_SIZES, MARK_PATHS, MARK_SCALE, render_icon


def path_data(commands):
    return " ".join(command + " " + " ".join(map(str, points)) for command, *points in commands)


def vector(monochrome=False):
    paths = []
    inset = 54 * (1 - MARK_SCALE)
    paths.append(f'<group android:scaleX="{MARK_SCALE}" android:scaleY="{MARK_SCALE}" android:translateX="{inset:.1f}" android:translateY="{inset:.1f}">')
    for color, width, commands in MARK_PATHS:
        color = "#FFFFFF" if monochrome else color
        paths.append(f'<path android:fillColor="@android:color/transparent" android:strokeColor="{color}" android:strokeWidth="{width}" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{path_data(commands)}" />')
    paths.append('</group>')
    return '<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n    ' + "\n    ".join(paths) + '\n</vector>\n'


def generate():
    frames = []
    for size in ICON_SIZES:
        data = QByteArray()
        buffer = QBuffer(data)
        buffer.open(QIODevice.WriteOnly)
        if not render_icon(size).save(buffer, "PNG"):
            raise RuntimeError("PNG icon encoding failed")
        frames.append(bytes(data))
    offset = 6 + 16 * len(frames)
    header = struct.pack("<HHH", 0, 1, len(frames))
    for size, frame in zip(ICON_SIZES, frames):
        header += struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0, 1, 32, len(frame), offset)
        offset += len(frame)
    (ROOT / "icon.ico").write_bytes(header + b"".join(frames))
    paths = "\n".join(f'<path d="{path_data(commands)}" stroke="{color}" stroke-width="{width}" />' for color, width, commands in MARK_PATHS)
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">
<title>Clender — original calendar and check mark</title>
<defs><linearGradient id="brand" x1="0" y1="0" x2="1" y2="1"><stop stop-color="{BLUE}"/><stop offset="1" stop-color="{PURPLE}"/></linearGradient></defs>
<rect x="2" y="2" width="104" height="104" rx="25" fill="url(#brand)"/>
<g transform="translate({54 * (1 - MARK_SCALE):.1f} {54 * (1 - MARK_SCALE):.1f}) scale({MARK_SCALE})" fill="none" stroke-linecap="round" stroke-linejoin="round">{paths}</g>
</svg>
'''
    (ROOT / "assets/clender-icon.svg").write_text(svg, encoding="utf-8")
    res = ROOT / "android/app/src/main/res"
    for name, content in (("ic_launcher_foreground.xml", vector()), ("ic_launcher_monochrome.xml", vector(monochrome=True))):
        (res / "drawable" / name).write_text(content, encoding="utf-8")
    (res / "drawable/ic_launcher_background.xml").write_text(f'''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient android:startColor="{BLUE}" android:endColor="{PURPLE}" android:angle="315" />
</shape>
''', encoding="utf-8")
    directory = res / "mipmap-anydpi"
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "ic_launcher.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
''', encoding="utf-8")


if __name__ == "__main__":
    generate()
