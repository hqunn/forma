"""Run a recorded video through the shipped pose engine and the shipped rules.

Why this exists. Every number in a set record is produced on a handset, in one
pass, with no way to look at the frame it came from. When a reading is
surprising - a squat the user believes was deep reading as shallow - the record
can say what the value was and not whether the landmark was in the right place.
This replays a video through the same `movenet.tflite`, the same letterbox and
un-padding, and the same measurements, and draws the result on the frame.

What it is not. The handset runs the TFLite GPU delegate; this runs the CPU
interpreter. Same model file and same weights, so the landmarks should agree
closely, but a small numerical difference between the two is expected and is
the first thing to suspect if a figure here disagrees with the device by a
little. Nothing in this file may be cited as a device measurement.

Usage:
    ml/.venv/Scripts/python.exe ml/replay.py <video> <outdir> [--stride N]
"""

import argparse
import csv
import json
import math
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
MODEL = os.path.join(REPO, "app", "benchmark", "app", "src", "main", "assets", "movenet.tflite")
SPECS = os.path.join(REPO, "docs", "specs")

SIDE = 192
COCO = [
    "NOSE", "LEFT_EYE", "RIGHT_EYE", "LEFT_EAR", "RIGHT_EAR",
    "LEFT_SHOULDER", "RIGHT_SHOULDER", "LEFT_ELBOW", "RIGHT_ELBOW",
    "LEFT_WRIST", "RIGHT_WRIST", "LEFT_HIP", "RIGHT_HIP",
    "LEFT_KNEE", "RIGHT_KNEE", "LEFT_ANKLE", "RIGHT_ANKLE",
]
IDX = {n: i for i, n in enumerate(COCO)}
BONES = [(5, 6), (5, 7), (7, 9), (6, 8), (8, 10), (5, 11), (6, 12),
         (11, 12), (11, 13), (13, 15), (12, 14), (14, 16)]
PRESENCE_FLOOR = 0.3


# ---------------------------------------------------------------- video

def probe(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height,r_frame_rate:stream_side_data=rotation",
         "-of", "json", path],
        capture_output=True, text=True, check=True).stdout
    s = json.loads(out)["streams"][0]
    num, den = s["r_frame_rate"].split("/")
    rot = 0
    for sd in s.get("side_data_list", []):
        if "rotation" in sd:
            rot = int(sd["rotation"])
    return s["width"], s["height"], float(num) / float(den), rot


def display_size(w, h, rot):
    """What ffmpeg actually emits.

    ffmpeg applies the display matrix by default, so a clip stored landscape
    with a rotation flag comes out of the pipe already upright and with its
    dimensions swapped. Reading it at the stored size reshapes the bytes
    transposed: the frame count and the byte total both still look right, and
    every frame is scrambled. The 22 November ten-repetition clip was stored
    1920x1080 with rotation -90 and produced no pose at all until this.
    """
    return (h, w) if rot in (90, -90, 270, -270) else (w, h)


def frames(path, width, height):
    """Decoded frames as uint8 RGB arrays, already in display orientation."""
    p = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-i", path, "-f", "rawvideo",
         "-pix_fmt", "rgb24", "-"],
        stdout=subprocess.PIPE)
    n = width * height * 3
    while True:
        buf = p.stdout.read(n)
        if len(buf) < n:
            break
        yield np.frombuffer(buf, np.uint8).reshape(height, width, 3)
    p.stdout.close()
    p.wait()


# ---------------------------------------------------------------- engine

