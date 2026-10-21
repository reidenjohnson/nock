"""Offline hit finder: before/after photo pairs -> arrow entry point and offset from the nearest blue tape spot.

Usage: python tools/hits.py notes/session2-data [more dirs...]   (writes notes/hits_<dir>.jpg contact sheets)
Proving ground for the on-phone version.
"""
import sys, glob, os
import cv2, numpy as np

TAPE_IN = 1.88  # painter's tape width, the factory edge
REFINE_GAP = 60
# The trail runs a little past the real entry (the shaft's shadow on the face + blur): measured +8..+22 px
# (avg 0.24 in) on 4 tape-measured arrows. Back off a bit less than that.
ENTRY_BACKOFF_IN = 0.2
CENTRE_BAND = 12  # px either side of the shaft searched for its centre

def register(before, after):
    """Warp 'before' onto 'after': the foam block nudges when an arrow hits."""
    g1 = cv2.cvtColor(before, cv2.COLOR_BGR2GRAY); g2 = cv2.cvtColor(after, cv2.COLOR_BGR2GRAY)
    orb = cv2.ORB_create(5000)
    k1, d1 = orb.detectAndCompute(g1, None); k2, d2 = orb.detectAndCompute(g2, None)
    m = sorted(cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=True).match(d1, d2), key=lambda x: x.distance)[:1000]
    H, _ = cv2.findHomography(np.float32([k1[x.queryIdx].pt for x in m]), np.float32([k2[x.trainIdx].pt for x in m]), cv2.RANSAC, 2.0)
    return cv2.warpPerspective(before, H, (after.shape[1], after.shape[0]))

