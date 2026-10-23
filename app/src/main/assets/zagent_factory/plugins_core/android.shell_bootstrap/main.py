def run(args, ctx):
    try:
        import android_api
    except Exception as e:
        return {"ok": False, "err": "android_api unavailable: %r" % (e,)}
    try:
        res = str(android_api.shell_bootstrap())
    except Exception as e:
        return {"ok": False, "err": repr(e)}
    ok = not res.startswith("fail:")
    return {"ok": ok, "data": res, "err": (None if ok else res)}
