"""Builds the Nuvio RS TV icons (launcher, TV banner, splash mark) from nuvio-rs-logo.png.

Usage: python3 branding/generate_tv_icons.py branding/nuvio-rs-logo.png app/src/main/res preview.png
"""
import os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

src = Image.open(sys.argv[1]).convert("RGB")
res = sys.argv[2]
BG = np.array([25, 30, 37], dtype=float)
CENTER = (652, 615)

px = np.asarray(src).astype(float)
up = np.where(px > BG, (px - BG) / (255 - BG), (BG - px) / BG)
raw = up.max(axis=2)
alpha = np.clip((raw - 0.03) / 0.97, 0, 1)
rgb = np.clip((px - BG) / np.maximum(raw, 1e-6)[..., None] + BG, 0, 255)
logo = Image.fromarray(np.dstack([rgb, alpha * 255]).astype(np.uint8), "RGBA")

def centered(canvas_side):
    canvas = Image.new("RGBA", (canvas_side, canvas_side), (0, 0, 0, 0))
    canvas.paste(logo, (canvas_side // 2 - CENTER[0], canvas_side // 2 - CENTER[1]), logo)
    return canvas

# Launcher icon (square, opaque): original artwork cropped around the logo.
side = 1070
square = src.crop((CENTER[0] - side // 2, CENTER[1] - side // 2, CENTER[0] + side // 2, CENTER[1] + side // 2))
for name, px_size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    square.resize((px_size, px_size), Image.LANCZOS).save(os.path.join(res, f"mipmap-{name}", "ic_launcher.png"), optimize=True)
square.resize((432, 432), Image.LANCZOS).save(os.path.join(res, "drawable", "ic_launcher.png"), optimize=True)

# Android 12+ splash icon on the #0D0D0D splash background (logo inside the 160/240 dp circle).
mark = Image.new("RGBA", (1080, 1080), (13, 13, 13, 255))
m = centered(1560).resize((1080, 1080), Image.LANCZOS)
mark = Image.alpha_composite(mark, m).convert("RGB")
mark.save(os.path.join(res, "drawable", "app_logo_mark.png"), optimize=True)

# TV banner (320x180 dp at xhdpi = 320x180 px), drawn at 4x.
W, H = 1280, 720
banner = Image.new("RGBA", (W, H), (0, 0, 0, 255))
grad = np.zeros((H, W, 3))
yy = np.linspace(0, 1, H)[:, None]
xx = np.linspace(0, 1, W)[None, :]
base = np.array([22, 24, 33]) * (1 - yy[..., None] * 0.25) + np.array([48, 22, 70]) * (yy[..., None] ** 2) * 0.55
glow = np.exp(-(((xx - 0.3) / 0.35) ** 2 + ((yy - 1.05) / 0.45) ** 2))[..., None] * np.array([70, 30, 110])
grad = np.clip(base + glow, 0, 255)
banner = Image.fromarray(grad.astype(np.uint8), "RGB").convert("RGBA")
logo_size = 440
font = ImageFont.truetype(sys.argv[4] if len(sys.argv) > 4 else os.path.join(res, "font", "dm_sans_variable.ttf"), 150)
try:
    font.set_variation_by_axes([14, 700])  # optical size, weight
except Exception:
    pass
draw = ImageDraw.Draw(banner)
text = "Nuvio RS"
bbox = draw.textbbox((0, 0), text, font=font)
gap = -40  # the logo canvas has transparent margins around the mark
group = logo_size + gap + (bbox[2] - bbox[0])
left = (W - group) // 2
lg = centered(1400).resize((logo_size, logo_size), Image.LANCZOS)
banner.alpha_composite(lg, (left, (H - logo_size) // 2))
tx = left + logo_size + gap - bbox[0]
ty = (H - (bbox[3] - bbox[1])) // 2 - bbox[1]
draw.text((tx, ty), text, font=font, fill=(255, 255, 255, 255))
banner = banner.convert("RGB")
banner.resize((320, 180), Image.LANCZOS).save(os.path.join(res, "mipmap-xhdpi", "banner.png"), optimize=True)
banner.resize((1280, 720), Image.LANCZOS).save(os.path.join(res, "drawable", "tv_banner.png"), optimize=True)
banner.resize((1920, 1080), Image.LANCZOS).save(os.path.join(res, "drawable-nodpi", "tv_banner.png"), optimize=True)

if len(sys.argv) > 3:
    prev = Image.new("RGB", (1280 + 20 + 720, 720), (40, 40, 40))
    prev.paste(banner, (0, 0))
    prev.paste(mark.resize((360, 360)), (1300, 0))
    prev.paste(square.resize((360, 360)), (1300, 360))
    prev.save(sys.argv[3])
