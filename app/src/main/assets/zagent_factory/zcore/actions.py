# -- coding: utf-8 --
"""zcore.actions - unified action registry and six-stage pipeline.

parse -> resolve -> gate -> approve -> execute -> audit

Rules:
 1. Unknown id returns NOT_FOUND; never falls through to a legacy branch.
 2. Gate uses manifest.level; undeclared level counts as 99 (denied).
 3. Host injects an approver; without one, approval is skipped.
"""
import base64
import hashlib
import importlib.util
import json
import os
import re
import sys
import time
import traceback

try:
    from . import gate as _gate
    from . import proc as _proc
    try:
        from .util import safe_child_env as _safe_env
    except Exception:
        _safe_env = None
    from .action_manifest import (ManifestError, coerce_args, from_file,
        scan_dir)
except ImportError:
    import gate as _gate
    import proc as _proc
    from action_manifest import (ManifestError, coerce_args, from_file,
        scan_dir)

HOME = os.path.expanduser(chr(126))
MIND = os.path.join(HOME, "mind")
AUDIT_PATH = os.path.join(MIND, "actions.jsonl")
AUDIT_MAX = 2 * 1024 * 1024

# 结果语义四态：调用方必须按 outcome 决定是否重试，不能只看 ok。
# ok 明确成功
# failed 明确失败，可换路径重试
# not_submitted 未提交给系统，可安全重试
# unknown 可能已执行，禁止任何自动重试
OUTCOME_OK = "ok"
OUTCOME_FAILED = "failed"
OUTCOME_NOT_SUBMITTED = "not_submitted"
OUTCOME_UNKNOWN = "unknown"

RETRYABLE_OUTCOMES = (OUTCOME_FAILED, OUTCOME_NOT_SUBMITTED)

APPROVER = None


def set_approver(fn):
    global APPROVER
    APPROVER = fn


USER_SCOPE_UNSUPPORTED = "user_scope_unsupported"

_CURRENT_USER_ID = [None]


def current_user_id():
    if _CURRENT_USER_ID[0] is None:
        try:
            import android_api
            _CURRENT_USER_ID[0] = int(android_api.app_user_id() or 0)
        except Exception:
            _CURRENT_USER_ID[0] = 0
    return _CURRENT_USER_ID[0]


def _check_user_scope(coerced):
    if "user_id" not in coerced:
        return None
    want = coerced.get("user_id")
    if want is None:
        return None
    try:
        want_int = int(want)
    except Exception:
        return USER_SCOPE_UNSUPPORTED + ": user_id must be int"
    cur = current_user_id()
    if cur <= 0:
        return None
    if want_int != cur:
        return USER_SCOPE_UNSUPPORTED + ": requested user_id=%d, current=%d" % (want_int, cur)
    return None




def _check_arg_semantics(aid, coerced):
    if not isinstance(coerced, dict):
        return None
    if aid == "android.shell_exec":
        cmd = str(coerced.get("cmd") or "").lower()
        if "rm -rf /" in cmd or "rm -fr /" in cmd:
            return "POLICY: rm -rf / would wipe root"
        if "mkfs" in cmd:
            return "POLICY: mkfs formats a partition"
        if "of=/dev/block" in cmd or "of=/dev/sd" in cmd or "of=/dev/mmcblk" in cmd:
            return "POLICY: dd writes a raw block device"
        if ">/dev/block" in cmd or "> /dev/block" in cmd:
            return "POLICY: redirect into a block device"
        for w in ("reboot", "shutdown", "poweroff"):
            if w in cmd:
                return "POLICY: " + w + " needs manual confirmation"
        return None
    if aid == "file.write":
        p = str(coerced.get("path") or "")
        if not p:
            return None
        try:
            norm = os.path.abspath(os.path.expanduser(p))
        except Exception:
            return None
        for pre in ("/system", "/vendor", "/product", "/odm", "/boot", "/data/system", "/data/adb"):
            if norm == pre or norm.startswith(pre + "/"):
                return "POLICY: refuse to write " + pre
        return None
    return None

def sh_quote(s):
    return chr(39) + str(s).replace(chr(39), chr(39) + chr(92) + chr(39) + chr(39)) + chr(39)


def _audit(rec):
    # 追加一行审计；超过 AUDIT_MAX 时轮转为 .1（单代，够用且简单）
    try:
        os.makedirs(MIND, exist_ok=True)
        try:
            if os.path.getsize(AUDIT_PATH) > AUDIT_MAX:
                os.replace(AUDIT_PATH, AUDIT_PATH + ".1")
        except OSError:
            pass
        line = json.dumps(rec, ensure_ascii=False, sort_keys=True)
        with open(AUDIT_PATH, "a", encoding="utf-8") as f:
            f.write(line + chr(10))
    except Exception:
        pass


REDACTED = {"redacted": True}

