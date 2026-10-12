# 平台坑清单

在 Android 应用沙箱里跑一个 Linux 桌面程序，会遇到一批**编译期完全看不出来、运行期才炸、而且报错信息极具误导性**的问题。

这份文档按「现象 → 根因 → 验证 → 修法」记录每一条。全部在这一台真机上复现过。

---

## 1. glibc 与 musl 二进制会被 seccomp 直接杀掉

**现象**

```
$ ./node --version
Killed
$ echo $?
159            # 128 + 31 = SIGSYS
```

**根因**

Android 应用的 seccomp 过滤器不允许 `rseq` 系统调用注册。glibc 在启动时（`__libc_start_main` 早期）就会注册 `rseq`，
于是**任何 glibc 二进制，静态或动态，都在 main 之前就被杀**。

musl 的情况更隐蔽：`--version` 之类不进入 V8 的路径能跑，**一旦执行 JS 就死**——因为 V8 初始化时才触发。

**验证方法**

不要靠猜。仓库里的 `tools/check-runtime.sh` 会对任意二进制做判定，
并逐项探测相关系统调用（`rseq`、`clone3`、`madvise`、`membarrier` …）：

```bash
bash tools/check-runtime.sh path/to/node
#   exit 159 → 这个运行时的 libc 会注册 rseq，被 seccomp 杀掉
#   exit 0   → 能在这个沙箱里跑
```

（原先用的是一次性的探测二进制，没有随仓库发布；现在换成了可读、可改、可复现的脚本。）

**结论**

| 运行时 | 结果 |
|---|---|
| glibc 静态 | ❌ 159 |
| glibc 动态 | ❌ 159 |
| musl | ❌ 执行 JS 时 159 |
| **bionic** | ✅ 正常 |

**修法**：内嵌运行时全部使用 **Termux 的 bionic 构建**。这是整个项目的地基。

---

## 2. `flock` 在 Android 上不被支持

**现象**

```
本轮运行失败  flock is not supported on android-arm64
```

来自 `@deepseek-ai/node-addon-system/lib/flock.js`：它只认 `linux` 与 `darwin`。

**根因**：该模块读取 `process.platform`，Android 下是 `android`，直接被拒。

**修法**：在 `tryLockExclusive()` 开头对 `platform === 'android'` 直接返回（降级为立即成功）。
这与 DSH 自己对浏览器 worker 的处理一致——**单进程运行时，进程内互斥已经足够**。

> ⚠️ 若将来出现多进程场景，这个降级会失去跨进程互斥。真正的修法是编译该 addon 的 Android 版本。

---

## 3. `link()` 被拒——**不要用 copyFile 兜底**

**现象**

```
EACCES: permission denied, link '/data/.../session.v4.jsonl.zstd.<tmp>.tmp'
     -> '/data/.../session.v4.jsonl.zstd'
```

**根因**：部分 Android 应用存储卷拒绝 `link(2)`。DSH 用硬链接做「原子发布」（临时文件 → 目标名）。

**为什么不能用 `copyFile` 兜底**（这是本项目踩过最贵的坑）

`link` 提供**两个**保证：**独占**（目标已存在就失败）和**原子**（读者永远看不到半成品）。
`copyFile` 两个都没有——它会先截断目标再逐步写入。

实际后果：一次会话写入被中途打断，日志中间出现空洞，之后**每次加载该会话都失败**：

```
corrupt session log: invalid committed event at line 991:
released v2 row 990 has seq gap (expected 990, got 986)
```

而 DSH 的会话读取在「可恢复模式」下也会因为坏行后面跟着 `turn/end` 事件而抛错——
即**整个会话报废，无法加载**。

**正确修法**：锁文件 + `rename`

```js
// 独占：用 O_EXCL 抢占锁文件（并发时短暂重试）
// 原子：rename 是原子的，读者看不到写一半的文件
handle = await open(lock, 'wx');
try {
    if (await exists(target)) return false;   // 目标已存在 → 不是我们要创建的
    await rename(staged, target);
    return true;
} finally {
    await handle.close();
    await rm(lock, { force: true });
}
```

若语义是「正本必须保留」（如内容寻址存储的别名），则用 **copyFile 到临时名 → 校验 sha256 → rename**，
**绝不能把正本 unlink 掉**。

**受影响的位置**（DSH 内）

- `dsh-session-persistence-jsonl`：`publishCurrentExclusive` / `materializePosix`
- `dsh-attachment-local`：`publishStagedObject`（可消耗 → rename）/ `publishImmutableAlias`（正本 → copy+校验+rename）

