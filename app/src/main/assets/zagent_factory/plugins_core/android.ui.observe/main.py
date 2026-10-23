def _sh_quote(s):
    return chr(39) + str(s).replace(chr(39), chr(39) + chr(92) + chr(39) + chr(39)) + chr(39)


import json


def _tree(api):
    try:
        raw = api.get_window_tree()
    except Exception as e:
        return None, repr(e)
    if not raw:
        return None, "empty window tree"
    try:
        d = json.loads(raw)
    except Exception:
        return None, "window tree is not JSON"
    if isinstance(d, dict) and d.get("error"):
        return None, str(d.get("error"))
    return d, None


def _dump(api, timeout):
    path = "/data/local/tmp/zagent_ui.xml"
    inner = "uiautomator dump --compressed " + path + " >/dev/null 2>&1; cat " + path + "; rm -f " + path
    cmd = "su -c " + chr(39) + inner + chr(39)
    try:
        r = api.shell_exec(cmd, int(timeout))
    except Exception as e:
        return None, repr(e)
    xml = ((r or {}).get("stdout") or "").strip()
    if "<hierarchy" not in xml:
        return None, "uiautomator dump failed"
    return xml, None


def run(args, ctx):
    try:
        import android_api
    except Exception as e:
        return {"ok": False, "outcome": "not_submitted", "err": "android_api unavailable: %r" % (e,)}
    data = {}
    notes = []
    tree, terr = _tree(android_api)
    if tree is not None:
        data["source"] = "accessibility"
        data["window_tree"] = tree
    else:
        notes.append("accessibility tree unavailable: " + str(terr))
        xml, xerr = _dump(android_api, args.get("timeout") or 20)
        if xml is None:
            notes.append("uiautomator fallback failed: " + str(xerr))
        else:
            data["source"] = "uiautomator"
            data["xml"] = xml
    if not data.get("source"):
        return {"ok": False, "outcome": "failed", "err": " | ".join(notes) or "no interface readable"}
    if args.get("include_screenshot"):
        try:
            shot = android_api.screenshot(args.get("excluded_packages") or [])
        except Exception as e:
            shot = {"ok": False, "err": repr(e)}
        if isinstance(shot, dict) and shot.get("ok"):
            data["screenshot"] = {"png_base64": shot.get("png_base64"), "window_id": shot.get("window_id"), "bytes": shot.get("bytes")}
        else:
            notes.append("screenshot failed: " + str((shot or {}).get("err")))
    if notes:
        data["notes"] = notes
    return {"ok": True, "outcome": "ok", "data": data}