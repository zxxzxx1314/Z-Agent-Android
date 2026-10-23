def run(args, ctx):
    try:
        ms = int(args.get("ms") or 300)
    except Exception:
        return {"ok": False, "err": "ms must be an integer"}
    ms = max(10, min(2000, ms))
    try:
        import android_api
    except Exception as e:
        return {"ok": False, "err": "android_api unavailable: %r" % (e,)}
    try:
        android_api.vibrate(ms)
    except Exception as e:
        return {"ok": False, "err": repr(e)}
    return {"ok": True, "data": "vibrated %d ms" % ms}
