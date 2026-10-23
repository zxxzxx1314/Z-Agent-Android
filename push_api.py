import os, base64, json, subprocess, urllib.request, urllib.error, time
from concurrent.futures import ThreadPoolExecutor

REPO_DIR = '/data/data/com.termux/files/home/zagent_push'
TOKEN = open(os.path.join(REPO_DIR, 'tk.txt')).read().strip()
REPO = 'zxxzxx1314/Z-Agent-Android'
API_URL = 'https://api.github.com'
HEADERS = {'Authorization': 'token ' + TOKEN, 'Accept': 'application/vnd.github.v3+json', 'User-Agent': 'Rain-Agent'}

def api_request(method, path, data=None, retries=5):
	url = API_URL + path
	for attempt in range(retries):
		req = urllib.request.Request(url, headers=HEADERS, method=method)
		if data:
			req.data = json.dumps(data).encode('utf-8')
			req.add_header('Content-Type', 'application/json')
		try:
			with urllib.request.urlopen(req, timeout=60) as resp:
				return json.loads(resp.read().decode('utf-8'))
		except urllib.error.HTTPError as e:
			body = e.read().decode('utf-8')
			if e.code in (403, 429) or 'rate limit' in body:
				print(f'Rate limited ({e.code}), waiting 10s...', flush=True)
				time.sleep(10)
				continue
			print(f'HTTP Error {e.code} on {method} {path}: {body}', flush=True)
			raise
		except Exception as e:
			print(f'Network error: {e}, retrying...', flush=True)
			time.sleep(5)
	raise Exception('Max retries exceeded')

def main():
	print('Fetching remote head...', flush=True)
	try:
		ref_info = api_request('GET', '/repos/' + REPO + '/git/ref/heads/main')
		remote_head_sha = ref_info['object']['sha']
		remote_commit = api_request('GET', '/repos/' + REPO + '/git/commits/' + remote_head_sha)
		remote_tree_sha = remote_commit['tree']['sha']
		print('Remote HEAD: ' + remote_head_sha, flush=True)
	except urllib.error.HTTPError as e:
		if e.code == 404:
			print('Remote empty, creating initial commit.', flush=True)
			remote_head_sha = None
			remote_tree_sha = None
		else:
			raise

	print('Enumerating local files...', flush=True)
	os.chdir(REPO_DIR)
	files_out = subprocess.check_output(['git', 'ls-tree', '-r', '-z', 'HEAD', '--full-tree'], stderr=subprocess.DEVNULL).decode('utf-8')
	tasks = []
	if files_out:
		for entry in files_out.split(chr(0)):
			if not entry.strip(): continue
			header, path = entry.split(chr(9), 1)
			parts = header.split()
			if len(parts) < 3: continue
			mode, typ, sha = parts[0], parts[1], parts[2]
			if typ != 'blob': continue
			tasks.append((path, mode))

	print(f'Found {len(tasks)} files to upload', flush=True)

	def create_blob(filepath, mode):
		with open(filepath, 'rb') as f:
			content = f.read()
		b64 = base64.b64encode(content).decode('utf-8')
		res = api_request('POST', '/repos/' + REPO + '/git/blobs', {'content': b64, 'encoding': 'base64'})
		return {'path': filepath, 'mode': mode, 'type': 'blob', 'sha': res['sha']}

	tree_entries = []
	completed = 0
	with ThreadPoolExecutor(max_workers=5) as executor:
		futures = [executor.submit(create_blob, path, mode) for path, mode in tasks]
		for future in futures:
			try:
				tree_entries.append(future.result())
				completed += 1
				if completed % 20 == 0:
					print(f'Uploaded {completed}/{len(futures)} blobs', flush=True)
			except Exception as e:
				print('Failed: ' + str(e), flush=True)
				raise

	print('Creating tree...', flush=True)
	tree_data = {'tree': tree_entries}
	if remote_tree_sha:
		tree_data['base_tree'] = remote_tree_sha
	tree_res = api_request('POST', '/repos/' + REPO + '/git/trees', tree_data)
	new_tree_sha = tree_res['sha']
	print('New tree: ' + new_tree_sha, flush=True)

	print('Creating commit...', flush=True)
	commit_data = {
		'message': 'feat: sync latest source code and fix P0/P1/P2',
		'tree': new_tree_sha,
		'parents': [remote_head_sha] if remote_head_sha else []
	}
	commit_res = api_request('POST', '/repos/' + REPO + '/git/commits', commit_data)
	new_commit_sha = commit_res['sha']
	print('New commit: ' + new_commit_sha, flush=True)

	print('Updating ref...', flush=True)
	if remote_head_sha:
		api_request('PATCH', '/repos/' + REPO + '/git/refs/heads/main', {'sha': new_commit_sha, 'force': True})
	else:
		api_request('POST', '/repos/' + REPO + '/git/refs', {'ref': 'refs/heads/main', 'sha': new_commit_sha})

	print('=== Push via API completed successfully! ===', flush=True)

main()
