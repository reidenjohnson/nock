"""
Bench for recognizing a saved target by the outline finder (TargetGuess.findQuad + TargetMemory.byOutline), on a frame
the app saved (adb shell run-as com.nock touch files/dump_look -> files/look.jpg).

Usage: python tools/outline_match.py <frame.jpg> <faces dir> <face id> [exclude x0,y0,x1,y1]
Prints every outline candidate (targetness, area) and the picture match of the saved target on the chosen one.
"""
import json
import sys

import cv2
import numpy as np

TPL = 96
MIN_TARGETNESS = 0.8


def order(p):
    p = np.float32(p)
    tl = p[np.argmin(p.sum(1))]; br = p[np.argmax(p.sum(1))]
    tr = p[np.argmin(p[:, 1] - p[:, 0])]; bl = p[np.argmax(p[:, 1] - p[:, 0])]
    return np.float32([tl, tr, br, bl])


def find_quads(bgr, exclude=None):
    s = 960.0 / max(bgr.shape[:2])
    small = cv2.resize(bgr, None, fx=s, fy=s, interpolation=cv2.INTER_AREA)
    g = cv2.GaussianBlur(cv2.cvtColor(small, cv2.COLOR_BGR2GRAY), (5, 5), 0)
    h, w = g.shape
    hsv = cv2.cvtColor(small, cv2.COLOR_BGR2HSV)
    colour = cv2.inRange(hsv, (0, 110, 90), (180, 255, 255))
    edges = cv2.Canny(g, 60, 160)

    def targetness(pts):
        c = pts.mean(0); inner = c + (pts - c) * 0.9
        m = np.zeros_like(g); cv2.fillPoly(m, [inner.astype(np.int32)], 255)
        n = max(1, cv2.countNonZero(m))
        sat = cv2.countNonZero(colour & m) / n; ed = cv2.countNonZero(edges & m) / n
        _, sd = cv2.meanStdDev(g, mask=m)
        return 3 * sat + 6 * ed + sd[0][0] / 60

    out = []
    for lo, hi in [(30, 90), (50, 150), (80, 200)]:
        e = cv2.dilate(cv2.Canny(g, lo, hi), np.ones((3, 3), np.uint8))
        cs, _ = cv2.findContours(e, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        for c in cs:
            a = cv2.contourArea(c)
            if a < 0.02 * w * h or a > 0.9 * w * h:
                continue
            ap = cv2.approxPolyDP(c, 0.02 * cv2.arcLength(c, True), True).reshape(-1, 2).astype(np.float32)
            if len(ap) != 4 or not cv2.isContourConvex(ap):
                continue
            cx, cy = ap.mean(0)
            if exclude is not None and exclude[0] <= cx / s <= exclude[2] and exclude[1] <= cy / s <= exclude[3]:
                continue
            tn = targetness(ap)
            score = a * tn * (1 - 0.8 * np.hypot(cx - w / 2, cy - h / 2) / np.hypot(w / 2, h / 2))
            out.append((score, tn, a / (w * h), order(ap / s), (lo, hi)))
    return out


def flat(gray, quad):
    t = TPL
    m = cv2.getPerspectiveTransform(np.float32(quad), np.float32([[0, 0], [t, 0], [t, t], [0, t]]))
    f = cv2.warpPerspective(gray, m, (t, t), flags=cv2.INTER_AREA)
    k = int(TPL * 0.06)
    c = cv2.GaussianBlur(f[k:t - k, k:t - k].astype(np.float32), (0, 0), 1.5)
    return (c - c.mean()) / (c.std() + 1e-6)


def main():
    frame = cv2.imread(sys.argv[1]); faces = sys.argv[2]; fid = sys.argv[3]
    ex = [float(v) for v in sys.argv[4].split(",")] if len(sys.argv) > 4 else None
    face = json.load(open(f"{faces}/{fid}.json", encoding="utf-8"))
    ref = cv2.cvtColor(cv2.imread(f"{faces}/{fid}.jpg"), cv2.COLOR_BGR2GRAY)
    rq = np.float32(face["outline"])
    fr = flat(ref, rq)
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    qs = sorted(find_quads(frame, ex), key=lambda q: -q[0])
    print(f"{len(qs)} candidates (targetness cut-off {MIN_TARGETNESS})")
    for sc, tn, af, q, th in qs[:15]:
        best = max(float((fr * flat(gray, np.roll(q, -k, 0))).mean()) for k in range(4))
        print(f"tn {tn:.2f}  area {af:.3f}  canny {th}  centre {q.mean(0).round()}  picture {best:.2f}"
              + ("" if tn >= MIN_TARGETNESS else "  (below cut-off)"))


if __name__ == "__main__":
    main()
