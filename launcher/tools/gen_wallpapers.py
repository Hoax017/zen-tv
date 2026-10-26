"""Génère 10 paysages nature, chacun en 4 moments de la journée (aube, jour, crépuscule, nuit).

Sortie : <out>/<theme>_<phase>.jpg en 1920x1080. Le launcher choisit la phase selon l'heure réelle.
"""
import math
import random
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

W, H = 1920, 1080
PHASES = ["night", "bluedawn", "sunrise", "morning", "noon", "afternoon", "sunset", "bluedusk"]

# Ciel, astre (x, hauteur relative à l'horizon, rayon, couleur, intensité ; None = sous l'horizon),
# teinte de la lumière et couleur de l'air (brume lointaine) pour chaque moment.
SKY = {
    "night": dict(top=(6, 10, 28), horizon=(26, 38, 70), sun=(1480, 0.20, 75, (235, 240, 255), 0.5),
                  tint=(0.32, 0.40, 0.62), light=0.35, atmo=(30, 42, 74), stars=900),
    "bluedawn": dict(top=(22, 34, 78), horizon=(118, 118, 168), sun=None,
                     tint=(0.5, 0.56, 0.8), light=0.5, atmo=(88, 94, 140), stars=250),
    "sunrise": dict(top=(48, 66, 124), horizon=(255, 172, 124), sun=(480, 0.98, 70, (255, 205, 155), 0.9),
                    tint=(1.0, 0.76, 0.64), light=0.8, atmo=(230, 166, 150), stars=0),
    "morning": dict(top=(76, 138, 210), horizon=(232, 216, 200), sun=(650, 0.55, 55, (255, 242, 214), 0.85),
                    tint=(1.0, 0.94, 0.86), light=0.95, atmo=(200, 206, 216), stars=0),
    "noon": dict(top=(58, 122, 204), horizon=(196, 222, 240), sun=(1100, 0.18, 55, (255, 250, 230), 0.9),
                 tint=(1.0, 1.0, 1.0), light=1.0, atmo=(178, 204, 228), stars=0),
    "afternoon": dict(top=(66, 124, 198), horizon=(246, 214, 160), sun=(1350, 0.55, 60, (255, 226, 170), 0.9),
                      tint=(1.0, 0.9, 0.74), light=0.95, atmo=(216, 200, 178), stars=0),
    "sunset": dict(top=(44, 34, 92), horizon=(252, 128, 82), sun=(1380, 0.96, 85, (255, 150, 90), 0.9),
                   tint=(0.95, 0.62, 0.58), light=0.62, atmo=(196, 108, 112), stars=60),
    "bluedusk": dict(top=(28, 28, 78), horizon=(168, 98, 128), sun=None,
                     tint=(0.55, 0.5, 0.72), light=0.45, atmo=(98, 80, 120), stars=180),
}


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def lit(color, sky):
    return tuple(int(min(255, c * t * sky["light"])) for c, t in zip(color, sky["tint"]))


def layer_color(base, sky, depth):
    """depth 0 = au loin (fondu dans l'air), 1 = premier plan."""
    return lerp(sky["atmo"], lit(base, sky), 0.25 + 0.75 * depth ** 1.1)


def gradient(top, bottom, horizon_y):
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)
    for y in range(H):
        d.line([(0, y), (W, y)], fill=lerp(top, bottom, min(1.0, y / horizon_y) ** 1.3))
    return img


def glow(img, cx, cy, r, color, strength):
    layer = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(layer)
    for k in range(60, 0, -1):
        rr = r * k / 12
        f = strength * (1 - k / 61) ** 3
        d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=tuple(int(c * f) for c in color))
    layer = layer.filter(ImageFilter.GaussianBlur(r / 3))
    out = ImageChops.add(img, layer)
    ImageDraw.Draw(out).ellipse([cx - r / 3, cy - r / 3, cx + r / 3, cy + r / 3], fill=color)
    return out


def stars(img, rng, n, max_y):
    d = ImageDraw.Draw(img)
    for _ in range(n):
        x, y = rng.randint(0, W), rng.randint(0, max_y)
        b = rng.randint(110, 255)
        r = rng.choice([1, 1, 1, 2])
        d.ellipse([x, y, x + r, y + r], fill=(b, b, min(255, b + 15)))
    return img


def clouds(img, rng, sky, count, y_range):
    layer = Image.new("RGB", (W, H))
    mask = Image.new("L", (W, H))
    md = ImageDraw.Draw(mask)
    col = lerp(sky["horizon"], (255, 255, 255), 0.55) if sky["light"] > 0.5 else lerp(sky["top"], sky["horizon"], 0.6)
    ImageDraw.Draw(layer).rectangle([0, 0, W, H], fill=col)
    for _ in range(count):
        cx, cy = rng.randint(-100, W + 100), rng.randint(*y_range)
        for _ in range(rng.randint(4, 8)):
            w, h = rng.randint(80, 260), rng.randint(25, 60)
            ox, oy = rng.randint(-180, 180), rng.randint(-20, 20)
            md.ellipse([cx + ox - w, cy + oy - h, cx + ox + w, cy + oy + h], fill=rng.randint(70, 140))
    img.paste(layer, (0, 0), mask.filter(ImageFilter.GaussianBlur(28)))
    return img


