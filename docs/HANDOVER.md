# 交接文档（DSH Android）

> 更新时间：2026-10-24。上一个会话很长（修了一整轮 0.2.1 升级 + 两个用户反馈），上下文用尽，交接给新会话。
> 读这一份就够接活。**不要凭记忆，所有"应该/大概"都要回到文件或真机去核。**

---

## 1. 这是什么

Android App `com.dsh.android`：把 DeepSeek Harness 内嵌进 APK（自带 bionic Node 运行时，不需要 Termux / proot / root）。仓库：`https://github.com/yhm080315-sudo/dsh-android`。

- 工作区（手机上）：`/sdcard/DSH`
- 构建机（容器里）：`/root`
- App 私有运行时：`/data/user/0/com.dsh.android/files/runtime`（每次装包整棵替换）
- profile：`/data/user/0/com.dsh.android/files/home/.dsh`（从 `/sdcard/DSH/.dsh-state` 恢复）

---

## 2. 现在的状态（最重要）

### 版本

| 项 | 值 |
|---|---|
| 已发布的稳定版 | **1.0.18**（`DSH 0.2.0-rc.2`，可正常对话） |
| 本地已提交未推送 | **1.0.19**（commit `e1f512b`，tag `v1.0.19`，只在本地） |
| 正在做 | **1.0.19-beta16**（= beta15 + 移除 `dsh-boot-animation`，白屏探针改为按 `#root` 渲染内容判断，versionCode **10019**）。beta15 已从 `/sdcard/Download` 删掉 |
| 版本号约定 | `APP_VERSION` / `APP_VERSION_CODE` / `DSH_VERSION` 三处必须同步：`VERSION`、`app/AndroidManifest.xml`、`build-v55/gen/BuildConfig.java` |

**1.0.19 尚未发布**，用户要求：beta13 测通 → 去掉 `-beta` 再提交 → main / tag / Release **一起推**，不单开 1.0.20。

**beta13 的改动（许可合规）**：载荷里删掉 `dsh-skin-whale-fantasy`（CC-BY-NC-SA-4.0，不能随包分发）、
`dsh-pro-mode-splash` / `dsh-web-mobile`（MIT，当时判为「非必要」）；保留 `dsh-boot-animation`
（白屏探针依赖它注入的 `window.__DSH_BOOT__`）与 `dsh-screen-operate`（核心功能，许可待作者确认）。

**beta15 的更正**：把 `dsh-web-mobile`（MIT，作者 mexiaosh）加回载荷。它是**手机端界面适配**
本身，不是可选布局增强——删了它，手机上打开的是桌面宽度界面。同步改了三处：`CLIENT_ROWS`
加回 `{"dsh-web-mobile", "dsh-web-mobile"}`（放在 boot-animation 之前，原顺序）、
`REMOVED_PLUGINS` 去掉它（留着会在启动时把它的 profile 行清掉）、`THIRD-PARTY-NOTICES.md`
补 1.3 节（MIT 全文，版权行照录包内 `LICENSE`：`Copyright (c) 2026 mexiaosqwq`）。
版权声明随载荷分发：`payload/THIRD-PARTY-NOTICES.md` → `files/runtime/THIRD-PARTY-NOTICES.md`，
诊断页 `http://127.0.0.1:3097/` 也会打出来。**profile 里遗留的插入行由 NodeServer.pruneRemovedRows()
每次启动清掉**——不清就会 `failed to import: Cannot find module '<plugin>'`（已实测复现）。

### 阻塞点

**GitHub 令牌失效了**（`401 Bad credentials`）。推送 / 建 Release / 上传 APK 全部卡在这里。需要一个 fine-grained PAT（只给 `dsh-android` 仓库，`Contents: Read and write`，7 天）。

**git push 走不通**（git 协议在这台机器上一直不通），只能用 **REST API** 推（blob → tree → commit → ref），见第 7 节。

---

## 3. 目录地图（构建机 `/root`）

