def run(args, ctx):
    text = str(args.get("text") or "")
    if not text:
        return {"ok": False, "err": "text is required"}
    try:
        import android_api
    except Exception as e:
        return {"ok": False, "err": "android_api unavailable: %r" % (e,)}
    try:
        android_api.toast(text)
    except Exception as e:
        return {"ok": False, "err": repr(e)}
    return {"ok": True, "data": "toast shown"}
