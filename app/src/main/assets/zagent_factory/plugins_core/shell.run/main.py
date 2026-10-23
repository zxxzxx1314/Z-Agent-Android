# -- coding: utf-8 --
"""shell.run - 执行 shell 命令。

Security: level=2 and isolation=process; the pipeline owns gate and approval.
The plugin never judges permissions itself.

stdout / stderr 各自截断到 64 KiB，超出时追加提示行，避免一次命令灌满上下文。
"""
import os
import subprocess

MAX_OUTPUT_BYTES = 64 * 1024


def _clip(b):
    if b is None:
        return ""
    if len(b) <= MAX_OUTPUT_BYTES:
        return b.decode("utf-8", "replace").rstrip()
    head = b[:MAX_OUTPUT_BYTES].decode("utf-8", "replace").rstrip()
    return head + chr(10) + "[truncated at %d of %d bytes]" % (MAX_OUTPUT_BYTES, len(b))


def run(args, ctx):
    cmd = str(args.get("cmd") or "").strip()
    if not cmd:
        return {"ok": False, "outcome": "not_submitted", "err": "cmd is required"}
    try:
        timeout = int(args.get("timeout") or 120)
    except Exception:
        timeout = 120
    timeout = max(1, min(3600, timeout))
    cwd = str(args.get("cwd") or "").strip() or None
    if cwd:
        cwd = os.path.expanduser(cwd)
        if not os.path.isdir(cwd):
            return {"ok": False, "outcome": "not_submitted", "err": "cwd not a directory: " + cwd}
    try:
        p = subprocess.run(["sh", "-c", cmd], cwd=cwd, timeout=timeout,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except subprocess.TimeoutExpired:
        return {"ok": False, "outcome": "unknown", "err": "timeout after %ds" % timeout}
    except Exception as e:
        return {"ok": False, "outcome": "not_submitted", "err": repr(e)}
    out = _clip(p.stdout)
    err = _clip(p.stderr)
    ok = p.returncode == 0
    data = {"rc": p.returncode, "out": out, "err": err}
    return {"ok": ok, "outcome": "ok" if ok else "failed", "data": data,
        "err": (None if ok else (err or out or ("exit %d" % p.returncode)))}
