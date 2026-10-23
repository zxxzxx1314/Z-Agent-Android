def _sh_quote(s):
    return chr(39) + str(s).replace(chr(39), chr(39) + chr(92) + chr(39) + chr(39)) + chr(39)


DUMP_PATH = "/data/local/tmp/zagent_ui.xml"


def run(args, ctx):
    try:
        import android_api
    except Exception as e:
        return {"ok": False, "outcome": "not_submitted", "err": "android_api unavailable: %r" % (e,)}
    try:
        snap = android_api.capability_snapshot() or {}
    except Exception:
        snap = {}
    if snap.get("root") != "granted":
        return {"ok": False, "outcome": "not_submitted",
                "err": "uiautomator dump requires root (root=%r)" % (snap.get("root"),)}
    timeout = int(args.get("timeout") or 20)
    inner = "uiautomator dump --compressed " + DUMP_PATH + " >/dev/null 2>&1; cat " + DUMP_PATH + "; rm -f " + DUMP_PATH
    cmd = "su -c " + chr(39) + inner + chr(39)
    try:
        r = android_api.shell_exec(cmd, timeout)
    except Exception as e:
        return {"ok": False, "outcome": "unknown", "err": repr(e)}
    if not isinstance(r, dict):
        return {"ok": False, "outcome": "unknown", "err": "unexpected shell result"}
    code = r.get("code")
    xml = (r.get("stdout") or "").strip()
    if code != 0 or not xml:
        err = (r.get("stderr") or "").strip() or ("dump produced no output (code=%r)" % (code,))
        return {"ok": False, "outcome": "failed", "err": err[:800]}
    if "<hierarchy" not in xml:
        return {"ok": False, "outcome": "failed", "err": "dump output is not a hierarchy xml"}
    return {"ok": True, "outcome": "ok", "data": {"xml": xml, "bytes": len(xml)}}