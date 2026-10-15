import json
import os
import re
import time

_MIND = os.path.join(os.path.expanduser('~'), 'mind')
_DIR = os.path.join(_MIND, 'checkpoints')


def _slug(name):
    s = re.sub(r'[^A-Za-z0-9_._-]+', '_', str(name))[:80]
    return s or 'unnamed'


def _path_for(name):
    return os.path.join(_DIR, _slug(name) + '.json')


def save(name, state, note=None):
    from zcore.util import atomic_write
    os.makedirs(_DIR, exist_ok=True)
    entry = {
        'name': _slug(name),
        'ts': time.time(),
        'ts_str': time.strftime('%Y-%m-%d %H:%M:%S'),
        'note': note or '',
        'state': state,
    }
    p = _path_for(name)
    atomic_write(p, json.dumps(entry, ensure_ascii=False, indent=1, sort_keys=True))
    return p


def load(name):
    p = _path_for(name)
    try:
        with open(p, encoding='utf-8') as f:
            return json.load(f)
    except Exception:
        return None


def clear(name):
    p = _path_for(name)
    try:
        os.remove(p)
        return True
    except OSError:
        return False


def list_all():
    try:
        names = os.listdir(_DIR)
    except OSError:
        return []
    out = []
    for n in names:
        if not n.endswith('.json'):
            continue
        full = os.path.join(_DIR, n)
        try:
            sz = os.path.getsize(full)
        except OSError:
            sz = 0
        try:
            with open(full, encoding='utf-8') as f:
                j = json.load(f)
                out.append((j.get('name', n[:-5]), j.get('ts_str', '?'), j.get('ts', 0), sz, j.get('note', '')))
        except Exception:
            out.append((n[:-5], '?', 0, sz, ''))
    out.sort(key=lambda t: t[2], reverse=True)
    return out


def latest():
    rows = list_all()
    if not rows:
        return None
    return load(rows[0][0])