```
/root/dsh-android-oss/          仓库（源码 + 补丁 + 文档）—— 唯一要动的地方
  app/src/com/dsh/android/      Java：MainActivity / NodeServer / DeviceServer /
                                DiagnosticServer / FileBridge / LauncherTheme / SelfUpdater …
  patches/apply-payload-patches.sh   补丁集（P1–P26，幂等，含 MUST_APPLY 必成清单）
  patches/README.md             补丁表
  docs/PITFALLS.md              踩坑清单（16 条，新坑往里加）
  docs/HANDOVER.md              本文件

/root/v39work/payload/          载荷（构建源）：dsh/node_modules（DSH 0.2.1-rc.2 + P21–P26）
/root/v39work/apk/assets/runtime.tar.gz   载荷打包产物，构建时被 aapt2 打进 APK
/root/payload-backup-0.2.0-rc.2/   0.2.0 载荷备份（回退用，别删）
/root/build-v55/                构建树（app/ assets/ gen/ classes/ dex/ r/）
/root/keys/                     keystore.jks + keystore.pass（**唯一不可再生**，已从旧目录救出）
/sdcard/Download/               给用户的包
```

**不要在 /root 下堆载荷副本**——之前堆到 17GB 被用户投诉，已清到 3.2GB。临时验证一律放 `/tmp` 并当场删。

---

## 4. 构建流程（照抄即可）

```sh
R=/root/dsh-android-oss; S=/root/build-v55
BT=/usr/lib/android-sdk/build-tools/29.0.3
PLAT=/tmp/plat30/android-11/android.jar

# 1) 载荷改了才需要重新打包
cd /root/v39work && rm -f apk/assets/runtime.tar.gz && tar -C payload -czf apk/assets/runtime.tar.gz .

# 2) 同步源码与版本
cp -f /root/v39work/apk/assets/runtime.tar.gz $S/assets/
cp -f $R/app/AndroidManifest.xml $S/app/AndroidManifest.xml
cp -f $R/app/src/com/dsh/android/*.java $S/app/src/com/dsh/android/
cat > $S/gen/BuildConfig.java <<'EOF'
package com.dsh.android;
final class BuildConfig {
    static final String RUNTIME_STAMP = "<新时间戳，必须与上一版不同>";
    static final String APP_VERSION = "<版本>";
    static final String DSH_VERSION = "<DSH 版本>";
    private BuildConfig() {}
}
EOF

# 3) 编译（退出码自己看，**不要管道给 grep**）
cd $S && find app/src gen -name '*.java' > sources.txt && rm -rf classes && mkdir classes
javac -source 8 -target 8 -encoding UTF-8 -nowarn -bootclasspath $PLAT -classpath $PLAT -d classes @sources.txt

# 4) 打包链
$BT/aapt2 link -o base.apk -I $PLAT --manifest app/AndroidManifest.xml -A assets --java r/gen \
  --version-code <code> --version-name "<name>" --min-sdk-version 24 --target-sdk-version 28 r/res.zip
rm -f dex/classes.dex && dalvik-exchange --dex --no-strict --min-sdk-version=24 --output=dex/classes.dex classes
OUT="DSH-Android-<ver>.apk"; cp base.apk unsigned.apk && (cd dex && zip -q -X ../unsigned.apk classes.dex) && rm -f "$OUT"
$BT/zipalign -p -f 4 unsigned.apk "$OUT"
KS_PASS=$(cat /root/keys/keystore.pass)
$BT/apksigner sign --ks /root/keys/keystore.jks --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" --ks-key-alias dshandroid "$OUT"
$BT/apksigner verify "$OUT"
```

**门禁（必须从 APK 里抠出来验，不是看构建树）**：

1. `aapt2 dump badging` 的 versionCode / versionName 对得上（**注意：清单里已经写了 versionCode，aapt2 的 `--version-code` 只在清单没写时生效**，这个坑踩过）
2. `assets/runtime.tar.gz` 里的 `@deepseek-ai/dsh/package.json` 版本对
3. 会话三符号仍在：`session.lock.d`、`breaking an abandoned session write lease`、`rewriteSalvagedLog`
4. 载荷里的插件（`tar -tzf ... | grep -c "/名字/"`，数字含目录条目）：
   `dsh-screen-operate` **12**、`dsh-web-mobile` **10**（= 9 个成员 + 插件目录自身）；
   `dsh-boot-animation`（beta16 起）、`dsh-skin-whale-fantasy`、`dsh-pro-mode-splash` 必须 **0**
   且 `THIRD-PARTY-NOTICES.md` 在 tar 根目录、内容非空
5. `apksigner verify` 通过
6. 只留最新的包在 `/sdcard/Download`（旧包当场删）

---

## 5. 补丁集怎么用（踩过的坑都在这）

```sh
bash /root/dsh-android-oss/patches/apply-payload-patches.sh /root/v39work/payload/dsh/node_modules
```

