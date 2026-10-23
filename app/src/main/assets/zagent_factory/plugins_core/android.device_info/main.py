def run(args, ctx):
    try:
        import android_api
    except Exception as e:
        return {'ok': False, 'err': 'android_api unavailable: %r' % (e,)}
    try:
        info = android_api.device_info()
    except Exception as e:
        return {'ok': False, 'err': repr(e)}
    if isinstance(info, dict) and info.get('error'):
        return {'ok': False, 'err': str(info.get('error'))}
    return {'ok': True, 'data': info}
