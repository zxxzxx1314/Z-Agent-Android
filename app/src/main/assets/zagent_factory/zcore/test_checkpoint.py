import os, sys, tempfile, time

REPO = '/data/data/com.agent.agoni/files/home/zagent_push/repo/app/src/main/python'
sys.path.insert(0, REPO)

sd = tempfile.mkdtemp(prefix='cp_')
from zcore import checkpoint
checkpoint._MIND = os.path.join(sd, 'mind')
checkpoint._DIR = os.path.join(checkpoint._MIND, 'checkpoints')

fails = 0
def chk(name, cond):
    global fails
    print(('PASS ' if cond else 'FAIL ') + name)
    if not cond:
        fails += 1

p = checkpoint.save('taskA', {'phase': 1, 'done': ['x']}, note='open')
chk('save returns path', p.endswith('taskA.json') and os.path.exists(p))

j = checkpoint.load('taskA')
chk('load name', j['name'] == 'taskA')
chk('load state', j['state'] == {'phase': 1, 'done': ['x']})
chk('load note', j['note'] == 'open')

time.sleep(0.05)
checkpoint.save('taskB', {'phase': 2})
rows = checkpoint.list_all()
chk('list count', len(rows) == 2)
chk('list order newest first', rows[0][0] == 'taskB')

lt = checkpoint.latest()
chk('latest name', lt['name'] == 'taskB')

checkpoint.save('weird/name with spaces!', {'k': 1})
sl = checkpoint._slug('weird/name with spaces!')
chk('slug sanitized', '/' not in sl and ' ' not in sl and sl)

chk('load missing', checkpoint.load('nope') is None)

chk('clear existing', checkpoint.clear('taskA') is True)
chk('clear missing', checkpoint.clear('taskA') is False)
chk('list after clear', len(checkpoint.list_all()) == 2)

print('CP' + ('OK' if fails == 0 else 'FAIL'))
sys.exit(0 if fails == 0 else 1)
