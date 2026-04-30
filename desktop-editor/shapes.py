"""Pure-Python shape rasterization for the burn-out editor's drag-shapes.

Each rasterizer returns a list of (x, y) integer pixel coordinates that fall
inside the filled shape. Bbox is inclusive on both endpoints; (x0, y0) and
(x1, y1) can be in any order (normalized internally).
"""

from __future__ import annotations


def _normalize(x0: int, y0: int, x1: int, y1: int):
    if x1 < x0: x0, x1 = x1, x0
    if y1 < y0: y0, y1 = y1, y0
    return x0, y0, x1, y1


def rasterize_rect(x0: int, y0: int, x1: int, y1: int) -> list[tuple[int, int]]:
    x0, y0, x1, y1 = _normalize(x0, y0, x1, y1)
    return [(x, y) for y in range(y0, y1 + 1) for x in range(x0, x1 + 1)]


def rasterize_circle(x0: int, y0: int, x1: int, y1: int) -> list[tuple[int, int]]:
    """Filled circle inscribed in the bbox.

    Center at the geometric center; radius = min(width, height) / 2 where
    width and height are pixel counts (x1 - x0 + 1, y1 - y0 + 1). A pixel
    (x, y) is included if its distance from center <= radius.
    """
    x0, y0, x1, y1 = _normalize(x0, y0, x1, y1)
    cx = (x0 + x1) / 2.0
    cy = (y0 + y1) / 2.0
    w = x1 - x0 + 1
    h = y1 - y0 + 1
    r = min(w, h) / 2.0
    r2 = r * r
    out: list[tuple[int, int]] = []
    for y in range(y0, y1 + 1):
        dy = y - cy
        for x in range(x0, x1 + 1):
            dx = x - cx
            if dx * dx + dy * dy <= r2:
                out.append((x, y))
    return out


# Triangle vertex layouts per orientation, expressed as functions of the bbox.
# 8 orientations form a CW rotation starting from "point up":
#   0=N (^)   1=NE (point at top-right corner)   2=E (>)   3=SE
#   4=S (v)   5=SW                                6=W (<)   7=NW
# Public so the GUI can draw a matching drag preview.
def triangle_vertices(x0, y0, x1, y1, orientation: int):
    cx = (x0 + x1) / 2.0
    cy = (y0 + y1) / 2.0
    layouts = {
        0: ((cx, y0), (x0, y1), (x1, y1)),    # N: isoceles, point up
        1: ((x1, y0), (x0, y0), (x1, y1)),    # NE: right-tri, point at top-right
        2: ((x1, cy), (x0, y0), (x0, y1)),    # E: isoceles, point right
        3: ((x1, y1), (x1, y0), (x0, y1)),    # SE: right-tri, point at bottom-right
        4: ((cx, y1), (x0, y0), (x1, y0)),    # S: isoceles, point down
        5: ((x0, y1), (x1, y1), (x0, y0)),    # SW: right-tri, point at bottom-left
        6: ((x0, cy), (x1, y0), (x1, y1)),    # W: isoceles, point left
        7: ((x0, y0), (x1, y0), (x0, y1)),    # NW: right-tri, point at top-left
    }
    return layouts[orientation]


def _sign(p, q, r) -> float:
    return (p[0] - r[0]) * (q[1] - r[1]) - (q[0] - r[0]) * (p[1] - r[1])


def rasterize_triangle(
    x0: int, y0: int, x1: int, y1: int, orientation: int,
) -> list[tuple[int, int]]:
    """Filled triangle in one of 8 orientations (0..7).

    A pixel is inside the triangle if all three sign-of-cross-product checks
    have the same sign (or zero). Edge pixels are included.
    """
    if orientation not in range(8):
        raise ValueError(f"orientation must be in 0..7, got {orientation}")
    x0, y0, x1, y1 = _normalize(x0, y0, x1, y1)
    v0, v1, v2 = triangle_vertices(x0, y0, x1, y1, orientation)

    out: list[tuple[int, int]] = []
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            p = (x, y)
            d1 = _sign(p, v0, v1)
            d2 = _sign(p, v1, v2)
            d3 = _sign(p, v2, v0)
            has_neg = (d1 < 0) or (d2 < 0) or (d3 < 0)
            has_pos = (d1 > 0) or (d2 > 0) or (d3 > 0)
            if not (has_neg and has_pos):
                out.append(p)
    return out
