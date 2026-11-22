# -*- coding: utf-8 -*-
"""Figure 7.4 - The two segmenters.

One machine counts and one times, and they are the same machine with the entry
comparison reversed. Drawn together so that the symmetry is visible, and so
that the asymmetry inside the engaged state is too: a counting exercise
resolves three phases within one engaged segment, a hold resolves one.
"""
from kit import *

NAME = "fig-7-4-segmenters"

W, H = 1380, 760
SW, SH = 200, 52


def build():
    b = ""

    def simple(x, y, name, sub):
        return box(x, y, SW, SH, name, sub, cls="boxp")

    def composite(x, y, w, h, name, sub):
        out = f'  <rect x="{x}" y="{y}" width="{w}" height="{h}" rx="4" class="box"/>\n'
        out += txt(x + 16, y + 28, name, "tb")
        out += txt(x + 16, y + 48, sub, "s")
        out += (f'  <line x1="{x}" y1="{y+62}" x2="{x+w}" y2="{y+62}" '
                f'stroke="{HAIR}" stroke-width="1.2"/>\n')
        return out

    # ---- Left panel: a counting exercise ------------------------------
    b += '  <rect x="36" y="56" width="664" height="624" rx="8" class="reg"/>\n'
    b += txt(60, 84, "A repetition: the driver falls to enter", "h")
    b += txt(60, 104, "Squat, push-up", "s")

    b += start_node(160, 146)
    b += simple(60, 184, "Not engaged", "phase: idle")
    b += line(160, 157, 160, 184)
    b += line(160, 236, 160, 326)
    b += txt(174, 266, "driver < enter bound", "s")
    b += txt(174, 286, "/ open a segment", "s")

    EX, EY, EW, EH = 60, 326, 420, 232
    b += composite(EX, EY, EW, EH, "Engaged", "the segment is open")
    b += txt(EX + 16, EY + 86, "the phase, resolved per frame:", "sb")
    for i, (cond, ph) in enumerate([
        ("driver still falling", "descent"),
        ("driver still rising", "ascent"),
        ("otherwise, within the band", "bottom"),
        ("of the running extreme", ""),
    ]):
        b += txt(EX + 16, EY + 116 + i * 24, cond, "s")
        if ph:
            b += txt(EX + EW - 16, EY + 116 + i * 24, ph, "mb", "end")

    b += path("M %d %d H 660 V %d H %d" % (EX + EW, EY + 110, 210, EX + SW))
    b += txt(492, EY + 10, "driver >= exit bound", "s")
    b += txt(492, EY + 30, "/ count it if the segment", "s")
    b += txt(492, EY + 50, "lasted between the", "s")
    b += txt(492, EY + 70, "declared min and max", "s")

    b += txt(60, 596, "The direction test is applied on both sides of the extremum.", "e")
    b += txt(60, 618, "Testing the band first would make every descending frame", "e")
    b += txt(60, 640, "read as the bottom, because the running extreme tracks it.", "e")

    # ---- Right panel: a hold -------------------------------------------
    b += '  <rect x="740" y="56" width="604" height="624" rx="8" class="reg"/>\n'
    b += txt(764, 84, "A hold: the driver rises to enter", "h")
    b += txt(764, 104, "Front plank", "s")

    b += start_node(864, 146)
    b += simple(764, 184, "Not engaged", "phase: out")
    b += line(864, 157, 864, 184)
    b += line(864, 236, 864, 326)
    b += txt(878, 266, "driver >= enter bound", "s")
    b += txt(878, 286, "/ open a segment", "s")

    HX, HY, HW2, HH = 764, 326, 320, 96
    b += composite(HX, HY, HW2, HH, "Engaged", "phase: holding")
    b += txt(HX + 16, HY + 86, "no descent, bottom or ascent", "s")

    b += path("M %d %d H 1290 V %d H %d" % (HX + HW2, HY + 48, 210, HX + SW))
    b += txt(1100, HY + 4, "driver < exit bound", "s")
    b += txt(1100, HY + 24, "/ add the duration, if it", "s")
    b += txt(1100, HY + 44, "lasted at least the minimum", "s")

    b += txt(764, 470, "All three phase roles collapse onto the held phase, so a", "e")
    b += txt(764, 492, "fault scoped to it is evaluated for the whole hold. A plank", "e")
    b += txt(764, 514, "reports seconds rather than a count, and that is the only", "e")
    b += txt(764, 536, "evaluator feature the second movement category cost.", "e")

    b += txt(44, 722,
             "A frame with no reading changes nothing in either machine. The "
             "state is held rather than guessed, because a dropped frame must "
             "not count a repetition or end a hold.",
             "cap")
    return svg(W, H, b)
