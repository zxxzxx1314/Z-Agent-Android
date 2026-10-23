def _sh_quote(s):
    return chr(39) + str(s).replace(chr(39), chr(39) + chr(92) + chr(39) + chr(39)) + chr(39)


def _is_ascii(s):
    try:
        s.encode("ascii")
        return True
    except Exception:
        return False


def run(args, ctx):
    try:
        import android_api
    except Exception as e:
        return {"ok": False, "outcome": "not_submitted", "err": "android_api unavailable: %r" % (e,)}
    text = str(args.get("text") or "")
    if not text:
        return {"ok": False, "outcome": "not_submitted", "err": "text is required"}
    if not _is_ascii(text):
        try:
            android_api.clipboard_set(text)
        except Exception as e:
            return {"ok": False, "outcome": "not_submitted", "err": "clipboard set failed: %r" % (e,)}
        return {"ok": False, "outcome": "not_submitted", "err": "non-ASCII text cannot be typed; copied to clipboard, please long-press to paste"}
    try:
        snap = android_api.capability_snapshot() or {}
    except Exception:
        snap = {}
    if snap.get("root") != "granted":
        try:
            android_api.clipboard_set(text)
        except Exception:
            pass
        return {"ok": False, "outcome": "not_submitted", "err": "typing requires root (root=%r); text copied to clipboard" % (snap.get("root"),)}
    esc = text.replace(" ", "%s")
    inner = "input text " + esc
    cmd = "su -c " + chr(39) + inner + chr(39)
    try:
        r = android_api.shell_exec(cmd, 20)
    except Exception as e:
        return {"ok": False, "outcome": "unknown", "err": repr(e)}
    if isinstance(r, dict) and r.get("code") == 0:
        return {"ok": True, "outcome": "ok", "data": {"via": "root_input_text", "len": len(text)}}
    return {"ok": False, "outcome": "failed", "err": str((r or {}).get("stderr") or "input text failed")[:300]}