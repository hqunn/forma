# -*- coding: utf-8 -*-
"""Figure 3.1 - Use-case diagram, Module 1: Training session.

Subject of the diagram is the mobile application. Module 1 has one actor and
no secondary actor, which is the offline property drawn rather than asserted.
"""
import math
from kit import *

NAME = "fig-3-1-use-case-module-1"
RX, RY = 114, 41


def rel(a, b, label, off):
    """A dashed UML dependency from oval a to oval b, arrowhead at b."""
    p1 = ell_edge(b[0], b[1], a[0], a[1], RX, RY)
    p2 = ell_edge(a[0], a[1], b[0], b[1], RX, RY)
    out = line(p1[0], p1[1], p2[0], p2[1], cls="lnd", marker="ao")
    dx, dy = p2[0] - p1[0], p2[1] - p1[1]
    L = math.hypot(dx, dy)
    nx, ny = -dy / L, dx / L
    mx, my = (p1[0] + p2[0]) / 2 + nx * off, (p1[1] + p2[1]) / 2 + ny * off + 4
    return out + txt(round(mx, 1), round(my, 1), label, "e", "middle")


def oval(c, uid, a, b=None):
    out = ell(c[0], c[1], RX, RY, "boxp")
    if b:
        out += txt(c[0], c[1] - 16, uid, "sb", "middle")
        out += txt(c[0], c[1] + 2, a, "tb", "middle")
        out += txt(c[0], c[1] + 20, b, "tb", "middle")
    else:
        out += txt(c[0], c[1] - 8, uid, "sb", "middle")
        out += txt(c[0], c[1] + 12, a, "tb", "middle")
    return out


def build():
    W, H = 960, 636
    b = ""

    b += '  <rect x="208" y="64" width="694" height="512" rx="8" class="reg"/>\n'
    b += txt(555, 92, "FORMA mobile application - Module 1: Training session",
             "h", "middle")

    UC01 = (366, 152)
    UC03 = (366, 322)
    UC05 = (366, 492)
    UC02 = (716, 176)
    UC04 = (716, 468)

    ax, ay = 110, 322
    b += actor(ax, 282, "")
    b += txt(ax, 392, "Trainee", "tb", "middle")

    for c in (UC01, UC03, UC05):
        b += assoc(ax, ay, c[0], c[1], RX, RY)

    b += rel(UC03, UC02, "«include»", -25)
    b += rel(UC04, UC03, "«extend»", 25)

    b += oval(UC01, "UC-01", "Select an exercise")
    b += oval(UC03, "UC-03", "Perform a training set")
    b += oval(UC05, "UC-05", "End the set and", "view its summary")
    b += oval(UC02, "UC-02", "Set up for a set")
    b += oval(UC04, "UC-04", "Deliver a", "corrective cue")

    b += txt(208, 608,
             "UC-02 is reached only as part of a set, so it carries no association of "
             "its own. UC-04 is raised by the system when a fault is", "cap")
    b += txt(208, 626,
             "confirmed, not requested by the trainee, so it extends UC-03 rather than "
             "starting from the actor.", "cap")
    return svg(W, H, b)
