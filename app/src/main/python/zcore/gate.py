# zcore/gate.py —— 权限门 + 熔断 + Kill 开关 + 能力白名单
#
# 目标：把「模型自愿遵守」变成「不遵守走不通」。
# 状态存储：~/mind/.gate.json（level / actions / expires / actor）
# 熔断：~/mind/.circuit（存在即锁死到 L0，AI 无权删）
# Kill：~/mind/.kill（外部可建，主循环与 check 都检测）
# 会话：~/mind/.session（首次自动生成随机 sid）
import json, os, time, uuid

HOME = os.path.expanduser("~")
MIND = os.path.join(HOME, "mind")
GATE_PATH = os.path.join(MIND, ".gate.json")
CIRCUIT_PATH = os.path.join(MIND, ".circuit")
KILL_PATH = os.path.join(MIND, ".kill")
SESSION_PATH = os.path.join(MIND, ".session")

L0_OBSERVE = 0
L1_ADVISE = 1
L2_SEMI = 2
L3_AUTO = 3

# 每个 action 的最低权限等级
MIN_LEVEL = {
    "read": 0,
    "file.write.tmp": 1,
    "file.write.mind": 1,
    "file.write.ext": 2,
    "file.delete": 2,
    "script.run": 2,
    "subprocess.run": 2,
    "plugin.run": 2,
    "evolve": 3,
    "restart": 3,
    "kill.clear": 99,
    "circuit.clear": 99,
    "gate.grant": 99,
}

def _ensure_mind():
    os.makedirs(MIND, exist_ok=True)

def _get_sid():
    _ensure_mind()
    try:
        if os.path.exists(SESSION_PATH):
            with open(SESSION_PATH, encoding="utf-8") as _f:
                v = _f.read().strip()
            if v:
                return v
    except Exception:
        pass
    sid = uuid.uuid4().hex[:10]
    try:
        with open(SESSION_PATH, "w", encoding="utf-8") as f:
            f.write(sid)
    except Exception:
        pass
    return sid

def _load():
    _ensure_mind()
    try:
        if os.path.exists(GATE_PATH):
            with open(GATE_PATH, encoding="utf-8") as _f:
                return json.load(_f)
    except Exception:
        pass
    return {"level": L2_SEMI, "actions": None, "expires": 0,
        "actor": "human:local", "ts": int(time.time())}

def _save(state):
    _ensure_mind()
    tmp = GATE_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False)
    os.replace(tmp, GATE_PATH)

def kill_check():
    """主循环与每次 check() 都调用；存在 ~/mind/.kill 即抛 SystemExit。"""
    if os.path.exists(KILL_PATH):
        try:
            print("[gate] kill switch detected: %s" % KILL_PATH)
        except Exception:
            pass
        raise SystemExit(42)

def circuit_status():
    if os.path.exists(CIRCUIT_PATH):
        try:
            with open(CIRCUIT_PATH, encoding="utf-8") as _f:
                return _f.read().strip()
        except Exception:
            return "(unreadable)"
    return None

def circuit_trip(reason):
    """熔断：写锁文件 + 强制降到 L0 + 清空 actions。"""
    _ensure_mind()
    try:
        with open(CIRCUIT_PATH, "a", encoding="utf-8") as f:
            f.write("[%d] %s" % (int(time.time()), reason) + chr(10))
    except Exception:
        pass
    s = _load()
    s["level"] = L0_OBSERVE
    s["actions"] = []
    s["expires"] = 0
    s["circuit"] = True
    s["ts"] = int(time.time())
    _save(s)

def grant(level, actions=None, ttl=3600, actor="human:local"):
    """给 level 与可选 actions 白名单。actions=None 表示无 action 限制。"""
    s = _load()
    s["level"] = int(level)
    s["actions"] = actions
    s["expires"] = int(time.time()) + int(ttl) if ttl and ttl > 0 else 0
    s["actor"] = actor
    s["ts"] = int(time.time())
    s["circuit"] = False
    _save(s)
    # 清掉熔断锁（授权即视为人类干预）
    try:
        if os.path.exists(CIRCUIT_PATH):
            os.remove(CIRCUIT_PATH)
    except Exception:
        pass
    return s

def revoke():
    return grant(L0_OBSERVE, actions=[], ttl=0, actor="human:local")

def _expired(s):
    exp = int(s.get("expires") or 0)
    if exp <= 0:
        return False
    return time.time() > exp

def check(action, payload=None):
    """返回 (ok, reason)；绝不抛异常（kill 除外）。"""
    kill_check()
    cs = circuit_status()
    if cs:
        return False, "circuit open: " + cs.replace(chr(10), " | ")
    s = _load()
    if _expired(s):
        s = revoke()
    cur = int(s.get("level") or 0)
    req = MIN_LEVEL.get(action, 99)
    if cur < req:
        return False, "level %d < required %d for %r" % (cur, req, action)
    acts = s.get("actions")
    if acts is not None and action not in acts:
        return False, "action %r not in whitelist %r" % (action, acts)
    return True, "ok"

def check_or_raise(action, payload=None):
    ok, why = check(action, payload)
    if not ok:
        raise PermissionError("[gate] %s: %s" % (action, why))
    return True

def status():
    s = _load()
    return {
        "level": s.get("level"),
        "actions": s.get("actions"),
        "expires": s.get("expires"),
        "actor": s.get("actor"),
        "circuit": circuit_status(),
        "sid": _get_sid(),
        "gate_path": GATE_PATH,
        "kill_path": KILL_PATH,
    }

def format_status():
    st = status()
    lines = ["[gate] level=%s actions=%s expires=%s actor=%s sid=%s" % (
        st["level"], st["actions"], st["expires"], st["actor"], st["sid"])]
    if st["circuit"]:
        lines.append("[gate] CIRCUIT OPEN: " + st["circuit"])
    if os.path.exists(KILL_PATH):
        lines.append("[gate] KILL FILE PRESENT: " + KILL_PATH)
    return chr(10).join(lines)

def human_grant_command(rest):
    """解析 /grant <level> [action...] [ttl=N]"""
    parts = (rest or "").split()
    if not parts:
        print("用法: /grant <level 0-3> [action action ...] [ttl=N]")
        print("例: /grant 2 script.run subprocess.run ttl=1800")
        return
    try:
        lvl = int(parts[0])
    except Exception:
        print("level 必须是 0-3")
        return
    actions = None
    ttl = 3600
    for p in parts[1:]:
        if p.startswith("ttl="):
            try:
                ttl = int(p[4:])
            except Exception:
                pass
        else:
            actions = actions or []
            actions.append(p)
    s = grant(lvl, actions=actions, ttl=ttl, actor="human:local")
    print("已授权: level=%s actions=%s expires=%s" % (
        s["level"], s["actions"], s["expires"]))

def human_revoke_command():
    revoke()
    print("已收回权限，降为 L0 观察。")