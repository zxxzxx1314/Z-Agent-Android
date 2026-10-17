import json
import os
import time

_MIND = os.path.join(os.path.expanduser('~'), 'mind')
_FILE = os.path.join(_MIND, 'events.jsonl')


def emit(kind, **fields):
    try:
        os.makedirs(_MIND, exist_ok=True)
        row = {'ts': time.time(), 'ts_str': time.strftime('%Y-%m-%d %H:%M:%S'), 'kind': str(kind)}
        for k, v in fields.items():
            row[k] = v
        with open(_FILE, 'a', encoding='utf-8') as f:
            f.write(json.dumps(row, ensure_ascii=False) + chr(10))
        return True
    except Exception:
        return False


def tail(n=20):
    n = max(1, int(n))
    try:
        with open(_FILE, 'r', encoding='utf-8', errors='ignore') as f:
            lines = f.readlines()
    except OSError:
        return []
    out = []
    for ln in lines[-n:]:
        ln = ln.strip()
        if not ln:
            continue
        try:
            out.append(json.loads(ln))
        except Exception:
            pass
    return out


def clear():
    try:
        os.remove(_FILE)
        return True
    except OSError:
        return False


def count():
    try:
        with open(_FILE, 'r', encoding='utf-8', errors='ignore') as f:
            return sum(1 for _ in f)
    except OSError:
        return 0