- **幂等判断是 `if note in text:`，`note` 必须是它自己 `replacement` 里真实出现的子串**。这一条栽过三次：note 是中文标签、note 被前一条补丁的 replacement 写进文件、note 指向根本不存在的文本 → 结果是补丁被**静默跳过**或**重复插入**。
- **`MUST_APPLY` / `MUST_STATE`**：必成条目失配或落盘文本不对 → 脚本非 0 退出。改补丁集时别绕过它。
- **同一行只能有一条补丁**。P20a 与 P25 曾经抢同一行 import，后执行的那条永远「锚点不匹配」。
- **锚点要从干净上游取，不要从改过的载荷取**。P25 的锚点踩过：拿载荷（已被手改）当锚点，在干净树上必然失配（干净树里 `copyFile`/`writeFile`/`rename` 三个都没有）。
- 干净树复验：

```sh
D=/tmp/deb/rootfs/data/data/com.termux/files/usr
NPM="$D/bin/node $D/lib/node_modules/npm/bin/npm-cli.js"
mkdir -p /tmp/clean && cd /tmp/clean && $NPM init -y
$NPM install @deepseek-ai/dsh@0.2.1-rc.2 --os=linux --cpu=arm64 --ignore-scripts
# ↑ 两个参数缺一不可：bionic node 跑 npm 会被判成 android，linux 平台包会被跳过；
#   koffi 的 postinstall 会试编译并失败
bash /root/dsh-android-oss/patches/apply-payload-patches.sh /tmp/clean/node_modules   # 第一遍全应用
bash /root/dsh-android-oss/patches/apply-payload-patches.sh /tmp/clean/node_modules   # 第二遍 0 应用全跳过
```

---

## 6. DSH 0.2.1 的原生绑定问题（重要背景）

DSH 0.2.1 新增原生依赖 `node-addon-require-builtin`，**npm 上没有 android 版本**，加载器没有 JS 兜底 → 启动即崩 `host preparation failed: No usable native binding found`。

**已解决**：`P21` 用 120 行 JS 替身顶掉它（只改 `node-addon-require-builtin/lib/index.js`）。要点：
- `internal/modules/cjs/loader` → `require("node:module")`（真货，`_resolveFilename` 是真的）
- `internal/modules/esm/loader` → facade（`resolveSync` 用 `createRequire(parent).resolve` + 真 dynamic import）
- `getOrCreateModuleJob` 只做占位（app-boot 只做 `in` 检测，从不调用）
- conditions 实测 bionic node v24.18.0：ESM `node,node-addons,import,module-sync`；CJS `node,node-addons,require,module-sync`

**已知限制（不影响使用，不要当 bug 报）**：ESM 路由是惰性的（Node 无法从 JS 换掉真 ESM loader，实际解析走 `createRequire(baseUrl)`，平坦载荷无影响）；HMR 因缺 `internal.loadCache` 自动降级（web profile 本来禁用）。

**编译 .node 这条路走不通**：容器没有 NDK/clang；树里的 musl 预编译在 bionic 上 `dlopen` 失败（缺 `libstdc++.so.6`）。

---

## 7. 推送（git 协议不通，只能走 API）

```python
token = <新 PAT>
# GET  /git/ref/heads/main            → 父提交
# POST /git/blobs                     → 每个文件一个 blob（base64）
# POST /git/trees  {tree:[...]}       → 全量树（用 ls-tree -r HEAD 取 path/mode）
# POST /git/commits{message,tree,parents}
# PATCH /git/refs/heads/main          {sha, force:true}
# PATCH /git/refs/tags/<tag>          {sha, force:true}
# POST /releases                      {tag_name,name,body,draft:false}
# POST https://uploads.github.com/repos/<o>/<r>/releases/<id>/assets?name=<file>
```

- 推送会生成**新的提交对象**（内容相同、sha 不同）→ `scripts/check-tag.sh` 会报「远端 tag 与 HEAD 不一致」，那是**误报**（它比 sha 不比树）。
- 上传后必须查 asset 的 `state` 是否为 `uploaded`（`starter` 表示没传完）；大小要与本地逐字节一致。
- **令牌用完 `shred -u` 销毁**，并提醒用户去 GitHub 撤销。

---

## 8. 待办（按优先级）