def ridge(rng, base, rough, amp, n=2 ** 9):
    pts = [0.0] * (n + 1)
    pts[0], pts[n] = rng.uniform(-1, 1), rng.uniform(-1, 1)
    step, scale = n, 1.0
    while step > 1:
        half = step // 2
        for i in range(half, n, step):
            pts[i] = (pts[i - half] + pts[i + half]) / 2 + rng.uniform(-1, 1) * scale
        step, scale = half, scale * rough
    return [(W * i / n, base - amp * p) for i, p in enumerate(pts)]


def smooth(rng, base, amp, waves=3):
    ph = [rng.uniform(0, 6.3) for _ in range(waves)]
    fr = [rng.uniform(0.6, 1.4) * (k + 1) for k in range(waves)]
    return [(x, base - amp * sum(math.sin(x / W * math.pi * f + p) / (k + 1)
                                  for k, (f, p) in enumerate(zip(fr, ph))))
            for x in range(0, W + 8, 8)]


def fill(d, pts, col):
    d.polygon(pts + [(W, H), (0, H)], fill=col)


def snow(d, pts, snowline, col):
    bottom = [(x, y + max(0.0, snowline - y) * 0.45) for x, y in pts]
    d.polygon(pts + bottom[::-1], fill=col)


def pines(d, rng, pts, col, density, hmin, hmax):
    for x, y in pts[::density]:
        x += rng.uniform(-6, 6)
        h = rng.uniform(hmin, hmax)
        w = h * 0.28
        d.polygon([(x, y - h), (x - w, y + 4), (x + w, y + 4)], fill=col)


def cypress(d, rng, pts, col, count):
    for _ in range(count):
        x, y = pts[rng.randrange(len(pts))]
        h = rng.uniform(60, 140)
        d.ellipse([x - h * 0.11, y - h, x + h * 0.11, y + 6], fill=col)


def palm(d, x, base_y, h, col, lean):
    """Tronc courbe plein qui s'affine, palmes en feuilles qui retombent."""
    spine = [(x + lean * (k / 30) ** 1.6, base_y - h * k / 30) for k in range(31)]
    left = [(px - (13 - 7 * k / 30), py) for k, (px, py) in enumerate(spine)]
    right = [(px + (13 - 7 * k / 30), py) for k, (px, py) in enumerate(spine)]
    d.polygon(left + right[::-1], fill=col)
    top = spine[-1]
    for ang in [-170, -140, -110, -80, -50, -20, 10, 195]:
        r = math.radians(ang)
        length = 190 if -150 < ang < -30 else 230
        mid = [(top[0] + math.cos(r) * length * k, top[1] + math.sin(r) * 70 * k + 120 * k * k)
               for k in [i / 16 for i in range(17)]]
        upper, lower = [], []
        for i, (mx, my) in enumerate(mid):
            w = 16 * math.sin(math.pi * i / 16) + 1
            upper.append((mx, my - w))
            lower.append((mx, my + w * 0.6))
        d.polygon(upper + lower[::-1], fill=col)
    d.ellipse([top[0] - 16, top[1] - 12, top[0] + 16, top[1] + 14], fill=col)


def mesas(rng, base, amp):
    pts, x = [], 0
    while x < W:
        w = rng.randint(120, 420)
        if rng.random() < 0.55:
            top = base - amp * rng.uniform(0.5, 1.0)
            pts += [(x, base), (x + 25, top + 10), (x + 40, top), (x + w - 40, top), (x + w - 25, top + 10)]
        else:
            pts += [(x, base - rng.uniform(0, 25))]
        x += w
    pts.append((W, base))
    return pts


