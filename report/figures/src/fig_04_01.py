# -*- coding: utf-8 -*-
"""Figure 4.1 - What constrains what, across the six decisions of Chapter 4.

Laid out in layers so that no edge passes behind a box: the two roots on the
top row, what they bound beneath them, and the two decisions that inherit from
both on the centre line.
"""
import math
from kit import *

NAME = "fig-4-1-decision-dependencies"
BW, BH = 214, 62


def centre(p):
    return p[0] + BW / 2, p[1] + BH / 2


def clip(cx, cy, sx, sy):
    """Where the ray (sx, sy) leaves the box centred on (cx, cy)."""
    if sx == 0:
        return cx, cy + math.copysign(BH / 2, sy)
    if sy == 0:
        return cx + math.copysign(BW / 2, sx), cy
    t = min(abs((BW / 2) / sx), abs((BH / 2) / sy))
    return cx + sx * t, cy + sy * t


def edge(a, b, label=None, off=0):
    ax, ay = centre(a)
    bx, by = centre(b)
    dx, dy = bx - ax, by - ay
    p1 = clip(ax, ay, dx, dy)
    p2 = clip(bx, by, -dx, -dy)
    out = line(round(p1[0], 1), round(p1[1], 1),
               round(p2[0], 1), round(p2[1], 1), cls="lnm", marker="am")
    if label:
        L = math.hypot(dx, dy)
        nx, ny = -dy / L, dx / L
        mx = (p1[0] + p2[0]) / 2 + nx * off
        my = (p1[1] + p2[1]) / 2 + ny * off + 4
        out += txt(round(mx, 1), round(my, 1), label, "e", "middle")
    return out


def build():
    W, H = 1000, 684
    b = ""

    D41 = (84, 48)
    BUD = (560, 48)
    D42 = (84, 214)
    D44 = (560, 214)
    D43 = (84, 380)
    D45 = (322, 380)
    D46 = (322, 546)

    # 4.1 reaches 4.3 down the left margin, clear of 4.2.
    b += path("M 84 79 L 42 79 L 42 411 L 80 411", cls="lnm", marker="am")
    # 4.4 reaches 4.6 down the right margin, clear of 4.5.
    b += path("M 774 245 L 940 245 L 940 577 L 540 577", cls="lnm", marker="am")
    b += txt(790, 424, "cue coverage is part", "e", "middle")
    b += txt(790, 442, "of adding one", "e", "middle")

    b += edge(D41, D42)
    b += txt(202, 172, "the device it runs on", "e")
    b += edge(BUD, D44)
    b += txt(656, 172, "no generation in the path", "e", "end")

    b += edge(BUD, D42, "frame-rate floor", -24)
    b += edge(D42, D45)
    b += txt(300, 330, "topology bounds the rules", "e", "end")
    b += edge(D44, D45)
    b += txt(558, 330, "a code, not a score", "e")
    b += edge(D45, D46)
    b += txt(440, 500, "constraints are data", "e")

    for p, t, s, c in [
        (BUD, "3.4.1  Response-time budget", "a requirement, not a decision", "boxd"),
        (D41, "4.1  Platform and device", "a constraint, not a decision", "boxd"),
        (D42, "4.2  Pose estimation engine", "MoveNet Lightning, on the GPU", "boxp"),
        (D44, "4.4  Immediate feedback path", "a pre-authored cue bank", "boxp"),
        (D43, "4.3  Client and stack", "Kotlin, on device, synced", "boxp"),
        (D45, "4.5  Assessment approach", "declared constraints", "boxp"),
        (D46, "4.6  Extensibility mechanism", "one specification file", "boxp"),
    ]:
        b += box(p[0], p[1], BW, BH, t, s, cls=c, tcls="tb", scls="s")

    b += txt(42, 656,
             "An arrow reads: the decision at its tail bounds the decision at its head.",
             "cap")
    return svg(W, H, b)
