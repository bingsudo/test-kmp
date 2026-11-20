"""Generates the app icon (iOS-26-style "liquid glass") for the desktop and Android apps.

Concept: a stack of video frames - ghosted copies behind one crisp glass frame with a play glyph -
duplicate frames collapsing into one.

Outputs (assets/icon/):
  icon_1024.png       full icon (squircle with background) - desktop / store
  icon.ico            Windows multi-size icon
  glyph_foreground.png  transparent glass glyph on the Android adaptive canvas (432x432)
  background.png      Android adaptive background (432x432)
  monochrome.png      Android themed-icon silhouette (432x432)
Run:  python scripts/make_icon.py
"""
import os

import numpy as np
from PIL import Image, ImageFilter

S = 2048  # work resolution (downsampled to 1024 for smooth edges)
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets", "icon")
os.makedirs(OUT, exist_ok=True)

yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)


def rrect_sd(cx, cy, hw, hh, r, n=2.0):
    """Signed distance (px) to a rounded rectangle; n>2 makes the corners squircle-like."""
    qx = np.abs(xx - cx) - (hw - r)
    qy = np.abs(yy - cy) - (hh - r)
    ax, ay = np.maximum(qx, 0), np.maximum(qy, 0)
    outside = (ax ** n + ay ** n) ** (1.0 / n)
    inside = np.minimum(np.maximum(qx, qy), 0)
    return outside + inside - r


def coverage(sd, aa=2.0):
    return np.clip(0.5 - sd / aa, 0, 1)


def smooth(x, a, b):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def blur(a, radius):
    img = Image.fromarray((np.clip(a, 0, 1) * 255).astype(np.uint8))
    return np.asarray(img.filter(ImageFilter.GaussianBlur(radius)), dtype=np.float32) / 255.0


def over(dst, rgb, alpha):
    """dst: HxWx4 premultiplied-free float RGBA; composite a colour layer with alpha (HxW)."""
    a = alpha[..., None]
    da = dst[..., 3:4]
    out_a = a + da * (1 - a)
    out_rgb = (np.asarray(rgb, np.float32) * a + dst[..., :3] * da * (1 - a)) / np.maximum(out_a, 1e-6)
    dst[..., :3] = out_rgb
    dst[..., 3:4] = out_a


def gradient(c0, c1, angle_deg):
    """Linear gradient between colours c0 -> c1 across the canvas."""
    t = np.deg2rad(angle_deg)
    d = (xx - S / 2) * np.cos(t) + (yy - S / 2) * np.sin(t)
    k = (d / (S * 0.75) + 0.5).clip(0, 1)[..., None]
    return np.asarray(c0, np.float32) * (1 - k) + np.asarray(c1, np.float32) * k


def glass_slab(canvas, sd, base_rgb, body_alpha, light=(-0.55, -0.83), rim=1.0, shadow=0.35, shadow_off=26, shadow_blur=34,
               interior=None):
    """One piece of liquid glass: drop shadow, translucent body, refraction-like inner glow, specular rim."""
    inside = coverage(sd)
    # soft drop shadow
    sh = blur(coverage(np.roll(np.roll(sd, shadow_off, 0), 0, 1)), shadow_blur) * shadow
    over(canvas, [0.0, 0.05, 0.25], sh * (1 - inside))
    # body
    body = inside * body_alpha
    rgb = interior if interior is not None else base_rgb
    over(canvas, rgb, body)
    # surface normal from the signed distance
    gy, gx = np.gradient(sd)
    norm = np.sqrt(gx * gx + gy * gy) + 1e-6
    nx, ny = gx / norm, gy / norm
    lx, ly = light
    facing = nx * lx + ny * ly                      # +1 facing the light
    # rim band just inside the edge
    band = smooth(-sd, 0, 5) * (1 - smooth(-sd, 5, 46))
    spec = np.clip(facing, 0, 1) ** 1.6
    back = np.clip(-facing, 0, 1) ** 1.6
    over(canvas, [1, 1, 1], inside * band * (0.10 + 0.9 * spec) * rim)           # bright light-side edge
    over(canvas, [0.75, 0.9, 1.0], inside * band * back * 0.35 * rim)            # faint refracted edge opposite
    # inner glow along the lit side (thicker, softer)
    glow = smooth(-sd, 0, 90) * (1 - smooth(-sd, 90, 220)) * np.clip(facing, 0, 1)
    over(canvas, [1, 1, 1], inside * glow * 0.22)
    # top sheen: soft white gradient over the upper part of the slab
    return inside


