# -- coding: utf-8 --
"""patch.apply - write patch tags into target files.

Thin shell: backup, compile check and rollback all live in host
run_patch_tag.
"""


def run(args, ctx):
    text = args.get("text") or ""
    if not str(text).strip():
        return {"ok": False, "err": "text is required"}
    host = (ctx or {}).get("host") or {}
    fn = host.get("run_patch_tag")
    if fn is None:
        return {"ok": False, "err": "host.run_patch_tag unavailable"}
    lines = [str(x) for x in (fn(str(text)) or [])]
    failed = [x for x in lines if x.startswith("FAIL")]
    return {"ok": not failed, "data": chr(10).join(lines),
        "err": (chr(10).join(failed) if failed else None)}
