import sys
sys.path.insert(0, r'/data/data/com.agent.agoni/files/home/zagent_push/repo/app/src/main/python')
from zcore import limiter, events
print('limiter:', limiter.status())
print('set_max(3):', limiter.set_max(3))
print('after:', limiter.status())
events.emit('test', a=1, b='x')
print('tail:', len(events.tail(5)))
print('count:', events.count())