class Engine:
    """Mirrors PoseEngine and FrameAdapter in app/forma."""

    def __init__(self, model=MODEL):
        from tensorflow.lite.python.interpreter import Interpreter
        self.it = Interpreter(model_path=model)
        self.it.allocate_tensors()
        self.inp = self.it.get_input_details()[0]["index"]
        self.out = self.it.get_output_details()[0]["index"]

    def letterbox(self, w, h):
        scale = SIDE / max(w, h)
        return scale, (SIDE - w * scale) / 2.0, (SIDE - h * scale) / 2.0

    def __call__(self, rgb):
        h, w = rgb.shape[:2]
        scale, pad_x, pad_y = self.letterbox(w, h)
        im = Image.fromarray(rgb).resize(
            (max(1, round(w * scale)), max(1, round(h * scale))), Image.BILINEAR)
        square = Image.new("RGB", (SIDE, SIDE), (0, 0, 0))
        square.paste(im, (round(pad_x), round(pad_y)))
        x = np.asarray(square, np.float32)[None] / 255.0
        self.it.set_tensor(self.inp, x)
        self.it.invoke()
        kp = self.it.get_tensor(self.out)[0][0]        # (17, 3) as row, col, score

        pts = []
        for row, col, score in kp:
            px, py = col * SIDE, row * SIDE
            # Cast off numpy's float32: it propagates through every
            # measurement and is not JSON serialisable at the far end.
            pts.append((
                float(min(1.0, max(0.0, (px - pad_x) / scale / w))),
                float(min(1.0, max(0.0, (py - pad_y) / scale / h))),
                float(score),
            ))
        found = any(p[2] >= PRESENCE_FLOOR for p in pts)
        return pts, found


# ---------------------------------------------------------------- rules

class Trigger:
    """Mirrors TriggerPolicy: the five gates, in the order the device applies
    them, and the reason a candidate failed."""

    def __init__(self, spec):
        t = spec["trigger"]
        self.floor = t["confidence_floor"]
        self.debounce = t.get("debounce_ms", 0)
        self.gap = t["min_gap_ms"]
        self.cap = t["max_cues_per_set"]
        self.same = t["max_consecutive_same_cue"]
        self.reset_ms = t.get("same_cue_reset_ms", 2000)
        self.last_raised = {}
        self.rank = {c: i for i, c in enumerate(t["priority"])}
        self.cues = spec["cues"]
        self.last_at = None
        self.last_code = None
        self.consecutive = 0
        self.spoken = 0
        self.why = {}

    def _no(self, codes, reason):
        for c in codes:
            self.why[reason] = self.why.get(reason, 0) + 1
        return None

    def select(self, raised, now_ms, confidence):
        """raised is a list of (code, since_ms). Returns a code or None."""
        # A fault absent for the cooldown is a new episode, so the
        # "already said twice" counter starts again for it.
        for code, _ in raised:
            seen = self.last_raised.get(code)
            if code == self.last_code and seen is not None and now_ms - seen >= self.reset_ms:
                self.consecutive = 0
            self.last_raised[code] = now_ms

        if not raised:
            return None
        if confidence < self.floor:
            return self._no([c for c, _ in raised], "confidence below floor")
        if self.cap is not None and self.spoken >= self.cap:
            return self._no([c for c, _ in raised], "set cue budget exhausted")
        if self.last_at is not None and now_ms - self.last_at < self.gap:
            return self._no([c for c, _ in raised], "inside minimum gap")
        for code, since in sorted(raised, key=lambda x: self.rank.get(x[0], 99)):
            if now_ms - since < self.debounce:
                self.why["below debounce"] = self.why.get("below debounce", 0) + 1
                continue
            if self.same is not None and code == self.last_code and self.consecutive >= self.same:
                self.why["already said twice"] = self.why.get("already said twice", 0) + 1
                continue
            self.consecutive = self.consecutive + 1 if code == self.last_code else 1
            self.last_code = code
            self.last_at = now_ms
            self.spoken += 1
            return code
        return None


def violates(op, v, t):
    return {"gt": v > t, "gte": v >= t, "lt": v < t, "lte": v <= t}[op]


def pt(pts, name, min_vis):
    x, y, v = pts[IDX[name]]
    return None if v < min_vis else (x, y, v)


def mirror(name):
    if name.startswith("LEFT_"):
        return "RIGHT_" + name[5:]
    if name.startswith("RIGHT_"):
        return "LEFT_" + name[6:]
    return name


