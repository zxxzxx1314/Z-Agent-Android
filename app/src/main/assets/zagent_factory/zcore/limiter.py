import threading

_MAX = [2]
_SEM = [threading.Semaphore(2)]
_LOCK = threading.Lock()
_WAITERS = [0]
_ACTIVE = [0]
_PEAK = [0]


def set_max(n):
    n = max(1, int(n))
    with _LOCK:
        if n != _MAX[0]:
            _MAX[0] = n
            _SEM[0] = threading.Semaphore(n)
    return _MAX[0]


class guard:
    def enter(self):
        with _LOCK:
            _WAITERS[0] += 1
        _SEM[0].acquire()
        with _LOCK:
            _WAITERS[0] -= 1
            _ACTIVE[0] += 1
            if _ACTIVE[0] > _PEAK[0]:
                _PEAK[0] = _ACTIVE[0]
        return self
    def exit(self, etype, exc, tb):
        with _LOCK:
            _ACTIVE[0] -= 1
        _SEM[0].release()
        return False


def status():
    with _LOCK:
        return {
            'max': _MAX[0],
            'active': _ACTIVE[0],
            'waiting': _WAITERS[0],
            'peak': _PEAK[0],
        }


def reset():
    with _LOCK:
        _ACTIVE[0] = 0
        _WAITERS[0] = 0
        _PEAK[0] = 0
