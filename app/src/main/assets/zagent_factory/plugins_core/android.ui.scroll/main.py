def _sh_quote(s):
    return chr(39) + str(s).replace(chr(39), chr(39) + chr(92) + chr(39) + chr(39)) + chr(39)


def _screen_size(api):
    try:
        r = api.shell_exec("wm size", 10)
    except Exception:
        return 1080, 1920
    text = ((r or {}).get("stdout") or "").strip()
    if "x" not in text:
        return 1080, 1920
    try:
        part = text.split()[-1]
        w, h = [int(v) for v in part.split("x")]
        return w, h
    except Exception:
        return 1080, 1920


def run(args, ctx):
    try:
        import android_api
    except Exception as e:
        return {"ok": False, "outcome": "not_submitted", "err": "android_api unavailable: %r" % (e,)}
    try:
        snap = android_api.capability_snapshot() or {}
    except Exception:
        snap = {}
    if snap.get("root") != "granted":
        return {"ok": False, "outcome": "not_submitted", "err": "scroll requires root (root=%r)" % (snap.get("root"),)}
    direction = str(args.get("direction") or "down").lower()
    amount = max(0.1, min(0.9, float(args.get("amount") or 0.6)))
    dur = int(args.get("duration_ms") or 300)
    w, h = _screen_size(android_api)
    cx, cy = w // 2, h // 2
    sx = int(w * amount / 2)
    sy = int(h * amount / 2)
    if direction == "down":
        x1, y1, x2, y2 = cx, cy + sy, cx, cy - sy
    elif direction == "up":
        x1, y1, x2, y2 = cx, cy - sy, cx, cy + sy
    elif direction == "left":
        x1, y1, x2, y2 = cx + sx, cy, cx - sx, cy
    elif direction == "right":
        x1, y1, x2, y2 = cx - sx, cy, cx + sx, cy
    else:
        return {"ok": False, "outcome": "not_submitted", "err": "direction must be up/down/left/right"}
    inner = "input swipe %d %d %d %d %d" % (x1, y1, x2, y2, dur)
    cmd = "su -c " + chr(39) + inner + chr(39)
    try:
        r = android_api.shell_exec(cmd, 20)
    except Exception as e:
        return {"ok": False, "outcome": "unknown", "err": repr(e)}
    if not isinstance(r, dict) or r.get("code") != 0:
        return {"ok": False, "outcome": "failed", "err": str((r or {}).get("stderr") or "input swipe failed")[:300]}
    return {"ok": True, "outcome": "ok", "data": {"via": "root_input_swipe", "direction": direction, "from": [x1, y1], "to": [x2, y2]}}