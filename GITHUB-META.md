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

**标题**：`v1.0.1 — DSH for Android 1.0.1（内嵌 DSH 0.2.0-rc.2）`

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

## 发布流程（照这个走，别跳步）

1. 改 `VERSION`（`APP_VERSION` / `APP_VERSION_CODE` / `DSH_VERSION`）
2. 构建：`install-dsh.sh` → `stage-bionic-assets.sh` → `build-app.sh`
3. **跑 `bash scripts/check-tag.sh`** —— 它做四件事：tag 是否指向当前提交、
   tag 里的 VERSION 是否与 tag 名一致、manifest 是否与 VERSION 同步、工作区是否干净。
   这个校验是**机械的**，因为同类错误本项目已经犯过三次，每次都是手工疏忽。
4. **用 tag 指向的那个提交重新构建一次，比对 APK 摘要** —— 否则发布物与源码可能不一致。
   这个项目就犯过：v1.0.1 的 tag 指向 `0c0b1414`，而实际发布的 APK 里含 `4ba31820` 才有的改动。
4. 提交、打 tag、推
5. 建 Release，上传 APK，附上 GPL 对应源码链接
6. 装包后跑一遍验证清单（见 Release 说明）

## 版本号约定

单一真相来源是仓库根目录的 **`VERSION`**：

```
APP_VERSION=1.0.1            本项目版本，与 Git tag 一致
APP_VERSION_CODE=10001       主*10000 + 次*100 + 修订
DSH_VERSION=0.2.0-rc.2       内嵌的 DeepSeek Harness 版本
```

- APK `versionName` = **`1.0.1+dsh.0.2.0-rc.2`**（SemVer 的 build metadata 位置放 DSH 版本）
- 产物名 = `DSH-Android-v1.0.1-dsh0.2.0-rc.2.apk`
- Release tag = `v1.0.1`
- 应用启动日志里有一行 `versions: app 1.0.1 · dsh 0.2.0-rc.2`

发新版时**只改 `VERSION` 一个文件**，其余（manifest、APK 名、日志）都会跟着走。

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
- GNU coreutils —— **GPL-3.0**，对应源码（Termux 构建版本）：
  https://github.com/termux/termux-packages/tree/master/packages/coreutils
- bash —— **GPL-3.0**，对应源码（Termux 构建版本）：
  https://github.com/termux/termux-packages/tree/master/packages/bash

> 这些二进制来自 Termux 的构建，带发行方补丁，属于修改版本；
> 因此「对应源码」指向 Termux 的构建源码，而不是上游原始版本。

构建脚本见仓库 `scripts/`，全部二进制均为构建时下载，未提交进版本库。
```

**GPL 义务提醒**：分发含 GPL 二进制的 APK 属于分发行为，需要提供对应源码或书面要约。
最省事的做法是像上面这样在 Release 页面给出源码链接。
