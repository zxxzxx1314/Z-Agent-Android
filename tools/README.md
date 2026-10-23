# tools/

开发辅助脚本。均为独立可执行，不含业务逻辑，CI 与本地通用。

依赖

bash 4.0+

python3 3.8+

node 18+（仅 mask_check.js）

rsync（仅 sync_factory.sh）

Termux: pkg install rsync

Debian/Ubuntu: apt-get install rsync

macOS: 系统自带

脚本
脚本	作用	使用
sync_factory.sh	把 app/src/main/python/ 同步到 app/src/main/assets/zagent_factory/，排除缓存与测试	./tools/sync_factory.sh
preflight.sh	提交前自检：canary、mask、敏感信息扫描	./tools/preflight.sh
canary_check.py	校验 assets/canary.txt 的 SHA-256 与 APK 内嵌一致	python3 tools/canary_check.py
mask_check.py	用 fixture 校验 Python zcore/mask.py 的脱敏规则	python3 tools/mask_check.py
mask_check_static.py	校验 mask 模式串在 Python / Kotlin / assets 三处一致	python3 tools/mask_check_static.py
mask_check.js	mask 规则的 Node 交叉实现，CI 中与 Python 结果互校	node tools/mask_check.js
约定

新增工具放本目录，命名 xxx_check.* 或 xxx_sync.*。

工具不得引入项目依赖之外的包；需要第三方包请在此说明。

CI 的 Preflight 步骤会调用全部检查脚本，改脚本后请本地跑一遍。

canary skip 说明

canary_check.py 在 app/src/main/assets/canary.txt 不存在时打印
canary source missing, skip 并返回 0。这是有意为之的降级：

正常分发包必须包含 canary.txt，其 SHA-256 与脚本中 EXPECTED 匹配。

缺少该文件说明当前工程处于"脱敏"状态（例如开源仓库不带出厂指纹），
CI 不拦截，但发布前应确认 EXPECTED 已正确更新且文件已放入。

如果 canary.txt 存在但 SHA 不匹配，脚本会 FAIL 并退出 1。
