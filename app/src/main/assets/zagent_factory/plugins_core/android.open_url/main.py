def run(args, ctx):
    url = str(args.get("url") or "").strip()
    if not url:
        return {"ok": False, "err": "url is required"}
    if not (url.startswith("http://") or url.startswith("https://")):
        return {"ok": False, "err": "only http/https allowed"}
    try:
        import android_api
    except Exception as e:
        return {"ok": False, "err": "android_api unavailable: %r" % (e,)}
    try:
        android_api.open_url(url)
    except Exception as e:
        return {"ok": False, "err": repr(e)}
    return {"ok": True, "data": "opened " + url}
