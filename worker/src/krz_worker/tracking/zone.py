"""The detection zone: people count only when their anchor (bottom-centre) is inside the camera's polygon."""

from __future__ import annotations

from collections.abc import Sequence

Point = tuple[float, float]


def contains(polygon: Sequence[Point], point: Point) -> bool:
    """Whether a normalised point (0..1) lies inside a normalised polygon; an empty polygon is the whole image.

    Ray casting: a point on an edge may count either way, which does not matter at pixel scale.
    """
    if len(polygon) < 3:
        return True
    x, y = point
    inside = False
    j = len(polygon) - 1
    for i in range(len(polygon)):
        xi, yi = polygon[i]
        xj, yj = polygon[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


#: Just inside the bottom edge. A box cut off by the bottom of the image has its feet at or below the edge, nearer the
#: camera than anything in view; on the edge itself the ray cast above would count it outside a zone drawn to the edge.
BOTTOM_EDGE = 0.999


def anchor(box: tuple[float, float, float, float], width: int, height: int) -> Point:
    """A person's anchor (bottom-centre, normalised). Feet cut off by the bottom edge stay on it, so a camera above the
    door keeps tracking people walking under it when the zone reaches the edge."""
    x1, _, x2, y2 = box
    return (x1 + x2) / 2 / width, min(y2 / height, BOTTOM_EDGE)
