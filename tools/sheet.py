"""Confirm sheet: per arrow, green + spot centre, yellow o app guess, pink <> answer key (if given)."""
import sys, glob, json, cv2, numpy as np
sys.path.insert(0, 'tools'); import hits

def build(folder, keyfile, out):
    key = json.load(open(keyfile)) if keyfile else {}
    fs = sorted(glob.glob(folder + '/*_after.jpg'), key=lambda f: int(f.split('_')[-2]))
    heads, tiles = [], []
    for n, f in enumerate(fs):
        t = f.split('_')[-2]; a = cv2.imread(f); b = cv2.imread(f.replace('_after', '_before'))
        hd = None
        if len(heads) >= 2: ang = sorted(heads)[len(heads) // 2]; hd = np.array([np.cos(ang), np.sin(ang)])
        r = hits.find_arrow(b, a, hd)
        spots, wpx = hits.blue_spots(a); ppi = wpx / hits.TAPE_IN
        e = r[1] if r else None
        if r is not None and np.linalg.norm(r[1] - r[0]) > 0.08 * a.shape[1]: heads.append(np.arctan2(*(r[1] - r[0])[::-1]))
        sp = min(spots, key=lambda s: np.linalg.norm(s - (e if e is not None else s)))
        k = key.get(t)
        x, y = int(sp[0]), int(sp[1]); R = int(2.2 * ppi); x0, y0 = max(0, x - R), max(0, y - R)
        c = cv2.resize(a[y0:y + R, x0:x + R].copy(), (560, 560), interpolation=cv2.INTER_CUBIC); s = 560 / (2 * R)
        P = lambda p: (int((p[0] - x0) * s), int((p[1] - y0) * s))
        cv2.drawMarker(c, P(sp), (0, 255, 0), cv2.MARKER_CROSS, 36, 3)
        if e is not None: cv2.circle(c, P(e), 16, (0, 255, 255), 3)
        lines = []
        if e is not None:
            o = (e - sp) / ppi; lines.append(('app: %+.2f E, %+.2f N' % (o[0], -o[1]), (0, 255, 255)))
        if k:
            tx, ty = k['off_in']; tr = sp + np.array([tx, -ty]) * ppi
            cv2.drawMarker(c, P(tr), (203, 102, 255), cv2.MARKER_DIAMOND, 30, 3)
            lines.append(('you: ' + k['said'].replace('"', 'in'), (203, 102, 255)))
            if e is not None: lines.append(('off by %.2f in' % np.linalg.norm(o - np.array([tx, -ty])), (245, 245, 244)))
        cv2.rectangle(c, (0, 0), (560, 30 + 30 * len(lines)), (32, 35, 33), -1)
        cv2.putText(c, '#%d %s' % (n + 1, k['square'] if k else ''), (12, 26), 0, 0.8, (245, 245, 244), 2)
        for i, (txt, col) in enumerate(lines): cv2.putText(c, txt, (12, 56 + 30 * i), 0, 0.75, col, 2)
        tiles.append(c)
    while len(tiles) % 3: tiles.append(np.full((560, 560, 3), 32, np.uint8))
    rows = [np.hstack(tiles[i:i + 3]) for i in range(0, len(tiles), 3)]
    cv2.imwrite(out, np.vstack(rows))

if __name__ == '__main__':
    build(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] != '-' else None, sys.argv[3] if len(sys.argv) > 3 else 'notes/sheet.jpg')
