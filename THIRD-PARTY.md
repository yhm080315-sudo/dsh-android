# 第三方组件与许可义务

本仓库**只包含本项目自己的代码**（MIT）。构建时会拉取/内嵌下列组件，使用时请自行遵守各自许可。

## 内嵌进 APK 的运行时

| 组件 | 许可 | 义务 |
|---|---|---|
| **@deepseek-ai/dsh** 及其依赖（177 个包） | MIT | 随附许可与版权声明 |
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

## 明确的排除项

### dsh-skin-whale-fantasy —— CC-BY-NC-SA-4.0

**不要把它加进本仓库。** 该许可：

- **禁止商用**（NonCommercial）
- **具传染性**（ShareAlike）：基于它的衍生作品必须沿用同一许可
- 且要求署名

如果你在自己设备上安装用于个人欣赏，属于个人使用；但**随仓库或 APK 分发**会带来许可冲突
（本项目为 MIT，允许商用）。需要主题能力时，请自行编写或选用宽松许可的皮肤。

### 其他插件

皮肤/动画类插件许可各异（例如 `dsh-boot-animation` 为 BSD-3、`dsh-pro-mode-splash` 为 MIT、
`dsh-whale-widget` 为 MIT）。安装前请逐个确认，并遵守其许可与署名要求。

## 与 DSH 本体无关联声明

本项目是**第三方移植**，与 DeepSeek 官方无隶属关系。上游版权归各自权利人所有。
