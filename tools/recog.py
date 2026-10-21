"""
Target recognition bench: today's matcher (TargetMemory.kt) vs coarse-to-fine, on his saved target photos.

Ground truth:
  * synthetic: each main photo warped by a known homography (smaller = further away, tilted, darker, blurred),
    so the true outline is exact;
  * real: views he lined up by hand (by_hand in the json) = his corners.
Error = mean corner distance of the outline, in % of the target's width (the bullseye lines ride on the same mapping).

Usage: python tools/recog.py <faces dir>   (a copy pulled from the phone, e.g. backups/.../files/faces)
"""
import glob
import json
import os
import sys

import cv2
import numpy as np

SCALE = 0.5
RATIO = 0.75
MIN_INLIERS = 60
NORM = 480          # refine: the target is compared at this many pixels on its long side
rng = np.random.default_rng(7)
orb = cv2.ORB_create(3000)
orb_fine = cv2.ORB_create(1500)
bf = cv2.BFMatcher(cv2.NORM_HAMMING)


def area(face):
    if face.get("outline"):
        return np.float32(face["outline"])
    s = np.float32(face["spots"])
    x0, y0 = s.min(0); x1, y1 = s.max(0); mx, my = (x1 - x0) / 2, (y1 - y0) / 2
    return np.float32([[x0 - mx, y0 - my], [x1 + mx, y0 - my], [x1 + mx, y1 + my], [x0 - mx, y1 + my]])


def feats(gray_full, quad=None):
    small = cv2.resize(gray_full, None, fx=SCALE, fy=SCALE, interpolation=cv2.INTER_AREA)
    mask = None
    if quad is not None:
        mask = np.zeros(small.shape, np.uint8); cv2.fillPoly(mask, [np.int32(quad * SCALE)], 255)
    return orb.detectAndCompute(small, mask)


def match(ref, live, min_n=MIN_INLIERS):
    (rk, rd), (lk, ld) = ref, live
    if rd is None or ld is None or len(rd) < 2 or len(ld) < 2:
        return None
    good = [m[0] for m in bf.knnMatch(rd, ld, k=2) if len(m) == 2 and m[0].distance < RATIO * m[1].distance]
    if len(good) < min_n:
        return None
    a = np.float32([rk[m.queryIdx].pt for m in good]); b = np.float32([lk[m.trainIdx].pt for m in good])
    h, mask = cv2.findHomography(a, b, cv2.RANSAC, 4.0)
    if h is None or mask.sum() < MIN_INLIERS:
        return None
    return h, int(mask.sum())


orb_ref = cv2.ORB_create(3000, nlevels=12)


def ref_feats(gray_full, quad):
    """Reference features at a fixed size: the target cropped and scaled so its long side is NORM px (a small target
    gets enlarged, so it has as much detail as a big one), with points returned in half-size photo coords."""
    n, size = norm_frame(quad)
    img = cv2.warpPerspective(gray_full, n, size, flags=cv2.INTER_LINEAR)
    mask = np.zeros(img.shape, np.uint8); cv2.fillPoly(mask, [np.int32(cv2.perspectiveTransform(quad[None], n)[0])], 255)
    k, d = orb_ref.detectAndCompute(img, mask)
    back = np.diag([SCALE, SCALE, 1.0]) @ np.linalg.inv(n)
    pts = cv2.perspectiveTransform(np.float32([p.pt for p in k])[None], back)[0] if k else []
    return [cv2.KeyPoint(float(x), float(y), 1) for x, y in pts], d


def live_feats(gray_full, full=False):
    if not full:
        return feats(gray_full)
    k, d = orb.detectAndCompute(gray_full, None)
    return [cv2.KeyPoint(p.pt[0] * SCALE, p.pt[1] * SCALE, 1) for p in k], d


