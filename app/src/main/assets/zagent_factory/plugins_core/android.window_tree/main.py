def _acc_health(api):
    try:
        st = api.accessibility_status()
    except Exception as e:
        return {"ok": False, "outcome": "failed",
                "err": "accessibility check failed: %r" % (e,)}
    if not isinstance(st, dict) or not st.get("ready"):
        health = st.get("health") if isinstance(st, dict) else "UNKNOWN"
        msg = st.get("msg") if isinstance(st, dict) else ""
        return {"ok": False, "outcome": "failed",
                "err": "accessibility not ready (%s): %s" % (health, msg)}
    return None


def run(args, ctx):
    try:
        import android_api
    except Exception as e:
        return {"ok": False, "outcome": "not_submitted", "err": "android_api unavailable: %r" % (e,)}
    h = _acc_health(android_api)
    if h is not None:
        return h
    try:
        raw = android_api.get_window_tree()
    except Exception as e:
        return {"ok": False, "outcome": "unknown", "err": repr(e)}
    if not raw:
        return {"ok": False, "outcome": "failed", "err": "empty window tree"}
    return {"ok": True, "outcome": "ok", "data": raw}
