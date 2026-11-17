#!/usr/bin/env python3
"""Makes Rinth Client's icons from its logo, a square PNG with a transparent background.

    python3 tools/icon/rinth_icon.py <logo.png>

Writes the desktop window icon's sizes under the pack, and the 32-pixel badge glyph shown before
players' name tags, from the logo. The logo is cropped to what it draws and centred on a square
first, so a margin around it doesn't shrink it.

The GUI's mark (rinth.client.ui.Logo) and the page's favicon and loader mark are the game's own
carrot, read from the game as it runs: nothing here writes them, and no game texture is copied.

Needs Pillow (pip install pillow).
"""

import io
import sys
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
PACK = ROOT / "client/src/main/resources/rinth-pack/assets/rinth/textures/gui"
BADGE = ROOT / "client/src/main/resources/rinth-pack/assets/rinth/textures/font/badge.png"
SIZES = (16, 32, 48, 128, 256)


def square(logo: Image.Image) -> Image.Image:
    """The logo cropped to what it draws, centred on a transparent square with a small margin."""
    box = logo.getbbox()
    if box is None:
        raise SystemExit("The logo draws nothing")
    drawn = logo.crop(box)
    side = round(max(drawn.size) * 1.06)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(drawn, ((side - drawn.width) // 2, (side - drawn.height) // 2), drawn)
    return canvas


def png(image: Image.Image, size: int) -> bytes:
    out = io.BytesIO()
    image.resize((size, size), Image.LANCZOS).save(out, "PNG", optimize=True)
    return out.getvalue()


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    logo = square(Image.open(sys.argv[1]).convert("RGBA"))
    for size in SIZES:
        (PACK / f"icon_{size}.png").write_bytes(png(logo, size))
    BADGE.write_bytes(png(logo, 32))
    print(f"Wrote the icons into {PACK} and the badge into {BADGE}")


if __name__ == "__main__":
    main()
