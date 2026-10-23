def run(args, ctx):
    title = str(args.get("title") or "Z-Agent")
    content = str(args.get("content") or "")
    if not content:
        return {"ok": False, "err": "content is required"}
    try:
        import android_api
    except Exception as e:
        return {"ok": False, "err": "android_api unavailable: %r" % (e,)}
    try:
        android_api.notify(title, content)
    except Exception as e:
        return {"ok": False, "err": repr(e)}
    return {"ok": True, "data": "notification sent"}