---

## 4. 目录 `fsync` 会一路 sync 到文件系统根

**现象**：附件上传失败，内部报 `EACCES`，指向 `/data/user/0` 或更上级。

**根因**：`dsh-attachment-local` 的 `ensureDurableHome()` 会把路径上**每一级祖先目录**都 fsync 一遍，
一路到 `/`。而应用**无权打开自己数据目录之上的任何目录**。

**修法**：目录同步改为**容忍权限错误**（跳过该层）。文件内容本身在写入时已经 fsync，
祖先目录的条目持久性在 Android 上只能是尽力而为：

```js
try { /* open + sync + close */ }
catch (error) {
    if (!["EACCES","EPERM","ENOTSUP","ENOENT"].includes(error?.code)) throw error;
}
```

---

## 5. 原生模块（`sharp` 等）无法加载 → 图片全部"损坏"

**现象**：上传图片后，agent 说"无法解码为受支持的图片"，然后宣称自己没有视觉能力。

**根因**：DSH 每收一张图都要先经 `sharp` 做解码验证与变体生成。
而 payload 里的 `@img/sharp-linux-arm64` 是 **glibc 的 linux 原生库** —— 在 bionic 上加载不了。
于是每张图都被判为 `INVALID_IMAGE`，**图片根本没进请求**，模型只收到文字。

> 注意误导性：这个报错看起来像"图片坏了"，实际是**解码器不存在**。

**修法**：绕开原生库

1. **纯 JS 解析图片头**（PNG / JPEG / GIF / WebP 的尺寸、格式、alpha 都能从头部字节读出来）
2. **需要缩放但缩放不可用时，直接发送原图**——
   DeepSeek 的视觉接口单张 base64 上限 32 MiB，手机截图远用不到缩放

实测解析器对真实文件的输出与头部真值一致（含 WebP VP8 / VP8L / VP8X 三种封装）。

---

## 6. WebView 对 `<input type="file">` 完全沉默

**现象**：点「上传图片」，**什么都不发生**。没有弹窗，没有报错，没有任何日志。

**根因**：WebView 默认不处理文件选择，宿主必须实现 `WebChromeClient.onShowFileChooser`。

**两个常见错误**

1. 只实现 `onShowFileChooser`，**忘了 `onActivityResult` 回传结果** → 选择器弹出、选完、然后永久卡住（回调没被应答）
2. 直接把选择器返回的 `content://` URI 交给页面 → WebView 可能读成**空文件**，上传静默失败

**修法**

```java
// onShowFileChooser：记录回调 + 起系统选择器
pendingFileCallback = callback;
startActivityForResult(params.createIntent(), REQUEST_FILE_CHOOSER);

// onActivityResult：把文件复制到应用缓存，回传 file:// URI
// 取消也必须回传 null，否则下次点击永久卡住
pendingFileCallback.onReceiveValue(chosen);   // chosen 可为 null
```

另外建议把 `onConsoleMessage` 也接上——**浏览器侧的错误默认完全不可见**，
而 DSH 的客户端失败信息恰恰只打 console。

---

## 7. `dx` 会静默丢弃整个类（编译成功 ≠ 代码进包）

**现象**：Java 改动编译通过、`classes/` 里的 `.class` 有改动、但 APK 的 `classes.dex` 里没有。

**根因**：`dalvik-exchange`（dx）遇到某些较新的 framework 引用时，
会**丢掉包含该引用的整个类**，而且**不报错、不警告、退出码为 0**。

实际案例：使用 `android.provider.OpenableColumns.DISPLAY_NAME` 后，
整个 `MainActivity` 从 dex 里消失；改用字符串字面量 `"_display_name"` 即正常。

**修法**

- 尽量避免较新的 framework 常量，改用字面量
- **构建后必须验证 dex 内容**，例如逐个 `grep` 关键字符串：

```bash
python3 -c "
import zipfile
d = zipfile.ZipFile('DSH-Android.apk').read('classes.dex')
for s in [b'onShowFileChooser', b'_display_name', b'parked the old runtime tree']:
    print(('OK  ' if s in d else 'MISS'), s.decode())
"
```

> 教训：**"编译通过" 与 "代码在包里" 是两件事**，中间那一步会无声失败。

---

## 8. 客户端插件图为空 → 页面只有 3709 字节

**现象**：界面能打开，但报

```
Failed to load plugins
client-modules: HTML did not preload @deepseek-ai/dsh-client-modules/client.js
```

