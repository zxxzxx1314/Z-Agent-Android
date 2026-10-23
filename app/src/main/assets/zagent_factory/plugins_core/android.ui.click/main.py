def _sh_quote(s):
    return chr(39) + str(s).replace(chr(39), chr(39) + chr(92) + chr(39) + chr(39)) + chr(39)


import xml.etree.ElementTree as ET

OUTCOME_MAP = {
    "COMPLETED": "ok",
    "CANCELLED": "failed",
    "NOT_DISPATCHED": "not_submitted",
    "UNKNOWN": "unknown",
}


def _to_outcome(raw, ok):
    if raw in OUTCOME_MAP:
        return OUTCOME_MAP[raw]
    return "ok" if ok else "unknown"


def _bounds(text):
    if not text:
        return None
    try:
        parts = text.replace("][", ",").strip("[]").split(",")
        x1, y1, x2, y2 = [int(v) for v in parts]
    except Exception:
        return None
    if x2 <= x1 or y2 <= y1:
        return None
    return (x1 + x2) // 2, (y1 + y2) // 2


def _root_tap(api, x, y):
    inner = "input tap %d %d" % (int(x), int(y))
    cmd = "su -c " + chr(39) + inner + chr(39)
    try:
        r = api.shell_exec(cmd, 15)
    except Exception as e:
        return False, repr(e)
    if isinstance(r, dict) and r.get("code") == 0:
        return True, None
    return False, str((r or {}).get("stderr") or "input tap failed")[:300]


def _dump_and_find(api, target, timeout):
    path = "/data/local/tmp/zagent_ui.xml"
    inner = "uiautomator dump --compressed " + path + " >/dev/null 2>&1; cat " + path + "; rm -f " + path
    cmd = "su -c " + chr(39) + inner + chr(39)
    try:
        r = api.shell_exec(cmd, int(timeout))
    except Exception as e:
        return None, repr(e)
    xml = ((r or {}).get("stdout") or "").strip()
    if "<hierarchy" not in xml:
        return None, "uiautomator dump failed"
    try:
        root_node = ET.fromstring(xml)
    except Exception as e:
        return None, "xml parse failed: %r" % (e,)
    for node in root_node.iter("node"):
        txt = node.get("text") or ""
        desc = node.get("content-desc") or ""
        if target not in txt and target not in desc:
            continue
        pt = _bounds(node.get("bounds"))
        if pt is not None:
            return pt, None
    return None, "text not found in uiautomator tree"


def run(args, ctx):
    try:
        import android_api
    except Exception as e:
        return {"ok": False, "outcome": "not_submitted", "err": "android_api unavailable: %r" % (e,)}
    text = args.get("text")
    x = args.get("x")
    y = args.get("y")
    dur = int(args.get("duration_ms") or 50)
    if text:
        try:
            res = android_api.click_node_by_text(str(text))
        except Exception as e:
            return {"ok": False, "outcome": "unknown", "err": repr(e)}
        if not isinstance(res, dict):
            return {"ok": False, "outcome": "unknown", "err": "unexpected return"}
        outcome = _to_outcome(res.get("outcome"), bool(res.get("ok")))
        if outcome == "ok":
            return {"ok": True, "outcome": "ok", "data": {"via": "accessibility", "text": text, "x": res.get("x"), "y": res.get("y")}}
        if outcome != "not_submitted":
            return {"ok": False, "outcome": outcome, "err": res.get("err") or "click failed"}
        pt, derr = _dump_and_find(android_api, str(text), 20)
        if pt is None:
            return {"ok": False, "outcome": "not_submitted", "err": derr}
        ok, terr = _root_tap(android_api, pt[0], pt[1])
        if not ok:
            return {"ok": False, "outcome": "not_submitted", "err": terr}
        return {"ok": True, "outcome": "ok", "data": {"via": "root_input_tap", "text": text, "x": pt[0], "y": pt[1]}}
    if x is None or y is None:
        return {"ok": False, "outcome": "not_submitted", "err": "need text, or both x and y"}
    try:
        res = android_api.gesture_tap(float(x), float(y), dur)
    except Exception as e:
        return {"ok": False, "outcome": "unknown", "err": repr(e)}
    if not isinstance(res, dict):
        return {"ok": False, "outcome": "unknown", "err": "unexpected return"}
    outcome = _to_outcome(res.get("outcome"), bool(res.get("ok")))
    if outcome == "ok":
        return {"ok": True, "outcome": "ok", "data": {"via": "gesture", "x": x, "y": y}}
    if outcome != "not_submitted":
        return {"ok": False, "outcome": outcome, "err": res.get("err") or "tap failed"}
    ok, terr = _root_tap(android_api, float(x), float(y))
    if not ok:
        return {"ok": False, "outcome": "not_submitted", "err": terr}
    return {"ok": True, "outcome": "ok", "data": {"via": "root_input_tap", "x": x, "y": y}}