def match_rep(ref, live, k=3, min_n=MIN_INLIERS):
    """Repeating prints (a 3-spot face has three of everything): no ratio test, each reference point offers its k best
    look-alikes and RANSAC keeps the ones that agree on one placement."""
    (rk, rd), (lk, ld) = ref, live
    if rd is None or ld is None or len(rd) < 2 or len(ld) < 2:
        return None
    ms = [m for ks in bf.knnMatch(rd, ld, k=k) for m in ks if m.distance < 64]
    if len(ms) < min_n:
        return None
    a = np.float32([rk[m.queryIdx].pt for m in ms]); b = np.float32([lk[m.trainIdx].pt for m in ms])
    h, mask = cv2.findHomography(a, b, cv2.USAC_MAGSAC, 2.0, maxIters=5000, confidence=0.999)
    if h is None:
        return None
    # one live point can't be two reference points: count unique live points among inliers
    inl = {ms[i].trainIdx for i in np.flatnonzero(mask.ravel())}
    if len(inl) < min_n:
        return None
    return h, len(inl)


def to_full(h_half):
    s = np.diag([SCALE, SCALE, 1.0]); return np.linalg.inv(s) @ h_half @ s


def norm_frame(quad):
    """Reference crop frame: the target's bounding box (+10%) scaled so its long side is NORM px. Returns full->norm."""
    x0, y0 = quad.min(0); x1, y1 = quad.max(0)
    m = 0.1 * max(x1 - x0, y1 - y0); x0 -= m; y0 -= m; x1 += m; y1 += m
    k = NORM / max(x1 - x0, y1 - y0)
    return np.array([[k, 0, -k * x0], [0, k, -k * y0], [0, 0, 1]]), (int(round((x1 - x0) * k)), int(round((y1 - y0) * k)))


def refine(ref_gray, quad, live_gray, h_full):
    """Warp the live photo into the reference's own (normalized) frame with the coarse mapping, match again there
    (same size, same perspective = many precise matches, only small moves allowed), return the corrected mapping."""
    n, size = norm_frame(quad)
    ref_n = cv2.warpPerspective(ref_gray, n, size, flags=cv2.INTER_AREA)
    mask = np.zeros(ref_n.shape, np.uint8); cv2.fillPoly(mask, [np.int32(cv2.perspectiveTransform(quad[None], n)[0])], 255)
    # live -> norm: norm <- ref <- live  = n @ inv(h_full)
    w = n @ np.linalg.inv(h_full)
    live_n = cv2.warpPerspective(live_gray, w, size, flags=cv2.INTER_AREA)
    rk, rd = orb_fine.detectAndCompute(ref_n, mask)
    lk, ld = orb_fine.detectAndCompute(live_n, mask)
    if rd is None or ld is None or len(rd) < 8 or len(ld) < 8:
        return None
    good = []
    for m in bf.knnMatch(rd, ld, k=2):
        if len(m) < 2 or m[0].distance >= RATIO * m[1].distance:
            continue
        if np.hypot(*(np.subtract(rk[m[0].queryIdx].pt, lk[m[0].trainIdx].pt))) > 0.06 * NORM:
            continue  # aligned already: a real match barely moves
        good.append(m[0])
    if len(good) < 20:
        return None
    a = np.float32([rk[m.queryIdx].pt for m in good]); b = np.float32([lk[m.trainIdx].pt for m in good])
    r, mask = cv2.findHomography(a, b, cv2.RANSAC, 2.0)
    if r is None or mask.sum() < 20:
        return None
    # ref -> live = inv(w) @ r @ n
    return np.linalg.inv(w) @ r @ n, int(mask.sum())


def err(h, quad, truth):
    p = cv2.perspectiveTransform(quad[None], h)[0]
    width = np.linalg.norm(truth[1] - truth[0])
    return 100 * np.mean(np.linalg.norm(p - truth, axis=1)) / width


