# -*- coding: utf-8 -*-
"""Figure 7.1 - Class diagram of the two client modules.

Drawn with the module boundary visible, because the boundary is the design
claim: every type that decides anything about the movement is on the left,
where no Android type can be referenced, and the right holds only conversion,
sequencing and timing. The single edge between the modules points right to
left, which is the direction the build permits.
"""
from kit import *

NAME = "fig-7-1-classes"

W, H = 1180, 870


def build():
    boxes, edges = [], ""

    def put(x, y, w, name, rows, stereo=None):
        svg_, h = uml_class(x, y, w, name, stereo=stereo, rows=rows)
        boxes.append(svg_)
        return (x, y, w, h)

    def mid(p):
        return p[0] + p[2] / 2, p[1] + p[3] / 2

    def down(a, bb, cls="ln"):
        return line(mid(a)[0], a[1] + a[3], mid(bb)[0], bb[1], cls=cls)

    def right(a, bb, y, cls="ln"):
        return line(a[0] + a[2], y, bb[0], y, cls=cls)

    def left(a, bb, y, cls="ln"):
        return line(a[0], y, bb[0] + bb[2], y, cls=cls)

    # Module frames.
    frames = '  <rect x="36" y="56" width="600" height="776" rx="8" class="reg"/>\n'
    frames += txt(60, 84, "assessment", "h")
    frames += txt(60, 104, "Kotlin JVM. No Android type is reachable from here.", "s")
    frames += '  <rect x="668" y="56" width="476" height="776" rx="8" class="reg"/>\n'
    frames += txt(692, 84, "app", "h")
    frames += txt(692, 104, "Android. Converts, sequences and times. Judges nothing.", "s")

    # Assessment, left column: the specification as loaded.
    SPEC = put(60, 132, 262, "ExerciseSpec", [
        "landmarks, phases", "repDriver, smoothing",
        "faults, cues, trigger", "framing, excludedFaults",
    ], stereo="data")
    LOAD = put(60, 316, 262, "SpecLoader", [
        "load(text): ExerciseSpec", "11 validation rules",
    ], stereo="object")
    MEAS = put(60, 452, 262, "Measurements", [
        "evaluate(m, frame): Double?", "explain(m, frame)",
        "eight functions",
    ], stereo="object")
    LAND = put(60, 620, 262, "Landmarks", [
        "indexOf(name): Int", "mirror(name): String",
    ], stereo="object")

    # Assessment, right column: the per-set machinery.
    SESS = put(356, 132, 262, "AssessmentSession", [
        "onFrame(frame): SessionUpdate", "framingSatisfied(frame)",
        "startNewSet()", "faultLog",
    ])
    SEG = put(356, 300, 262, "Segmenter", [
        "onFrame(value, t)", ": SegmentUpdate",
    ])
    EVAL = put(356, 420, 262, "FaultEvaluator", [
        "onFrame(frame, phase)", ": List<RaisedFault>",
    ])
    TRIG = put(356, 540, 262, "TriggerPolicy", [
        "select(raised, now, conf)", ": SpokenCue?", "suppressed",
    ])
    CAL = put(356, 690, 262, "RepOutcome", [
        "rep, extremes", "raised: Set<String>",
    ], stereo="data")

    # The Android module.
    VM = put(692, 132, 262, "SessionViewModel", [
        "begin(context, spec)", "onFrame(bitmap, rotation)",
        "startSet() / endSet()", "state: AppState",
    ])
    ENG = put(692, 316, 262, "PoseEngine", [
        "submit(bitmap, t, rotation)", "close()",
    ])
    ADA = put(692, 436, 262, "FrameAdapter", [
        "fromReadings(...): Frame", "letterbox(), unpad()",
    ], stereo="object")
    CUE = put(692, 596, 262, "CuePlayer", [
        "preload(assets)", "play(asset): Long?",
    ])
    NAR = put(692, 716, 262, "Narrator, StageTones", [
        "say(asset): Boolean", "framed(), ready(), ...",
    ])

    # What one frame does inside the assessment module, in order.
    edges += down(SESS, SEG)
    edges += down(SEG, EVAL)
    edges += down(EVAL, TRIG)

    # What those four read.
    edges += right(SPEC, SESS, 180, cls="lnd")
    edges += right(LOAD, SEG, 346, cls="lnd")
    edges += right(MEAS, EVAL, 482, cls="lnd")
    edges += line(mid(LAND)[0], LAND[1], mid(MEAS)[0], MEAS[1] + MEAS[3], cls="lnd")

    # Sequencing inside the Android module.
    edges += down(VM, ENG)
    edges += down(ENG, ADA)
    edges += down(ADA, CUE)
    edges += down(CUE, NAR)

    # The one edge between the modules, pointing from the dependent module to
    # the one it depends on, which is right to left. There is no edge in the
    # other direction: nothing in assessment can name an Android type.
    edges += left(VM, SESS, 180, cls="lnd")
    edges += txt(652, 170, "uses", "s", "middle")

    # The session emits one of these at every closing boundary.
    edges += line(mid(SESS)[0], SESS[1] + SESS[3], mid(CAL)[0], CAL[1], cls="lnd")

    b = frames + edges + "".join(boxes)
    b += txt(590, 856,
             "Solid: what one frame does, in the order drawn.  Dashed: uses.  "
             "Both edges crossing the module boundary point left, and the "
             "build makes the reverse impossible.",
             "cap", "middle")
    return svg(W, H, b)