1. **等 `1.0.19-beta12` 构建 + 真机验证**（#1/#2 修复，见第 9 节），通过后去 `-beta` 提交为 **1.0.19**
2. **推送 + Release 1.0.19**（需要新令牌）
3. **审 PR #3**（外国用户 `muhib-karim` 提的英文界面 + 英文文档；中文放 `values-zh` 不变）。建议 1.0.19 稳定后再合，合前必须逐行读 diff
4. 可选：`patches/apply-payload-patches.sh` 与用户给的"权威副本"（`work/repo-main/.../apply-payload-patches.sh`，24,649 B / sha `fd88e1c3`）目前**不是同一份**（我们这份是超集，36KB+）。推送前需要一次对账，或者把差异写进 `patches/README.md`
5. 可选：内置 python3（用户问过"能不能装开发环境"）。Termux 有 bionic 版 python，原理同 node；代价 +40~60MB。带 native 扩展的包（numpy 等）仍然装不了，那要走 proot

---

## 9. 两个用户反馈（beta12 正在修）

### #1 老 WebView 上界面卡在「重新连接中」（影响面最大）

华为平板 BKY-W00 / Android 12（鸿蒙 4.2），系统 WebView 内核低于 Chrome 100。日志：`Promise.withResolvers`、`AbortSignal.any`、`signal?.throwIfAborted` 都不是函数。引擎在跑（3220、`running: true`），是**前端用了新 API**。

反馈者已验证的修法：在 `@deepseek-ai/dsh-web-frontend/dist/index.html` 的 `<title>` 之后注入 polyfill，重打包后正常。

修法要求：做成 **P26** 补丁（可重放）；**先查 CSP**，内联脚本可能被拦（被拦就改 sha256 白名单或同源 `.js`）；按前端 bundle **实际用到的**新 API 补，不要无脑全补。

### #2 AGENTS.md 模板两个 bug

OPPO PKM110 / Android 16 / **未 root**。`NodeServer.writeAgentInstructions()`：
- **模板写死「已 root（KernelSU）」** → 改成运行时探测（`su -c id -u`，超时 2 秒），结果写日志
- **写入的 workspace 路径与真实会话 cwd 不一致** → 说明文件永不生效。要写到会话真正会读的位置；用户级副本 `$HOME/.dsh/AGENTS.md` 两份都要写、内容一致（这个机制 1.0.17 做过，别破坏）

---

## 10. 这个项目最容易犯的错（血泪清单）

1. **门禁比聪明值钱**。这一轮门禁拦住了 8 次真错（忘重打 tar、没重跑 `aapt2 link`、编译失败、内容缺失、versionCode 没改），只误报过 2 次（在 dex 里找源码表达式/变量名 —— **dex 只有字符串常量**）。**门禁要验"从 APK 里抠出来的东西"，不是验构建树。**
2. **语法检查 ≠ 能跑**。`node --check` 过、`--version` 正常，`publishExclusive` 却根本没定义，只有真写会话才 `ReferenceError`。**能真跑就跑一次**。
3. **"看不到"和"没有"是两件事**。控制台按钮找了半天，其实是芯片被 WebView 盖住了（先加的子 View，后来的 WebView 覆盖它）。
4. **修 A 的时候别把 B 弄没**。P20 为了修白屏自动加载 GUI，结果把"回控制台"的路彻底堵死，而那条路**本来就不存在**（用户纠正过我：不是 P20 改没的）。
5. **拿改过的副本当基准 = 循环验证**。锚点必须来自干净上游。
6. **不要"下一轮再补"**。用户明确要求：有问题一次改好；上下文不够就交子 agent。
7. 每次改版本号，`RUNTIME_STAMP` 也要换，否则真机不会重新解包载荷（装上去还是旧运行时）。
8. `cp -rf src target/` 会把目录名带过去，装成 `target/src` 这种名字。目标名必须写全。

---

## 11. 分工约定（已经跑顺的）

- **她在真机上做**：写/测插件、看日志、拿证据、给修法建议（`/sdcard/DSH/work/*.md` 是她的产物，不要删她没让删的）
- **构建机做**：改 Java / 改载荷 / 打补丁 / 构建 / 门禁 / 发布
- **子 agent 做**：上下文不够时，把任务写清楚派出去（锚点从哪来、怎么验、退出码自己看、只动哪里），它们往往能反过来纠正父会话的错误结论——这一轮有三份报告纠正过我
- 回报结论时**只给证据**（文件位置、原始输出、复现步骤），不猜
