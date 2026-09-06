"""Compose captioned marketing screenshots for the Play Store / free stores."""
from PIL import Image, ImageDraw, ImageFont, ImageOps
import os

HERE = os.path.dirname(__file__)
SHOTS = os.path.join(HERE, "..", "store", "shots")
OUT = os.path.join(HERE, "..", "store", "final")
os.makedirs(OUT, exist_ok=True)

W, H = 1080, 1920
TOP = (78, 106, 240)     # #4E6AF0
BOTTOM = (47, 68, 200)   # #2F44C8
CANVAS_BG = (244, 246, 255)
TEXT_DARK = (23, 27, 58)

FONT = r"C:\Windows\Fonts\arialbd.ttf"

SHOTS_META = [
    ("raw_1_home.png",     "7 PDF tools. 100% offline."),
    ("raw_2_merge.png",    "Merge PDFs — in any order"),
    ("raw_3_extract.png",  "See every page. Tap to keep."),
    ("raw_4_images.png",   "Photos to one PDF, instantly"),
    ("raw_5_compress.png", "2.3 MB became 175 KB"),
    ("raw_6_watermark.png","Watermark in any language"),
    ("raw_7_protect.png",  "Lock PDFs with a password"),
    ("raw_8_transfer.png", "PC transfer over WiFi. No cloud."),
]

def gradient(size):
    grad = Image.linear_gradient("L").resize(size)
    top = Image.new("RGB", size, TOP)
    bottom = Image.new("RGB", size, BOTTOM)
    return Image.composite(bottom, top, grad)

def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return m

def fit_text(draw, text, max_width, start_size):
    size = start_size
    while size > 30:
        f = ImageFont.truetype(FONT, size)
        if draw.textlength(text, font=f) <= max_width:
            return f
        size -= 4
    return ImageFont.truetype(FONT, 30)

for filename, caption in SHOTS_META:
    path = os.path.join(SHOTS, filename)
    shot = Image.open(path).convert("RGB")

    canvas = Image.new("RGB", (W, H), CANVAS_BG)
    draw = ImageDraw.Draw(canvas)

    # caption in the top band
    band_h = 320
    band = gradient((W, band_h))
    canvas.paste(band, (0, 0))
    font = fit_text(draw, caption, W - 120, 64)
    tw = draw.textlength(caption, font=font)
    bbox = font.getbbox(caption)
    th = bbox[3] - bbox[1]
    draw.text(((W - tw) / 2, (band_h - th) / 2 - bbox[1]), caption, font=font, fill=(255, 255, 255))

    # screenshot below: height fits remaining space with margins
    shot_h = H - band_h - 80
    shot_w = int(shot.width * shot_h / shot.height)
    shot = shot.resize((shot_w, shot_h), Image.LANCZOS)

    radius = 36
    mask = rounded_mask(shot.size, radius)
    # shadow-ish border
    border_box = [ (W - shot_w) // 2 - 6, band_h + 34, (W + shot_w) // 2 + 6, band_h + 34 + shot_h + 6 ]
    draw.rounded_rectangle(border_box, radius=radius + 6, fill=(210, 216, 240))
    canvas.paste(shot, ((W - shot_w) // 2, band_h + 40), mask)

    out_path = os.path.join(OUT, filename.replace("raw_", "store_"))
    canvas.save(out_path, quality=92)
    print("saved", os.path.basename(out_path))

print("DONE")
