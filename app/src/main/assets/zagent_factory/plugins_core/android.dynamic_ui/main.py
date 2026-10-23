def run(args, ctx):
    try:
        import android_api
    except Exception as e:
        return {"ok": False, "err": "android_api unavailable: %r" % (e,)}
    raw = str(args.get("ui") or "").strip()
    if not raw:
        return {"ok": False, "err": "ui is required"}
    try:
        res = str(android_api.show_dynamic_ui(raw))
    except Exception as e:
        return {"ok": False, "err": repr(e)}
    if res.startswith("Error:"):
        return {"ok": False, "err": res}
    return {"ok": True, "data": res}
