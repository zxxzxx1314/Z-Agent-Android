import os
import subprocess
import time

def _t(name, desc, props=None, req=None):
	return {'name': name, 'description': desc,
	 'inputSchema': {'type': 'object',
	 'properties': props or {},
	 'required': req or []}}

def tools():
	return [
		_t('sys_check', 'check root'),
		_t('sys_exec', 'shell exec, root optional',
		 {'cmd': {'type': 'string'}, 'root': {'type': 'boolean'},
		 'timeout': {'type': 'integer'}}, ['cmd']),
		_t('sys_read', 'read file, root can access /data/data',
		 {'path': {'type': 'string'}, 'max_bytes': {'type': 'integer'}},
		 ['path']),
		_t('sys_list', 'list dir, root can enter /data/data',
		 {'path': {'type': 'string'}, 'root': {'type': 'boolean'}},
		 ['path']),
	]

def _run(argv, timeout):
	t0 = time.time()
	try:
		p = subprocess.run(argv, stdin=subprocess.DEVNULL,
		 stdout=subprocess.PIPE, stderr=subprocess.PIPE,
		 timeout=timeout)
		out = (p.stdout or b'').decode('utf-8', 'replace')
		err = (p.stderr or b'').decode('utf-8', 'replace')
		return {'rc': p.returncode, 'out': out[-8000:], 'err': err[-4000:],
		 'ms': int((time.time() - t0) * 1000)}
	except subprocess.TimeoutExpired:
		return {'rc': 124, 'out': '', 'err': 'timeout',
		 'ms': int((time.time() - t0) * 1000)}
	except Exception as e:
		return {'rc': -1, 'out': '', 'err': repr(e), 'ms': 0}

def _su_ok():
	r = _run(['su', '-c', 'id'], 6)
	return r.get('rc') == 0 and 'uid=0' in (r.get('out') or '')

def call(name, args):
	args = args or {}
	if name == 'sys_check':
		ok = _su_ok()
		base = _run(['id'], 5)
		return {'root': ok, 'uid': (base.get('out') or '').strip()}
	if name == 'sys_exec':
		cmd = args.get('cmd', '')
		root = bool(args.get('root', False))
		to = int(args.get('timeout', 60))
		argv = ['su', '-c', cmd] if root else ['sh', '-c', cmd]
		r = _run(argv, to)
		r['root'] = root
		return r
	if name == 'sys_read':
		p = args.get('path', '')
		n = int(args.get('max_bytes', 65536))
		if os.path.exists(p):
			try:
				with open(p, 'rb') as f:
					b = f.read(n)
				return {'path': p, 'size': len(b),
				 'text': b.decode('utf-8', 'replace')}
			except PermissionError:
				pass
		su = _run(['su', '-c', 'cat ' + p], 20)
		su['via_root'] = True
		return su
	if name == 'sys_list':
		p = args.get('path', '/sdcard/Download')
		root = bool(args.get('root', False))
		argv = ['su', '-c', 'ls -la ' + p] if root else ['ls', '-la', p]
		return _run(argv, 15)
	raise ValueError('unknown sys tool: ' + str(name))