def angle(a, b, c):
    v1 = (a[0] - b[0], a[1] - b[1])
    v2 = (c[0] - b[0], c[1] - b[1])
    n1, n2 = math.hypot(*v1), math.hypot(*v2)
    if n1 == 0 or n2 == 0:
        return 0.0
    cos = max(-1.0, min(1.0, (v1[0] * v2[0] + v1[1] * v2[1]) / (n1 * n2)))
    return math.degrees(math.acos(cos))


def angle_from_vertical(a, b):
    return math.degrees(math.atan2(abs(b[0] - a[0]), abs(b[1] - a[1])))


def dist(a, b):
    return math.hypot(b[0] - a[0], b[1] - a[1])


def signed_offset_from_line(p, a, b):
    """Mirrors Measurements.signedOffsetFromLine, sign convention included."""
    dx, dy = b[0] - a[0], b[1] - a[1]
    ln = math.hypot(dx, dy)
    if ln == 0:
        return 0.0
    cross = dx * (p[1] - a[1]) - dy * (p[0] - a[0])
    signed = cross / ln
    return -signed if dx < 0 else signed


def normaliser(name, pts, min_vis, flip):
    """Mirrors Normalisers. foot_length is gone with COCO-17."""
    side = "RIGHT" if flip else "LEFT"
    if name == "shoulder_ankle_length":
        need = [side + "_SHOULDER", side + "_ANKLE"]
    elif name == "shoulder_width":
        need = ["LEFT_SHOULDER", "RIGHT_SHOULDER"]
    elif name == "hip_width":
        need = ["LEFT_HIP", "RIGHT_HIP"]
    elif name == "torso_length":
        need = ["LEFT_SHOULDER", "RIGHT_SHOULDER", "LEFT_HIP", "RIGHT_HIP"]
    else:
        raise ValueError("unknown normaliser '%s'" % name)
    q = [pt(pts, n, min_vis) for n in need]
    if any(x is None for x in q):
        return None
    if name == "torso_length":
        sh = ((q[0][0] + q[1][0]) / 2, (q[0][1] + q[1][1]) / 2)
        hp = ((q[2][0] + q[3][0]) / 2, (q[2][1] + q[3][1]) / 2)
        return dist(sh, hp)
    return dist(q[0], q[1])


def one_side(fn, args, pts, min_vis, flip):
    names = [mirror(n) for n in args] if flip else list(args)
    p = [pt(pts, n, min_vis) for n in names]
    if any(q is None for q in p):
        return None
    if fn == "angle":
        return angle(p[0], p[1], p[2])
    if fn == "y_delta":
        return p[0][1] - p[1][1]
    if fn == "angle_from_vertical":
        return angle_from_vertical(p[0], p[1])
    if fn == "signed_angle_between_segments":
        return angle_from_vertical(p[0], p[1]) - angle_from_vertical(p[2], p[3])
    if fn == "signed_offset_from_line":
        return signed_offset_from_line(p[0], p[1], p[2])
    if fn == "dist":
        return dist(p[0], p[1])
    if fn == "mean_y":
        return sum(q[1] for q in p) / len(p)
    raise ValueError("replay does not implement '%s'" % fn)


def _side(m, pts, min_vis, flip):
    v = one_side(m["fn"], m["args"], pts, min_vis, flip)
    if v is None:
        return None
    scale = m.get("normalise_by")
    if scale is None:
        return v
    den = normaliser(scale, pts, min_vis, flip)
    if den is None or den == 0:
        return None
    return v / den


def measure(m, pts, min_vis):
    """Returns (combined, declared, mirrored), mirroring Measurements.explain."""
    d = _side(m, pts, min_vis, False)
    if m.get("aggregate") != "mean_of_sides":
        return d, d, None
    r = _side(m, pts, min_vis, True)
    if d is not None and r is not None:
        return (d + r) / 2.0, d, r
    return (d if d is not None else r), d, r


