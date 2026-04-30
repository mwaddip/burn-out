"""Pure-rasterization tests for shape primitives. No GUI, no PyQt5."""

import sys
from pathlib import Path

import pytest

# Make shapes.py importable from a sibling directory.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from shapes import rasterize_rect, rasterize_circle, rasterize_triangle


# ---- rect ----

def test_rect_single_pixel():
    assert rasterize_rect(5, 7, 5, 7) == [(5, 7)]


def test_rect_3x3():
    result = sorted(rasterize_rect(0, 0, 2, 2))
    expected = [(x, y) for y in range(3) for x in range(3)]
    assert result == sorted(expected)


def test_rect_offset_2x2():
    result = sorted(rasterize_rect(10, 20, 11, 21))
    assert result == [(10, 20), (10, 21), (11, 20), (11, 21)]


def test_rect_inverted_bbox_normalizes():
    # Drag from bottom-right to top-left should produce the same set as forward drag.
    assert sorted(rasterize_rect(2, 2, 0, 0)) == sorted(rasterize_rect(0, 0, 2, 2))


# ---- circle ----

def test_circle_single_pixel():
    assert rasterize_circle(5, 7, 5, 7) == [(5, 7)]


def test_circle_3x3_fills_bbox():
    # 3x3 bbox: width=height=3 (pixel count). radius = 3/2 = 1.5.
    # Corners at distance sqrt(2) ≈ 1.41 from center (1, 1) -> all inside.
    result = set(rasterize_circle(0, 0, 2, 2))
    assert len(result) == 9


def test_circle_5x5_excludes_corners():
    # 5x5 bbox: radius = 2.5. Corners at sqrt(8) ≈ 2.83 -> excluded.
    # Edge midpoints at distance 2 -> included.
    result = set(rasterize_circle(0, 0, 4, 4))
    assert (2, 2) in result        # center
    assert (0, 2) in result        # left edge midpoint
    assert (2, 0) in result        # top edge midpoint
    assert (4, 2) in result        # right edge midpoint
    assert (2, 4) in result        # bottom edge midpoint
    assert (0, 0) not in result    # corner
    assert (4, 4) not in result


def test_circle_oblong_bbox_uses_min_dim():
    # 3-wide, 5-tall bbox: radius = min(3, 5) / 2 = 1.5.
    # Center at (1, 2). Pixels with dist <= 1.5 are inside.
    result = set(rasterize_circle(0, 0, 2, 4))
    assert (1, 2) in result        # center
    assert (0, 2) in result        # dist 1
    assert (1, 0) not in result    # dist 2 > 1.5
    assert (1, 4) not in result    # dist 2 > 1.5


def test_circle_inverted_bbox_normalizes():
    assert sorted(rasterize_circle(4, 4, 0, 0)) == sorted(rasterize_circle(0, 0, 4, 4))


# ---- triangle ----

def test_triangle_single_pixel_all_orientations():
    for orient in range(8):
        assert rasterize_triangle(5, 5, 5, 5, orient) == [(5, 5)]


def test_triangle_orient_0_point_up_3x3():
    # bbox (0,0)-(2,2), orient 0 (N/point-up).
    # Vertices: (1, 0), (0, 2), (2, 2).
    result = set(rasterize_triangle(0, 0, 2, 2, 0))
    assert (1, 0) in result  # apex
    assert (0, 2) in result  # base-left
    assert (2, 2) in result  # base-right
    assert (1, 1) in result  # center column
    assert (1, 2) in result  # base mid
    # Above the triangle's edges:
    assert (0, 0) not in result
    assert (2, 0) not in result
    assert (0, 1) not in result
    assert (2, 1) not in result


def test_triangle_orient_4_point_down_3x3():
    # bbox (0,0)-(2,2), orient 4 (S/point-down).
    # Vertices: (1, 2), (0, 0), (2, 0).
    result = set(rasterize_triangle(0, 0, 2, 2, 4))
    assert (1, 2) in result  # apex
    assert (0, 0) in result
    assert (2, 0) in result
    assert (0, 2) not in result
    assert (2, 2) not in result


def test_triangle_isoceles_orientations_each_cover_their_apex():
    # Orient 0/2/4/6 are isoceles with apex on the midpoint of an edge.
    # 5x5 bbox: cx=cy=2. Apexes: (2,0)=N, (4,2)=E, (2,4)=S, (0,2)=W.
    apexes = {0: (2, 0), 2: (4, 2), 4: (2, 4), 6: (0, 2)}
    for orient, apex in apexes.items():
        result = set(rasterize_triangle(0, 0, 4, 4, orient))
        assert apex in result, f"orient {orient} missing apex {apex}"


def test_triangle_corner_orientations_each_cover_their_corner():
    # Orient 1/3/5/7 are right triangles with the "point" at a corner.
    corners = {1: (4, 0), 3: (4, 4), 5: (0, 4), 7: (0, 0)}
    for orient, corner in corners.items():
        result = set(rasterize_triangle(0, 0, 4, 4, orient))
        assert corner in result, f"orient {orient} missing corner {corner}"


def test_triangle_orient_1_NE_excludes_opposite_corner():
    # Orient 1: vertices (4,0), (0,0), (4,4) — covers top-right wedge.
    # The opposite corner (0,4) should be outside.
    result = set(rasterize_triangle(0, 0, 4, 4, 1))
    assert (0, 4) not in result


def test_triangle_orient_5_SW_excludes_opposite_corner():
    # Orient 5: vertices (0,4), (4,4), (0,0). The opposite corner (4,0) is outside.
    result = set(rasterize_triangle(0, 0, 4, 4, 5))
    assert (4, 0) not in result


def test_all_8_orientations_distinct():
    sets = [frozenset(rasterize_triangle(0, 0, 4, 4, i)) for i in range(8)]
    assert len(set(sets)) == 8


def test_triangle_inverted_bbox_normalizes():
    forward = sorted(rasterize_triangle(0, 0, 4, 4, 0))
    reverse = sorted(rasterize_triangle(4, 4, 0, 0, 0))
    assert forward == reverse


def test_triangle_invalid_orientation_raises():
    with pytest.raises((ValueError, KeyError)):
        rasterize_triangle(0, 0, 4, 4, 8)
