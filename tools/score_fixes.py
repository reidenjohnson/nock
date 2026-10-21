"""Score the hit finder against your drag-fixes: every *_hit.json with "fixed" (and not "ignored") is an answer.

Usage: python tools/score_fixes.py notes/session7-arrows notes/session8-data notes/round9-1007
Replays each folder in time order like the phone (shared arrow heading), prints per-arrow error in inches.
"""
import os, sys, glob, json, math, cv2, numpy as np
sys.path.insert(0, 'tools'); import hits

def run(folder):
    fs = sorted(glob.glob(folder + '/*_after.jpg'), key=lambda f: int(f.split('_')[-2]))
    heads, errs = [], []
    for f in fs:
        jf = f.replace('_after.jpg', '_hit.json')
        try: j = json.load(open(jf))
        except FileNotFoundError: continue
        a = cv2.imread(f); b = cv2.imread(f.replace('_after', '_before'))
        hd = None
        if len(heads) >= 2: ang = sorted(heads)[len(heads) // 2]; hd = np.array([np.cos(ang), np.sin(ang)])
        r = hits.find_arrow(b, a, hd)
        if r is not None:
            fl, e = r; v = e - fl
            if np.linalg.norm(v) > 0.08 * a.shape[1]: heads.append(np.arctan2(v[1], v[0]))
        if 'fixed' not in j or j.get('ignored'): continue
        ppi = j.get('ppi', 62.0); fx, fy = j['fixed']
        err = 9.0 if r is None else math.hypot(e[0] - fx, e[1] - fy) / ppi
        errs.append(err)
        print('%-40s %s  error %.2f in' % (os.path.basename(f), 'none' if r is None else '(%4.0f,%4.0f) vs fix (%4.0f,%4.0f)' % (e[0], e[1], fx, fy), err))
    return errs

if __name__ == '__main__':
    allerr = []
    for d in sys.argv[1:]: allerr += run(d)
    s = sorted(allerr)
    print('%d arrows: median %.2f in, <=0.3 in: %d, worst %.2f in' % (len(s), s[len(s) // 2], sum(e <= 0.3 for e in s), s[-1]))
