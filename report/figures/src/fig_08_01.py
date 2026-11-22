# -*- coding: utf-8 -*-
"""Figure 9.1 - Screen flow."""
from kit import *

NAME = "fig-8-1-screen-flow"

BW, BH = 170, 70


def screen(x, y, sid, name, dark=False):
    out = box(x, y, BW, BH, cls="boxk" if dark else "boxp")
    c = WHITE if dark else INK
    out += txt(x + BW / 2, y + 28, sid, "sb", "middle",
               fill="#9FD3DE" if dark else None)
    out += txt(x + BW / 2, y + 50, name, "tb", "middle", fill=c if dark else None)
    return out


def build():
    b = ""
    W, H = 1200, 630

    train = [(60, "S1", "Home", False), (280, "S2", "Exercise selection", False),
             (500, "S3", "Set-up", True), (720, "S4", "Live session", True),
             (940, "S5", "Set summary", True)]
    for x, sid, name, dark in train:
        b += screen(x, 120, sid, name, dark)
    for x in (230, 450, 670, 890):
        b += line(x, 155, x + 50, 155, cls="ln")
    b += txt(255, 146, "Start", "e", "middle")

    # S3 carries the guide, which opens over it and closes back onto it.
    b += path("M500,180 H472 V218 H650 V194", cls="lnm", marker="am")
    b += txt(660, 214, "How to do this - the guide, over the set-up screen", "e")

    # after a set, the shortest path is back to the set-up screen
    b += path("M1025,190 V252 H590 V194", cls="lna", marker="aa")
    b += txt(800, 270, "Repeat set - straight back to set-up", "e", "middle")

    b += path("M1025,120 V66 H145 V116", cls="lnm", marker="am")
    b += txt(580, 58, "Finish", "e", "middle")

    hist = [(280, "S6", "Session history"), (500, "S7", "Session detail"),
            (720, "S8", "Fault detail")]
    for x, sid, name in hist:
        b += screen(x, 380, sid, name)
    for x in (450, 670):
        b += line(x, 415, x + 50, 415, cls="ln")

    b += screen(60, 380, "S9", "Settings")
    b += line(110, 190, 110, 380, cls="lnm", marker="am")
    b += txt(122, 300, "Settings", "e")

    b += path("M230,165 H254 V415 H278", cls="lnm", marker="am")
    b += txt(262, 356, "History", "e")

    # Home opens the last set directly. The list is for everything else.
    b += path("M190,190 V330 H585 V376", cls="lna", marker="aa")
    b += txt(600, 326, "Last session - straight to the set just finished", "e")

    b += txt(60, 500, "S3 to S5 are the training path:", "tb")
    b += txt(336, 500,
             "a dark surface, read in the upper half of the display from across a room. "
             "The rest are ordinary light screens used at arm's length.", "s")

    b += txt(60, 560,
             "Every route into a set passes through S3, which advises on framing without "
             "blocking. After a set the shortest path is back to S3 rather than", "cap")
    b += txt(60, 582,
             "Home, because the common case is another set of the same exercise and the "
             "user is still across the room. Home opens the last set in one tap.", "cap")
    return svg(W, H, b)
