# -- coding: utf-8 --
# zcore.facts - fact cache (P1): reduce repeated exploration
#
# storage: ~/mind/facts.json (independent from gate state)
# optional TTL in seconds (None = never expire)
# only JSON-serializable values accepted
# atomic write via zcore.util.atomic_write
# module-level in-memory singleton, lazy-loaded
import json
import os
import time

_MIND = os.path.join(os.path.expanduser('~'), 'mind')
_FACTS = os.path.join(_MIND, 'facts.json')
_cache = None


def _load():
    global _cache
    if _cache is not None:
        return
    try:
        with open(_FACTS, encoding='utf-8') as f:
            _cache = json.load(f)
        if not isinstance(_cache, dict):
            _cache = {}
    except Exception:
        _cache = {}


def _save():
    from zcore.util import atomic_write
    os.makedirs(_MIND, exist_ok=True)
    atomic_write(_FACTS, json.dumps(_cache, ensure_ascii=False, indent=1, sort_keys=True))


def _expired(entry):
    ttl = entry.get('ttl')
    if not ttl:
        return False
    return (time.time() - entry.get('ts', 0)) > ttl


def set(key, value, ttl=None):
    # write one fact; ttl seconds until expiry (None = forever)
    _load()
    _cache[str(key)] = {'v': value, 'ts': time.time(), 'ttl': ttl}
    _save()


def get(key, default=None):
    _load()
    k = str(key)
    e = _cache.get(k)
    if e is None:
        return default
    if _expired(e):
        del _cache[k]
        _save()
        return default
    return e.get('v')


def forget(key):
    _load()
    k = str(key)
    if k in _cache:
        del _cache[k]
        _save()
        return True
    return False


def clear_expired():
    _load()
    dead = [k for k, e in _cache.items() if _expired(e)]
    for k in dead:
        del _cache[k]
    if dead:
        _save()
    return len(dead)


def keys(prefix=None):
    _load()
    out = []
    for k, e in _cache.items():
        if _expired(e):
            continue
        if prefix is not None and not k.startswith(prefix):
            continue
        out.append(k)
    return sorted(out)


def cached(key, fn, ttl=None):
    # if key cached return; else call fn() and cache its return
    _load()
    hit = get(key)
    if hit is not None:
        return hit
    val = fn()
    set(key, val, ttl)
    return val