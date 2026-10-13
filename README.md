# Z-Agent

`Z-Agent` 是一个 Android 原生 AI Agent 应用：Kotlin 外壳 + Chaquopy 桥接 Python 内核，支持工具调用、插件系统、任务调度与多轮深度思考。

---

## 开源协议

本项目采用 **GNU Affero General Public License v3.0 (AGPL-3.0)** 授权。

完整协议文本见 `LICENSE` 文件。

### 你可以

- 自由使用、修改、分发本软件
- 用于个人项目、学习研究
- 在遵守 AGPL-3.0 前提下集成到自己的项目

### 你必须

- **开源**：若你修改本软件并通过网络提供服务（含 SaaS），必须公开完整源代码
- **保留声明**：保留原始版权声明与协议文本
- **同协议**：衍生作品必须以 AGPL-3.0 分发

### 你不能

- **闭源商用**：把本软件或其衍生品闭源后作为商业产品出售
- 移除或隐藏版权与协议声明
- 用本软件提供闭源商业服务而不公开源码

如有商业授权需求（闭源商用、私有部署），请联系作者协商单独授权。

---

## 功能

- **对话模式**：Plan / Build / 深度思考 三档切换
- **工具调用**：脚本执行、文件读写、网页抓取
- **插件系统**：内置 5 个插件（时间感知/任务调度/网页搜索/系统信息/文件搜索），可独立启停
- **双主题**：浅色/深色，语义化 design token
- **签名校验**：启动时校验 APK 签名，防二次打包

---

## 构建

### 1. 克隆工程

```bash
git clone <你的仓库地址>
cd Z-Agent-App
```

### 2. 配置本地密钥（可选）

复制 `local.properties.example` 为 `local.properties`，填入：

```properties
# API Key（可选）
ZAGENT_API_KEY=sk-xxxxxxxx

# 官方签名 SHA-256（可选，留空跳过校验）
ZAGENT_EXPECTED_SIGNATURE=ab12cd34...

# Release 签名（可选）
RELEASE_STORE_FILE=/path/to/your.jks
RELEASE_STORE_PASSWORD=xxxx
RELEASE_KEY_ALIAS=xxxx
RELEASE_KEY_PASSWORD=xxxx
```

该文件已被 `.gitignore` 忽略，不会提交。

### 3. 构建

```bash
./gradlew assembleDebug # 调试包
./gradlew assembleRelease # 正式包（R8 混淆 + 资源压缩）
```

或推送到 GitHub，Actions 自动编译（见 `.github/workflows/android-build.yml`）。

---

## 安全说明

- 所有敏感信息（API Key / 签名 / keystore）走 `local.properties` + `BuildConfig`，源码零硬编码
- Release 构建开启 R8 混淆与资源压缩，移除调试符号
- 启动时校验 APK 签名 SHA-256，不一致则拒绝启动

---

## 许可摘要

| 场景 | 是否允许 |
|---|---|
| 个人使用 | 允许 |
| 学习研究 | 允许 |
| 开源项目集成 | 允许（须保持 AGPL-3.0） |
| 闭源商用 | 禁止（需单独授权） |
| SaaS 服务 | 允许（须公开源码） |

详见 `LICENSE` 全文。