def synth(gray, quad, scale, tilt, dark):
    """A known view: shrink (further away), tilt, darken, blur. Returns (image, H full->image)."""
    hgt, wid = gray.shape[:2]
    c = quad.mean(0)
    src = np.float32([[0, 0], [wid, 0], [wid, hgt], [0, hgt]])
    d = tilt * wid
    dst = np.float32([[d, d * 0.5], [wid - d, -d * 0.3], [wid + d * 0.4, hgt], [-d * 0.2, hgt - d * 0.4]])
    t = cv2.getPerspectiveTransform(src, dst)
    zoom = np.array([[scale, 0, c[0] * (1 - scale)], [0, scale, c[1] * (1 - scale)], [0, 0, 1]])
    h = zoom @ t
    out = cv2.warpPerspective(gray, h, (wid, hgt), borderMode=cv2.BORDER_REPLICATE)
    out = cv2.GaussianBlur(np.clip(out * dark, 0, 255).astype(np.uint8), (0, 0), 0.8)
    out = np.clip(out + rng.normal(0, 3, out.shape), 0, 255).astype(np.uint8)
    return out, h


def main(d):
    faces = [json.load(open(f)) for f in sorted(glob.glob(os.path.join(d, "*.json")))]
    rows = []
    for f in faces:
        ref = cv2.imread(os.path.join(d, f["id"] + ".jpg"), cv2.IMREAD_GRAYSCALE)
        quad = area(f)
        rf = feats(ref, quad)
        rfn = ref_feats(ref, quad)
        cases = []
        for scale in (1.0, 0.6, 0.4):
            for tilt in (0.0, 0.08):
                img, h = synth(ref, quad, scale, tilt, 0.7 if tilt else 1.0)
                cases.append((f"synth x{scale} tilt{tilt}", img, cv2.perspectiveTransform(quad[None], h)[0]))
        for v in f.get("views", []):
            if v.get("by_hand"):
                img = cv2.imread(os.path.join(d, v["file"]), cv2.IMREAD_GRAYSCALE)
                hv = np.array(v["h"]).reshape(3, 3)
                cases.append(("hand view", img, cv2.perspectiveTransform(quad[None], to_full(hv))[0]))
        for name, img, truth in cases:
            m = match(rf, feats(img))
            old = (err(to_full(m[0]), quad, truth), m[1]) if m else None
            lf = feats(img)
            m2 = match_rep(rfn, lf)
            how = "half"
            if m2 is None:
                m2 = match_rep(rfn, live_feats(img, True)); how = "full"
            new = None
            if m2:
                r = refine(ref, quad, img, to_full(m2[0]))
                new = (err(r[0], quad, truth), r[1], m2[1], how) if r else (err(to_full(m2[0]), quad, truth), 0, m2[1], how)
            rows.append((f["name"], name, old, new))
    for n, c, o, nw in rows:
        fo = f"{o[0]:6.2f}% ({o[1]:4d})" if o else "   not found  "
        fn = f"{nw[0]:6.2f}% (coarse {nw[2]:4d} {nw[3]}, fine {nw[1]:4d})" if nw else "   not found"
        print(f"{n:8s} {c:22s} today {fo}   refined {fn}")


if __name__ == "__main__" and len(sys.argv) == 2:
    main(sys.argv[1])


# ---------- Shape path: find four-sided outlines, flatten each, compare as a picture (for prints with few corners) ----------
TPL = 96  # flattened compare size


def order(q):
    """Corners as TL, TR, BR, BL."""
    c = q.mean(0); a = np.arctan2(q[:, 1] - c[1], q[:, 0] - c[0]); q = q[np.argsort(a)]  # clockwise from left-ish (y down)
    i = np.argmin(q.sum(1)); return np.roll(q, -i, 0)


def quads(gray_full):
    s = 960 / max(gray_full.shape)
    g = cv2.GaussianBlur(cv2.resize(gray_full, None, fx=s, fy=s, interpolation=cv2.INTER_AREA), (5, 5), 0)
    h, w = g.shape; out = []
    for lo, hi in ((30, 90), (50, 150), (80, 200)):
        e = cv2.dilate(cv2.Canny(g, lo, hi), np.ones((3, 3), np.uint8))
        for c in cv2.findContours(e, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)[0]:
            a = cv2.contourArea(c)
            if a < 0.002 * w * h or a > 0.9 * w * h:
                continue
            ap = cv2.approxPolyDP(c, 0.02 * cv2.arcLength(c, True), True)
            if len(ap) != 4 or not cv2.isContourConvex(ap):
                continue
            q = order(ap.reshape(4, 2).astype(np.float32) / s)
            if all(np.abs(q - o).max() > 0.03 * np.linalg.norm(q[1] - q[0]) for o in out):
                out.append(q)
    return out


