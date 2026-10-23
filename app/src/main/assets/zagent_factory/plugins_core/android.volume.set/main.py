def run(args, ctx):
    try:
        level = int(args.get("level"))
    except Exception:
        return {"ok": False, "err": "level must be an integer"}
    if level < 0 or level > 100:
        return {"ok": False, "err": "level out of range 0-100"}
    try:
        import android_api
    except Exception as e:
        return {"ok": False, "err": "android_api unavailable: %r" % (e,)}
    try:
        android_api.set_volume(level)
    except Exception as e:
        return {"ok": False, "err": repr(e)}
    return {"ok": True, "data": "volume set to %d" % level}
