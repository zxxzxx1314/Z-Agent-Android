import json as _json


def run(args, ctx):
    try:
        import android_api
    except Exception as e:
        return {'ok': False, 'err': 'android_api unavailable: %r' % (e,)}
    url = str(args.get('url') or '').strip()
    js = str(args.get('js') or '')
    if not url:
        return {'ok': False, 'err': 'url is required'}
    if not js.strip():
        return {'ok': False, 'err': 'js is required'}
    try:
        raw = android_api.eval_js(url, js)
    except Exception as e:
        return {'ok': False, 'err': repr(e)}
    inner = None
    if isinstance(raw, str) and raw.lstrip().startswith('{'):
        try:
            inner = _json.loads(raw)
        except Exception:
            inner = None
    if isinstance(inner, dict) and inner.get('ok') is False:
        return {'ok': False, 'err': str(inner.get('error') or inner.get('err') or 'eval failed'), 'data': inner}
    return {'ok': True, 'data': raw}
