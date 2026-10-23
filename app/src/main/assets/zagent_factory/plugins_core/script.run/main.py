# -- coding: utf-8 --
"""script.run - execute a python script.

Thin shell: gate / approval / isolation all live in host run_python_code.
"""


def run(args, ctx):
    code = args.get("code") or ""
    if not str(code).strip():
        return {"ok": False, "err": "code is required"}
    host = (ctx or {}).get("host") or {}
    fn = host.get("run_python_code")
    if fn is None:
        return {"ok": False, "err": "host.run_python_code unavailable"}
    rc, o, e = fn(str(code))
    return {"ok": rc == 0, "data": {"rc": rc, "out": o, "err": e},
        "err": (None if rc == 0 else (e or o or "exit %d" % rc))}
