[English](README.en.md) | 中文
# 负载补丁

`apply-payload-patches.sh` 把 DSH 负载改造成能在 Android 应用沙箱里运行。

```bash
bash patches/apply-payload-patches.sh            # 默认作用于 ./bionic-dsh/node_modules
bash patches/apply-payload-patches.sh <目录>      # 或指定任意 runtime 目录
```

脚本**可重复执行**：按锚点字符串匹配，已打过会跳过；上游代码若已变更导致锚点失配，
会打印「锚点失配」并**贴出该处上游原文**，而不是静默改错。

## 已覆盖

| 编号 | 补丁 | 文件 |
|---|---|---|
| P2 | `flock` 在 Android 降级为立即成功 | `node-addon-system/lib/flock.js` |
| P3-helper | 新增 `publishExclusive`（锁 + rename + 记录持锁 pid） | `dsh-session-persistence-jsonl/lib/index.js` |
| P3a/P3b | 两处会话发布改走 `publishExclusive` | 同上 |
| P16a–d | 抢救过的日志在写打开时按完整事件流重建 | 同上 |
| P20b–e | 会话写租约改用 `session.lock.d`（`mkdir` 仲裁，记 pid） | 同上 |
| P4 | 目录 fsync 容忍权限错误 | `dsh-attachment-local/lib/index.js` |
| P9 | 裸包名按 profile 解析 | `cordis-plugin-loader/lib/index.js` |
| P11 | 新建文件改用「锁 + rename」发布（`link` 被 app 域拒绝） | `dsh-fs-local/lib/index.js` |
| P12 | 补上缺失的 `@vscode/ripgrep-android-arm64` 平台包 | `node_modules/@vscode/`（新增目录） |
| P13 | ptc worker 继承 `LD_LIBRARY_PATH` 等运行时必需变量 | `dsh-ptc-runtime-node/lib/index.js` |
| P14/P15/P16a | 交付卡片「打开」在 Android 上可用（`dsh-open`） | `host-open-in-app` / `native-command` |
| P17/P17b | `SIGINT` 不再关停 harness，`SIGTERM` 留痕 | `dsh/lib/profile-boot-*.js`（**文件名带哈希，脚本按前缀定位**） |
| P19a–d | 开机动画的媒体/目录请求带退避重试 | `dsh-boot-animation/lib/client.js` |
| P21 | `node-addon-require-builtin` 无 android 预编译 → 纯 JS 替身（同一包、同样导出） | `node-addon-require-builtin/lib/index.js` |
| P22 | 纯 JS sharp 编不了 WebP → alpha 图（安卓截图）追加 PNG 档 + 编码失败回落原图 | `dsh-attachment-local/lib/index.js` |
| P23 | 跨副本 `LlmError` 不再被改写成 `TRANSPORT`（否则 AUTH / MISSING_CREDENTIAL 全变成"网络错误"并被重试 5 次） | `dsh-llm-deepseek/lib/index.js` |
| P24 | 凭据服务缺位或抛错时直读 `$DSH_HOME/.credentials.yaml` 的 `refs:`（只读，不进环境） | `dsh-llm-deepseek-api-key/lib/index.js` |
| P25 | `fs/promises` 的 import 补上 `rename` / `copyFile` / `writeFile`（P3a 发布、P16d 重建要用，上游 import 里没有 → 新会话 `rename is not defined`） | `dsh-session-persistence-jsonl/lib/index.js` |
| P26 | 老 WebView（内核 < Chrome 100）缺 `Promise.withResolvers` / `AbortSignal.any` / `throwIfAborted` 等新 API → 在 `dsh-web-frontend/dist/index.html` 的 `<title>` 后注入内联 polyfill（按 bundle 实际用到的 API 补，新内核上是空操作） | `@deepseek-ai/dsh-web-frontend/dist/index.html` |

## 未自动覆盖（需手工处理，见 docs/PITFALLS.md）

> P1（`node-addon-require-builtin` 在 Android 上加载失败）已由 **P21** 覆盖：
> 不改编 `dsh-app-boot`，只把该包的 `lib/index.js` 换成纯 JS 实现。

| 编号 | 内容 | 原因 |
|---|---|---|
| P5 | 纯 JS 图片管线（替代 `sharp`） | 已在**载荷里实现**（`sharp` 被换成 `0.35.5-purejs`，原版改名 `sharp.glibc-original`），但未进脚本 |
| P10 | 用 bionic coreutils 替换 static musl busybox | 属于负载组装，见 `stage-bionic-assets.sh` |

## 替换 `node_modules` 时必须一起搬的「载荷专有件」

升级方式是把上游 `node_modules` 整棵树换掉，但下面这些**不在 `@deepseek-ai/dsh`
的依赖闭包里**，npm 装不出来，换树时必须从旧载荷搬过来（否则插件/图片/语音会静默消失）：

| 条目 | 来源 |
|---|---|
| `dsh-screen-operate` / `dsh-web-mobile` | 第一方/第三方插件，单独装入。`dsh-boot-animation` 自 1.0.19-beta16 起已从载荷删除（P19a–d 于是变成「目标文件不存在」的空转，属预期）。`dsh-skin-whale-fantasy`（CC-BY-NC-SA-4.0）/ `dsh-pro-mode-splash` 自 1.0.19-beta13 起已从载荷移除（许可合规 + 非必要）；`dsh-web-mobile`（MIT）曾在 beta13/beta14 被误删，beta15 起已加回（手机端界面适配，非可选） |
| `sharp`（`0.35.5-purejs`）+ `sharp.glibc-original` | P5 手工实现 + 原版留档 |
| `node-addon-require-builtin-linux-arm64-musl` | `stage-app-assets.sh` 组装期生成 |
| `@vscode/ripgrep-android-arm64` | P12 生成（脚本会重建） |

另外：载荷是 **linux/arm64** 的安装树（用 bionic node 跑 npm 时，npm 会把宿主判成
`os=android`，于是所有 `linux` 平台包被跳过）。安装时必须显式
`--os=linux --cpu=arm64`，否则 `@koromix/koffi-linux-arm64`、`sherpa-onnx-linux-arm64`、
`node-addon-require-builtin-linux-arm64-*` 会全部缺失。
