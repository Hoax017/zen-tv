"""Bannière (320x180) et icône (192x192) de Zen Home, à partir du premier fond d'écran."""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parent.parent
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
src = Image.open(sorted((root / "assets/wallpapers").glob("*.jpg"))[0])


def font(size):
    for f in ["/System/Library/Fonts/SFNS.ttf", "/System/Library/Fonts/Helvetica.ttc",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "C:/Windows/Fonts/segoeui.ttf"]:
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            pass
    return ImageFont.load_default()


banner = src.resize((320, 180))
d = ImageDraw.Draw(banner)
d.text((160, 90), "Zen Home", font=font(38), fill="white", anchor="mm")
banner.save(out / "banner.png")

icon = src.crop((420, 0, 1500, 1080)).resize((192, 192))
d = ImageDraw.Draw(icon)
d.text((96, 96), "Z", font=font(110), fill="white", anchor="mm")
icon.save(out / "icon.png")
