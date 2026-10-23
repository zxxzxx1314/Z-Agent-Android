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
    excluded = args.get("excluded_packages") or []
    if isinstance(excluded, str):
        excluded = [x for x in excluded.split(",") if x]
    if not isinstance(excluded, list):
        excluded = []
    try:
        res = android_api.screenshot(excluded)
    except Exception as e:
        return {"ok": False, "outcome": "unknown", "err": repr(e)}
    if not isinstance(res, dict):
        return {"ok": False, "outcome": "unknown", "err": "unexpected return"}
    ok = bool(res.get("ok"))
    outcome = _to_outcome(res.get("outcome"), ok)
    if ok:
        return {"ok": True, "outcome": outcome, "data": {"png_base64": res.get("png_base64"), "window_id": res.get("window_id"), "bytes": res.get("bytes")}}
    return {"ok": False, "outcome": outcome, "err": res.get("err") or "screenshot failed"}