def flat(gray_full, quad, size=TPL, inset=0.06):
    """The quad flattened to size x size (inset a little so the paper's own border/background doesn't count)."""
    d = np.float32([[0, 0], [size, 0], [size, size], [0, size]])
    m = cv2.getPerspectiveTransform(np.float32(quad), d)
    f = cv2.warpPerspective(gray_full, m, (size, size), flags=cv2.INTER_AREA).astype(np.float32)
    k = int(size * inset); f = cv2.GaussianBlur(f[k:size - k, k:size - k], (0, 0), 1.5)
    return (f - f.mean()) / (f.std() + 1e-6)


def ncc(a, b):
    return float((a * b).mean())


def shape_find(ref_gray, quad, live_gray):
    """Best (score, quad in live) over every outline found and its 4 turns."""
    t = flat(ref_gray, quad)
    best = (-1, None)
    for q in quads(live_gray):
        for r in range(4):
            qr = np.roll(q, -r, 0)
            sc = ncc(t, flat(live_gray, qr))
            if sc > best[0]:
                best = (sc, qr)
    return best


def ecc_refine(ref_gray, quad, live_gray, h_full):
    """Sub-pixel: align the flattened live target to the reference picture (ECC, works on smooth rings too)."""
    n, size = norm_frame(quad)
    ref_n = cv2.warpPerspective(ref_gray, n, size, flags=cv2.INTER_AREA)
    w = n @ np.linalg.inv(h_full)
    live_n = cv2.warpPerspective(live_gray, w, size, flags=cv2.INTER_AREA)
    mask = np.zeros(ref_n.shape, np.uint8); cv2.fillPoly(mask, [np.int32(cv2.perspectiveTransform(quad[None], n)[0])], 255)
    mask = cv2.erode(mask, np.ones((9, 9), np.uint8))
    warp = np.eye(3, dtype=np.float32)
    try:
        cc, warp = cv2.findTransformECC(ref_n.astype(np.float32), live_n.astype(np.float32), warp, cv2.MOTION_HOMOGRAPHY,
                                        (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 100, 1e-6), mask, 5)
    except cv2.error:
        return None
    # warp maps ref_n coords -> live_n coords; ref -> live = inv(w) @ warp @ n
    return np.linalg.inv(w) @ warp.astype(np.float64) @ n, cc


def shape_bench(d):
    faces = [json.load(open(f)) for f in sorted(glob.glob(os.path.join(d, "*.json")))]
    refs = {f["id"]: cv2.imread(os.path.join(d, f["id"] + ".jpg"), cv2.IMREAD_GRAYSCALE) for f in faces}
    print("\nshape path: score for the right target / best wrong target, error after ECC")
    for f in faces:
        quad = area(f); ref = refs[f["id"]]
        cases = []
        for scale in (1.0, 0.6, 0.4):
            for tilt in (0.0, 0.08):
                img, h = synth(ref, quad, scale, tilt, 0.7 if tilt else 1.0)
                cases.append((f"synth x{scale} tilt{tilt}", img, cv2.perspectiveTransform(quad[None], h)[0]))
        for v in f.get("views", []):
            img = cv2.imread(os.path.join(d, v["file"]), cv2.IMREAD_GRAYSCALE)
            hv = np.array(v["h"]).reshape(3, 3)
            cases.append(("hand view" if v.get("by_hand") else "learned view", img, cv2.perspectiveTransform(quad[None], to_full(hv))[0]))
        for name, img, truth in cases[:9]:
            sc, q = shape_find(ref, quad, img)
            wrong = max(shape_find(refs[o["id"]], area(o), img)[0] for o in faces if o["id"] != f["id"])
            e = e2 = None
            if q is not None:
                h0 = cv2.getPerspectiveTransform(np.float32(quad), np.float32(q))
                e = err(h0, quad, truth)
                r = ecc_refine(ref, quad, img, h0)
                e2 = err(r[0], quad, truth) if r else None
            print(f"{f['name']:8s} {name:22s} right {sc:5.2f}  wrong {wrong:5.2f}  outline {e if e is None else round(e,2)}%  ECC {e2 if e2 is None else round(e2,2)}%")


