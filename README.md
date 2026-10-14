# DSH for Android

把 [DeepSeek Harness](https://www.npmjs.com/package/@deepseek-ai/dsh)（下称 DSH）打包成**独立 Android 应用**。

装一个 APK 就能用：**不需要 Termux、不需要 proot、不需要 root、不需要电脑**。运行时（Node、bash、ripgrep、coreutils）全部内嵌在 APK 里。

> **状态**：可用。已在小米 2509FPN0BC / Android 17 上验证（截屏、点按、文件读写、图片上传、插件系统均正常）。
> **代价**：`targetSdk` 必须是 28，因此**无法上架 Google Play**。原因见 [docs/PITFALLS.md](docs/PITFALLS.md)。

---

## 它长什么样

- **WebView 里是真正的 DSH Web 界面**——不是重写的，是同源代码
- 主界面、插件、皮肤、agent 全部跟桌面端一致
- 额外内置一块**设备控制接口**（截屏 / 点按 / 滑动 / 输入 / OCR / 启动应用），走 `su` 通道
- 会话、附件、凭据都存在应用私有目录，升级不丢
- 启动时那块**原生控制台也能换色、加背景图**，改手机上的一个文本文件即可，不用重新打包

---

## 启动界面外观

App 启动后先显示的是原生控制台（标题 / 状态 / 按钮 / 运行日志），它读手机上的配置：

```ini
# /sdcard/DSH/launcher/theme.conf
background   = /sdcard/Pictures/wallpaper.jpg   # 也可以只丢一张 bg.jpg 到同目录
scrim        = 0.62      # 背景图不透明度（压暗蒙版）
card_opacity = 0.88      # 面板/卡片不透明度，有背景图时才有意义
bg           = #0B1220   # 底色，也是图片的兜底色
card         = #16223C
primary      = #4D6BFE
text         = #EAF0FA
```

改完**下次启动生效**。没授权 / 图读不出来时自动退回纯色，界面照常工作。
App 首次运行会写一份带全部键和默认值的 `theme.example.conf` 供照抄。
完整键表、失败回退、排查表见 **[docs/APPEARANCE.md](docs/APPEARANCE.md)**。

---

## 快速开始

### 前置

- Linux 构建机（x86_64 或 aarch64 均可）
- JDK 21
- `android-sdk/build-tools/29.0.3`（提供 `aapt2` / `zipalign` / `apksigner` / `dalvik-exchange`）
- Android platform jar（**API 30**）
- Node.js（用于 `install-dsh.sh` 拉包）

**注意：不需要 Gradle。** 本项目用 `aapt2 → javac → dx → zipalign → apksigner` 手工打包。

### 三步构建

```bash
# 1. 干净安装 DSH（拉 npm 包到 bionic-dsh/）
bash scripts/install-dsh.sh

# 2. 组装内嵌运行时（Termux bionic 二进制 + DSH + 补丁）
bash scripts/stage-bionic-assets.sh

# 3. 出 APK
bash scripts/build-app.sh
```

产物：`DSH-Android.apk`，用 `keystore.jks` 签名（脚本首次运行会生成，**请妥善保管，丢了就无法覆盖升级**）。

### 发布的 APK 与版本号

版本号只有一处定义：仓库根目录的 **`VERSION`** 文件。构建脚本、APK 文件名、
`AndroidManifest.xml` 和文档都从它读，不在两处各写一遍。

```
APP_VERSION=1.0.1            本项目版本（对应 Git tag v1.0.1）
APP_VERSION_CODE=10001       Android versionCode（= 1*10000 + 0*100 + 1）
DSH_VERSION=0.2.0-rc.2       内嵌的 DeepSeek Harness 版本
```

APK 的 `versionName` 把两层都带上：**`1.0.1+dsh.0.2.0-rc.2`**，
产物名为 `DSH-Android-v1.0.1-dsh0.2.0-rc.2.apk`。
应用启动日志里也会打一行 `versions: app … · dsh …`——
报 bug 时看这行就知道是哪一层的版本。

**已发布 APK 的签名证书 SHA-256（用于校验下载物真伪）：**

```
76:F0:49:3B:E7:75:8E:94:4F:30:A0:6D:65:95:DE:70:B7:24:FF:B0:D1:C4:B4:8F:90:FD:CF:B5:11:D5:4B:B9
```

```bash
apksigner verify --print-certs DSH-Android-v1.0.1-dsh0.2.0-rc.2.apk | grep "SHA-256 digest"
```

⚠️ **装过官方 APK 的人无法用自己构建的 APK 覆盖升级**——签名不同，Android 会拒绝安装，
必须先卸载（会清空应用数据）。想自己构建长期使用，请从第一次就用你自己的 keystore。

### 给内嵌负载打补丁

DSH 是为 Linux/macOS 写的，在 Android 上有若干必须处理的地方（硬链接、seccomp、原生模块……）。
这些改动以补丁形式维护：

```bash
bash patches/apply-payload-patches.sh <runtime 目录>
```

每个补丁都可重复执行（按锚点字符串匹配，已打过会跳过）。详见 [patches/README.md](patches/README.md)。

---

## 架构

```
┌─────────────────────────────────────────────────────────┐
│ MainActivity                                            │
│  ├─ 日志面板 / 中文控制按钮 / WebView 宿主              │
│  ├─ LauncherTheme   控制台配色 + 背景图（读 /sdcard 配置）│
│  ├─ onShowFileChooser → 系统选择器 → 本地暂存 → 页面    │
│  └─ onConsoleMessage → 浏览器控制台写入应用日志         │
├─────────────────────────────────────────────────────────┤
│ NodeServer（前台服务）                                  │
│  ├─ prepare()      解包负载（改名挪走旧树，绝不原地删） │
│  ├─ prepareProfile() 建立 profile 的 node_modules 视图  │
│  ├─ 启动 dsh web，镜像 stdout/stderr 到 /sdcard 日志    │
│  └─ 环境变量：PATH / HOME / TMPDIR / LD_LIBRARY_PATH …  │
├─────────────────────────────────────────────────────────┤
│ TarExtractor   手写 tar 解析（PAX / GNU 长名 / 断点）   │
│ DeviceServer   127.0.0.1 设备控制（su 通道，参数白名单）│
│ DiagnosticServer  只读状态口（应用内）                  │
│ SelfUpdater / UpdateProvider  自更新（可选）            │
└─────────────────────────────────────────────────────────┘
          ↓ 内嵌负载（assets/runtime.tar.gz）
   usr/bin/{node,bash,rg,cat,ls,cp,…}   bionic 二进制
   dsh/node_modules/@deepseek-ai/…      DSH 及插件
```

### 启动流程

1. 解包 `assets/runtime.tar.gz` 到 `files/runtime/`（约 600 MB，解压后）
2. 建立 `$DSH_HOME/profiles/web/node_modules`（真目录 + 符号链接视图）
3. 侧载 `/sdcard/DSH/plugins/*.tgz`（可选插件）
4. 启动 `node dsh/lib/bin.js web --port 3220`
5. 轮询页面直到客户端插件图注入完成，再把 WebView 挂上去

---

## 关键设计决策

### 为什么是 bionic，不是 glibc 或 musl

Android 应用的 seccomp 过滤器会杀掉一切在启动时注册 `rseq` 的运行时：

| 运行时 | 结果 |
|---|---|
| glibc（静态 / 动态） | `exit=159`（128+SIGSYS），**完全无法启动** |
| musl | `--version` 能跑，一执行 JS 就 159 |
| **bionic（Termux 构建）** | ✅ 一切正常 |

所以内嵌运行时全部来自 Termux 的 bionic 构建。这是本项目最核心的一条结论。

### 为什么 targetSdk 是 28

Android 10+ 的 W^X 策略禁止应用执行自己写出来的文件——而本方案正是"把二进制解包到应用目录再执行"。
`targetSdk ≤ 28` 是唯一能绕开该策略的合法方式（无需 root）。

**代价**：无法上架 Google Play（其对 targetSdk 有下限要求）。这是本方案与生俱来的取舍，不是可以修的 bug。

### 为什么清场用"改名挪走"而不是删除

升级时负载会被整个替换。旧树里可能有 root 拥有的文件（早期实验留下的），
应用既改不动也删不掉——原地删除会静默失败，然后解包卡在某个文件上报 `EACCES`。

**重命名只需要父目录的写权限**，所以先把旧树 `rename` 到 `runtime.stale-<时间戳>`，
再删除（删不掉也无所谓），解包面对的永远是空目录。

### 为什么不用续传

曾经实现过断点续传（记录条目号 + 偏移量）。但检查点只对**写出它的那份压缩包**有效——
换一个包再续传，读到的是垃圾，且会在同一个文件上反复失败。

**现在是每次从空树重新解包**（约 6 秒）。省 6 秒换来一堆脏状态，不划算。

---

## 与其它移动端方案的关系
## 与其它移动端方案的关系

安卓上跑 dsh 目前有两条路，各有代价。说清代价比互相贴标签有用 —— 它们不是谁替代谁，
而是把成本记在了不同的账上。

|  | 容器派（proot + 完整 glibc rootfs） | 本项目（Termux bionic） |
|---|---|---|
| **做法** | proot/proroot + 完整发行版 rootfs | 用 Termux 的包在 Android bionic 上裸跑 |
| **装机** | 装 APK 就完事 | 装 APK 就完事（本项目已经打好包） |
| **环境** | 完整 Ubuntu，`apt` 随便用，原生模块直接能跑 | 需要为 bionic **逐个打补丁/重编** |
| **运行时开销** | 容器层（proroot 已无 ptrace 开销） | 无容器层，理论最快 |
| **装新东西** | `apt install` | 自己编 bionic 版，或折进负载 |
| **体积** | 一个完整发行版 | 只带用得到的那几个二进制 |
| **沙箱** | 两边都受限 | 两边都受限 |
| **跟着上游升级** | 换 rootfs / 重装包，**几乎零成本** | **每次上游改动都要重打一遍补丁** |

### 诚实的取舍

**本项目把成本从"运行时"挪到了"维护"。** 负载里现在有十几处补丁（`patches/apply-payload-patches.sh`），
每一处都对应一个 bionic 或应用沙箱的差异：`flock` 不可用、`link()` 被拒、目录 `fsync` 到根、
glibc 原生模块加载失败、`dx` 静默丢类等等。**这些补丁是持续成本**：上游一旦改动相关代码，
就要重新定位、重新打、重新验（`docs/PITFALLS.md` 与那套发布前校验就是为此存在）。

**如果目标是长期紧跟上游**，容器派在这种场景下更省事；
**如果目标是体积小、启动快、不依赖 root**，本项目更合适。

### 设备能力：两边都需要提权

本项目的设备能力（读屏、点按、通知、剪贴板、传感器）由应用自己提供接口，容器派里的同类操作
通常直接调 `su`——**但这不是"要不要 root"的差别，两边都要**：

```
screencap    只有 shell 或 root 能调用，普通应用调不了
input        同上
```

本项目的屏幕操作插件与设备桥现在都走 `su -c screencap` / `su -c input`。
要真正不依赖 root，得换成下面两条之一，**本项目都还没做**：

- **Shizuku**：用户一次性授权，拿到 adb 级权限，`screencap` / `input` 照样可用，不需要 root。
- **MediaProjection + 无障碍服务**：纯应用层，取帧与注入各授权一次，
  但注入受无障碍限制、取帧要维持一个投屏会话。

换句话说：**现在两条路都需要提权**，区别只在提权的形式和用户要做的操作。


## 为什么不用 Termux

Termux 路线（Termux + proot-distro）能跑任何 Linux 程序，代价是：

1. 用户得先装 Termux、换源、装发行版、装 Node——**门槛高到普通人过不去**
2. proot 是用户态系统调用翻译，I/O 明显变慢
3. 依赖用户在 Termux 里手工维护环境

本项目把整个运行时**内嵌进 APK**，用户只需要装一个包。

付出的是：必须解包后执行（因而 `targetSdk` 只能是 28）、必须只依赖 bionic 二进制、
以及**成堆的平台坑**——全部记录在 [docs/PITFALLS.md](docs/PITFALLS.md)。

---

## 常见问题

**Q：为什么 `targetSdk` 是 28？能不能改？**
不能。Android 10+ 的 W^X 禁止应用执行自己写出来的文件，而本方案正是"解包到应用目录再执行"。
`targetSdk ≤ 28` 是唯一合法绕开方式。代价是无法上架 Google Play。

**Q：为什么 APK 这么大？**
内嵌了完整的 Node.js（45 MB）+ DSH 及 177 个依赖包 + 一批 coreutils。
解压后约 600 MB，所以首次启动要解包几秒。

**Q：能不能支持 iOS？**
不能。iOS 不允许 fork/exec，也不允许执行动态生成的代码，本方案的前提不成立。

**Q：为什么图片能看，但某些图片处理功能不可用？**
原生图像库（`sharp` / libvips）没有 Android 版本，本项目改用纯 JS 解析图片头，
并在需要缩放时直接发送原图（视觉接口单张上限 32 MiB，够用）。
真正的图像编辑（裁切、滤镜、格式转换）在本环境下不可用。

**Q：插件装在哪里？升级会丢吗？**
装在 `files/home/.dsh/profiles/web/node_modules`（**真目录**，位于用户数据区）。
运行时的包用符号链接挂进去。这样升级替换运行时时，你的插件不受影响。

**Q：怎么装第三方插件？**
把 npm tarball 丢进 `/sdcard/DSH/plugins/`，重启应用即自动安装。
注意检查插件许可——**有些皮肤是禁止商用的**（见 [THIRD-PARTY.md](THIRD-PARTY.md)）。

---

## 关于凭据镜像（安全取舍）

应用会把 `.credentials.yaml` 镜像到 `/sdcard/DSH/.dsh-state/`，这样**卸载重装后不必重填 API Key**。

代价：那份镜像位于共享存储，其他持有存储权限的应用可以读到。
（另外那行 `chmod 600` 在 FUSE 上是装饰性的 —— 展示的模式不是访问控制。）

**要真的关掉，得留一个开关文件**（每次启动都会重建镜像，所以只删目录是没用的，
下一次启动就又回来了）：

```bash
mkdir -p /sdcard/DSH/.dsh-state && touch /sdcard/DSH/.dsh-state/NO-BACKUP
```

有了这个文件，应用既不镜像也不恢复（`restoreUserState` / `backupUserState` 都会跳过），
代价是卸载重装后需要重新填 API Key。删掉该文件即恢复镜像行为。

## 已知限制

| 限制 | 说明 |
|---|---|
| **无 iOS** | iOS 不允许 fork/exec，本方案根本性不适用 |
| **无法上架 Play** | 见上文 targetSdk 28 |
| **APK 很大** | 约 130–180 MB（内嵌整个 Node 运行时） |
| **原生模块** | 任何依赖预编译 `.node` 的 npm 包都没有 Android 版本，需要自行处理 |
| **首次启动慢** | 解包约 6 秒（一次性） |

---

## 平台坑清单

**这一节是本项目最有价值的部分。** 完整版见 [docs/PITFALLS.md](docs/PITFALLS.md)，
包含每一条的**现象、根因、验证方式和修法**：

- glibc / musl / bionic 的 seccomp 差异（含 `tsys.bin` 系统调用排查工具）
- `flock` 不被支持 → 单进程环境下的正确降级
- `link()` 被拒 → 原子发布必须用「锁 + rename」，**不能用 copyFile**
- 目录 `fsync` 一路 sync 到根 → `EACCES`
- `sharp` 等原生图像库无法加载 → 纯 JS 头部解析替代
- WebView 默认不处理 `<input type=file>`（且必须回传结果，否则永久卡住）
- `dx` 会**静默丢弃**含新版 API 引用的整个类（编译成功 ≠ 代码进包）
- 客户端插件图为空 → 页面只有 3709 字节

---

## 许可

- **本项目代码**（`app/`、`scripts/`、`patches/`、`tools/`）：MIT，见 [LICENSE](LICENSE)
- **内嵌的 DSH**：MIT（`@deepseek-ai/dsh` 及其 177 个依赖包）
- **内嵌的 Termux 二进制**：各自许可证，含 **GPL** 组件（coreutils、bash、busybox）
- 第三方清单与义务见 [THIRD-PARTY.md](THIRD-PARTY.md)

⚠️ **本仓库不包含任何第三方插件**。皮肤类插件往往带有限制性许可
（例如鲸鱼皮肤为 `CC-BY-NC-SA-4.0`，禁止商用且具传染性），请自行评估后再安装。

---

## 致谢

- [DeepSeek Harness](https://www.npmjs.com/package/@deepseek-ai/dsh) —— 被移植的本体
- [Termux](https://termux.dev) —— 提供了唯一能在 Android 应用沙箱里存活的 bionic 运行时
- [xAI grok-build](https://github.com/xai-org/grok-build)、[PhoneBuddy SDK](https://github.com/APUS-AI-Lab/PhoneBuddySDK) —— 移动端 agent 引擎的另一条路线（纯 Rust、零子进程），本项目的取舍与它们正相反，值得对比阅读
