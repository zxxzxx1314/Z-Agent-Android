#!/usr/bin/env bash
set -euo pipefail
DIR=
(
𝑐
𝑑
"
(cd"(dirname "0")" && pwd) ROOT=(cd "DIR/.." && pwd) SRC="ROOT/app/src/main/assets/zagent_factory"
DST="$ROOT/app/src/main/python"

if [ ! -d "
𝑆
𝑅
𝐶
"
]
;
𝑡
ℎ
𝑒
𝑛
𝑒
𝑐
ℎ
𝑜
"
𝑚
𝑖
𝑠
𝑠
𝑖
𝑛
𝑔
−
𝑠
𝑟
𝑐
(
SRC"];thenecho"missing−src(SRC)"; exit 1; fi
if [ ! -d "
𝐷
𝑆
𝑇
"
]
;
𝑡
ℎ
𝑒
𝑛
𝑒
𝑐
ℎ
𝑜
"
𝑚
𝑖
𝑠
𝑠
𝑖
𝑛
𝑔
−
𝑑
𝑠
𝑡
(
DST"];thenecho"missing−dst(DST)"; exit 1; fi

echo "Syncing from factory to python..."

使用 rsync 保证完全一致，包括删除多余文件

if command -v rsync >/dev/null 2>&1; then
rsync -av --delete --exclude='pycache/' --exclude='.pyc' "
𝑆
𝑅
𝐶
/
"
"
SRC/""DST/"
else
find "
DST" -type f -name ".pyc" -delete
cp -r "
𝑆
𝑅
𝐶
/
.
"
"
SRC/.""DST/"
fi

find "
DST" -type f -name "*.pyc" -delete
echo "Sync complete."
