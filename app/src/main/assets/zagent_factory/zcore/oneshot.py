"""zcore.oneshot - single-shot result delivery for concurrent callbacks (task 35).

Only the first deliver() wins; later ones are dropped.
"""

import threading


class OneShot:
 """Thread-safe single-winner result box."""

 __slots__ = ("_lock", "_done", "_value", "_waiters")

 def __init__(self):
  self._lock = threading.Lock()
  self._done = False
  self._value = None
  self._waiters = []

 def deliver(self, value):
  """Return True if this call won; False if already delivered."""
  with self._lock:
   if self._done:
    return False
   self._done = True
   self._value = value
   waiters = list(self._waiters)
   self._waiters.clear()
  for ev in waiters:
   try:
    ev.set()
   except Exception:
    pass
  return True

 def is_done(self):
  with self._lock:
   return self._done

 def get(self, default=None):
  with self._lock:
   return self._value if self._done else default

 def wait(self, timeout=None):
  """Block until delivered or timeout. Returns (ok, value)."""
  with self._lock:
   if self._done:
    return True, self._value
   ev = threading.Event()
   self._waiters.append(ev)
  ev.wait(timeout)
  with self._lock:
   if self._done:
    return True, self._value
  return False, None


def guard(fn):
 """Wrap a callback so only the first invocation runs."""
 box = OneShot()
 def wrapper(*a, **kw):
  if not box.deliver(True):
   return None
  return fn(*a, **kw)
 wrapper.box = box
 return wrapper