def _manifest_sensitive(aid, which):
    m = REGISTRY.get(aid) or {}
    return bool(m.get(which))

def redact_args(aid, args):
    if _manifest_sensitive(aid, "sensitive_args"):
        return {"redacted": True, "type": aid}
    return args

def redact_result(aid, result):
    if _manifest_sensitive(aid, "sensitive_output"):
        r = {"redacted": True, "type": aid}
        if isinstance(result, dict) and result.get("outcome"):
            r["outcome"] = result["outcome"]
        return r
    return result

def redact_text(text):
    if not text or "[act:" not in text:
        return text
    calls, _errors = parse(text)
    out = text
    for aid, _args, (s, e) in reversed(calls):
        if not _manifest_sensitive(aid, "sensitive_output"):
            continue
        repl = "[act:%s]%s" % (aid, json.dumps({"redacted": True, "type": aid}, ensure_ascii=False))
        out = out[:s] + repl + out[e:]
    return out

def _args_hash(args):
    try:
        raw = json.dumps(args, ensure_ascii=False, sort_keys=True).encode("utf-8")
    except Exception:
        raw = repr(args).encode("utf-8", "replace")
    return hashlib.sha256(raw).hexdigest()[:16]


_GATE_BY_LEVEL = {0: "read", 1: "read", 2: "plugin.run", 3: "evolve"}

ACT_OPEN = "[act:"


def parse(text):
    calls, errors = [], []
    if not text or ACT_OPEN not in text:
        return calls, errors
    i = 0
    while True:
        s = text.find(ACT_OPEN, i)
        if s < 0:
            break
        p = s + len(ACT_OPEN)
        m = re.match(r"[A-Za-z0-9_.-]+", text[p:])
        if not m:
            errors.append({"pos": s, "err": "empty action id"})
            i = p
            continue
        aid = m.group(0)
        q = p + len(aid)
        depth, in_str, esc, k = 0, False, False, q
        while k < len(text):
            ch = text[k]
            if in_str:
                if esc:
                    esc = False
                elif ch == chr(92):
                    esc = True
                elif ch == chr(34):
                    in_str = False
            else:
                if ch == chr(34):
                    in_str = True
                elif ch == "]" and depth == 0:
                    break
                elif ch in "{[":
                    depth += 1
                elif ch in "}]" and depth > 0:
                    depth -= 1
            k += 1
        if k >= len(text):
            errors.append({"pos": s, "err": "unterminated block", "id": aid})
            break
        body = text[q:k].strip()
        args = {}
        if body:
            try:
                args = json.loads(body)
                if not isinstance(args, dict):
                    raise ValueError("args must be a JSON object")
            except Exception as e:
                errors.append({"pos": s, "err": "bad JSON args: %r" % (e,), "id": aid})
                i = k + 1
                continue
        calls.append((aid, args, (s, k + 1)))
        i = k + 1
    return calls, errors


def strip_calls(text, spans):
    for s, e in reversed(spans):
        text = text[:s] + chr(10) + "[act]" + chr(10) + text[e:]
    return text


class Registry(object):
    def __init__(self):
        self._by_id = {}
        self.bad = []
        self.scanned_at = 0.0
    
    def clear(self):
        self._by_id.clear()
        self.bad = []
    
    def add(self, m):
        self._by_id[m["id"]] = m
    
    def scan(self, roots):
        self.clear()
        for r in roots:
            good, bad = scan_dir(r)
            for m in good:
                self.add(m)
            self.bad.extend(bad)
        self.scanned_at = time.time()
        return self
    
    def get(self, aid):
        return self._by_id.get(aid)
    
    def ids(self):
        return sorted(self._by_id)
    
    def level_of(self, aid):
        m = self._by_id.get(aid)
        return 99 if m is None else int(m["level"])
    
    def prompt_block(self, max_desc=120):
        if not self._by_id:
            return ""
        lines = [chr(10) + chr(10) + "[available actions: [act:id {json}]]"]
        for aid in self.ids():
            m = self._by_id[aid]
            d = (m.get("desc") or "").strip().replace(chr(10), " ")
            if len(d) > max_desc:
                d = d[:max_desc] + "..."
            sig = ", ".join(sorted((m.get("args") or {}).keys()))
            lines.append("- %s | %s | level=%d | args: %s" % (
                aid, m.get("name") or aid, m["level"], sig or "-"))
            if d:
                lines.append(" " + d)
        return chr(10).join(lines)
    
    def as_json(self):
        return {
            "actions": [self._by_id[k] for k in self.ids()],
            "bad": list(self.bad),
            "scanned_at": int(self.scanned_at),
        }


REGISTRY = Registry()


def registry():
    return REGISTRY


def _gate_level():
    try:
        return int(_gate.status().get("level") or 0)
    except Exception:
        return 0