def karst(rng, base, amp, count):
    ys = [base] * (W // 4 + 1)
    for _ in range(count):
        cx, h, s = rng.uniform(0, W), amp * rng.uniform(0.45, 1.0), rng.uniform(35, 120)
        for i in range(len(ys)):
            x = i * 4
            ys[i] = min(ys[i], base - h * math.exp(-abs((x - cx) / s) ** 2.4))
    return [(i * 4, y) for i, y in enumerate(ys)]


def water(img, water_y, sky, darken=0.55):
    top = img.crop((0, max(0, 2 * water_y - H), W, water_y))
    refl = top.transpose(Image.FLIP_TOP_BOTTOM).resize((W, H - water_y)).filter(ImageFilter.GaussianBlur(4))
    refl = Image.blend(refl, Image.new("RGB", refl.size, lerp(sky["top"], (0, 0, 0), 0.4)), darken)
    d = ImageDraw.Draw(refl)
    rng = random.Random(water_y)
    hl = lerp(sky["horizon"], (255, 255, 255), 0.2)
    for _ in range(300):
        y = int(rng.random() ** 2 * refl.height)
        x = rng.randint(0, W)
        d.line([(x, y), (x + rng.randint(20, 120 + y), y)], fill=lerp(hl, (0, 0, 0), 0.3 + 0.4 * (y / refl.height)))
    img.paste(refl, (0, water_y))
    return img


def fog(img, sky, y, height, strength):
    mask = Image.new("L", (W, H))
    md = ImageDraw.Draw(mask)
    for yy in range(int(y - height), int(y + height)):
        a = strength * (1 - abs(yy - y) / height)
        md.line([(0, yy), (W, yy)], fill=int(255 * max(0, a)))
    img.paste(Image.new("RGB", (W, H), sky["atmo"]), (0, 0), mask.filter(ImageFilter.GaussianBlur(25)))
    return img


def aurora(img, rng, strength):
    layer = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(layer)
    for band, col in enumerate([(40, 230, 150), (60, 190, 210), (160, 90, 230)]):
        y0, ph = 180 + band * 80, rng.uniform(0, 6)
        for x in range(0, W, 3):
            y = y0 + 80 * math.sin(x / 260 + ph) + 30 * math.sin(x / 90 + ph * 2)
            hgt = 170 + 60 * math.sin(x / 140 + ph)
            for k in range(0, int(hgt), 4):
                f = (1 - k / hgt) ** 2 * 0.55 * strength
                d.line([(x, y - k), (x + 3, y - k)], fill=tuple(int(c * f) for c in col))
    return ImageChops.add(img, layer.filter(ImageFilter.GaussianBlur(18)))


def grain(img):
    noise = Image.effect_noise((W, H), 24).convert("RGB").point(lambda v: max(0, v - 128))
    return Image.blend(img, ImageChops.add(img, noise), 0.22)


# ---------- Thèmes ----------

def sky_base(phase, rng, horizon_y, cloud_count=4, sun_x=None):
    s = SKY[phase]
    rng = random.Random(rng.random())  # le ciel a sa propre graine : le relief reste identique d'une phase à l'autre
    img = gradient(s["top"], s["horizon"], horizon_y)
    if s["stars"]:
        img = stars(img, rng, s["stars"], int(horizon_y * 0.9))
    if s["sun"]:
        sx, sy_rel, r, col, strength = s["sun"]
        if sun_x is not None and phase in ("night", "noon"):
            sx = sun_x  # lune / soleil haut déplacés quand ils tomberaient sur le bâtiment
        img = glow(img, sx, horizon_y * sy_rel, r, col, strength)
    if cloud_count and phase != "night":
        img = clouds(img, rng, s, cloud_count, (80, int(horizon_y * 0.6)))
    return img, s


def t_lac_alpin(phase, rng):
    img, s = sky_base(phase, rng, 620)
    d = ImageDraw.Draw(img)
    for i, (base, amp) in enumerate([(430, 210), (500, 150)]):
        pts = ridge(rng, base, 0.55, amp)
        fill(d, pts, layer_color((120, 130, 150), s, i * 0.3))
        snow(d, pts, base - amp * 0.35, layer_color((250, 250, 255), s, 0.35 + i * 0.2))
    pts = ridge(rng, 600, 0.7, 50)
    fill(d, pts, layer_color((40, 70, 50), s, 0.8))
    pines(d, rng, pts, layer_color((30, 55, 40), s, 0.85), 3, 20, 45)
    return water(img, 640, s)


def t_dunes(phase, rng):
    img, s = sky_base(phase, rng, 560, cloud_count=2)
    d = ImageDraw.Draw(img)
    for i in range(5):
        base = 560 + i * 110
        fill(d, smooth(rng, base, 50 - i * 5), layer_color((236, 190, 130), s, i / 4))
    return img


def t_foret_brume(phase, rng):
    img, s = sky_base(phase, rng, 700, cloud_count=0)
    d = ImageDraw.Draw(img)
    for i in range(6):
        base = 420 + i * 110
        pts = ridge(rng, base, 0.6, 70)
        col = layer_color((40, 80, 55), s, i / 5)
        fill(d, pts, col)
        pines(d, rng, pts, col, 2, 25 + i * 8, 60 + i * 14)
        if i < 5:
            img = fog(img, s, base + 70, 90, 0.55)
            d = ImageDraw.Draw(img)
    return img


def t_fjord_aurore(phase, rng):
    img, s = sky_base(phase, rng, 700, cloud_count=2)
    if phase in ("night", "bluedusk", "bluedawn"):
        img = aurora(img, random.Random(7), 1.0 if phase == "night" else 0.4)
    d = ImageDraw.Draw(img)
    for i, (base, amp) in enumerate([(560, 230), (640, 170), (720, 120)]):
        pts = ridge(rng, base, 0.55, amp)
        fill(d, pts, layer_color((80, 95, 115), s, i / 2))
        snow(d, pts, base - amp * 0.3, layer_color((245, 248, 255), s, 0.3 + i * 0.25))
    return water(img, 780, s)


def t_toscane(phase, rng):
    img, s = sky_base(phase, rng, 560)
    d = ImageDraw.Draw(img)
    palette = [(150, 170, 110), (190, 180, 90), (120, 160, 80), (200, 170, 80), (100, 140, 70)]
    for i, col in enumerate(palette):
        pts = smooth(rng, 560 + i * 105, 45)
        fill(d, pts, layer_color(col, s, i / 4))
        if i in (1, 3):
            cypress(d, rng, pts, layer_color((30, 60, 35), s, i / 4 + 0.1), 6)
    return img


def t_plage_tropicale(phase, rng):
    img, s = sky_base(phase, rng, 620, cloud_count=5)
    img = water(img, 620, s, darken=0.35)
    d = ImageDraw.Draw(img)
    sand = smooth(rng, 900, 25)
    fill(d, sand, layer_color((240, 220, 180), s, 0.8))
    dark = layer_color((25, 35, 25), s, 1.0)
    palm(d, 230, 930, 470, dark, 140)
    palm(d, 400, 950, 380, dark, 90)
    palm(d, 1720, 940, 420, dark, -120)
    return img


LAMP = (255, 196, 110)


def lights_on(phase):
    return phase in ("sunset", "bluedusk", "night", "bluedawn")


def lamp(img, phase, x, y, r=10, strength=0.8):
    """Petite lumière chaude : visible seulement le soir et la nuit."""
    if not lights_on(phase):
        return img
    return glow(img, x, y, r, LAMP, strength if phase == "night" else strength * 0.6)


def pagoda(d, x, base_y, width, tiers, wall, roof, window):
    y = base_y
    w = width
    for t in range(tiers):
        h = 70 - t * 6
        d.rectangle([x - w * 0.36, y - h, x + w * 0.36, y], fill=wall)
        for k in (-1, 0, 1):
            wx = x + k * w * 0.18
            d.rectangle([wx - w * 0.04, y - h * 0.7, wx + w * 0.04, y - h * 0.25], fill=window)
        ry = y - h
        d.polygon([(x - w * 0.62, ry - 4), (x - w * 0.5, ry + 8), (x + w * 0.5, ry + 8), (x + w * 0.62, ry - 4),
                   (x + w * 0.3, ry - 26), (x - w * 0.3, ry - 26)], fill=roof)
        y = ry - 22
        w *= 0.8
    d.rectangle([x - 3, y - 70, x + 3, y], fill=roof)
    for k in range(4):
        d.ellipse([x - 9, y - 18 - k * 14, x + 9, y - 12 - k * 14], fill=roof)
    return y


def torii(d, x, base_y, h, col, dark):
    w = h * 0.9
    for sx in (-1, 1):
        px = x + sx * w * 0.36
        d.polygon([(px - 9, base_y), (px + 9, base_y), (px + 7, base_y - h), (px - 7, base_y - h)], fill=col)
    d.rectangle([x - w * 0.48, base_y - h * 0.78, x + w * 0.48, base_y - h * 0.72], fill=col)
    d.polygon([(x - w * 0.62, base_y - h - 18), (x - w * 0.5, base_y - h - 2), (x + w * 0.5, base_y - h - 2),
               (x + w * 0.62, base_y - h - 18), (x, base_y - h - 10)], fill=col)
    d.polygon([(x - w * 0.66, base_y - h - 24), (x + w * 0.66, base_y - h - 24),
               (x + w * 0.62, base_y - h - 16), (x - w * 0.62, base_y - h - 16)], fill=dark)


def t_temple(phase, rng):
    img, s = sky_base(phase, rng, 660, cloud_count=2)
    d = ImageDraw.Draw(img)
    for i, (base, amp) in enumerate([(470, 200), (560, 140)]):
        fill(d, ridge(rng, base, 0.55, amp), layer_color((95, 110, 125), s, i * 0.35))
    img = fog(img, s, 600, 60, 0.45)
    d = ImageDraw.Draw(img)
    # Rive boisée et pagode à droite.
    shore = [(x, y + (0 if x > 1150 else 400)) for x, y in ridge(rng, 650, 0.6, 25)]
    fill(d, shore, layer_color((40, 65, 45), s, 0.8))
    pines(d, rng, [(x, y) for x, y in shore if x > 1180], layer_color((30, 52, 38), s, 0.85), 3, 30, 80)
    window = LAMP if lights_on(phase) else layer_color((70, 40, 30), s, 0.9)
    top = pagoda(d, 1500, 650, 230, 4, layer_color((150, 60, 45), s, 0.85), layer_color((45, 40, 45), s, 0.95), window)
    # Torii posé au bord de l'eau, à gauche.
    torii(d, 520, 668, 210, layer_color((200, 55, 40), s, 0.9), layer_color((40, 30, 30), s, 0.95))
    img = water(img, 668, s)
    for lx in (1380, 1620, 1500):
        img = lamp(img, phase, lx, 640, 9, 0.9)
    for k in range(4):
        img = lamp(img, phase, 1500, 600 - k * 66, 26, 0.35)
    return img


def leaf(d, x, y, length, angle, col):
    a = math.radians(angle)
    pts_u, pts_l = [], []
    for i in range(13):
        k = i / 12
        cx, cy = x + math.cos(a) * length * k, y + math.sin(a) * length * k + 40 * k * k
        w = length * 0.22 * math.sin(math.pi * k)
        nx, ny = -math.sin(a), math.cos(a)
        pts_u.append((cx + nx * w, cy + ny * w))
        pts_l.append((cx - nx * w, cy - ny * w))
    d.polygon(pts_u + pts_l[::-1], fill=col)


def canopy(d, rng, base, col, size):
    x = -60
    while x < W + 60:
        r = rng.uniform(size * 0.6, size)
        y = base + rng.uniform(-size * 0.4, size * 0.3)
        d.ellipse([x - r, y - r, x + r, y + r], fill=col)
        d.rectangle([x - r * 0.15, y, x + r * 0.15, H], fill=col)
        x += r * rng.uniform(0.9, 1.4)
    d.rectangle([0, base + size * 0.3, W, H], fill=col)


def t_jungle_cabane(phase, rng):
    img, s = sky_base(phase, rng, 560, cloud_count=3)
    d = ImageDraw.Draw(img)
    fill(d, smooth(rng, 470, 60), layer_color((60, 110, 75), s, 0.15))
    img = fog(img, s, 500, 70, 0.5)
    d = ImageDraw.Draw(img)
    canopy(d, rng, 520, layer_color((45, 100, 60), s, 0.4), 80)
    img = fog(img, s, 600, 60, 0.35)
    d = ImageDraw.Draw(img)
    canopy(d, rng, 620, layer_color((30, 80, 45), s, 0.7), 110)
    # Cabane sur pilotis au-dessus de la rivière.
    wood = layer_color((120, 80, 50), s, 0.9)
    thatch = layer_color((170, 130, 70), s, 0.9)
    water_y = 820
    for sx in (840, 900, 1020, 1140, 1200):
        d.rectangle([sx - 6, 700, sx + 6, water_y], fill=layer_color((80, 55, 35), s, 0.95))
    d.rectangle([800, 690, 1240, 708], fill=wood)
    d.rectangle([850, 580, 1190, 692], fill=wood)
    for k in range(9):
        d.line([(850, 592 + k * 12), (1190, 592 + k * 12)], fill=layer_color((100, 65, 40), s, 0.95), width=2)
    window = LAMP if lights_on(phase) else layer_color((40, 28, 20), s, 1.0)
    d.rectangle([900, 610, 960, 660], fill=window)
    d.rectangle([1070, 610, 1130, 692], fill=window)
    d.polygon([(780, 600), (1020, 470), (1260, 600), (1240, 612), (800, 612)], fill=thatch)
    for k in range(14):
        x0 = 800 + k * 32
        d.line([(x0, 612), (x0 + 6, 622)], fill=thatch, width=4)
    d.line([(1240, 700), (1300, water_y)], fill=wood, width=6)
    d.line([(1270, 700), (1330, water_y)], fill=wood, width=6)
    for k in range(5):
        y = 715 + k * 22
        d.line([(1246 + k * 10, y), (1276 + k * 10, y)], fill=wood, width=4)
    img = water(img, water_y, s, darken=0.5)
    img = lamp(img, phase, 930, 635, 40, 0.5)
    img = lamp(img, phase, 1100, 650, 40, 0.5)
    img = lamp(img, phase, 1020, 560, 12, 0.9)
    d = ImageDraw.Draw(img)
    # Premier plan : grandes feuilles, palmiers et lianes.
    dark = layer_color((15, 45, 25), s, 1.0)
    palm(d, 180, 1000, 560, dark, 120)
    palm(d, 1800, 1010, 500, dark, -110)
    for x, y, ang in [(0, 1080, -40), (40, 1080, -70), (-20, 980, -15), (1920, 1080, -140), (1880, 1080, -110),
                      (1940, 990, -165), (300, 1080, -60), (1650, 1080, -120)]:
        leaf(d, x, y, rng.uniform(220, 320), ang, dark)
    for x in (90, 260, 1580, 1760, 1860):
        length = rng.uniform(150, 380)
        d.line([(x, 0), (x + 10, length)], fill=dark, width=3)
        for k in range(0, int(length), 34):
            leaf(d, x + 5, k, 30, 30 if k % 68 else 150, dark)
    if phase == "night":
        for _ in range(26):
            img = glow(img, rng.uniform(300, 1700), rng.uniform(560, 860), 6, (210, 255, 120), 0.7)
    return img


def overlay(img, mask, color, blur=0):
    """Pose une couleur à travers un masque (L), pour la brume, les faisceaux, les cascades."""
    if blur:
        mask = mask.filter(ImageFilter.GaussianBlur(blur))
    img.paste(Image.new("RGB", (W, H), color), (0, 0), mask)
    return img


def window_color(phase, s):
    return LAMP if lights_on(phase) else layer_color((40, 35, 40), s, 1.0)


def tower(d, x, base_y, w, h, wall, roof, win, roof_h=None):
    d.rectangle([x - w / 2, base_y - h, x + w / 2, base_y], fill=wall)
    rh = roof_h or w * 1.6
    d.polygon([(x - w / 2 - 6, base_y - h), (x + w / 2 + 6, base_y - h), (x, base_y - h - rh)], fill=roof)
    for k in range(int(h // 46)):
        wy = base_y - h + 20 + k * 46
        d.rectangle([x - 4, wy, x + 4, wy + 16], fill=win)


def t_chateau(phase, rng):
    img, s = sky_base(phase, rng, 720, cloud_count=3, sun_x=520)
    d = ImageDraw.Draw(img)
    for i, (base, amp) in enumerate([(560, 220), (660, 160)]):
        pts = ridge(rng, base, 0.5, amp)
        fill(d, pts, layer_color((100, 110, 135), s, i * 0.3))
        snow(d, pts, base - amp * 0.25, layer_color((240, 244, 255), s, 0.3 + i * 0.2))
    # Mer de nuages sous le château.
    mask = Image.new("L", (W, H))
    md = ImageDraw.Draw(mask)
    for _ in range(40):
        cx, cy, r = rng.uniform(0, W), rng.uniform(760, 900), rng.uniform(80, 200)
        md.ellipse([cx - r * 2, cy - r * 0.6, cx + r * 2, cy + r * 0.6], fill=185)
    md.rectangle([0, 880, W, H], fill=215)
    cloud_col = lerp(s["atmo"], (255, 255, 255), 0.35 if s["light"] > 0.5 else 0.05)
    # Piton rocheux.
    rock = layer_color((85, 80, 90), s, 0.85)
    spire = [(1180, H), (1230, 760), (1250, 600), (1290, 520), (1330, 470), (1560, 470), (1590, 520),
             (1610, 640), (1650, 800), (1700, H)]
    d.polygon(spire, fill=rock)
    for k in range(10):
        y = 520 + k * 50
        d.line([(1240 + k * 3, y), (1270 + k * 6, y + 30)], fill=layer_color((65, 60, 70), s, 0.9), width=3)
    wall = layer_color((175, 168, 160), s, 0.85)
    roof = layer_color((60, 70, 110), s, 0.9)
    win = window_color(phase, s)
    d.rectangle([1300, 400, 1590, 475], fill=wall)
    for k in range(15):
        d.rectangle([1300 + k * 20, 388, 1310 + k * 20, 400], fill=wall)
    tower(d, 1320, 470, 46, 190, wall, roof, win)
    tower(d, 1450, 470, 70, 300, wall, roof, win, roof_h=150)
    tower(d, 1565, 470, 42, 150, wall, roof, win)
    tower(d, 1395, 470, 34, 230, wall, roof, win)
    d.line([(1450, 170 - 150), (1450, 170 - 190)], fill=roof, width=3)
    d.polygon([(1450, 170 - 190), (1480, 170 - 182), (1450, 170 - 174)], fill=layer_color((190, 40, 40), s, 0.9))
    for x in (1320, 1395, 1450, 1565):
        img = lamp(img, phase, x, 400, 30, 0.35)
    img = overlay(img, mask, cloud_col, blur=30)
    d = ImageDraw.Draw(img)
    if s["light"] > 0.5:
        for _ in range(6):
            bx, by = rng.uniform(300, 1100), rng.uniform(250, 500)
            d.line([(bx - 12, by - 6), (bx, by), (bx + 12, by - 6)], fill=layer_color((30, 30, 40), s, 1.0), width=3)
    return img


def t_pyramide(phase, rng):
    img, s = sky_base(phase, rng, 640, cloud_count=3)
    d = ImageDraw.Draw(img)
    fill(d, smooth(rng, 560, 50), layer_color((60, 105, 75), s, 0.15))
    img = fog(img, s, 600, 70, 0.5)
    d = ImageDraw.Draw(img)
    canopy(d, rng, 640, layer_color((45, 95, 60), s, 0.45), 70)
    stone = layer_color((150, 140, 115), s, 0.75)
    shade = layer_color((115, 105, 88), s, 0.8)
    cx, base, steps = 960, 760, 8
    for k in range(steps):
        w = 520 - k * 52
        y = base - k * 46
        d.rectangle([cx - w / 2, y - 46, cx + w / 2, y], fill=stone)
        d.rectangle([cx - w / 2, y - 8, cx + w / 2, y], fill=shade)
    top = base - steps * 46
    d.polygon([(cx - 60, base), (cx + 60, base), (cx + 40, top), (cx - 40, top)], fill=shade)
    for k in range(30):
        y = base - k * (base - top) / 30
        d.line([(cx - 60 + k * 0.66, y), (cx + 60 - k * 0.66, y)], fill=stone, width=2)
    d.rectangle([cx - 70, top - 90, cx + 70, top], fill=stone)
    d.rectangle([cx - 80, top - 110, cx + 80, top - 90], fill=shade)
    d.rectangle([cx - 22, top - 70, cx + 22, top], fill=window_color(phase, s))
    for tx in (cx - 85, cx + 85):
        img = lamp(img, phase, tx, top - 20, 14, 1.0)
    img = lamp(img, phase, cx, top - 40, 50, 0.4)
    img = fog(img, s, 770, 50, 0.45)
    d = ImageDraw.Draw(img)
    canopy(d, rng, 860, layer_color((25, 70, 40), s, 0.85), 120)
    dark = layer_color((12, 40, 22), s, 1.0)
    for x, y, ang in [(0, 1080, -50), (-20, 990, -20), (1920, 1080, -130), (1940, 1000, -160), (250, 1080, -70)]:
        leaf(d, x, y, rng.uniform(240, 330), ang, dark)
    return img


def t_phare(phase, rng):
    img, s = sky_base(phase, rng, 640, cloud_count=4, sun_x=700)
    img = water(img, 640, s, darken=0.6)
    d = ImageDraw.Draw(img)
    rock = layer_color((70, 68, 72), s, 0.9)
    island = [(1050, H), (1100, 760), (1180, 690), (1300, 650), (1450, 640), (1560, 670), (1680, 740), (1800, 860), (1880, H)]
    d.polygon(island, fill=rock)
    d.polygon([(0, H), (0, 900), (160, 860), (330, 930), (420, H)], fill=layer_color((50, 48, 52), s, 1.0))
    white = layer_color((235, 235, 230), s, 0.9)
    red = layer_color((190, 45, 40), s, 0.9)
    lx, base, top = 1400, 660, 300
    for k in range(6):
        y0 = base - (base - top) * k / 6
        y1 = base - (base - top) * (k + 1) / 6
        w0 = 52 - 18 * k / 6
        w1 = 52 - 18 * (k + 1) / 6
        d.polygon([(lx - w0, y0), (lx + w0, y0), (lx + w1, y1), (lx - w1, y1)], fill=red if k % 2 else white)
    d.rectangle([lx - 46, top - 8, lx + 46, top], fill=layer_color((40, 40, 45), s, 1.0))
    lampc = LAMP if lights_on(phase) else layer_color((200, 220, 230), s, 0.9)
    d.rectangle([lx - 26, top - 60, lx + 26, top - 8], fill=lampc)
    d.polygon([(lx - 34, top - 60), (lx + 34, top - 60), (lx, top - 100)], fill=red)
    d.rectangle([lx - 120, base - 50, lx - 50, base], fill=white)
    d.polygon([(lx - 128, base - 50), (lx - 42, base - 50), (lx - 85, base - 85)], fill=red)
    d.rectangle([lx - 100, base - 38, lx - 86, base - 20], fill=window_color(phase, s))
    if lights_on(phase):
        mask = Image.new("L", (W, H))
        md = ImageDraw.Draw(mask)
        md.polygon([(lx, top - 34), (0, top - 240), (0, top + 160)], fill=110 if phase == "night" else 70)
        md.polygon([(lx, top - 34), (W, top - 120), (W, top + 30)], fill=60 if phase == "night" else 40)
        img = overlay(img, mask, LAMP, blur=22)
        img = lamp(img, phase, lx, top - 34, 40, 1.0)
    # Écume au pied des rochers.
    d = ImageDraw.Draw(img)
    foam = layer_color((240, 245, 250), s, 0.6)
    for _ in range(40):
        x = rng.uniform(1040, 1890)
        y = rng.uniform(820, 1060)
        if x < 1100 or x > 1790:
            d.line([(x, y), (x + rng.uniform(20, 60), y)], fill=foam, width=2)
    return img


def floating_island(d, rng, cx, top_y, w, depth, rock, grass):
    top = [(cx - w / 2 + w * i / 30, top_y + rng.uniform(-4, 4)) for i in range(31)]
    bottom = []
    for i in range(1, 30):
        k = i / 30
        x = cx - w / 2 + w * k
        y = top_y + depth * (1 - abs(k - 0.5) * 2) ** 0.8 + rng.uniform(-14, 14)
        bottom.append((x, y))
    d.polygon(top + bottom[::-1], fill=rock)
    d.polygon(top + [(x, y + 12) for x, y in top[::-1]], fill=grass)
    return top


def t_iles_flottantes(phase, rng):
    img, s = sky_base(phase, rng, 900, cloud_count=5)
    d = ImageDraw.Draw(img)
    mask = Image.new("L", (W, H))
    md = ImageDraw.Draw(mask)
    for _ in range(30):
        cx, cy, r = rng.uniform(0, W), rng.uniform(880, 1000), rng.uniform(80, 200)
        md.ellipse([cx - r * 2, cy - r * 0.5, cx + r * 2, cy + r * 0.5], fill=230)
    md.rectangle([0, 960, W, H], fill=240)
    rock = layer_color((110, 95, 90), s, 0.6)
    grass = layer_color((90, 150, 80), s, 0.65)
    falls = Image.new("L", (W, H))
    fd = ImageDraw.Draw(falls)
    for cx, ty, w, dep, depth in [(320, 380, 260, 180, 0.3), (1650, 300, 200, 150, 0.35), (1250, 640, 150, 110, 0.4)]:
        floating_island(d, rng, cx, ty, w, dep, layer_color((110, 95, 90), s, depth), layer_color((90, 150, 80), s, depth + 0.05))
        fd.rectangle([cx + w * 0.3, ty + 4, cx + w * 0.3 + 10, H], fill=150)
    # Île principale avec temple.
    top = floating_island(d, rng, 860, 470, 560, 320, rock, grass)
    fd.rectangle([1060, 476, 1082, H], fill=200)
    fd.rectangle([640, 476, 652, H], fill=140)
    marble = layer_color((235, 230, 220), s, 0.8)
    d.rectangle([700, 455, 1020, 470], fill=marble)
    for k in range(7):
        x = 720 + k * 46
        d.rectangle([x, 335, x + 16, 455], fill=marble)
    d.rectangle([700, 320, 1020, 335], fill=marble)
    d.pieslice([760, 210, 960, 410], 180, 360, fill=layer_color((200, 180, 120), s, 0.8))
    d.rectangle([845, 380, 875, 455], fill=window_color(phase, s))
    for x in (690, 1030):
        img = lamp(img, phase, x, 450, 12, 1.0)
    img = lamp(img, phase, 860, 400, 60, 0.35)
    img = overlay(img, falls, lerp(s["atmo"], (255, 255, 255), 0.5), blur=4)
    img = overlay(img, mask, lerp(s["atmo"], (255, 255, 255), 0.3 if s["light"] > 0.5 else 0.05), blur=30)
    if lights_on(phase):
        for _ in range(20):
            img = glow(img, rng.uniform(100, 1800), rng.uniform(200, 800), 4, (180, 220, 255), 0.6)
    return img


def column(d, x, base_y, h, w, col, broken=0):
    top = base_y - h + broken
    d.rectangle([x - w / 2, top, x + w / 2, base_y], fill=col)
    if not broken:
        d.rectangle([x - w / 2 - 6, top - 10, x + w / 2 + 6, top], fill=col)


def t_ruines_grecques(phase, rng):
    img, s = sky_base(phase, rng, 640, cloud_count=4)
    img = water(img, 640, s, darken=0.45)
    d = ImageDraw.Draw(img)
    hill = smooth(rng, 760, 40)
    fill(d, [(x, y - 90 * math.exp(-((x - 1100) / 520) ** 2)) for x, y in hill], layer_color((150, 140, 90), s, 0.7))
    marble = layer_color((238, 232, 218), s, 0.85)
    base_y = 690
    d.rectangle([780, base_y, 1420, base_y + 16], fill=marble)
    d.rectangle([760, base_y + 16, 1440, base_y + 30], fill=marble)
    broken = [0, 0, 0, 0, 60, 0, 140, 210, 0]
    for k, br in enumerate(broken):
        column(d, 820 + k * 72, base_y, 250, 30, marble, br)
    d.rectangle([800, base_y - 270, 1250, base_y - 250], fill=marble)
    d.polygon([(790, base_y - 270), (1100, base_y - 360), (1260, base_y - 316), (1250, base_y - 270)], fill=marble)
    for x in (1300, 1360):
        d.rectangle([x, base_y - 12, x + 50, base_y], fill=marble)
    pts = [(x, y - 90 * math.exp(-((x - 1100) / 520) ** 2)) for x, y in hill]
    cypress(d, rng, [p for p in pts if p[0] < 700 or p[0] > 1500], layer_color((35, 60, 35), s, 0.9), 7)
    for x in (900, 1150, 1330):
        img = lamp(img, phase, x, base_y - 10, 10, 1.0)
    d = ImageDraw.Draw(img)
    fill(d, smooth(rng, 960, 30), layer_color((110, 105, 60), s, 0.95))
    return img


def t_pics_enneiges(phase, rng):
    img, s = sky_base(phase, rng, 700, cloud_count=3)
    d = ImageDraw.Draw(img)
    for i, (base, amp) in enumerate([(520, 300), (620, 230)]):
        pts = ridge(rng, base, 0.48, amp)
        fill(d, pts, layer_color((110, 115, 135), s, i * 0.4))
        snow(d, pts, base - amp * 0.1, layer_color((252, 252, 255), s, 0.4 + i * 0.2))
    img = fog(img, s, 720, 70, 0.5)
    d = ImageDraw.Draw(img)
    pts = ridge(rng, 900, 0.7, 60)
    fill(d, pts, layer_color((25, 50, 35), s, 1.0))
    pines(d, rng, pts, layer_color((20, 42, 30), s, 1.0), 2, 60, 150)
    return img


def t_karst(phase, rng):
    img, s = sky_base(phase, rng, 700, cloud_count=0)
    d = ImageDraw.Draw(img)
    for i in range(4):
        base = 640 + i * 60
        fill(d, karst(rng, base, 260 + i * 60, 9), layer_color((70, 110, 85), s, i / 3))
        img = fog(img, s, base + 10, 60, 0.6 - i * 0.12)
        d = ImageDraw.Draw(img)
    return water(img, 840, s, darken=0.45)


THEMES = {
    "lac_alpin": t_lac_alpin,
    "dunes": t_dunes,
    "foret_brume": t_foret_brume,
    "temple": t_temple,
    "fjord_aurore": t_fjord_aurore,
    "toscane": t_toscane,
    "plage": t_plage_tropicale,
    "jungle_cabane": t_jungle_cabane,
    "pics_enneiges": t_pics_enneiges,
    "karst": t_karst,
    "chateau": t_chateau,
    "pyramide": t_pyramide,
    "phare": t_phare,
    "iles_flottantes": t_iles_flottantes,
    "ruines_grecques": t_ruines_grecques,
}


if __name__ == "__main__":
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    only = sys.argv[2:] or list(THEMES)
    for t_i, name in enumerate(THEMES):
        if name not in only:
            continue
        for phase in PHASES:
            # Même graine pour les 4 phases : même paysage, seule la lumière change.
            img = THEMES[name](phase, random.Random(1000 + t_i))
            grain(img).save(out / f"{name}_{phase}.jpg", quality=84, optimize=True)
        print("ok", name)
