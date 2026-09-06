"""Generate PDF Toolbox launcher icons (adaptive + legacy + store)."""
from PIL import Image, ImageDraw
import os

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")

TOP = (78, 106, 240)     # #4E6AF0
BOTTOM = (47, 68, 200)   # #2F44C8
FOLD = (200, 210, 255)   # light blue-gray fold
RED = (250, 82, 82)      # #FA5252
BLUE = (66, 99, 235)     # #4263EB
WHITE = (255, 255, 255)


def gradient_bg(size):
    grad = Image.linear_gradient("L").resize((size, size))
    top = Image.new("RGB", (size, size), TOP)
    bottom = Image.new("RGB", (size, size), BOTTOM)
    return Image.composite(bottom, top, grad)


def draw_scene(size, content_scale=1.0):
    """White document sheet with folded corner + content bars, centered."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = size * content_scale
    off = (size - s) / 2

    # page geometry (portrait, cut top-right corner)
    pw, ph = s * 0.54, s * 0.72
    px, py = off + (s - pw) / 2 - s * 0.02, off + (s - ph) / 2
    cut = pw * 0.28
    r = pw * 0.06
    page = [(px, py), (px + pw - cut, py), (px + pw, py + cut), (px + pw, py + ph - r),
            (px + pw - r, py + ph), (px + r, py + ph), (px, py + ph - r)]
    d.polygon(page, fill=WHITE)
    # fold triangle
    d.polygon([(px + pw - cut, py), (px + pw, py + cut), (px + pw - cut, py + cut)], fill=FOLD)

    # content bars: red title + two blue lines
    bar_h = ph * 0.075
    gap = ph * 0.11
    inset = pw * 0.16
    y0 = py + ph * 0.30
    for i, (color, wfrac) in enumerate([(RED, 0.45), (BLUE, 0.68), (BLUE, 0.52)]):
        bw = pw * wfrac
        d.rounded_rectangle([px + inset, y0 + i * gap, px + inset + bw, y0 + i * gap + bar_h],
                            radius=bar_h / 2, fill=color)
    return img


def legacy_icon(size):
    """Full rounded-square icon with content."""
    bg = gradient_bg(size).convert("RGBA")
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.18), fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(bg, (0, 0), mask)
    scene = draw_scene(size, content_scale=0.92)
    out.alpha_composite(scene)
    return out


def foreground_icon(size):
    """Adaptive foreground layer: content inside safe zone (center 66/108)."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    scene = draw_scene(size, content_scale=0.56)
    img.alpha_composite(scene)
    return img


DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

os.makedirs(os.path.join(RES, "mipmap-anydpi-v26"), exist_ok=True)
for name in ("ic_launcher", "ic_launcher_round"):
    with open(os.path.join(RES, "mipmap-anydpi-v26", f"{name}.xml"), "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@color/ic_launcher_background"/>\n'
                '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
                '</adaptive-icon>\n')

with open(os.path.join(RES, "values", "ic_launcher_background.xml"), "w") as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n'
            '<resources>\n    <color name="ic_launcher_background">#4263EB</color>\n</resources>\n')

for dpi, mult in DENSITIES.items():
    folder = os.path.join(RES, f"mipmap-{dpi}")
    os.makedirs(folder, exist_ok=True)
    # adaptive foreground: 108dp layer, MUST keep alpha (transparent outside content)
    fg_size = int(108 * mult)
    foreground_icon(fg_size).save(os.path.join(folder, "ic_launcher_foreground.png"))
    # legacy launcher icons: 48dp, keep alpha for the rounded corners
    ic_size = int(48 * mult)
    legacy_icon(ic_size).save(os.path.join(folder, "ic_launcher.png"))
    legacy_icon(ic_size).save(os.path.join(folder, "ic_launcher_round.png"))
    print(f"{dpi}: fg={fg_size}px ic={ic_size}px")

# store listing icon: 512 full-bleed
store = Image.new("RGB", (512, 512), TOP)
grad = Image.linear_gradient("L").resize((512, 512))
store = Image.composite(Image.new("RGB", (512, 512), BOTTOM), store, grad)
store.paste(legacy_icon(512).convert("RGB"), (0, 0),
            legacy_icon(512).split()[3].point(lambda a: 255 if a > 8 else 0))
store.save(os.path.join(os.path.dirname(__file__), "..", "store-icon-512.png"))
print("store-icon-512.png written")
print("DONE")
