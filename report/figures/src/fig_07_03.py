# -*- coding: utf-8 -*-
"""Figure 7.3 - One frame to one cue, or to silence.

The two branches at the bottom are the point of the diagram. Silence is not
the absence of a result; it is a decision taken with the fault already raised
and already recorded.
"""
from kit import *

NAME = "fig-7-3-frame-to-cue"

W, H = 1240, 880
TOP, BOT = 56, 766
HW = 176


def build():
    b = ""

    lanes = [
        (104, "CameraX", "analysis thread"),
        (328, "SessionViewModel", None),
        (556, "PoseEngine", "worker thread"),
        (784, "AssessmentSession", None),
        (975, "TriggerPolicy", None),
        (1152, "CuePlayer", "mixer thread"),
    ]
    for x, name, sub in lanes:
        w = HW if x < 1100 else 128
        b += box(x - w / 2, TOP, w, 52 if sub else 44, name, sub,
                 cls="boxp", tcls="tb", scls="s")
        b += line(x, TOP + (52 if sub else 44), x, BOT, cls="life", marker=None)

    def msg(y, a, bx, label, dash=False, above=True):
        cls = "lnd" if dash else "ln"
        out = line(a, y, bx, y, cls=cls)
        mx = (a + bx) / 2
        out += txt(mx, y - 8 if above else y + 16, label, "s", "middle")
        return out

    def selfmsg(y, x, label, h=26):
        out = path("M %d %d H %d V %d H %d" % (x, y, x + 72, y + h, x + 2))
        out += txt(x + 84, y + h / 2 + 4, label, "s")
        return out

    CAM, VM, ENG, SESS, TRIG, CUE = [x for x, _, _ in lanes]

    b += msg(150, CAM, VM, "analyse(image, t)")
    b += msg(196, VM, ENG, "submit(bitmap, t, rotation)")
    b += msg(232, ENG, VM, "accepted, or dropped if busy", dash=True)
    b += msg(292, ENG, VM, "onResult(landmarks, t)")
    b += selfmsg(322, VM, "FrameAdapter.fromReadings(...): Frame")
    b += msg(396, VM, SESS, "onFrame(frame)")
    b += selfmsg(426, SESS, "segment, measure, evaluate")
    b += msg(500, SESS, TRIG, "select(raised, now, confidence)")

    # The branch.
    AX, AY, AW, AH = 232, 536, 952, 212
    b += f'  <rect x="{AX}" y="{AY}" width="{AW}" height="{AH}" class="boxd"/>\n'
    b += f'  <rect x="{AX}" y="{AY}" width="46" height="22" class="boxd"/>\n'
    b += txt(AX + 23, AY + 16, "alt", "sb", "middle")

    b += txt(AX + 58, AY + 16, "a cue survives every limit", "s")
    b += msg(AY + 48, TRIG, SESS, "SpokenCue", dash=True)
    b += msg(AY + 84, SESS, VM, "SessionUpdate, carrying the cue", dash=True)
    b += msg(AY + 120, VM, CUE, "play(asset)")

    b += line(AX, AY + 140, AX + AW, AY + 140, cls="lnd", marker=None)
    b += txt(AX + 12, AY + 160, "no cue: floor, budget, gap, debounce or repeat", "s")
    b += msg(AY + 192, TRIG, SESS, "null", dash=True)

    b += txt(60, 800,
             "The fault is raised and written to the session record in both "
             "branches. Only the last message differs.",
             "e")

    b += txt(44, 858,
             "Dashed arrows are returns. The engine answers on its own "
             "thread, so the submission and the result it triggers belong to "
             "different frames in flight.",
             "cap")
    return svg(W, H, b)