页面大小始终 3709 字节（正常应为 34–36 KB）。

**根因**：客户端插件扫描是在 **profile 目录**下解析 `node_modules` 的。
如果 profile 里没有 `node_modules`（或它指向了不存在的位置），扫描结果为空，
`__DSH_BOOT__` 的 entries 为 `[]`，页面就没有引导脚本。

**修法**：启动前确保 `$DSH_HOME/profiles/web/node_modules` 存在，并且能解析到打包的插件包。

**验证**：看日志里 `page check` 的字节数——**34846 字节 = 正常，3709 字节 = 插件图为空**。

---

## 9. 裸包名按"loader 自己的位置"解析，而非 profile

**现象**：插件已安装在 profile 里、也能被解析到，但启动时报

```
dsh-whale-widget (dsh-whale-widget): failed to import
```

**根因**：`cordis-plugin-loader` 在**没有 Node 内部加载器**时（Android 就是这种情况）走：

```js
else return await import(name);   // 裸包名 → 相对于本文件所在位置解析
```

于是 `import("某个插件")` 是从**运行时安装目录**去找的。
插件装在运行时里时"恰好"能找到；一旦装到 profile 里（更安全的位置）就找不到了。

**修法**：按 loader 的 `baseUrl`（即 profile 目录）解析：

```js
const resolved = pathToFileURL(createRequire(baseUrl).resolve(name)).href;
```

---

## 10. 静态 musl 的 busybox 会被打死

**现象**：`cat` / `ls` / `tar` / `head` 全部报 `Unknown signal 31`。

**根因**：内嵌的 busybox 是 **static musl** 构建——与第 1 条同一个病：`rseq` → SIGSYS。

**修法**：换成 bionic 版本的 coreutils。注意 Termux 的 busybox 启动器**把库路径硬编码成**
`/data/data/com.termux/...`，在自定义运行时里加载不了——所以直接用**独立的 coreutils 二进制**
（`cat`、`ls`、`cp`、`mv`、`rm`、`head`、`tail`、`wc`、`sort`、`uniq`、`cut`、`tr`……），
它们没有路径假设。`grep` / `find` 由 `rg` 覆盖。

---

## 11. 其他必踩项（简表）

| 现象 | 根因 | 修法 |
|---|---|---|
| `node` 找不到 `node-addon-require-builtin` | 该 addon 无 Android 版本 | 让 `dsh-app-boot` 在加载失败时降级（try/catch） |
| `credentials-local: .credentials.yaml is readable beyond its owner (mode 755)` | Android 给文件组可读权限 | 启动前 `chmod 600`，并在缺失时预置 `{}` |
| `Cannot find package 'semver'` 等 | profile 的模块视图缺失 | 见第 8 条 |
| OpenSSL 报 `fopen(/data/data/com.termux/.../openssl.cnf): Permission denied` | Termux 的 node 把该路径编译进去了 | 设 `OPENSSL_CONF` / `SSL_CERT_FILE` / `SSL_CERT_DIR` 指向包内副本 |
| 升级后用户的插件消失 | 插件装在运行时目录，而运行时会整体替换 | profile 的 `node_modules` 用**真目录**（在用户数据区），运行时包用符号链接挂进去；另支持从 `/sdcard/.../plugins/*.tgz` 侧载 |
| 升级后 API Key 丢失 | 卸载会清空应用数据 | 把关键状态镜像到共享存储，首次启动恢复 |
| 解包卡在同一个文件（`EACCES`） | 旧树里有应用无法删除的文件（属主为 root） | **改名挪走**旧树再解包，见 README「关键设计决策」 |
| 解包读到垃圾、反复失败 | 断点续传的检查点属于另一份压缩包 | 取消续传，每次从空树解包 |
| `HOME` 与状态文件不一致 | 状态处理用 `homeDir()`，启动却传了旧路径 | 路径收敛到单一访问函数，禁止第二处拼写 |

---

## 排查方法论

这份清单里大部分条目是**花了多轮才定位**的，共同教训有三条：

1. **日志里"没有出现"的那一行，往往比出现的更能说明问题。**
   （例：插件页显示"未运行"，日志里却没有 import 错误 → 说明请求/加载根本没走到那一步。）

2. **先确定请求走到了哪一层，再插桩。** 只在服务端加日志、而错误发生在网关之前，就是白加一轮。

3. **能本地复刻的，绝不去真机试。**
   在容器里用同一份运行时 + 同一份 profile 布局跑一遍，
   往往一次就能复现并验证修复（第 9 条就是这么定位的）。
