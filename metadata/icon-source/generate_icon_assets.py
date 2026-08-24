"""Regenerate OTT launcher icon assets from metadata/icon-source/ott_icon_master.png.

Run from this directory:  python generate_icon_assets.py
Requires pillow + numpy. Overwrites working-tree assets; review with git diff.
"""
import os
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
FORK = os.path.abspath(os.path.join(HERE, "..", ".."))
MASTER = os.path.join(HERE, "ott_icon_master.png")
PINK = (0xED, 0x08, 0x72)  # OTT magenta (orange_main_500)

src = np.asarray(Image.open(MASTER).convert("RGB")).astype(np.float32)
H, W, _ = src.shape
R, G, B = src[..., 0], src[..., 1], src[..., 2]
mn = src.min(axis=2)
chroma = src.max(axis=2) - mn

is_magenta = (R - G >= 25) & (B - G >= 5) & (mn <= 250)
is_gray = (~is_magenta) & (chroma <= 30) & (mn <= 235)

strong_gray = src[is_gray & (mn < 200)]
dom_gray = np.median(strong_gray, axis=0) if len(strong_gray) else np.array([128, 128, 131], float)
print(f"dominant gray (hören text): {dom_gray.round(0)}")



def build_rgba(c_magenta, c_gray):
    c_magenta = np.asarray(c_magenta, float)
    c_gray = np.asarray(c_gray, float)
    fg = np.zeros((H, W, 4), np.float32)
    a_m = np.clip((255.0 - mn) / (255.0 - c_magenta.min()), 0, 1) * is_magenta
    a_g = np.clip((255.0 - mn) / (255.0 - c_gray.min()), 0, 1) * is_gray
    sel = np.where(is_magenta[..., None], np.array(c_magenta, float),
                   np.where(is_gray[..., None], np.array(c_gray, float), 255.0))
    fg[..., :3] = sel
    fg[..., 3] = np.maximum(a_m, a_g) * 255
    return fg


def composite_over_white(rgba):
    a = rgba[..., 3:4] / 255.0
    return rgba[..., :3] * a + 255.0 * (1 - a)


# sanity: unmix model against master using the measured dominant magenta
dom_pink = np.median(src[is_magenta & (mn < 60)], axis=0)
model_diff = np.abs(composite_over_white(build_rgba(dom_pink, dom_gray)) - src)
print(f"unmix model error: mean {model_diff.mean():.2f}, p99 {np.percentile(model_diff, 99):.1f}")

fg = build_rgba(PINK, dom_gray)
fg_im = Image.fromarray(fg.round().astype(np.uint8), "RGBA")


def square_composite(size):
    return Image.fromarray(composite_over_white(fg).round().astype(np.uint8), "RGB").resize((size, size), Image.LANCZOS)


def mask_scale(size, draw_fn):
    big = size * 4
    m = Image.new("L", (big, big), 0)
    draw_fn(ImageDraw.Draw(m), big)
    return m.resize((size, size), Image.LANCZOS)


def legacy_square(size):
    im = square_composite(size).convert("RGBA")
    im.putalpha(mask_scale(size, lambda d, n: d.rounded_rectangle([0, 0, n - 1, n - 1], radius=round(n * 3 / 72), fill=255)))
    return im


def legacy_round(size):
    im = square_composite(size).convert("RGBA")
    im.putalpha(mask_scale(size, lambda d, n: d.ellipse([0, 0, n - 1, n - 1], fill=255)))
    return im


dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
count = 0
for d, scale in dens.items():
    res = f"{FORK}/app/src/main/res/mipmap-{d}"
    fg_im.resize((round(108 * scale),) * 2, Image.LANCZOS).save(f"{res}/linphone_launcher_icon_foreground.png")
    legacy_square(round(48 * scale)).save(f"{res}/ic_launcher.png")
    legacy_round(round(48 * scale)).save(f"{res}/ic_launcher_round.png")
    count += 3
square_composite(512).save(f"{FORK}/metadata/en-US/images/icon.png")
count += 1
print(f"wrote {count} asset files")
