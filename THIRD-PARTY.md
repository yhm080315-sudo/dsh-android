# 第三方组件与许可义务

本仓库**只包含本项目自己的代码**（MIT）。构建时会拉取/内嵌下列组件，使用时请自行遵守各自许可。

## 编进 APK 的第三方代码

| 组件 | 版本 | 许可 | 义务 |
|---|---|---|---|
| **Shizuku API / provider / aidl / shared** (`dev.rikka.shizuku:*`) | 13.1.0 | Apache-2.0 | 随附 Apache-2.0 许可全文（`payload/THIRD-PARTY-NOTICES.md` 第 1.3 节，随载荷进包）。AAR 在构建时解出 `classes.jar` 放进 `javac` classpath，并用 D8 一起打成 `classes.dex`（`dx` 会拒绝 Shizuku 的 `invokedynamic`，见 `docs/PITFALLS.md`） |

Shizuku 只在**用户自己安装并授权**后才起作用；APK 里只带它的客户端库，不含 Shizuku 本体。

## 内嵌进 APK 的运行时

| 组件 | 许可 | 义务 |
|---|---|---|
| **@deepseek-ai/dsh** 及其依赖（`node_modules` 下约 568 个包目录） | MIT | 随附许可与版权声明 |
| Node.js（Termux bionic 构建） | MIT | 随附许可 |
| ripgrep | MIT / Unlicense（双许可） | 随附许可 |
| GNU coreutils（cat/ls/cp/…） | **GPL-3.0** | ⚠️ 需提供 **Termux 构建版本的对应源码** + 许可证全文 |
| bash | **GPL-3.0** | 同上 |
| busybox（若使用） | **GPL-2.0** | 同上 |

**关于 GPL**：把 GPL 二进制打包进 APK 属于「分发」，因此需要履行 GPL 义务。

注意：**只链到 gnu.org 是不够的**。所分发的二进制来自 Termux 的构建，
它们带了发行方的补丁，属于「修改版本」，因此「对应源码」指的是 Termux 构建它们时用的那份源码，
而不是上游原始版本。正确的指向是 Termux 对应包目录 + 构建修订：

| 二进制 | 对应源码 |
|---|---|
| GNU coreutils | https://github.com/termux/termux-packages/tree/master/packages/coreutils |
| bash | https://github.com/termux/termux-packages/tree/master/packages/bash |
| busybox（若使用） | https://github.com/termux/termux-packages/tree/master/packages/busybox |

同时请随分发包附上 **GPL 许可证全文**（`COPYING`），并注明修改情况（Termux 的补丁）。
最省事的做法仍然是**不在仓库里提交这些二进制**，改为构建时下载——`scripts/` 即按此方式工作。

## APK 内实际包含的插件（源码不在本仓库）

这些插件随载荷 `assets/runtime.tar.gz` 进包，装好后在
`files/runtime/dsh/node_modules/` 下。全文声明（含 `dsh-web-mobile` 的 MIT 正文与 Shizuku 的 Apache-2.0 正文）在
载荷根目录 `THIRD-PARTY-NOTICES.md`，诊断页 `http://127.0.0.1:3097/` 也会把它打出来，
可在设备上用浏览器验证。**声明正文的源文件是仓库里的
[`payload/THIRD-PARTY-NOTICES.md`](payload/THIRD-PARTY-NOTICES.md)**，
`scripts/stage-bionic-assets.sh` 在组装载荷时把它拷到载荷根目录（缺失即构建失败）。

| 插件 | 版本 | 许可 | 义务 / 状态 |
|---|---|---|---|
| ~~`dsh-boot-animation`~~ | 0.4.2 | **BSD-3-Clause** | **自 1.0.19-beta16 起已移除**（纯装饰，用户要求）。不再分发 → 不再需要随附 BSD-3 全文；探针改为按渲染内容判断（`window.__DSH_BOOT__` 由外壳 HTML 注入，与该插件无关） |
| `dsh-screen-operate` | 0.1.5 | **未声明 —— 许可待作者确认** | `package.json` 无 `license`，包内无 `LICENSE`/`COPYING`；不作为对外再分发的授权依据 |

## 明确的排除项

### dsh-skin-whale-fantasy —— CC-BY-NC-SA-4.0

**已从载荷中移除**（曾出现在早期 APK 里）。该许可：

- **禁止商用**（NonCommercial）
- **具传染性**（ShareAlike）：基于它的衍生作品必须沿用同一许可
- 且要求署名

如果你在自己设备上安装用于个人欣赏，属于个人使用；但**随仓库或 APK 分发**会带来许可冲突
（本项目为 MIT，允许商用）。需要主题能力时，请自行编写或选用宽松许可的皮肤。

### 其他插件

皮肤/动画类插件许可各异（例如 `dsh-boot-animation` 为 BSD-3、`dsh-pro-mode-splash` 为 MIT、
`dsh-whale-widget` 为 MIT）。安装前请逐个确认，并遵守其许可与署名要求。
`dsh-pro-mode-splash`（MIT，纯装饰）已因「非必要」从载荷中移除；`dsh-web-mobile`（MIT，
作者 mexiaosh）**在载荷里**——它是手机端界面适配，不是可选增强，MIT 全文见
`THIRD-PARTY-NOTICES.md` 第 1.2 节；`dsh-whale-widget` 不在载荷里，用户自行安装时其许可由其作者提供。

## 与 DSH 本体无关联声明

本项目是**第三方移植**，与 DeepSeek 官方无隶属关系。上游版权归各自权利人所有。