def build(include_background):
    canvas = np.zeros((S, S, 4), np.float32)
    if include_background:
        sd = rrect_sd(S / 2, S / 2, S * 0.5, S * 0.5, S * 0.225, n=4.2)   # squircle
        a = coverage(sd)
        bg = gradient([0.07, 0.62, 1.0], [0.0, 0.18, 0.78], 62)            # bright blue -> deep blue
        over(canvas, bg, a)
        # soft light bloom top-left, cool glow bottom-right
        bloom = np.exp(-(((xx - S * 0.25) ** 2 + (yy - S * 0.12) ** 2) / (2 * (S * 0.38) ** 2)))
        over(canvas, [0.75, 0.93, 1.0], a * bloom * 0.55)
        glow2 = np.exp(-(((xx - S * 0.85) ** 2 + (yy - S * 0.95) ** 2) / (2 * (S * 0.3) ** 2)))
        over(canvas, [0.0, 0.85, 1.0], a * glow2 * 0.35)
        # icon-edge specular
        gy, gx = np.gradient(sd)
        norm = np.sqrt(gx * gx + gy * gy) + 1e-6
        facing = (gx * -0.55 + gy * -0.83) / norm
        band = smooth(-sd, 0, 4) * (1 - smooth(-sd, 4, 22))
        over(canvas, [1, 1, 1], a * band * (0.18 + 0.6 * np.clip(facing, 0, 1) ** 2))

    cx, cy = S / 2, S / 2 + S * 0.012
    scale = 1.0 if include_background else 0.78
    # ghost frames (the duplicates) - smaller, offset up/left, increasingly transparent
    for k, (dx, dy, f, alpha) in enumerate([(-0.115, -0.115, 0.82, 0.16), (-0.058, -0.058, 0.91, 0.26)]):
        hw, hh = S * 0.265 * f * scale, S * 0.205 * f * scale
        sd = rrect_sd(cx + dx * S * scale, cy + dy * S * scale, hw, hh, S * 0.07 * f * scale)
        glass_slab(canvas, sd, [0.85, 0.94, 1.0], alpha, rim=0.75, shadow=0.12, shadow_off=14, shadow_blur=22)
    # the crisp front frame
    hw, hh = S * 0.265 * scale, S * 0.205 * scale
    sd = rrect_sd(cx + S * 0.03 * scale, cy + S * 0.035 * scale, hw, hh, S * 0.07 * scale)
    fill = gradient([1.0, 1.0, 1.0], [0.62, 0.82, 1.0], 75)
    glass_slab(canvas, sd, [1, 1, 1], 0.34, rim=1.0, shadow=0.38, shadow_off=36, shadow_blur=40, interior=fill)

    # play glyph: rounded triangle, brighter glass
    pcx, pcy = cx + S * 0.045 * scale, cy + S * 0.035 * scale
    R = S * 0.115 * scale
    pts = np.array([[pcx - R * 0.62, pcy - R * 0.95], [pcx - R * 0.62, pcy + R * 0.95], [pcx + R * 1.0, pcy]])
    sd_t = tri_sd(pts) - S * 0.016 * scale
    t_fill = gradient([1.0, 1.0, 1.0], [0.80, 0.91, 1.0], 80)
    glass_slab(canvas, sd_t, [1, 1, 1], 0.93, rim=0.8, shadow=0.30, shadow_off=14, shadow_blur=16, interior=t_fill)
    return canvas


def tri_sd(p):
    """Signed distance to a triangle with vertices p (3x2)."""
    def seg(a, b):
        ab, ap = b - a, np.stack([xx, yy], -1) - a
        t = np.clip((ap @ ab) / (ab @ ab), 0, 1)[..., None]
        return np.linalg.norm(ap - t * ab, axis=-1)

    d = np.minimum(np.minimum(seg(p[0], p[1]), seg(p[1], p[2])), seg(p[2], p[0]))

    def side(a, b):
        return (b[0] - a[0]) * (yy - a[1]) - (b[1] - a[1]) * (xx - a[0])

    s0, s1, s2 = side(p[0], p[1]), side(p[1], p[2]), side(p[2], p[0])
    inside = ((s0 >= 0) & (s1 >= 0) & (s2 >= 0)) | ((s0 <= 0) & (s1 <= 0) & (s2 <= 0))
    return np.where(inside, -d, d)


def to_image(canvas, size):
    rgba = np.clip(canvas, 0, 1)
    img = Image.fromarray((rgba * 255).astype(np.uint8), "RGBA")
    return img.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    full = build(True)
    icon = to_image(full, 1024)
    icon.save(os.path.join(OUT, "icon_1024.png"))
    icon.save(os.path.join(OUT, "icon.ico"), sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    # Android adaptive layers (108dp canvas; the launcher masks to ~72dp, keep the glyph inside 66dp)
    glyph = to_image(build(False), 432)
    glyph.save(os.path.join(OUT, "glyph_foreground.png"))
    bgc = np.zeros((S, S, 4), np.float32)
    over(bgc, gradient([0.07, 0.62, 1.0], [0.0, 0.18, 0.78], 62), np.ones((S, S), np.float32))
    bloom = np.exp(-(((xx - S * 0.25) ** 2 + (yy - S * 0.12) ** 2) / (2 * (S * 0.38) ** 2)))
    over(bgc, [0.75, 0.93, 1.0], bloom * 0.5)
    glow2 = np.exp(-(((xx - S * 0.85) ** 2 + (yy - S * 0.95) ** 2) / (2 * (S * 0.3) ** 2)))
    over(bgc, [0.0, 0.85, 1.0], glow2 * 0.35)
    to_image(bgc, 432).convert("RGB").save(os.path.join(OUT, "background.png"))
    mono = np.asarray(glyph, np.float32) / 255.0
    sil = np.zeros_like(mono)
    sil[..., :3] = 1.0
    sil[..., 3] = np.clip(mono[..., 3] * 1.0, 0, 1)
    Image.fromarray((sil * 255).astype(np.uint8), "RGBA").save(os.path.join(OUT, "monochrome.png"))
    print("icons written to", os.path.abspath(OUT))
