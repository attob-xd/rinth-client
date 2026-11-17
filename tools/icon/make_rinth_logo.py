#!/usr/bin/env python3
"""Draws Rinth Client's logo: a pixel carrot, leaves up, across a dark rounded tile with a warm rim.

    python3 tools/icon/make_rinth_logo.py            # writes tools/icon/rinth-logo.png
    python3 tools/icon/rinth_icon.py tools/icon/rinth-logo.png

The carrot is drawn here cell by cell on a 16-cell grid, so the art is Rinth's own: it copies no
game texture. Its body tapers along the diagonal from the tip (bottom left) to the shoulder (top
right), lit on its upper side, with a few darker ridges across it; three leaves fan out above.

Needs Pillow (pip install pillow).
"""

import math
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
OUT = HERE / "rinth-logo.png"

SIZE = 512
GRID = 16
CELL = 22  # The carrot's cells, in pixels: 16 * 22 = 352 of the 512.

TILE = (24, 30, 44, 255)
RIM = (242, 140, 40, 255)

BODY = (240, 128, 32)
LIGHT = (255, 170, 72)
SHADE = (196, 92, 22)
RIDGE = (170, 76, 18)
LEAF = (78, 182, 84)
LEAF_LIGHT = (122, 214, 108)
LEAF_DARK = (44, 128, 60)

# The body's axis, in cells: from the tip to the shoulder, and its half width at each end.
TIP = (2.5, 13.5)
SHOULDER = (10.0, 6.0)
TIP_WIDTH = 0.55
SHOULDER_WIDTH = 2.45
# Where along the body (0 at the tip, 1 at the shoulder) a ridge crosses it.
RIDGES = (0.3, 0.52, 0.74)
# The leaves: each a line of cells from the shoulder outwards.
LEAVES = [((10.0, 6.0), (13.2, 1.6)), ((10.4, 6.4), (14.6, 4.2)), ((9.6, 5.6), (10.0, 1.4))]


def body_cells() -> dict[tuple[int, int], tuple[int, int, int]]:
    """Each body cell and its colour."""
    ax, ay = TIP
    bx, by = SHOULDER
    dx, dy = bx - ax, by - ay
    length = math.hypot(dx, dy)
    ux, uy = dx / length, dy / length
    cells = {}
    for y in range(GRID):
        for x in range(GRID):
            cx, cy = x + 0.5, y + 0.5
            along = ((cx - ax) * ux + (cy - ay) * uy) / length
            if not 0 <= along <= 1.04:
                continue
            across = (cx - ax) * -uy + (cy - ay) * ux  # Negative on the upper (lit) side.
            half = TIP_WIDTH + (SHOULDER_WIDTH - TIP_WIDTH) * min(along, 1)
            if abs(across) > half:
                continue
            colour = BODY
            if across < -half * 0.35:
                colour = LIGHT
            elif across > half * 0.45:
                colour = SHADE
            if any(abs(along - ridge) < 0.045 for ridge in RIDGES) and across > -half * 0.6:
                colour = RIDGE
            cells[(x, y)] = colour
    return cells


def leaf_cells() -> dict[tuple[int, int], tuple[int, int, int]]:
    """Each leaf cell and its colour: lighter towards the leaf's end, a dark cell at its root."""
    cells = {}
    for index, ((ax, ay), (bx, by)) in enumerate(LEAVES):
        steps = int(math.hypot(bx - ax, by - ay) * 2) + 1
        for step in range(steps + 1):
            t = step / steps
            x, y = int(ax + (bx - ax) * t), int(ay + (by - ay) * t)
            colour = LEAF_DARK if t < 0.25 else (LEAF_LIGHT if (step + index) % 3 == 0 else LEAF)
            cells[(x, y)] = colour
    return cells


def main() -> None:
    image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((8, 8, SIZE - 9, SIZE - 9), radius=104, fill=RIM)
    draw.rounded_rectangle((30, 30, SIZE - 31, SIZE - 31), radius=86, fill=TILE)
    origin = (SIZE - GRID * CELL) // 2
    cells = leaf_cells()
    cells.update(body_cells())  # The body over the leaves' roots.
    # A dark outline: every empty cell beside a filled one, so the carrot reads on the tile.
    outline = {
        (x + dx, y + dy)
        for (x, y) in cells
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
        if (x + dx, y + dy) not in cells and 0 <= x + dx < GRID and 0 <= y + dy < GRID
    }
    for x, y in outline:
        draw.rectangle(
            (origin + x * CELL, origin + y * CELL, origin + (x + 1) * CELL - 1, origin + (y + 1) * CELL - 1),
            fill=(14, 16, 24, 255),
        )
    for (x, y), colour in cells.items():
        draw.rectangle(
            (origin + x * CELL, origin + y * CELL, origin + (x + 1) * CELL - 1, origin + (y + 1) * CELL - 1),
            fill=colour + (255,),
        )
    image.save(OUT)
    print(f"Wrote {OUT}")


if __name__ == "__main__":
    main()
