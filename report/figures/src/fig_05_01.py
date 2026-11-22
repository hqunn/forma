# -*- coding: utf-8 -*-
"""Figure 5.1 - The two deployable parts, and the layers inside the handset.

Laid out so the live path is one unbroken column from camera to speaker, with
the record and the synchronisation boundary set aside from it. The only line
that leaves the handset leaves from the bottom of the second column, which is
the point the figure exists to make.
"""
from kit import *

NAME = "fig-5-1-architecture"

W, H = 1100, 620

# Column A: the live path.
AX, AW, BOXH = 72, 290, 56
AMID = AX + AW / 2
A_Y = [120, 198, 276, 354, 432]

# Column B: the record and the crossing.
BX, BW2 = 400, 170
BMID = BX + BW2 / 2


def build():
    b = ""

    # The two deployable parts.
    b += node(40, 40, 560, 520, "device", "Android handset")
    b += node(800, 320, 240, 240, "service", "FORMA server")

    b += txt(AX, 110, "LIVE PATH", "sb")
    b += txt(BX, 110, "RECORD", "sb")

    live = [
        ("Capture", "frames, each timestamped"),
        ("Pose estimation", "one model, fixed configuration"),
        ("Adaptation", "engine output to frame type"),
        ("Assessment", "segment, measure, judge"),
        ("Feedback output", "fault code to spoken cue"),
    ]
    for (title, sub), y in zip(live, A_Y):
        b += box(AX, y, AW, BOXH, title, sub, cls="box")
    for y in A_Y[:-1]:
        b += line(AMID, y + BOXH, AMID, y + BOXH + 22)

    b += txt(AX, 522, "No network call, file read or model load on this path.", "e")

    # The record, and the one thing that crosses.
    b += box(BX, 354, BW2, BOXH, "Local record", "sessions, reps, faults", cls="boxp")
    b += box(BX, 482, BW2, BOXH, "Synchronisation", "between sets only", cls="boxp")
    b += line(AX + AW, 382, BX, 382)
    b += line(BMID, 410, BMID, 482)

    # The boundary crossing. Dashed, because it is occasional and may not happen.
    b += line(BX + BW2, 510, 800, 510, cls="lnd")
    b += txt(685, 496, "records only", "s", "middle")
    b += txt(685, 528, "no frames or landmarks", "s", "middle")

    for i, (title, sub) in enumerate([
        ("Account", "sign in"),
        ("Session store", "durable history"),
        ("Reconciliation", "including deletions"),
    ]):
        b += box(820, 380 + i * 54, 200, 44, title, sub, cls="boxq",
                 tcls="t", scls="s")

    b += txt(42, 596,
             "The shaded column is the live path. It runs entirely inside one "
             "process on the handset; the dashed line is the only crossing, and "
             "it never runs during a set.",
             "cap")
    return svg(W, H, b)
