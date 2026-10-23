# -- coding: utf-8 --
"""zcore.action_manifest - parse and validate action manifests.

Any validation failure degrades to "not registered"; never blocks others.
Legacy single-file plugin (plugins/<id>.py) becomes a minimal manifest.
"""
import json
import os
import re

VALID_ISOLATION = ("inproc", "process")
ID_RE = re.compile(r"^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$")
ARG_TYPES = ("string", "int", "float", "bool", "list", "dict", "any")
DEFAULT_LEVEL = 99


class ManifestError(Exception):
    pass


def _err(msg):
    raise ManifestError(msg)


def normalize_args(spec):
    if spec is None:
        return {}
    if not isinstance(spec, dict):
        _err("args must be an object")
    out = {}
    for name, s in spec.items():
        if not isinstance(s, dict):
            if s is None:
                s = {"type": "string"}
            else:
                _err("args.%s must be an object" % name)
        t = str(s.get("type") or "any").lower()
        if t not in ARG_TYPES:
            _err("args.%s has unknown type %r" % (name, t))
        out[str(name)] = {
            "type": t,
            "required": bool(s.get("required", False)),
            "default": s.get("default", None),
            "desc": str(s.get("desc") or ""),
        }
    return out


VALID_REQUIRE_LEVELS = ("NONE", "PARTIAL", "REQUIRED")


def _normalize_requires(spec):
    # 宽容解析：任何字段类型不对都降级为默认值，绝不因为 requires 写错而丢弃动作。
    if not isinstance(spec, dict):
        return {}
    out = {}
    rv = str(spec.get("root") or "NONE").upper()
    if rv not in VALID_REQUIRE_LEVELS:
        rv = "NONE"
    out["root"] = rv
    av = str(spec.get("accessibility") or "NONE").upper()
    if av not in VALID_REQUIRE_LEVELS:
        av = "NONE"
    out["accessibility"] = av
    try:
        ms = int(spec.get("min_sdk") or 0)
    except Exception:
        ms = 0
    out["min_sdk"] = max(0, ms)
    api = spec.get("android_api") or []
    if isinstance(api, str):
        api = [api]
    if not isinstance(api, list):
        api = []
    out["android_api"] = [str(x) for x in api if x]
    return out


def satisfies(requires, snapshot):
    # requires 为空视为无条件；snapshot 缺字段时按不可用处理（保守拒绝）。
    if not requires:
        return True
    if not isinstance(snapshot, dict):
        return False
    need = requires.get("root") or "NONE"
    if need != "NONE":
        have = str(snapshot.get("root") or "denied")
        if have == "denied" or have == "unknown":
            return False
        if need == "REQUIRED" and have != "granted":
            return False
    needA = requires.get("accessibility") or "NONE"
    if needA == "REQUIRED" and not snapshot.get("accessibility"):
        return False
    ms = int(requires.get("min_sdk") or 0)
    if ms > 0:
        try:
            sdk = int(snapshot.get("sdk") or 0)
        except Exception:
            sdk = 0
        if sdk < ms:
            return False
    have_api = snapshot.get("android_api") or []
    have_set = set(str(x) for x in have_api) if isinstance(have_api, list) else set()
    for name in (requires.get("android_api") or []):
        if name not in have_set:
            return False
    return True


def coerce_args(manifest, args):
    spec = manifest.get("args") or {}
    src = dict(args or {})
    if not isinstance(src, dict):
        return None, "args must be a JSON object"
    out = {}
    for name, s in spec.items():
        if name in src and src[name] is not None:
            v = src[name]
        elif s.get("default") is not None:
            v = s["default"]
        elif s.get("required"):
            return None, "missing required arg %r" % name
        else:
            continue
        t = s.get("type")
        try:
            if t == "int":
                v = int(v)
            elif t == "float":
                v = float(v)
            elif t == "bool":
                v = v if isinstance(v, bool) else str(v).lower() in ("1", "true", "yes", "on")
            elif t == "string":
                v = str(v)
            elif t == "list":
                if isinstance(v, str):
                    v = [x for x in v.split(",") if x]
                elif not isinstance(v, list):
                    return None, "arg %r must be a list" % name
            elif t == "dict":
                if not isinstance(v, dict):
                    return None, "arg %r must be an object" % name
        except (TypeError, ValueError):
            return None, "arg %r cannot be coerced to %s" % (name, t)
        out[name] = v
    for k, v in src.items():
        if k not in out:
            out[k] = v
    return out, None


