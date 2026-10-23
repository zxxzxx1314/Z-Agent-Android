import os, re, signal, subprocess, time


ADVICE = {
    "timeout": "拆分命令 / 提高 timeout / 检查死循环",
    "not_found": "检查路径 / 装依赖 / which 探测",
    "permission": "换方案（改路径/加权限/shizuku），不要原地重试",
    "killed": "降低并发（最多 2）/ 减小内存峰值",
    "exit": "看 stderr 定位业务错误",
    "internal": "封装自身异常，需人工排查",
    "ok": "",
}


def classify(rc, stderr, timedout):
    if timedout:
        return "timeout"
    if rc == 0:
        return "ok"
    if stderr:
        if re.search(r"not found|no such file", stderr[:2048], re.I):
            return "not_found"
        if re.search(r"permission denied|eacces", stderr[:2048], re.I):
            return "permission"
        if re.search(r"out of memory|oom|killed", stderr[:2048], re.I):
            return "killed"
    if rc == 127:
        return "not_found"
    if rc == 126:
        return "permission"
    if rc == -9 or rc == -15:
        return "killed"
    return "exit"


def _oserr_kind(e, argv0=None):
    if argv0 and argv0.startswith("/") and not os.path.exists(argv0):
        return "not_found"
    en = getattr(e, "errno", None)
    if en == 2:
        return "not_found"
    if en == 13:
        return "permission"
    return "internal"


def _terminate_group(proc):
    pgid = proc.pid
    try:
        os.killpg(pgid, signal.SIGTERM)
    except OSError:
        pass
    try:
        proc.wait(timeout=2)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(pgid, signal.SIGKILL)
    except OSError:
        pass
    try:
        proc.wait(timeout=2)
    except Exception:
        pass


def run(argv, timeout=120, env=None):
    if isinstance(argv, str):
        argv = ["sh", "-c", argv]
    argv0 = argv[0] if argv else None
    t0 = time.time()
    def ms():
        return int((time.time() - t0) / 0.001)
    try:
        p = subprocess.Popen(
            argv,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
            start_new_session=True,
        )
    except (FileNotFoundError, PermissionError, OSError) as e:
        k = _oserr_kind(e, argv0)
        rc = 127 if k == "not_found" else (126 if k == "permission" else -1)
        return {"rc": rc, "out": "", "err": str(e), "kind": k, "advice": ADVICE.get(k, ""), "ms": ms()}
    except Exception as e:
        return {"rc": -1, "out": "", "err": repr(e), "kind": "internal", "advice": ADVICE["internal"], "ms": ms()}
    try:
        out_b, err_b = p.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        _terminate_group(p)
        return {"rc": 124, "out": "", "err": "timeout", "kind": "timeout", "advice": ADVICE["timeout"], "ms": ms()}
    except Exception as e:
        _terminate_group(p)
        return {"rc": -1, "out": "", "err": repr(e), "kind": "internal", "advice": ADVICE["internal"], "ms": ms()}
    out = (out_b or b"").decode("utf-8", "replace").rstrip()
    err = (err_b or b"").decode("utf-8", "replace").rstrip()
    kind = classify(p.returncode, err, False)
    return {"rc": p.returncode, "out": out, "err": err, "kind": kind, "advice": ADVICE.get(kind, ""), "ms": ms()}


def annotate(rc, err, timed_out=False):
    if rc == 0:
        return err
    k = classify(rc, err, timed_out)
    a = ADVICE.get(k, "")
    if not a:
        return err
    tag = "[proc:" + k + "] " + a
    return (err + chr(10) + tag) if err else tag