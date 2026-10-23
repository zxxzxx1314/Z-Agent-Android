def run(args, ctx):
    try:
        import android_api
    except Exception as e:
        return {'ok': False, 'err': 'android_api unavailable: %r' % (e,)}
    try:
        res = android_api.shell_status()
    except Exception as e:
        return {'ok': False, 'err': repr(e)}
    if not isinstance(res, dict):
        return {'ok': True, 'data': res}
    if res.get('ok') is False:
        return res
    return {'ok': True, 'data': res}
