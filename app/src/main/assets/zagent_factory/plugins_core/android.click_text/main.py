def _acc_health(api):
    try:
        st = api.accessibility_status()
    except Exception as e:
        return {"ok": False, "outcome": "failed", "err": "accessibility check failed: %r" % (e,)}
    if not isinstance(st, dict) or not st.get("ready"):
        health = st.get("health") if isinstance(st, dict) else "UNKNOWN"
        msg = st.get("msg") if isinstance(st, dict) else ""
        return {"ok": False, "outcome": "failed", "err": "accessibility not ready (%s): %s" % (health, msg)}
    return None


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


def run(args, ctx):
    try:
        import android_api
    except Exception as e:
        return {"ok": False, "outcome": "not_submitted", "err": "android_api unavailable: %r" % (e,)}
    h = _acc_health(android_api)
    if h is not None:
        return h
    text = str(args.get("text") or "")
    if not text:
        return {"ok": False, "outcome": "not_submitted", "err": "text is required"}
    try:
        res = android_api.click_node_by_text(text)
    except Exception as e:
        return {"ok": False, "outcome": "unknown", "err": repr(e)}
    if not isinstance(res, dict):
        return {"ok": False, "outcome": "unknown", "err": "unexpected return: %r" % (res,)}
    ok = bool(res.get("ok"))
    outcome = _to_outcome(res.get("outcome"), ok)
    if ok:
        return {"ok": True, "outcome": outcome, "data": {"clicked": text, "x": res.get("x"), "y": res.get("y")}}
    return {"ok": False, "outcome": outcome, "err": res.get("err") or "click failed"}