class Segmenter:
    """Mirrors Segmenter for both movement shapes, with absolute bounds.

    A hold is the same machine with the entry comparison reversed: the plank
    is entered when the shoulder-to-hip line rises past 65 degrees from
    vertical and left when it falls under 50.
    """

    def __init__(self, spec):
        rd = spec["rep_driver"]
        self.rising = rd["direction"] == "increasing_is_entry"
        self.is_hold = rd["reports"]["type"] == "duration"
        self.held = rd["reports"].get("phase")
        self.idle_name = spec["phases"][0]["name"]
        names = [p["name"] for p in spec["phases"]]
        if not self.is_hold:
            self.idle_name, self.desc, self.bot, self.asc = names[:4]
        else:
            self.idle_name = next(n for n in names if n != self.held)
        self.enter = rd["hysteresis"]["descent_enter"]["value"]
        self.exit = rd["hysteresis"]["ascent_exit"]["value"]
        self.win = rd["smoothing"]["window_frames"]
        self.min_ms, self.max_ms = rd["min_rep_ms"], rd["max_rep_ms"]
        self.buf, self.prev, self.engaged = [], None, False
        self.extreme, self.start_ms, self.reps = None, 0, 0
        # A hold is counted frame by frame, not as the span from entry to
        # exit: the wall clock keeps running when the subject leaves the
        # frame but the hold does not. seg_ms is the hold under way and
        # last_tick is the last frame that contributed to it, cleared
        # whenever the driver cannot be read. Mirrors Segmenter.kt.
        self.hold_ms, self.seg_ms, self.last_tick = 0, 0, None

    STILL = 1.0
    BAND = 10.0

    def __call__(self, value, t_ms):
        if value is None:
            held = self.held if self.is_hold else self.bot
            self.last_tick = None
            return {"phase": held if self.engaged else self.idle_name,
                    "driver": None, "engaged": self.engaged,
                    "rep_completed": False, "reps": self.reps,
                    "hold_ms": self.hold_ms + (self.seg_ms if self.engaged else 0)}
        self.buf.append(value)
        self.buf = self.buf[-self.win:]
        s = sorted(self.buf)[len(self.buf) // 2]
        was = self.engaged
        done = False

        if self.rising:
            if self.engaged and self.last_tick is not None:
                self.seg_ms += t_ms - self.last_tick
            if not self.engaged and s >= self.enter:
                self.engaged, self.start_ms, self.seg_ms = True, t_ms, 0
            elif self.engaged and s < self.exit:
                if self.seg_ms >= self.min_ms:
                    self.hold_ms += self.seg_ms
                self.seg_ms = 0
                self.engaged = False
            self.last_tick = t_ms if self.engaged else None
        else:
            if not self.engaged and s < self.enter:
                self.engaged, self.start_ms, self.extreme = True, t_ms, s
            elif self.engaged:
                self.extreme = min(self.extreme, s)
                if s >= self.exit:
                    if self.min_ms <= (t_ms - self.start_ms) <= self.max_ms:
                        self.reps += 1
                        done = True
                    self.engaged = False

        if self.is_hold:
            phase = self.held if self.engaged else self.idle_name
            live = self.hold_ms + (self.seg_ms if self.engaged else 0)
            self.prev = s
            return {"phase": phase, "driver": s, "engaged": self.engaged,
                    "rep_completed": False, "reps": self.reps, "hold_ms": live}

        phase = self.idle_name
        if self.engaged:
            if self.prev is None or not was:
                phase = self.desc
            else:
                d = s - self.prev
                if d < -self.STILL:
                    phase = self.desc
                elif d > self.STILL:
                    phase = self.asc
                elif s <= self.extreme + self.BAND:
                    phase = self.bot
                else:
                    phase = self.asc
        self.prev = s
        return {"phase": phase, "driver": s, "engaged": self.engaged,
                "rep_completed": done, "reps": self.reps, "hold_ms": 0}


# ---------------------------------------------------------------- drawing

INK = (236, 240, 244)
DIM = (150, 166, 178)
OK = (95, 195, 150)
BAD = (232, 131, 106)
CYAN = (69, 182, 212)


def pt_raw(pts, name):
    return pts[IDX[name]]


def draw(rgb, pts, info, min_vis):
    im = Image.fromarray(rgb).convert("RGB")
    d = ImageDraw.Draw(im, "RGBA")
    W, H = im.size
    S = max(1.0, W / 540.0)

    def P(i):
        return pts[i][0] * W, pts[i][1] * H

    for a, b in BONES:
        lo = min(pts[a][2], pts[b][2])
        col = DIM if lo < min_vis else CYAN
        d.line([P(a), P(b)], fill=col + (200,), width=int(3 * S))
    for i, (x, y, v) in enumerate(pts):
        r = 5 * S if v >= min_vis else 3 * S
        col = CYAN if v >= min_vis else DIM
        d.ellipse([x * W - r, y * H - r, x * W + r, y * H + r],
                  fill=col + (235,), outline=(10, 18, 24, 220))

    # The shoulder-to-ankle line, which the two hip faults measure against,
    # and the knee-height line the squat's depth rule uses.
    sh, an = pt_raw(pts, "LEFT_SHOULDER"), pt_raw(pts, "LEFT_ANKLE")
    if sh and an:
        d.line([(sh[0] * W, sh[1] * H), (an[0] * W, an[1] * H)],
               fill=(120, 134, 146, 150), width=int(2 * S))
    hip, knee = IDX["LEFT_HIP"], IDX["LEFT_KNEE"]
    ky = pts[knee][1] * H
    d.line([(0, ky), (W, ky)], fill=(120, 134, 146, 120), width=int(1.5 * S))

    pad = int(10 * S)
    lines = ["frame %d   t %.2fs" % (info["i"], info["t"])]
    if info["is_hold"]:
        lines.append("phase %-9s hold %.1fs" % (info["phase"], info["hold"]))
    else:
        lines.append("phase %-9s rep %d" % (info["phase"], info["reps"]))
    lines.append("driver %s" % (
        "UNMEASURABLE" if info["driver"] is None else "%.1f deg" % info["driver"]))
    for f in info["faults"]:
        v = info["vals"][f["code"]]
        mark = "  <<" if f["code"] in info["live"] else ""
        thr = info["thresholds"].get(f["code"])
        lines.append("%-20s %8s  vs %+.3f%s" % (
            f["code"][:20], "-" if v is None else "%+.4f" % v, thr, mark))
    lines.append("L hip %.2f knee %.2f ank %.2f sh %.2f" % (
        pts[hip][2], pts[knee][2],
        pts[IDX["LEFT_ANKLE"]][2], pts[IDX["LEFT_SHOULDER"]][2]))
    lines.append("confidence %.2f   cues spoken %d" % (info["conf"], info["spoken"]))
    bh = int((len(lines) * 15 + 14) * S)
    d.rectangle([0, 0, int(300 * S), bh], fill=(8, 14, 19, 210))
    for n, line in enumerate(lines):
        d.text((pad, pad + n * 15 * S), line, fill=INK)

    if info["said"]:
        d.rectangle([0, H - int(64 * S), W, H], fill=(232, 102, 60, 235))
        d.text((pad, H - int(52 * S)), "CUE SPOKEN", fill=(255, 255, 255))
        d.text((pad, H - int(30 * S)), info["said"][2], fill=(255, 255, 255))
    elif info["live"]:
        d.rectangle([0, H - int(34 * S), int(340 * S), H], fill=(8, 14, 19, 210))
        d.text((pad, H - int(26 * S)), "raised: " + " ".join(info["live"])[:40], fill=BAD)
    return im


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("outdir")
    ap.add_argument("--stride", type=int, default=1,
                    help="write every Nth annotated frame; every frame is still measured")
    ap.add_argument("--spec", default="bodyweight-squat.json")
    a = ap.parse_args()

    spec = json.load(open(os.path.join(SPECS, a.spec), encoding="utf-8"))
    min_vis = spec["framing"]["min_visibility"]
    driver_m = spec["rep_driver"]["signal"]
    faults = spec["faults"]
    per_rep_faults = [f for f in faults if f.get("evaluate") == "per_rep"]
    continuous = [f for f in faults if f.get("evaluate") != "per_rep"]
    print("spec %s: %d faults (%d per repetition, %d continuous)"
          % (spec["id"], len(faults), len(per_rep_faults), len(continuous)))

    w, h, fps, rot = probe(a.video)
    w, h = display_size(w, h, rot)
    print("video %dx%d @ %.2f fps (display orientation, rotation flag %d)"
          % (w, h, fps, rot))
    if fps > 45:
        print("  NOTE: %.0f fps against the handset's ~30. The per-repetition "
              "verdict is unaffected; the driver sees twice the samples." % fps)
    if abs((w / h) - (720 / 1280)) > 0.02 and abs((h / w) - (720 / 1280)) > 0.02:
        print("  NOTE: aspect ratio differs from the handset's 720x1280 analysis frame.")

    os.makedirs(a.outdir, exist_ok=True)
    fdir = os.path.join(a.outdir, "frames")
    os.makedirs(fdir, exist_ok=True)

    eng = Engine()
    seg = Segmenter(spec)
    rows = []
    no_pose = 0
    samples = {f["code"]: [] for f in per_rep_faults}
    streak = {f["code"]: 0 for f in continuous}
    since = {}
    trig = Trigger(spec)
    cues = []            # (frame, t, code)
    required = spec["framing"]["required_visible"]
    raised_frames = {f["code"]: 0 for f in faults}
    per_rep = []

    for i, rgb in enumerate(frames(a.video, w, h)):
        t = i / fps
        pts, found = eng(rgb)
        if not found:
            # MoveNet answers on every frame whatever is in shot, so an empty
            # room is detected by the scores and not by the absence of a
            # result. Same gate as FrameAdapter.
            no_pose += 1
            continue

        dv, _, _ = measure(driver_m, pts, min_vis)
        st = seg(dv, int(t * 1000))

        vals = {}
        for f in faults:
            vals[f["code"]] = measure(f["measure"], pts, min_vis)[0]

        # Continuous faults: mirrors FaultEvaluator. Scoped to their phases,
        # raised once min_frames consecutive frames violate.
        live = []
        for f in continuous:
            v = vals[f["code"]]
            if st["phase"] not in f["phases"] or v is None or \
                    not violates(f["operator"], v, f["threshold"]["value"]):
                streak[f["code"]] = 0
                continue
            streak[f["code"]] += 1
            if streak[f["code"]] >= f["min_frames"]:
                live.append(f["code"])
                raised_frames[f["code"]] += 1

        # The trigger, exactly as the device applies it. framingConfidence
        # takes the better of the two sides, which is what the measurements
        # use, so the harness does the same.
        ready = []
        for f in continuous:
            if f["code"] in live:
                since.setdefault(f["code"], t * 1000.0)
                ready.append((f["code"], since[f["code"]]))
            elif f["code"] in since:
                del since[f["code"]]
        conf = 0.0
        for nm in required:
            conf += max(pts[IDX[nm]][2], pts[IDX[mirror(nm)]][2])
        conf = conf / max(1, len(required))
        spoke = trig.select(ready, t * 1000.0, conf)
        if spoke:
            cues.append((i, t, spoke))

        # Per-repetition faults: mirrors AssessmentSession.bestOf. Every frame
        # of the repetition, then the min_frames-th best. Independent of the
        # driver, which goes unmeasurable whenever the ankle drops under the
        # floor at the bottom of a movement.
        for f in per_rep_faults:
            v = vals[f["code"]]
            if st["engaged"] and v is not None:
                samples[f["code"]].append((v, i, t))
        if st["rep_completed"]:
            row = {"rep": st["reps"]}
            for f in per_rep_faults:
                xs = samples[f["code"]]
                n = f["min_frames"]
                if len(xs) >= n:
                    passing_larger = f["operator"] in ("lt", "lte")
                    xs = sorted(xs, key=lambda z: -z[0] if passing_larger else z[0])
                    v, fi, ft = xs[n - 1]
                    row[f["code"]] = v
                    row["t"] = ft
                    row["frame"] = fi
                    row["raised"] = row.get("raised", [])
                    if violates(f["operator"], v, f["threshold"]["value"]):
                        row["raised"].append(f["code"])
                row["samples"] = len(samples[f["code"]])
                samples[f["code"]] = []
            per_rep.append(row)

        rows.append({
            "frame": i, "t": round(t, 3), "phase": st["phase"],
            "driver": None if st["driver"] is None else round(st["driver"], 2),
            "hold_s": round(st.get("hold_ms", 0) / 1000.0, 2),
            "rep": st["reps"],
            "raised": "|".join(live),
            **{f["code"]: (None if vals[f["code"]] is None
                           else round(vals[f["code"]], 5)) for f in faults},
            **{"s_" + COCO[k].lower(): round(pts[k][2], 3) for k in
               (IDX["LEFT_HIP"], IDX["LEFT_KNEE"], IDX["LEFT_ANKLE"],
                IDX["LEFT_SHOULDER"], IDX["RIGHT_HIP"], IDX["RIGHT_KNEE"])},
        })

        if i % a.stride == 0:
            info = dict(i=i, t=t, phase=st["phase"], driver=st["driver"],
                        reps=st["reps"], hold=st.get("hold_ms", 0) / 1000.0,
                        vals=vals, live=live, faults=faults,
                        is_hold=seg.is_hold, conf=conf, spoken=len(cues),
                        said=(cues[-1] if cues and i - cues[-1][0] < 45 else None),
                        thresholds={f["code"]: f["threshold"]["value"] for f in faults})
            draw(rgb, pts, info, min_vis).save(
                os.path.join(fdir, "f%05d.jpg" % i), quality=86)

    if not rows:
        print("no frame contained a pose above the presence floor: "
              "%d frames rejected. Nothing to measure." % no_pose)
        return 1

    with open(os.path.join(a.outdir, "frames.csv"), "w", newline="", encoding="utf-8") as f:
        wtr = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        wtr.writeheader()
        wtr.writerows(rows)

    # seg.hold_ms only commits when a hold ends, so a clip that stops while the
    # subject is still in position reports 0. The last frame's live value is
    # the one that is correct either way. The device does this already, in
    # Segmenter.currentHoldMs; only this harness had the gap.
    final_hold_ms = rows[-1]["hold_s"] * 1000.0 if rows else 0.0

    summary = {"video": os.path.basename(a.video), "width": w, "height": h,
               "fps": fps, "rotation": rot, "frames": len(rows),
               "reps": seg.reps, "hold_s": round(final_hold_ms / 1000.0, 2),
               "per_rep": per_rep, "frames_without_pose": no_pose,
               "spec": spec["id"], "min_visibility": min_vis,
               "continuous_raised_frames": raised_frames,
               "cues_spoken": [{"frame": c[0], "t": round(c[1], 2), "code": c[2]}
                               for c in cues],
               "suppressed": trig.why,
               "note": "CPU TFLite interpreter, not the handset GPU delegate."}
    json.dump(summary, open(os.path.join(a.outdir, "summary.json"), "w"), indent=2)

    print("frames measured: %d   without a pose: %d   repetitions: %d   hold %.1fs"
          % (len(rows), no_pose, seg.reps, final_hold_ms / 1000.0))
    for f in continuous:
        n = raised_frames[f["code"]]
        print("  %-24s raised on %d frames (%.0f%% of the set)"
              % (f["code"], n, 100.0 * n / max(1, len(rows))))
    print("  CUES SPOKEN: %d" % len(cues))
    for c in cues:
        print("     t=%6.2fs   %s" % (c[1], c[2]))
    if trig.why:
        print("  suppressed:", trig.why)
    for r in per_rep:
        bits = ["rep %d" % r["rep"]]
        for f in per_rep_faults:
            if f["code"] in r:
                bits.append("%s %+.4f" % (f["code"], r[f["code"]]))
        bits.append(",".join(r.get("raised", [])) or "pass")
        print("  " + "   ".join(bits))


if __name__ == "__main__":
    sys.exit(main() or 0)
