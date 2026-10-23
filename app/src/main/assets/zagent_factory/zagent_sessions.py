import json
import os
import sqlite3
import time

DB_NAME = 'sessions.db'


def _d(home):
    d = os.path.join(home, 'sessions')
    os.makedirs(d, exist_ok=True)
    return d


def _dbp(home):
    return os.path.join(_d(home), DB_NAME)


def _conn(home):
    c = sqlite3.connect(_dbp(home), timeout=10.0)
    try:
        c.execute('PRAGMA journal_mode=WAL')
    except Exception:
        pass
    c.execute('CREATE TABLE IF NOT EXISTS sessions (sid TEXT PRIMARY KEY, title TEXT, mtime INTEGER)')
    c.execute('CREATE TABLE IF NOT EXISTS messages (sid TEXT, seq INTEGER, role TEXT, body TEXT, PRIMARY KEY (sid, seq))')
    c.execute('CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT)')
    c.commit()
    _migrate(home, c)
    return c


def _migrate(home, c):
    if c.execute('SELECT v FROM kv WHERE k=?', ('m1',)).fetchone():
        return
    d = _d(home)
    try:
        names = os.listdir(d)
    except Exception:
        names = []
    for name in names:
        if not name.endswith('.json') or name == 'index.json':
            continue
        sid = name[:-5]
        try:
            with open(os.path.join(d, name), 'r', encoding='utf-8') as f:
                msgs = json.load(f)
        except Exception:
            msgs = []
        if not isinstance(msgs, list):
            msgs = []
        c.execute('INSERT OR IGNORE INTO sessions VALUES (?,?,?)', (sid, '', int(time.time())))
        i = 0
        for m in msgs:
            if not isinstance(m, dict):
                continue
            r = m.get('role')
            if r not in ('user', 'assistant'):
                continue
            c.execute('INSERT OR REPLACE INTO messages VALUES (?,?,?,?)', (sid, i, r, m.get('content') or ''))
            i += 1
    c.execute('INSERT OR REPLACE INTO kv VALUES (?,?)', ('m1', '1'))
    c.commit()
    for name in names:
        if name.endswith('.json'):
            try:
                os.rename(os.path.join(d, name), os.path.join(d, name + '.migrated'))
            except Exception:
                pass


def _tt(msgs):
    for m in msgs:
        if isinstance(m, dict) and m.get('role') == 'user':
            t = (m.get('content') or '').strip().replace(chr(10), ' ')
            if t:
                return t[:24]
    return '(empty)'


def _redact(msgs):
    try:
        from zcore.actions import redact_text
    except Exception:
        return msgs
    out = []
    for m in msgs:
        t = m.get('content') or ''
        if isinstance(t, str) and '[act:' in t:
            m = dict(m)
            m['content'] = redact_text(t)
        out.append(m)
    return out


def load_msgs(home, sid):
    c = _conn(home)
    try:
        rows = c.execute('SELECT role, body FROM messages WHERE sid=? ORDER BY seq', (sid,)).fetchall()
        return [{'role': r[0], 'content': r[1]} for r in rows]
    finally:
        c.close()


def save_msgs(home, sid, msgs):
    keep = [m for m in msgs if isinstance(m, dict) and m.get('role') in ('user', 'assistant')]
    keep = _redact(keep)
    c = _conn(home)
    try:
        c.execute('DELETE FROM messages WHERE sid=?', (sid,))
        for i, m in enumerate(keep):
            c.execute('INSERT INTO messages VALUES (?,?,?,?)', (sid, i, m.get('role'), m.get('content') or ''))
        c.execute('INSERT OR REPLACE INTO sessions VALUES (?,?,?)', (sid, _tt(keep), int(time.time())))
        c.commit()
    finally:
        c.close()


def load_index(home):
    c = _conn(home)
    try:
        r = c.execute('SELECT v FROM kv WHERE k=?', ('cur',)).fetchone()
        cur = r[0] if r else None
        rows = c.execute('SELECT sid, title, mtime FROM sessions ORDER BY mtime DESC').fetchall()
        items = [{'sid': x[0], 'title': x[1] or '', 'mtime': x[2] or 0} for x in rows]
        return {'current': cur, 'items': items}
    finally:
        c.close()


def save_index(home, idx):
    c = _conn(home)
    try:
        cur = (idx or {}).get('current')
        if cur:
            c.execute('INSERT OR REPLACE INTO kv VALUES (?,?)', ('cur', cur))
        else:
            c.execute('DELETE FROM kv WHERE k=?', ('cur',))
        c.commit()
    finally:
        c.close()


def list_sessions(home):
    idx = load_index(home)
    out = []
    for it in idx.get('items', []):
        sid = it.get('sid')
        if not sid:
            continue
        msgs = load_msgs(home, sid)
        out.append({'sid': sid, 'title': it.get('title') or _tt(msgs), 'mtime': it.get('mtime') or 0, 'count': len(msgs), 'current': sid == idx.get('current')})
    return out


def create(home, msgs=None):
    sid = 's' + str(int(time.time() * 1000))
    msgs = msgs or []
    c = _conn(home)
    try:
        c.execute('INSERT OR REPLACE INTO sessions VALUES (?,?,?)', (sid, _tt(msgs), int(time.time())))
        c.execute('INSERT OR REPLACE INTO kv VALUES (?,?)', ('cur', sid))
        c.commit()
    finally:
        c.close()
    if msgs:
        save_msgs(home, sid, msgs)
    return sid


def ensure(home, msgs=None):
    idx = load_index(home)
    if not idx.get('current'):
        return create(home, msgs)
    return idx['current']


def touch(home, sid, msgs):
    save_msgs(home, sid, msgs)


def rename(home, sid, title):
    c = _conn(home)
    try:
        c.execute('UPDATE sessions SET title=? WHERE sid=?', (title, sid))
        c.commit()
    finally:
        c.close()
    return True


def remove(home, sid):
    c = _conn(home)
    try:
        c.execute('DELETE FROM messages WHERE sid=?', (sid,))
        c.execute('DELETE FROM sessions WHERE sid=?', (sid,))
        r = c.execute('SELECT v FROM kv WHERE k=?', ('cur',)).fetchone()
        if r and r[0] == sid:
            nxt = c.execute('SELECT sid FROM sessions ORDER BY mtime DESC LIMIT 1').fetchone()
            if nxt:
                c.execute('INSERT OR REPLACE INTO kv VALUES (?,?)', ('cur', nxt[0]))
            else:
                c.execute('DELETE FROM kv WHERE k=?', ('cur',))
        c.commit()
        r2 = c.execute('SELECT v FROM kv WHERE k=?', ('cur',)).fetchone()
        return r2[0] if r2 else None
    finally:
        c.close()


def export_md(home, sid, out_dir):
    msgs = load_msgs(home, sid)
    idx = load_index(home)
    title = '(untitled)'
    for it in idx.get('items', []):
        if it.get('sid') == sid:
            title = it.get('title') or title
            break
    lines = ['# ' + title, '']
    for m in msgs:
        lines.append('## ' + ('user' if m.get('role') == 'user' else 'AI'))
        lines.append('')
        lines.append(m.get('content') or '')
        lines.append('')
    os.makedirs(out_dir, exist_ok=True)
    fn = os.path.join(out_dir, 'chat-' + sid + '.md')
    with open(fn, 'w', encoding='utf-8') as f:
        f.write(chr(10).join(lines))
    return fn

