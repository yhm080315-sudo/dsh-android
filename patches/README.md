# 负载补丁

`apply-payload-patches.sh` 把 DSH 负载改造成能在 Android 应用沙箱里运行。

```bash
bash patches/apply-payload-patches.sh            # 默认作用于 ./bionic-dsh/node_modules
bash patches/apply-payload-patches.sh <目录>      # 或指定任意 runtime 目录
```

脚本**可重复执行**：按锚点字符串匹配，已打过会跳过；上游代码若已变更导致锚点失配，
会明确报告「锚点不匹配」而不是静默改错。

## 已覆盖

| 编号 | 补丁 | 文件 |
|---|---|---|
| P2 | `flock` 在 Android 降级为立即成功 | `node-addon-system/lib/flock.js` |
| P3 | 会话发布改为「锁 + rename」原子发布 | `dsh-session-persistence-jsonl/lib/index.js` |
| P4 | 目录 fsync 容忍权限错误 | `dsh-attachment-local/lib/index.js` |
| P9 | 裸包名按 profile 解析 | `cordis-plugin-loader/lib/index.js` |
| P11 | 新建文件改用「锁 + rename」发布（`link` 被 app 域拒绝） | `dsh-fs-local/lib/index.js` |
| P12 | 补上缺失的 `@vscode/ripgrep-android-arm64` 平台包 | `node_modules/@vscode/`（新增目录） |

## 未自动覆盖（需手工处理，见 docs/PITFALLS.md）

| 编号 | 内容 | 原因 |
|---|---|---|
| P1 | `node-addon-require-builtin` 加载失败时降级 | 涉及 `dsh-app-boot` 启动期异常处理，改动上下文较大 |
| P5 | 纯 JS 图片头解析（替代 `sharp`） | 实现较长，且需按上游版本调整 |
| P10 | 用 bionic coreutils 替换 static musl busybox | 属于负载组装，见 `stage-bionic-assets.sh` |

这三项在 `stage-bionic-assets.sh` / 负载组装阶段处理，或参考 PITFALLS 自行实现。