def _result(aid, ok, data=None, err=None, meta=None, outcome=None):
    # outcome 缺省按 ok 推断；显式传入时以调用方为准（如超时应为 unknown）。
    if outcome is None:
        outcome = OUTCOME_OK if ok else OUTCOME_FAILED
    r = {}
    r["ok"] = bool(ok)
    r["outcome"] = outcome
    r["data"] = data
    r["err"] = err
    m = {}
    m["id"] = aid
    if meta:
        m.update(meta)
    r["meta"] = m
    return r


def is_retryable(result):
    # 只有 failed / not_submitted 允许重试；unknown 一律禁止。
    return (result or {}).get("outcome") in RETRYABLE_OUTCOMES


def _exec_inproc(m, args, ctx):
    path = os.path.join(m.get("dir") or "", m.get("entry") or "main.py")
    if not os.path.exists(path):
        return {"ok": False, "err": "entry not found: " + path}
    modname = "zact_" + m["id"].replace(".", "_")
    spec = importlib.util.spec_from_file_location(modname, path)
    if spec is None or spec.loader is None:
        return {"ok": False, "err": "cannot load entry: " + path}
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    fn = getattr(mod, "run", None)
    if fn is None:
        return {"ok": False, "err": "entry has no run(args, ctx)"}
    code = getattr(fn, "__code__", None)
    if code is not None and code.co_argcount <= 1:
        r = fn(args)
    else:
        r = fn(args, ctx)
    if not isinstance(r, dict):
        r = {"ok": True, "data": r}
    r.setdefault("ok", True)
    return r


_RUNNER = (
    "import base64, importlib.util, json, sys, traceback" + chr(10) +
    "payload = json.loads(base64.b64decode(sys.argv[1]).decode('utf-8'))" + chr(10) +
    "try:" + chr(10) +
    " spec = importlib.util.spec_from_file_location(payload['modname'], payload['path'])" + chr(10) +
    " mod = importlib.util.module_from_spec(spec)" + chr(10) +
    " spec.loader.exec_module(mod)" + chr(10) +
    " fn = getattr(mod, 'run', None)" + chr(10) +
    " if fn is None:" + chr(10) +
    " sys.stdout.write(json.dumps({'ok': False, 'err': 'missing run'}, ensure_ascii=False))" + chr(10) +
    " sys.exit(3)" + chr(10) +
    " __code = getattr(fn, 'code', None)" + chr(10) +
    " if __code is not None and __code.co_argcount <= 1:" + chr(10) +
    " r = fn(payload['args'])" + chr(10) +
    " else:" + chr(10) +
    " r = fn(payload['args'], payload.get('ctx') or {})" + chr(10) +
    " if not isinstance(r, dict):" + chr(10) +
    " r = {'ok': True, 'data': r}" + chr(10) +
    " r.setdefault('ok', True)" + chr(10) +
    " sys.stdout.write(json.dumps(r, ensure_ascii=False, default=str))" + chr(10) +
    "except SystemExit:" + chr(10) +
    " raise" + chr(10) +
    "except Exception:" + chr(10) +
    " sys.stdout.write(json.dumps({'ok': False, 'err': traceback.format_exc()[-4000:]}, ensure_ascii=False))" + chr(10) +
    " sys.exit(1)" + chr(10)
)


def _exec_process(m, args, ctx):
    path = os.path.join(m.get("dir") or "", m.get("entry") or "main.py")
    if not os.path.exists(path):
        return {"ok": False, "err": "entry not found: " + path}
    payload = {"path": path, "modname": "zact_" + m["id"].replace(".", "_"),
        "args": args, "ctx": ctx}
    blob = base64.b64encode(
        json.dumps(payload, ensure_ascii=False).encode("utf-8")).decode("ascii")
    res = _proc.run([sys.executable, "-c", _RUNNER, blob],
        timeout=int(m.get("timeout") or 60), env=(_safe_env() if _safe_env else None))
    if res.get("kind") == "timeout":
        # 子进程已启动，可能已产生副作用，标记 unknown 禁止重试。
        return {"ok": False, "outcome": OUTCOME_UNKNOWN,
            "err": "timeout after %ss" % m.get("timeout")}
    out = (res.get("out") or "").strip()
    try:
        parsed = json.loads(out) if out else None
    except Exception:
        parsed = None
    if parsed is None:
        return {"ok": False, "err": (res.get("err") or out or "no output")[-2000:]}
    return parsed


def _pick_runner(m):
    if os.environ.get("ZAGENT_NO_SUBPROCESS") == "1":
        return _exec_inproc
    if m.get("isolation") == "process":
        return _exec_process
    return _exec_inproc


