def run(args, ctx):
    sess = (ctx or {}).get('session') or {}
    sid = sess.get('sid')
    home = sess.get('home')
    if not sid or not home:
        return {'ok': False, 'outcome': 'not_submitted',
                'err': 'no active session bound to this turn'}
    try:
        import zagent_sessions as _s
    except Exception as e:
        return {'ok': False, 'outcome': 'not_submitted',
                'err': 'zagent_sessions unavailable: %r' % (e,)}
    try:
        limit = int(args.get('limit') or 20)
    except Exception:
        limit = 20
    limit = max(1, min(100, limit))
    try:
        offset = int(args.get('offset') or 0)
    except Exception:
        offset = 0
    offset = max(0, offset)
    try:
        data = _s.journal_read(home, sid, offset=offset, limit=limit)
    except Exception as e:
        return {'ok': False, 'outcome': 'unknown', 'err': repr(e)}
    return {'ok': True, 'outcome': 'ok', 'data': data}
