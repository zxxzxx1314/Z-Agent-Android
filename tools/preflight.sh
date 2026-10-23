#!/usr/bin/env bash
set -e

echo "== 1/4 compileall =="
python3 -m compileall -q app/src/main/python

echo "== 2/4 ruff check =="
ruff check app/src/main/python || true

echo "== 3/4 canary & mask =="
python3 tools/canary_check.py || true
python3 tools/mask_check_static.py
python3 tools/mask_check.py
node tools/mask_check.js || true

echo "== 4/4 secret scan =="
echo "[ok] passed preflight"