def execute(aid, args, ctx=None, approve=True):
    t0 = time.time()
    ctx = ctx or {}
    m = REGISTRY.get(aid)
    if m is None:
        _audit({"ts": int(t0), "id": aid, "ok": False, "err": "not_found", "ms": 0})
        return _result(aid, False, err="NOT_FOUND: %r" % aid)
    coerced, cerr = coerce_args(m, args)
    if cerr:
        _audit({"ts": int(t0), "id": aid, "ok": False, "err": "args", "ms": 0})
        return _result(aid, False, err="ARGS: " + cerr, meta={"level": m["level"]})
    scope_err = _check_user_scope(coerced)
    if scope_err:
        _audit({"ts": int(t0), "id": aid, "ok": False, "err": "user_scope", "ms": 0})
        return _result(aid, False, err=scope_err, meta={"level": m["level"]})
    policy_err = _check_arg_semantics(aid, coerced)
    if policy_err:
        return _result(aid, False, err=policy_err, outcome=OUTCOME_NOT_SUBMITTED)
    _cap = ctx.get("cap") if isinstance(ctx, dict) else None
    if isinstance(_cap, dict):
        _cl = _cap.get("level")
        if _cl is not None:
            try:
                _cli = int(_cl)
            except Exception:
                _cli = None
            if _cli is not None and int(m["level"]) > _cli:
                _audit({"ts": int(t0), "id": aid, "ok": False, "err": "cap", "ms": 0})
                return _result(aid, False, err="CAP: level %d > cap %d" % (int(m["level"]), _cli), meta={"level": m["level"]})
    try:
        okg, why = _gate.check(_GATE_BY_LEVEL.get(int(m["level"]), "plugin.run"))
    except SystemExit:
        raise
    except Exception as e:
        okg, why = False, repr(e)
    if not okg:
        _audit({"ts": int(t0), "id": aid, "ok": False, "err": "gate", "ms": 0})
        return _result(aid, False, err="GATE: " + why, meta={"level": m["level"]})
    cur = _gate_level()
    if cur < int(m["level"]):
        msg = "level %d < required %d for %r" % (cur, m["level"], aid)
        _audit({"ts": int(t0), "id": aid, "ok": False, "err": "level", "ms": 0})
        return _result(aid, False, err="GATE: " + msg, meta={"level": m["level"]})
    if approve and APPROVER is not None and int(m["level"]) <= 2:
        try:
            if not APPROVER(m, coerced):
                _audit({"ts": int(t0), "id": aid, "ok": False, "err": "denied", "ms": 0})
                return _result(aid, False, err="DENIED", meta={"level": m["level"]})
        except Exception:
            pass
    runner = _pick_runner(m)
    try:
        r = runner(m, coerced, ctx)
    except SystemExit:
        raise
    except Exception:
        # 执行器内部异常无法判定动作是否生效，保守标记 unknown。
        r = {"ok": False, "outcome": OUTCOME_UNKNOWN,
            "err": traceback.format_exc()[-3000:]}
    ms = int((time.time() - t0) * 1000)
    ok = bool(r.get("ok"))
    audit_args = {"redacted": True, "type": aid} if m.get("sensitive_args") else _args_hash(coerced)
    _audit({"ts": int(t0), "id": aid, "ok": ok, "ms": ms,
        "args": audit_args, "level": m["level"],
        "err": (None if ok else str(r.get("err"))[:400])})
    return _result(aid, ok, data=r.get("data"), err=r.get("err"),
        outcome=r.get("outcome"),
        meta={"level": m["level"], "ms": ms, "isolation": m.get("isolation")})


def execute_text(text, ctx=None, approve=True):
    calls, errors = parse(text)
    results = []
    for aid, args, _span in calls:
        results.append(execute(aid, args, ctx=ctx, approve=approve))
    stripped = strip_calls(text, [c[2] for c in calls])
    return results, errors, stripped


def format_results(results):
    if not results:
        return ""
    lines = []
    for r in results:
        m = r.get("meta") or {}
        outcome = r.get("outcome") or (OUTCOME_OK if r.get("ok") else OUTCOME_FAILED)
        tag = "OK"
        if outcome == OUTCOME_UNKNOWN:
            tag = "UNKNOWN"
        elif outcome == OUTCOME_NOT_SUBMITTED:
            tag = "NOT_SUBMITTED"
        elif not r.get("ok"):
            tag = "FAIL"
        head = "[%s] %s (%sms)" % (m.get("id"), tag, m.get("ms", 0))
        lines.append(head)
        if r.get("ok"):
            d = r.get("data")
            body = d if isinstance(d, str) else json.dumps(d, ensure_ascii=False, default=str)
            lines.append(body[:4000] if body else "(no output)")
        else:
            lines.append(str(r.get("err"))[:2000])
            if outcome == OUTCOME_UNKNOWN:
                lines.append("（该动作可能已生效，请先重新观察，不要直接重试）")
        lines.append("")
    return chr(10).join(lines).rstrip()