if __name__ == "__main__" and len(sys.argv) > 2 and sys.argv[2] == "shape":
    shape_bench(sys.argv[1])


def verify_score(ref_gray, quad, live_gray, h_full):
    """How much the live target, flattened through h, looks like the reference picture (NCC, -1..1)."""
    q = cv2.perspectiveTransform(quad[None], h_full)[0]
    return ncc(flat(ref_gray, quad), flat(live_gray, q))


def pipeline(ref_gray, quad, rfn, live_gray, lf_half, lf_full):
    """Propose (top-k ORB, 15+ points) -> fine align (ECC) -> confirm (picture match). Returns (h, score, n) or None."""
    for lf in (lf_half, lf_full):
        m = match_rep(rfn, lf(), min_n=15)
        if m is None:
            continue
        h0 = to_full(m[0])
        r = ecc_refine(ref_gray, quad, live_gray, h0)
        h = r[0] if r else h0
        return h, verify_score(ref_gray, quad, live_gray, h), m[1]
    return None


def combo_bench(d):
    faces = [json.load(open(f)) for f in sorted(glob.glob(os.path.join(d, "*.json")))]
    refs = {f["id"]: cv2.imread(os.path.join(d, f["id"] + ".jpg"), cv2.IMREAD_GRAYSCALE) for f in faces}
    rfns = {f["id"]: ref_feats(refs[f["id"]], area(f)) for f in faces}
    print("\ncombined: right target score / error  |  best wrong target score")
    for f in faces:
        quad = area(f); ref = refs[f["id"]]
        cases = []
        for scale in (1.0, 0.6, 0.4, 0.3):
            for tilt in (0.0, 0.08):
                img, h = synth(ref, quad, scale, tilt, 0.6 if tilt else 1.0)
                cases.append((f"synth x{scale} tilt{tilt}", img, cv2.perspectiveTransform(quad[None], h)[0]))
        for v in f.get("views", []):
            img = cv2.imread(os.path.join(d, v["file"]), cv2.IMREAD_GRAYSCALE)
            hv = np.array(v["h"]).reshape(3, 3)
            cases.append(("hand view" if v.get("by_hand") else f"learned ({v.get('inliers')})", img, cv2.perspectiveTransform(quad[None], to_full(hv))[0]))
        for name, img, truth in cases:
            cache = {}
            lh = lambda: cache.setdefault("h", feats(img)); lfull = lambda: cache.setdefault("f", live_feats(img, True))
            r = pipeline(ref, quad, rfns[f["id"]], img, lh, lfull)
            right = f"{r[1]:5.2f} err {err(r[0], quad, truth):6.2f}% ({r[2]:4d})" if r else "  not found               "
            wrongs = []
            for o in faces:
                if o["id"] == f["id"]:
                    continue
                w = pipeline(refs[o["id"]], area(o), rfns[o["id"]], img, lh, lfull)
                if w: wrongs.append((w[1], o["name"]))
            wr = max(wrongs) if wrongs else (None, "")
            print(f"{f['name']:8s} {name:20s} {right}   wrong {wr[0] if wr[0] is None else round(wr[0], 2)} {wr[1]}")


if __name__ == "__main__" and len(sys.argv) > 2 and sys.argv[2] == "combo":
    combo_bench(sys.argv[1])