def blue_spots(img):
    """Tape spots and the tape width in px. Width = median short side of each piece's outline
    (arrow holes healed first). The old distance-transform estimate read ~1.7x too narrow."""
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    m = cv2.inRange(hsv, (95, 120, 80), (125, 255, 255))
    m = cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((15, 15), np.uint8))
    n, lab, st, cen = cv2.connectedComponentsWithStats(m)
    spots, sides = [], []
    for i in range(1, n):
        if st[i, 4] < 800 or st[i, 1] < 0.08 * img.shape[0]: continue  # skip specks and the tape roll on top
        cnt, _ = cv2.findContours((lab == i).astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        w, h = cv2.minAreaRect(cnt[0])[1]
        sides.append(min(w, h)); spots.append(cen[i])
    return spots, float(np.median(sides)) if sides else 0.0

def white_square_ppi(img, size_in=6.75):
    """Px per inch from the Block face's printed white square (6.75 in, four bricks with 3/8 in gaps)."""
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    white = cv2.inRange(hsv, (0, 0, 165), (180, 70, 255))
    white = cv2.morphologyEx(white, cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))
    big = cv2.morphologyEx(white, cv2.MORPH_CLOSE, np.ones((41, 41), np.uint8))  # bridge the gaps + holes
    n, lab, st, _ = cv2.connectedComponentsWithStats(big)
    c = [i for i in range(1, n) if st[i, 4] > 0.01 * img.shape[0] * img.shape[1] and 0.8 < st[i, 2] / st[i, 3] < 1.25]
    if not c: return None
    i = max(c, key=lambda i: st[i, 4])
    cnt, _ = cv2.findContours((lab == i).astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    w, h = cv2.minAreaRect(cnt[0])[1]
    return (w + h) / 2 / size_in

def walk(on, o, u, start, maxlen, max_gap):
    """Distance along o + u*t of the last changed pixel before a gap longer than max_gap (None if none)."""
    H, W = on.shape
    last = None; gap = 0; t = start
    while t < maxlen:
        x, y = (o + u * t).astype(int)
        if not (2 <= x < W - 2 and 2 <= y < H - 2): break
        if on[y, x] or on[y - 1, x] or on[y + 1, x] or on[y, x - 1] or on[y, x + 1]: last = t; gap = 0
        else:
            gap += 2
            if gap > max_gap: break
        t += 2
    return last

def refine_end(after, b, fl, u, last):
    """The foam shifts a little on impact. Re-align 'before' to 'after' locally around the far part of the
    shaft (masking the shaft itself), redo the change map there, and walk again with a longer gap allowance
    (dark shaft over black foam); the local alignment keeps shifted-tape noise from extending the trail."""
    H, W = after.shape[:2]
    pad = int(0.06 * W)
    p0 = fl + u * (0.3 * last); p1 = fl + u * (1.8 * last)
    x0 = int(max(0, min(p0[0], p1[0]) - pad)); x1 = int(min(W, max(p0[0], p1[0]) + pad))
    y0 = int(max(0, min(p0[1], p1[1]) - pad)); y1 = int(min(H, max(p0[1], p1[1]) + pad))
    if x1 - x0 < 40 or y1 - y0 < 40: return last
    ga = cv2.cvtColor(after[y0:y1, x0:x1], cv2.COLOR_BGR2GRAY).astype(np.float32)
    gb = cv2.cvtColor(b[y0:y1, x0:x1], cv2.COLOR_BGR2GRAY).astype(np.float32)
    mask = np.full(ga.shape, 255, np.uint8)
    e2 = fl + u * 2 * last
    cv2.line(mask, (int(fl[0] - x0), int(fl[1] - y0)), (int(e2[0] - x0), int(e2[1] - y0)), 0, 25)
    M = np.eye(2, 3, dtype=np.float32)
    try:
        _, M = cv2.findTransformECC(ga, gb, M, cv2.MOTION_EUCLIDEAN,
                                    (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 60, 1e-4), mask, 5)
    except cv2.error:
        return last
    bw = cv2.warpAffine(b[y0:y1, x0:x1], M, (x1 - x0, y1 - y0), flags=cv2.INTER_LINEAR + cv2.WARP_INVERSE_MAP)
    loc = cv2.absdiff(cv2.GaussianBlur(after[y0:y1, x0:x1], (5, 5), 0), cv2.GaussianBlur(bw, (5, 5), 0)).max(2)
    on = loc > 18
    k = 0.3 * last; lastk = None; gap = 0
    while k < 1.8 * last:
        px_, py_ = fl + u * k
        ix, iy = int(px_) - x0, int(py_) - y0
        if not (2 <= ix < on.shape[1] - 2 and 2 <= iy < on.shape[0] - 2): break
        if on[iy, ix] or on[iy - 1, ix] or on[iy + 1, ix] or on[iy, ix - 1] or on[iy, ix + 1]:
            lastk = k; gap = 0
        else:
            gap += 2
            if gap > REFINE_GAP: break
        k += 2
    return lastk if lastk is not None else last

def centreline(diff, fl, u, reach):
    """The longest ray from the fletching hugs one edge of the shaft and can run on into the shaft's shadow
    past the entry. Fit the shaft's real centre line instead: across the shaft at many points along its
    middle stretch, take the centre of the changed band, then fit a line through those centres."""
    H, W = diff.shape
    n = np.array([-u[1], u[0]])
    side = np.arange(-CENTRE_BAND, CENTRE_BAND + 1)
    pts = []
    for t in np.arange(0.2 * reach, 0.8 * reach, 3.0):
        p = fl + u * t
        q = p[None, :] + side[:, None] * n[None, :]
        xs, ys = q[:, 0].astype(int), q[:, 1].astype(int)
        if xs.min() < 0 or ys.min() < 0 or xs.max() >= W or ys.max() >= H: continue
        w = np.clip(diff[ys, xs] - 18, 0, None)
        if w.sum() <= 0: continue
        pts.append(p + n * (side * w).sum() / w.sum())
    if len(pts) < 5: return fl, u
    vx, vy, x0, y0 = cv2.fitLine(np.float32(pts), cv2.DIST_HUBER, 0, 0.01, 0.01).ravel()
    v = np.array([vx, vy]); v = v if v @ u > 0 else -v
    o = np.array([x0, y0]); o = o + v * ((fl - o) @ v)  # the point on the line level with the fletching
    return o, v

def find_arrow(before, after, heading=None):
    """heading: unit vector most arrows in this session point (fletching -> target), or None to learn it."""
    b = register(before, after)
    diff = cv2.absdiff(cv2.GaussianBlur(after, (5, 5), 0), cv2.GaussianBlur(b, (5, 5), 0)).max(2).astype(np.float32)
    hsv = cv2.cvtColor(after, cv2.COLOR_BGR2HSV)
    orange = cv2.inRange(hsv, (3, 140, 140), (22, 255, 255))
    newor = (orange > 0) & (diff > 40)
    newor = cv2.morphologyEx(newor.astype(np.uint8), cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    n, lab, st, cen = cv2.connectedComponentsWithStats(newor)
    if n < 2: return None
    f = max(range(1, n), key=lambda i: st[i, 4])
    fl = cen[f]  # fletching centre
    # The shaft: from the fletching, try every direction (half-degree steps) and keep the one with the
    # longest continuous run of changed pixels. Deterministic (the earlier Hough version was random).
    H, W = diff.shape
    on = (diff > 18)
    best = None
    angles = np.radians(np.arange(0, 360, 0.5))
    if heading is not None:
        h = np.arctan2(heading[1], heading[0])
        angles = angles[np.cos(angles - h) >= np.cos(np.radians(20))]  # arrows from a fixed phone all point about the same way
    start = 0.02 * W   # skip the vanes right around the fletching centre
    # A hit lands on the target: the trail must end near the tape spots (their box, grown 25% per side).
    spots, _ = blue_spots(after)
    box = None
    if len(spots) >= 2:
        xs_, ys_ = [p[0] for p in spots], [p[1] for p in spots]
        mx, my = 0.25 * (max(xs_) - min(xs_)) + 0.05 * W, 0.25 * (max(ys_) - min(ys_)) + 0.05 * W
        box = (min(xs_) - mx, min(ys_) - my, max(xs_) + mx, max(ys_) + my)
    # Max gap in the shaft trail. Longer (tried 0.04*W) found the 1 missing arrow but overshot the tip on
    # several others; a "no hit" beats a wrong one.
    max_gap = 30
    maxlen = 0.35 * W
    steps = np.arange(start, maxlen, 2.0)
    for a_ in angles:
        ux, uy = np.cos(a_), np.sin(a_)
        xs = (fl[0] + ux * steps).astype(int); ys = (fl[1] + uy * steps).astype(int)
        ok = (xs >= 2) & (xs < W - 2) & (ys >= 2) & (ys < H - 2)
        xs, ys = xs[ok], ys[ok]
        hit = on[ys, xs] | on[ys - 1, xs] | on[ys + 1, xs] | on[ys, xs - 1] | on[ys, xs + 1]
        last = None; gap = 0
        for k, hv in enumerate(hit):
            if hv: last = k; gap = 0
            else:
                gap += 2
                if gap > max_gap: break
        if last is None: continue
        reach = steps[last]
        if box is not None:
            ex, ey = fl[0] + ux * reach, fl[1] + uy * reach
            if not (box[0] <= ex <= box[2] and box[1] <= ey <= box[3]): continue  # ends off the target
        if best is None or reach > best[0]: best = (reach, a_)
    if best is None: return None
    last, ang = best
    u = np.array([np.cos(ang), np.sin(ang)])
    for _ in range(4):  # a short first ray only covers part of the shaft: fit, walk further, fit again
        fl, u = centreline(diff, fl, u, last)
        last = walk(on, fl, u, start, maxlen, max_gap) or last
    last = refine_end(after, b, fl, u, last)
    spots, wpx = blue_spots(after)
    back = ENTRY_BACKOFF_IN * wpx / TAPE_IN if wpx else 0
    return fl, fl + u * max(0.0, last - back)

def main(dirs):
    for d in dirs:
        afters = sorted(glob.glob(os.path.join(d, 'shot_*_*_after.jpg')), key=lambda f: int(f.split('_')[-2]))
        tiles = []
        # pass 1: learn the session's arrow heading from the clearest (longest) shafts
        heads = []
        for fa in afters:
            r = find_arrow(cv2.imread(fa.replace('_after', '_before')), cv2.imread(fa))
            if r: v = r[1] - r[0]; heads.append((np.linalg.norm(v), v / max(np.linalg.norm(v), 1)))
        heads.sort(key=lambda h: -h[0])
        top = [h[1] for h in heads[:max(3, len(heads) // 2)]]
        ang = np.median([np.arctan2(v[1], v[0]) for v in top])
        heading = np.array([np.cos(ang), np.sin(ang)])
        for fa in afters:
            fb = fa.replace('_after', '_before')
            a, b = cv2.imread(fa), cv2.imread(fb)
            r = find_arrow(b, a, heading)
            spots, wpx = blue_spots(a)
            name = os.path.basename(fa).split('_')[2]
            if r is None or not spots: print(name, 'no arrow found'); continue
            fl, entry = r
            sp = min(spots, key=lambda s: np.linalg.norm(s - entry))
            ppi = wpx / TAPE_IN
            sq = white_square_ppi(a)
            if sq and abs(sq / ppi - 1) < 0.15: ppi = sq  # agree -> trust the bigger ruler
            off = (entry - sp) / ppi
            print(f'{name}: {np.linalg.norm(off):.1f} in from spot ({abs(off[0]):.1f} {"right" if off[0] > 0 else "left"}, {abs(off[1]):.1f} {"low" if off[1] > 0 else "high"})  {ppi:.1f} px/in')
            vis = a.copy()
            cv2.line(vis, tuple(map(int, fl)), tuple(map(int, entry)), (0, 0, 255), 3)
            cv2.circle(vis, tuple(map(int, entry)), 16, (0, 255, 255), 3)
            cv2.drawMarker(vis, tuple(map(int, sp)), (0, 255, 0), cv2.MARKER_CROSS, 40, 3)
            x, y = int(entry[0]), int(entry[1]); h = 260
            crop = vis[max(0, y - h):y + h, max(0, x - h):x + h]
            tiles.append(cv2.resize(crop, (260, 260)))
        if tiles:
            cv2.imwrite(os.path.join('notes', 'hits_' + os.path.basename(d.rstrip('/')) + '.jpg'), np.hstack(tiles))

if __name__ == '__main__':
    main(sys.argv[1:])
