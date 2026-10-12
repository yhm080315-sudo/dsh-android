# GitHub 发布素材

推仓库时需要的文案，直接复制粘贴。

---

## 仓库简介（About 字段，建议 350 字符内）

```
把 DeepSeek Harness 打包成独立 Android 应用：自带 Node 运行时，装个 APK 就能用。
不需要 Termux、不需要 proot、不需要 root、不需要电脑。
内含完整的 Android 平台适配踩坑记录（seccomp 杀 glibc、targetSdk 28 的由来、硬链接被拒……）。
MIT 许可。
```

**Topics**（最多 20 个）：

```
android  deepseek  dsh  ai-agent  nodejs  bionic  termux  webview  android-app  mobile-ai  llm-agent  coding-agent  arm64  no-root
```

---

## 首次发布说明（Release Notes）

**标题**：`v1.0.0 — DSH for Android：自带运行时的独立 APK`

```markdown
把 DeepSeek Harness 完整移植到 Android，做成一个**自带运行时**的独立应用。

## 特性

- **零依赖**：不需要 Termux / proot / root / 电脑，装 APK 即用
- **WebView 里跑的是真正的 DSH Web 界面**——同源代码，不是重写
- **插件系统可用**：第三方插件（皮肤、界面增强）正常加载
- **图片视觉可用**：上传图片能被模型看到（已适配无原生图像库的环境）
- **设备控制**：内置截屏 / 点按 / 滑动 / 输入 / OCR / 启动应用（走 su 通道）
- **数据可持久**：会话、附件、凭据存在应用私有目录，升级不丢；插件装在升级不动的分区

## 内嵌运行时

| 组件 | 说明 |
|---|---|
| Node.js | Termux bionic 构建，24.x |
| bash + 34 个 coreutils | bionic |
| ripgrep 15.x | bionic |
| DSH | `@deepseek-ai/dsh` 及其 177 个依赖包 |

## 已知限制（重要，请先读）

- **无法上架 Google Play**：`targetSdk` 必须是 28，原因见 docs/PITFALLS.md
- **无 iOS 支持**：iOS 不允许 fork/exec，本方案根本性不适用
- **APK 体积约 130–180 MB**：内嵌了完整的 Node 运行时
- **首次启动需解包约 6 秒**（一次性）
- **原生模块不可用**：任何依赖预编译 `.node` 的 npm 包都不存在 Android 版本

## 安装

从 Releases 下载 APK 直接安装（需要允许「安装未知来源应用」）。

首次启动后建议在应用内执行：

1. **授予文件访问**（读写 `/sdcard` 上的文件）
2. **授予 root 权限**（可选，用于访问系统路径与其他应用数据）

## 致谢

本项目的核心结论来自真机上的反复试错，全部记录在 `docs/PITFALLS.md`。
如果它替你省下了几天时间，欢迎回帖说明你遇到了哪些我们没遇到的情况。
```

---

## 建议的仓库设置

| 项 | 值 |
|---|---|
| 名称 | `dsh-android` |
| 可见性 | Public |
| 初始化 README | **不要勾**（本地已有完整仓库） |
| License | 选 MIT（仓库内已有 LICENSE 文件） |
| Issues | 开启 |
| Discussions | 可选开启 |

---

## 发布 APK 的正确方式

**不要把 APK 提交进仓库**（180 MB，且内含 GPL 组件）。

放进 **Releases**，并在发布说明里附上：

```markdown
## 源码与许可

本 APK 内含以下第三方组件，其许可与源码获取方式如下：

- DeepSeek Harness (`@deepseek-ai/dsh`) —— MIT，https://www.npmjs.com/package/@deepseek-ai/dsh
- Node.js（Termux bionic 构建）—— MIT，https://github.com/termux/termux-packages
- ripgrep —— MIT / Unlicense，https://github.com/BurntSushi/ripgrep
- GNU coreutils —— **GPL-3.0**，源码：https://www.gnu.org/software/coreutils/
- bash —— **GPL-3.0**，源码：https://www.gnu.org/software/bash/

构建脚本见仓库 `scripts/`，全部二进制均为构建时下载，未提交进版本库。
```

**GPL 义务提醒**：分发含 GPL 二进制的 APK 属于分发行为，需要提供对应源码或书面要约。
最省事的做法是像上面这样在 Release 页面给出源码链接。
