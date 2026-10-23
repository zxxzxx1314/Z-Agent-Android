def run(args, ctx):
    try:
        import android_api
    except Exception as e:
        return {"ok": False, "err": "android_api unavailable: %r" % (e,)}
    path = str(args.get("path") or "").strip()
    if not path:
        return {"ok": False, "err": "path is required"}
    try:
        text = str(android_api.ocr_image(path))
    except Exception as e:
        return {"ok": False, "err": repr(e)}
    if text.startswith("Error:"):
        return {"ok": False, "err": text}
    return {"ok": True, "data": text}
