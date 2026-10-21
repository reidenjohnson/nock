"""Score the hit finder against an answer key, replaying arrows the way the phone does (sequential heading)."""
import sys, glob, json, math, cv2, numpy as np
sys.path.insert(0, 'tools'); import hits

def run(folder, keyfile):
    key = json.load(open(keyfile))
    fs = sorted(glob.glob(folder + '/*_after.jpg'), key=lambda f: int(f.split('_')[-2]))
    heads, errs = [], []
    for f in fs:
        t = f.split('_')[-2]
        a = cv2.imread(f); b = cv2.imread(f.replace('_after', '_before'))
        hd = None
        if len(heads) >= 2: ang = sorted(heads)[len(heads) // 2]; hd = np.array([np.cos(ang), np.sin(ang)])
        r = hits.find_arrow(b, a, hd)
        if r is None: print(key[t]['square'], 'none'); errs.append(9); continue
        fl, e = r; v = e - fl
        if np.linalg.norm(v) > 0.08 * a.shape[1]: heads.append(np.arctan2(v[1], v[0]))
        spots, wpx = hits.blue_spots(a); ppi = wpx / hits.TAPE_IN
        sp = min(spots, key=lambda s: np.linalg.norm(s - e)); o = (e - sp) / ppi
        tx, ty = key[t]['off_in']; err = math.hypot(o[0] - tx, -o[1] - ty); errs.append(err)
        print('%-3s app (%+.2f E, %+.2f N)  truth (%+.2f, %+.2f)  error %.2f in' % (key[t]['square'], o[0], -o[1], tx, ty, err))
    print('median %.2f in, worst %.2f in' % (sorted(errs)[len(errs) // 2], max(errs)))

if __name__ == '__main__':
    run(sys.argv[1], sys.argv[2])
