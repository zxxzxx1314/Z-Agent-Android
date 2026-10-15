# -- coding: utf-8 --
import os, sys, time, tempfile

REPO = '/data/data/com.agent.agoni/files/home/zagent' + chr(95) + 'push/repo/app/src/main/python'
sys.path.insert(0, REPO)

sd = tempfile.mkdtemp(prefix='facts_')
from zcore import facts
facts._MIND = os.path.join(sd, 'mind')
facts._FACTS = os.path.join(facts._MIND, 'facts.json')
facts._cache = None

fails = 0
def chk(name, cond):
    global fails
    print(('PASS ' if cond else 'FAIL ') + name)
    if not cond:
        fails += 1

facts.set('a', 1)
chk('set/get', facts.get('a') == 1)
chk('miss default', facts.get('nope', 'd') == 'd')
chk('forget', facts.forget('a') is True and facts.get('a') is None)

facts.set('b', 'v', ttl=1)
chk('ttl fresh', facts.get('b') == 'v')
time.sleep(1.15)
chk('ttl expired', facts.get('b') is None)

calls = [0]
def slow():
    calls[0] += 1
    return 'res'
r1 = facts.cached('k1', slow)
r2 = facts.cached('k1', slow)
chk('cached memo', r1 == r2 == 'res' and calls[0] == 1)

facts.set('p', 'x')
facts.cache = None
chk('persist across reload', facts.get('p') == 'x')

facts.set('t1', 1, ttl=1)
time.sleep(1.15)
n = facts.clear_expired()
chk('clear_expired', n >= 1 and facts.get('t1') is None)

facts.set('pre_a', 1)
facts.set('pre_b', 2)
facts.set('other', 3)
ks = facts.keys(prefix='pre')
chk('keys prefix', ks == ['pre_a', 'pre_b'])

print('FACTS' + ('OK' if fails == 0 else 'FAIL'))
sys.exit(0 if fails == 0 else 1)