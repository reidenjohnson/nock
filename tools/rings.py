"""Ring-face centres anywhere in a frame (port of TargetGuess.anyRings). Usage: python tools/rings.py <frame.jpg> [x0,y0,x1,y1]"""
import sys
import cv2
import numpy as np

def rings(bgr, quad=None, canny=((30, 90), (60, 160)), min_frac=0.0005, verbose=False):
    g = cv2.GaussianBlur(cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY), (5, 5), 0)
    h, w = g.shape
    quad = quad if quad is not None else np.int32([[0, 0], [w, 0], [w, h], [0, h]])
    inside = np.zeros_like(g); cv2.fillPoly(inside, [np.int32(quad)], 255)
    areaQ = cv2.contourArea(np.float32(quad))
    circles = []
    for lo, hi in canny:
        e = cv2.dilate(cv2.Canny(g, lo, hi) & inside, np.ones((3, 3), np.uint8))
        cs, _ = cv2.findContours(e, cv2.RETR_LIST, cv2.CHAIN_APPROX_NONE)
        for c in cs:
            if len(c) < 20: continue
            a = cv2.contourArea(c)
            if a < min_frac * areaQ: continue
            if len(c) < 5: continue
            (cx, cy), (ax1, ax2), _ = cv2.fitEllipse(c)  # a ring seen at an angle is an ellipse
            A, B = max(ax1, ax2) / 2, min(ax1, ax2) / 2
            if B < 0.35 * A: continue                       # flatter than ~70 deg off face-on
            fill = a / (np.pi * A * B + 1e-6)
            if not 0.85 < fill < 1.15: continue             # a smooth closed oval, not a blob or a corner
            circles.append((cx, cy, np.sqrt(A * B)))
    faces = []
    for c in sorted(circles, key=lambda c: -c[2]):
        f = next((f for f in faces if np.hypot(f[0][0] - c[0], f[0][1] - c[1]) < 0.15 * f[1][0]), None)
        if f is None: faces.append(((c[0], c[1]), [c[2]]))
        elif all(abs(c[2] - r) > 0.06 * f[1][0] for r in f[1]): f[1].append(c[2])
    if verbose: print(len(circles), "circles;", [(tuple(np.round(f[0])), [round(r) for r in f[1]]) for f in faces])
    return [f for f in faces if len(f[1]) >= 2]

if __name__ == "__main__":
    im = cv2.imread(sys.argv[1])
    q = None
    if len(sys.argv) > 2:
        x0, y0, x1, y1 = map(int, sys.argv[2].split(",")); q = [[x0, y0], [x1, y0], [x1, y1], [x0, y1]]
    print(rings(im, q, verbose=True))
