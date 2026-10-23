# 贡献指南

分支

main 为发布分支，直接推送会触发 CI 构建。

改动用 feat/* fix/* chore/* 前缀，走 PR。

提交前

./tools/preflight.sh 全绿。

改动 Python 后 ./tools/sync_factory.sh 同步出厂基因。

不要提交 local.properties、tk.txt、*.keystore、build/、__pycache__/。

提交信息

Conventional Commits：

text
复制
下载
feat(ui): 上滑时不再自动滚动抢焦点
fix(py): android_api 缩进压平
chore(tools): 新增 preflight 脚本
代码风格

Kotlin / Java / Python：4 空格缩进（见 .editorconfig）。

XML / YAML / JSON：4 空格。

JS / CSS / HTML：2 空格。

行尾 LF，文件末尾留空行。

自动化检查与规范
Python (Ruff)

项目使用 Ruff 进行静态分析和格式化。

安装与使用：

bash
复制
下载
pip install ruff
ruff check app/src/main/python
ruff check --fix app/src/main/python
Kotlin (Lint)

依赖 Android Studio 自带的 Lint，或 CI 中的 ./gradlew lint。代码风格遵循 4 空格缩进。

提交前必须通过
bash
复制
下载
./tools/preflight.sh
目录约定

新增文档放 docs/。

新增工具脚本放 tools/，.sh 需可执行。

Kotlin 源码放 app/src/main/java/cn/rain/zagent/。

Python 源码放 app/src/main/python/，随后同步到 assets。