# ---------- Gold-centre proposals (ring faces: few corners, but every face has a saturated gold disc) ----------
def gold_blobs(bgr, quad=None, min_area=40):
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    g = cv2.inRange(hsv, (18, 100, 60), (38, 255, 255))
    if quad is not None:
        m = np.zeros(g.shape, np.uint8); cv2.fillPoly(m, [np.int32(quad)], 255); g &= m
    g = cv2.morphologyEx(g, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    out = []
    for c in cv2.findContours(g, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)[0]:
        a = cv2.contourArea(c)
        if a < min_area:
            continue
        (x, y), r = cv2.minEnclosingCircle(c)
        if a < 0.55 * np.pi * r * r:
            continue
        out.append((x, y, r))
    return sorted(out, key=lambda b: -b[2])[:8]


def gold_proposals(ref_bgr, quad, live_bgr, live_gray, ref_gray):
    """Homographies ref -> live from pairing the reference's gold centres with gold discs in view, best picture first."""
    import itertools
    rb = gold_blobs(ref_bgr, quad); lb = gold_blobs(live_bgr)
    if not rb or not lb:
        return []
    t = flat(ref_gray, quad)
    hyps = []
    if len(rb) >= 3:
        src = np.float32([b[:2] for b in rb[:3]])
        for tri in itertools.permutations(range(len(lb)), 3):
            dst = np.float32([lb[i][:2] for i in tri])
            a = cv2.getAffineTransform(src, dst); hyps.append(np.vstack([a, [0, 0, 1]]))
    elif len(rb) == 2:
        for i, j in itertools.permutations(range(len(lb)), 2):
            a, _ = cv2.estimateAffinePartial2D(np.float32([b[:2] for b in rb]), np.float32([lb[i][:2], lb[j][:2]]))
            if a is not None: hyps.append(np.vstack([a, [0, 0, 1]]))
    else:
        x, y, r = rb[0]
        for (u, v, s) in lb:
            k = s / r; hyps.append(np.array([[k, 0, u - k * x], [0, k, v - k * y], [0, 0, 1.0]]))
    scored = []
    for h in hyps:
        q = cv2.perspectiveTransform(quad[None], h)[0]
        w = np.linalg.norm(q[1] - q[0])
        if w < 40 or w > 4 * max(live_gray.shape):
            continue
        scored.append((ncc(t, flat(live_gray, q)), h))
    scored.sort(key=lambda s: -s[0])
    return scored[:3]


def gold_bench(d):
    faces = [json.load(open(f)) for f in sorted(glob.glob(os.path.join(d, "*.json")))]
    print("\ngold proposals -> ECC -> picture check (right score / error | best other target's score)")
    for f in faces:
        quad = area(f)
        ref_bgr = cv2.imread(os.path.join(d, f["id"] + ".jpg")); ref = cv2.cvtColor(ref_bgr, cv2.COLOR_BGR2GRAY)
        cases = []
        for scale in (1.0, 0.6, 0.4, 0.3):
            for tilt in (0.0, 0.08):
                img, h = synth(ref_bgr, quad, scale, tilt, 0.6 if tilt else 1.0)
                cases.append((f"synth x{scale} tilt{tilt}", img, cv2.perspectiveTransform(quad[None], h)[0]))
        for v in f.get("views", []):
            if v.get("by_hand"):
                img = cv2.imread(os.path.join(d, v["file"])); hv = np.array(v["h"]).reshape(3, 3)
                cases.append(("hand view", img, cv2.perspectiveTransform(quad[None], to_full(hv))[0]))
        print(f["name"], "gold centres on its photo:", len(gold_blobs(ref_bgr, quad)))
        for name, img, truth in cases:
            gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
            ps = gold_proposals(ref_bgr, quad, img, gray, ref)
            best = None
            for s0, h0 in ps:
                r = ecc_refine(ref, quad, gray, h0); h = r[0] if r else h0
                s = verify_score(ref, quad, gray, h)
                if best is None or s > best[0]: best = (s, h)
            print(f"   {name:20s} " + (f"{best[0]:5.2f} err {err(best[1], quad, truth):6.2f}%" if best else "no proposal"))


if __name__ == "__main__" and len(sys.argv) > 2 and sys.argv[2] == "gold":
    gold_bench(sys.argv[1])