def from_dict(d, fallback_id=None):
    if not isinstance(d, dict):
        _err("manifest must be an object")
    aid = str(d.get("id") or fallback_id or "").strip()
    if not aid:
        _err("missing id")
    if not ID_RE.match(aid):
        _err("id %r must look like domain.name" % aid)
    level = d.get("level")
    if level is None:
        level = DEFAULT_LEVEL
    try:
        level = int(level)
    except (TypeError, ValueError):
        _err("level must be int")
    if not (0 <= level <= 99):
        _err("level out of range")
    iso = str(d.get("isolation") or "process").lower()
    if iso not in VALID_ISOLATION:
        _err("isolation must be one of %r" % (VALID_ISOLATION,))
    try:
        timeout = int(d.get("timeout") or 60)
    except (TypeError, ValueError):
        _err("timeout must be int")
    if timeout <= 0 or timeout > 3600:
        _err("timeout out of range (1..3600)")
    perm = d.get("permission") or []
    if isinstance(perm, str):
        perm = [perm]
    if not isinstance(perm, list):
        _err("permission must be a list")
    replaces = d.get("replaces") or []
    if isinstance(replaces, str):
        replaces = [replaces]
    tags = d.get("tags") or []
    if isinstance(tags, str):
        tags = [tags]
    return {
        "id": aid,
        "name": str(d.get("name") or aid),
        "desc": str(d.get("desc") or ""),
        "version": str(d.get("version") or "1.0"),
        "level": level,
        "permission": [str(x) for x in perm],
        "isolation": iso,
        "timeout": timeout,
        "args": normalize_args(d.get("args")),
        "returns": d.get("returns") or {},
        "replaces": [str(x) for x in replaces],
        "tags": [str(x) for x in tags],
        "entry": str(d.get("entry") or "main.py"),
        "ui": str(d.get("ui") or ""),
        "dir": d.get("dir"),
        "requires": _normalize_requires(d.get("requires")),
        "sensitive_args": bool(d.get("sensitive_args")),
        "sensitive_output": bool(d.get("sensitive_output")),
    }


def from_file(path, fallback_id=None):
    try:
        with open(path, "r", encoding="utf-8") as f:
            d = json.load(f)
    except FileNotFoundError:
        _err("manifest.json not found")
    except Exception as e:
        _err("manifest unreadable: %r" % (e,))
    d = dict(d)
    d.setdefault("dir", os.path.dirname(os.path.abspath(path)))
    return from_dict(d, fallback_id)


_LEGACY_NAME_RE = re.compile(r"@name[:\uff1a]?\s(.+)")
_LEGACY_DESC_RE = re.compile(r"@desc[:\uff1a]?\s(.+)")
_LEGACY_PERM_RE = re.compile(r"@permission[:\uff1a]?\s(.+)")
_LEGACY_LEVEL_RE = re.compile(r"@level[:\uff1a]?\s(\d+)")


def from_legacy_py(path):
    pid = os.path.basename(path)[:-3]
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            head = f.read(3000)
    except Exception as e:
        _err("legacy plugin unreadable: %r" % (e,))
    m = _LEGACY_NAME_RE.search(head)
    name = m.group(1).strip() if m else pid
    m = LEGACY_DESC_RE.search(head)
    desc = m.group(1).strip() if m else ""
    m = LEGACY_LEVEL_RE.search(head)
    level = int(m.group(1)) if m else 2
    m = LEGACY_PERM_RE.search(head)
    perm = [x.strip() for x in m.group(1).split(",") if x.strip()] if m else []
    return from_dict({
        "id": "legacy." + re.sub(r"[^a-z0-9]", "", pid.lower()),
        "name": name,
        "desc": desc,
        "level": level,
        "permission": perm,
        "isolation": "process",
        "timeout": 120,
        "dir": os.path.dirname(os.path.abspath(path)),
        "entry": os.path.basename(path),
        "tags": ["legacy"],
    })


def scan_dir(root):
    good, bad = [], []
    if not root or not os.path.isdir(root):
        return good, bad
    for fn in sorted(os.listdir(root)):
        if fn.startswith((".", "_")):
            continue
        p = os.path.join(root, fn)
        try:
            if os.path.isdir(p):
                mf = os.path.join(p, "manifest.json")
                if not os.path.exists(mf):
                    continue
                good.append(from_file(mf, fallback_id=fn))
            elif fn.endswith(".py"):
                good.append(from_legacy_py(p))
        except ManifestError as e:
            bad.append({"path": p, "err": str(e)})
        except Exception as e:
            bad.append({"path": p, "err": repr(e)})
    return good, bad