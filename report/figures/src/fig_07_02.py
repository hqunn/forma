# -*- coding: utf-8 -*-
"""Figure 7.2 - One training session, end to end.

The flow is a straight line until the set ends. Nothing before the set can
send the trainee backwards: the product advises on framing and leaves the
decision to start with them.
"""
from kit import *

NAME = "fig-7-2-session-activity"

W, H = 1000, 880
BW, BH = 320, 56
CX = 420
GAP = 30


def build():
    b = ""
    y = [48]

    def act(title, sub=None, cls="box"):
        """One step, stacked below the last, with the connector above it."""
        out = line(CX, y[0], CX, y[0] + GAP)
        top = y[0] + GAP
        out += box(CX - BW / 2, top, BW, BH, title, sub, cls=cls)
        y[0] = top + BH
        return out

    def dia(label, h=92, w=260):
        out = line(CX, y[0], CX, y[0] + GAP)
        top = y[0] + GAP
        out += diamond(CX, top + h / 2, w, h)
        out += txt(CX, top + h / 2 + 5, label, "tb", "middle")
        y[0] = top + h
        return out

    b += start_node(CX, 48)
    y[0] = 59

    b += act("Choose an exercise", "the specification is loaded and validated")
    b += act("Show how the exercise is done",
             "the first time, and on request after that")
    b += act("Report the framing",
             "on screen and out loud; it advises, it does not refuse")
    b += act("Wait for the user to start",
             "they decide: the start control is always live")

    set_top = y[0] + GAP
    b += act("Assess one frame", "segment, measure, evaluate, decide", cls="boxw")
    loop_from = y[0]
    b += dia("Set ended?")
    loop_to = y[0]

    b += txt(CX + 10, (loop_from + loop_to) / 2 - 36, "yes", "s")
    b += act("Summarise and store", "every fault raised, spoken or not")
    b += act("Synchronise when connected", "between sets, never during one",
             cls="boxp")

    b += line(CX, y[0], CX, y[0] + 14)
    b += end_node(CX, y[0] + 27)

    # The one return path: the frame loop. Nothing before the set loops back,
    # because nothing before the set can fail.
    bx = CX + 160
    b += path("M %d %d H %d V %d H %d" % (
        CX + 130, loop_to - 46, bx, set_top + BH / 2, CX + BW / 2))
    b += txt(bx + 10, (loop_to - 46 + set_top + BH / 2) / 2, "no: next frame", "s")

    b += txt(42, H - 14,
             "The shaded step is the only one with a deadline. Everything "
             "above it happens before the set and is allowed to take as long "
             "as it needs.",
             "cap")
    return svg(W, H, b)
