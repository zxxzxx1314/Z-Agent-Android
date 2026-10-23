"""zcore.ptyframe - PTY frame protocol (task 28).

Wire format:
 1 byte type
 2 bytes length (big endian)
 N bytes payload

Types:
 0 terminal bytes -> payload is raw bytes to write to the pty
 1 window size -> payload is exactly 4 bytes: rows(2) + cols(2),
 big endian; receiver must raise SIGWINCH
 other types -> skip length bytes (forward compatible)

Rules:
 - Never assume payload encoding; treat as bytes.
 - Unknown type must be skipped by length, never an error.
 - Length is unsigned 16-bit, so max payload is 65535.
 - A truncated header/frame is an error, not a partial emit.
"""

import struct

TYPE_DATA = 0
TYPE_WINSZ = 1

HEADER_LEN = 3
MAX_PAYLOAD = 0xFFFF
WINSZ_LEN = 4

_HDR = struct.Struct(">BH")
_WINSZ = struct.Struct(">HH")


class FrameError(ValueError):
    """Raised when a frame header or payload is malformed."""


def pack_data(payload):
    """Build a type-0 frame carrying terminal bytes."""
    if not isinstance(payload, (bytes, bytearray)):
        raise TypeError("payload must be bytes")
    n = len(payload)
    if n > MAX_PAYLOAD:
        raise FrameError("payload too large: %d" % n)
    return _HDR.pack(TYPE_DATA, n) + bytes(payload)


def pack_winsz(rows, cols):
    """Build a type-1 frame carrying rows/cols; receiver raises SIGWINCH."""
    if not (0 <= rows <= 0xFFFF and 0 <= cols <= 0xFFFF):
        raise FrameError("rows/cols out of range")
    body = _WINSZ.pack(int(rows), int(cols))
    return _HDR.pack(TYPE_WINSZ, len(body)) + body


def pack_raw(ftype, payload):
    """Build a frame of arbitrary type (for tests / future types)."""
    if not isinstance(payload, (bytes, bytearray)):
        raise TypeError("payload must be bytes")
    n = len(payload)
    if not (0 <= ftype <= 0xFF) or n > MAX_PAYLOAD:
        raise FrameError("bad type/length")
    return _HDR.pack(int(ftype), n) + bytes(payload)


class FrameDecoder:
    """Incremental decoder. Feed bytes, pop complete frames."""

    __slots__ = ("_buf",)

    def __init__(self):
        self._buf = bytearray()

    def feed(self, data):
        """Append bytes. Returns a list of (type, payload) tuples."""
        if data:
            self._buf.extend(data)
        out = []
        while True:
            if len(self._buf) < HEADER_LEN:
                break
            ftype, length = _HDR.unpack_from(self._buf, 0)
            total = HEADER_LEN + length
            if len(self._buf) < total:
                break
            payload = bytes(self._buf[HEADER_LEN:total])
            del self._buf[:total]
            out.append((ftype, payload))
        return out

    def pending(self):
        return len(self._buf)

    def reset(self):
        self._buf.clear()


def decode_winsz(payload):
    """Parse a type-1 payload into (rows, cols). Raises FrameError."""
    if len(payload) != WINSZ_LEN:
        raise FrameError("winsz payload must be 4 bytes, got %d" % len(payload))
    return _WINSZ.unpack(payload)


def iter_frames(data):
    """Decode a complete byte string into a list of frames."""
    return FrameDecoder().feed(data)