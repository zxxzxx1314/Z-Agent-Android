def run(args, ctx):
    try:
        import android_api
    except Exception as e:
        return {"ok": False, "err": "android_api unavailable: %r" % (e,)}
    try:
        v = android_api.volume()
    except Exception as e:
        return {"ok": False, "err": repr(e)}
    if isinstance(v, dict) and v.get("error"):
        return {"ok": False, "err": str(v["error"])}
    return {"ok": True, "data": v}
