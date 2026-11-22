# -*- coding: utf-8 -*-
"""Figure 3.2 - Use-case diagram, Module 2: History and profile.

The subject is again the mobile application, so the server appears as a
secondary actor. Only two of the five use cases reach it, which is what
offline-first means stated in notation rather than in prose.
"""
from kit import *

NAME = "fig-3-2-use-case-module-2"
RX, RY = 114, 41


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
    W, H = 1000, 724
    b = ""

    b += '  <rect x="250" y="64" width="500" height="590" rx="8" class="reg"/>\n'
    b += txt(500, 92, "FORMA mobile application", "h", "middle")
    b += txt(500, 112, "Module 2: History and profile", "s", "middle")

    UC06 = (500, 172)
    UC07 = (500, 280)
    UC08 = (500, 388)
    UC09 = (500, 496)
    UC10 = (500, 604)

    ax, ay = 130, 388
    b += actor(ax, 348, "")
    b += txt(ax, 458, "Trainee", "tb", "middle")

    sx, sy, sw, sh = 800, 350, 160, 76
    b += f'  <rect x="{sx}" y="{sy}" width="{sw}" height="{sh}" rx="3" class="boxp"/>\n'
    b += txt(sx + sw / 2, sy + 30, "«actor»", "s", "middle")
    b += txt(sx + sw / 2, sy + 54, "FORMA server", "tb", "middle")

    for c in (UC06, UC07, UC08, UC09):
        b += assoc(ax, ay, c[0], c[1], RX, RY)
    for c in (UC06, UC10):
        b += assoc(sx, sy + sh / 2, c[0], c[1], RX, RY)

    b += oval(UC06, "UC-06", "Sign in or", "create an account")
    b += oval(UC07, "UC-07", "Browse session history")
    b += oval(UC08, "UC-08", "View a session in detail")
    b += oval(UC09, "UC-09", "Delete a session")
    b += oval(UC10, "UC-10", "Synchronise with", "the profile")

    b += txt(250, 686,
             "The server is drawn as a classifier with the «actor» keyword "
             "because the subject of the diagram is the application, not the whole", "cap")
    b += txt(250, 704,
             "product. UC-07 to UC-09 reach no actor but the trainee: history is "
             "readable, and deletable, with no account and no connection.", "cap")
    return svg(W, H, b)
