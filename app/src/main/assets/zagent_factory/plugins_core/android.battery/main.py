def run(args, ctx):
    try:
        import android_api
    except Exception as e:
        return {'ok': False, 'err': 'android_api unavailable: %r' % (e,)}
    try:
        bat = android_api.battery_info()
    except Exception as e:
        return {'ok': False, 'err': repr(e)}
    if isinstance(bat, dict) and bat.get('error'):
        return {'ok': False, 'err': str(bat.get('error'))}
    try:
        dev = android_api.device_info()
    except Exception as e:
        dev = {'error': repr(e)}
    return {'ok': True, 'data': {'battery': bat, 'device': dev}}
