# -- coding: utf-8 --
"""file.list - list one directory level."""
import os


def run(args, ctx):
    path = os.path.expanduser(str(args.get("path") or ".").strip())
    if not os.path.isdir(path):
        return {"ok": False, "err": "not a directory: " + path}
    try:
        names = sorted(os.listdir(path))
    except Exception as e:
        return {"ok": False, "err": repr(e)}
    lines = []
    for n in names:
        full = os.path.join(path, n)
        lines.append(n + "/" if os.path.isdir(full) else n)
    return {"ok": True, "data": chr(10).join(lines) if lines else "(empty)",
        "meta": {"count": len(lines)}}
