def run(args, ctx):
    try:
        import android_api
    except Exception as e:
        return {"ok": False, "err": "android_api unavailable: %r" % (e,)}
    cmd = str(args.get("cmd") or "").strip()
    if not cmd:
        return {"ok": False, "err": "cmd is required"}
    try:
        to = int(args.get("timeout") or 60)
    except Exception:
        to = 60
    to = max(1, min(300, to))
    try:
        return android_api.shell_exec(cmd, to)
    except Exception as e:
        return {"ok": False, "err": repr(e)}
