.DEFAULT_GOAL := help
.PHONY: help sync check lint format debug release clean

help:
	@echo "Z-Agent 常用命令："
	@echo " make sync 同步出厂基因（python -> assets/zagent_factory）"
	@echo " make check 提交前自检（语法 / ruff / canary / mask / 敏感信息）"
	@echo " make lint 仅跑 Ruff 静态检查"
	@echo " make format 自动格式化 Python 代码"
	@echo " make debug 构建 Debug APK"
	@echo " make release 构建 Release APK"
	@echo " make clean 清理构建产物"

sync:
	bash tools/sync_factory.sh

check:
	bash tools/preflight.sh

lint:
	ruff check app/src/main/python

format:
	ruff format app/src/main/python
	ruff check --fix app/src/main/python

debug:
	./gradlew :app:assembleDebug

release:
	./gradlew :app:assembleRelease

clean:
	./gradlew clean
