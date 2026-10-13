#!/usr/bin/env bash
#
# 给内嵌的 DSH 负载打上 Android 适配补丁。
#
# 用法：
#   bash patches/apply-payload-patches.sh [runtime 目录]
#
# 默认目标是 ./bionic-dsh/node_modules（install-dsh.sh 的产物）。
# 脚本可重复执行：每个补丁都按锚点匹配，已打过会跳过并说明。
#
# 每个补丁的理由见 docs/PITFALLS.md，编号与那里一一对应。
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TARGET="${1:-$HERE/../bionic-dsh/node_modules}"

if [ ! -d "$TARGET/@deepseek-ai" ]; then
    echo "找不到 DSH 包目录：$TARGET" >&2
    echo "先运行 scripts/install-dsh.sh，或用第一个参数指定 runtime 目录" >&2
    exit 1
fi

echo "== 对 $TARGET 应用 Android 适配补丁 =="

python3 - "$TARGET" <<'PY'
import sys, os, io

root = sys.argv[1]
applied, skipped = [], []

def patch(rel, anchor, replacement, note, label):
    """Replace `anchor` with `replacement` in `rel`, idempotently."""
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        skipped.append(f"{label}（文件不存在：{rel}）")
        return
    with io.open(path, encoding="utf-8") as fh:
        text = fh.read()
    if note in text:
        skipped.append(f"{label}（已打过）")
        return
    if anchor not in text:
        skipped.append(f"{label}（锚点不匹配，可能上游已变更）")
        return
    with io.open(path, "w", encoding="utf-8") as fh:
        fh.write(text.replace(anchor, replacement, 1))
    applied.append(label)

# ── P2: flock 在 Android 不被支持 ───────────────────────────────────────────
patch(
    "@deepseek-ai/node-addon-system/lib/flock.js",
    """export async function tryLockExclusive(fd) {
    const errno = await new Promise((resolve) => {""",
    """export async function tryLockExclusive(fd) {
    /*
     * Android: no native binding exists for bionic, and the module's own design
     * note records that a single-process runtime already excludes writers
     * in-process -- the browser worker stubs this entry for the same reason.
     */
    if (process.platform === 'android') {
        return;
    }
    const errno = await new Promise((resolve) => {""",
    "single-process runtime already excludes writers",
    "P2  flock 降级",
)

# ── P3: 会话发布的原子性（锁 + rename，不能用 copyFile）────────────────────
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """			await link(tmp, finalPath);""",
    """			await publishExclusive(tmp, finalPath);""",
    "publishExclusive",
    "P3a 会话发布改用原子发布",
)
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """		await internals.fs.link(staged, currentPath);""",
    """		if (!await publishExclusive(staged, currentPath)) return false;""",
    "publishExclusive",
    "P3b 当前代发布改用原子发布",
)

# ── P3c: publishExclusive 也要能破陈旧锁 ──────────────────────────────────
# P3 的降级路径在 20 次重试后 return false，而上层把 false 读成「别人已发布」。
# 若持锁进程被 kill，锁会留下，该会话的发布从此静默停止 —— 与 P11 同源的缺陷。
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """		} catch (error) {
			if (error?.code !== "EEXIST") throw error;
			if (attempt >= 20) return false;
			await new Promise((resolve) => setTimeout(resolve, 25));
		}""",
    """		} catch (error) {
			if (error?.code !== "EEXIST") throw error;
			try {
				const age = Date.now() - (await stat(lock)).mtimeMs;
				if (age > 30000) {
					console.error(`[session] breaking a stale publish-lock (${Math.round(age / 1000)}s old): ${lock}`);
					await rm(lock, { force: true });
					continue;
				}
			} catch {
				continue;
			}
			/* v8 ignore next -- the lock only exists while another publish is mid-flight. */
			if (attempt >= 20) return false;
			await new Promise((resolve) => setTimeout(resolve, 25));
		}""",
    "breaking a stale publish-lock",
    "P3c 会话发布破陈旧锁",
)

# ── P13: ptc worker 会清空环境，而这份 node 离开库路径就起不来 ──────────────
# `dsh-ptc-runtime-node` 为 worker 构造 env 时，把不在 STARTUP_ENVIRONMENT_NAMES
# 里的变量**一律置空**（map => [key, void 0]）。于是 worker 里的 node 丢掉
# LD_LIBRARY_PATH → CANNOT LINK EXECUTABLE（libz.so.1）；丢掉 OPENSSL_CONF →
# 落回编译期写死的 Termux 路径并被拒；丢掉 TMPDIR → os.tmpdir() 退化成不可写的 /tmp。
# 这个 Set 在语义上就是「予以继承」名单，所以把运行时必需的那几个加进去 ——
# 它们正是 NodeServer 给主 node 进程设的同一批，不是新依赖。
#
# 影响面：只有走 ptc node 运行时的能力（主要是 workflow 工具）。subagent 在主进程内
# 运行，bash 由 shell 工具保留环境，二者都不受影响。
patch(
    "@deepseek-ai/dsh-ptc-runtime-node/lib/index.js",
    """const STARTUP_ENVIRONMENT_NAMES = new Set([
	"PATH",
	"PATHEXT",
	"SYSTEMROOT",
	"WINDIR",
	"TEMP",
	"TMP"
]);""",
    """/*
 * Android: this node is a bionic build, so it cannot start without its library
 * path, and its OpenSSL paths are compiled in pointing at the Termux prefix
 * (see PITFALLS #11). Clearing these in a worker is not a clean environment, it
 * is a broken one -- and TMPDIR would fall back to an unwritable /tmp.
 */
const STARTUP_ENVIRONMENT_NAMES = new Set([
	"PATH",
	"PATHEXT",
	"SYSTEMROOT",
	"WINDIR",
	"TEMP",
	"TMP",
	"LD_LIBRARY_PATH",
	"OPENSSL_CONF",
	"SSL_CERT_FILE",
	"SSL_CERT_DIR",
	"TMPDIR"
]);""",
    "this node is a bionic build, so it cannot start",
    "P13 ptc worker 继承运行时必需的环境变量",
)

# ── P14: Android 上「用外部程序打开文件」永远不可用 ──────────────────────────
# 交付物卡片的「打开」按钮会问主机有没有可用的应用，而 specFor() 只认
# darwin / win32 / linux —— Android 直接返回 undefined，于是任何应用都判定为
# 不可用，界面显示「此主机没有可用的桌面，无法使用外部程序打开文件」。
#
# 两处改动：让 specFor 认 android，并在目录里加一个 android 条目，
# 指向载荷自带的 dsh-open（它把文件交给 App 的桥去分享，那是 Android 上
# 「用外部程序打开」的正确形态：系统弹出应用选择器）。
patch(
    "@deepseek-ai/dsh-host-open-in-app/lib/index.js",
    """	return platform === "darwin" || platform === "win32" || platform === "linux" ? app.platforms[platform] : void 0;""",
    """	/*
	 * Android counts too: there is no desktop here, but "open in an external
	 * application" still has a meaning -- the system share sheet, reached through
	 * the launcher shipped in usr/bin/dsh-open. Leaving android out of this list
	 * is what made the deliverables panel claim no desktop was available.
	 */
	return platform === "darwin" || platform === "win32" || platform === "linux" || platform === "android" ? app.platforms[platform] : void 0;""",
    "Android counts too: there is no desktop here",
    "P14a 让 open-in-app 认 android 平台",
)
patch(
    "@deepseek-ai/dsh-host-open-in-app/lib/index.js",
    """	{
		id: "filemanager",
		platforms: { linux: spec(desktopCli("xdg-open")) }""",
    """	{
		id: "android",
		platforms: { android: spec(cli("dsh-open")) }
	},
	{
		id: "filemanager",
		platforms: { linux: spec(desktopCli("xdg-open")) }""",
    'id: "android"',
    "P14b 目录里加 Android 条目",
)

# ── P15: 交付卡片的「打开」在 Android 上恒不可用（真正的闸门在 native-command）──
# 上一轮（P14）改的是 open-in-app 的应用目录 —— 那条线在链路上根本走不到。
# 判定链实际是：
#   dsh-native-command 的 nativeFileManager()  → 只认 darwin/win32/linux，android 返回 null
#   dsh-api-session-controller: available = fileManager !== null && canOpenPath()
#   dsh-client-ui-deliverables: available === false → 409
#   dsh-client-ui-open-in-app:   if (!props.available) return null   ← 按钮直接不渲染
# 所以必须从这里改：让 android 也是一个有原生文件动作的平台，并把动作接到
# 载荷自带的 dsh-open（它再去调本 App 的 /device/open）。
patch(
    "@deepseek-ai/dsh-native-command/lib/index.js",
    """	return platform === "linux" ? "directory" : null;""",
    """	/*
	 * Android has no desktop, but it does have a native action for this: the system
	 * share sheet, reached through usr/bin/dsh-open, which hands the file to this app
	 * (com.dsh.android) so it can start the chooser. Returning null here is what made
	 * the deliverables card claim no desktop was available and render no button.
	 */
	if (platform === "android") return "android"; /* dsh-open-android */
	return platform === "linux" ? "directory" : null;""",
    "dsh-open-android",
    "P15a nativeFileManager 认 android",
)
patch(
    "@deepseek-ai/dsh-native-command/lib/index.js",
    """	if (manager === "directory") {
		await run("xdg-open", [dirname(path)], signal, "hidden");
		return;
	}""",
    """	if (manager === "directory") {
		await run("xdg-open", [dirname(path)], signal, "hidden");
		return;
	}
	if (manager === "android") {
		/* dsh-open-reveal */
		await run("dsh-open", [path], signal, "hidden");
		return;
	}""",
    "dsh-open-reveal",
    "P15b revealNativePath 走 dsh-open",
)
patch(
    "@deepseek-ai/dsh-native-command/lib/index.js",
    """	const wsl = platform === "linux" && isWsl(internals);""",
    """	const wsl = platform === "linux" && isWsl(internals);
	if (platform === "android") {
		/* dsh-open-open */
		await run("dsh-open", [path], signal, "hidden");
		return;
	}""",
    "dsh-open-open",
    "P15c openNativePathWithIntent 走 dsh-open",
)

# ── P16: canOpenNativePath —— 与 nativeFileManager 成对的另一半 ────────────
# 复审者指出：可用性是 AND 条件
#   available = fileManager !== null && canOpenPath()
# P15 只修了前一半，后一半在 dsh-native-command:210 仍然对 android 返回 false，
# 所以卡片依旧没有按钮。两处必须一起改。
patch(
    "@deepseek-ai/dsh-native-command/lib/index.js",
    """	if (platform === "darwin" || platform === "win32") return true;
	if (platform !== "linux") return false;""",
    """	if (platform === "darwin" || platform === "win32" || platform === "android") return true; /* dsh-open-canopen */
	if (platform !== "linux") return false;""",
    "dsh-open-canopen",
    "P16a canOpenNativePath 认 android",
)

# ── P17: 工具的中断信号会打死 harness 本身 ──────────────────────────────────
# 现场：agent 的 bash 工具被中断时，terminal 会对前台进程发 SIGINT；这个信号也送到
# harness，而 harness 自己注册了 process.on("SIGINT") → interrupt(130) → 直接关停。
# 表现是"测到一半自己退出，退出码 130，没有崩溃、没有报错"。setsid 挡不住（它按进程组发）。
# Android 上不需要 SIGINT 关停 —— App 停运行时用的是 SIGTERM（SIGTERM 分支保留不动）。
patch(
    "@deepseek-ai/dsh/lib/profile-boot-BZ2ZjNWi.js",
    """		process.on(\"SIGINT\", () => {
			interrupt(130);
		});""",
    """		process.on(\"SIGINT\", () => {
			/* dsh-sigint-ignored */
			console.error(\"[dsh] SIGINT ignored (the shell tool's interrupt reached the harness)\");
		});""",
    "dsh-sigint-ignored",
    "P17 SIGINT 不再关停 harness",
)
# 另一半：SIGTERM 也要留痕。exit 0 与"事件循环自然结束"无法区分，
# 这正是每次都要多花一轮才能定位的原因。
patch(
    "@deepseek-ai/dsh/lib/profile-boot-BZ2ZjNWi.js",
    """		process.on(\"SIGTERM\", () => {
			interrupt(0);
		});""",
    """		process.on(\"SIGTERM\", () => {
			/* dsh-sigterm-logged */
			console.error(\"[dsh] SIGTERM received -> shutting down (exit 0)\");
			interrupt(0);
		});""",
    "dsh-sigterm-logged",
    "P17b SIGTERM 留痕",
)

# ── P16: 抢救式读取之后必须重建日志，否则新事件永久消失 ─────────────────────
# 现场（2026-10-13，共四份日志）：seq 断号或"完整帧里出现撕裂记录"。
# 新版读取已经能服务连续前缀，但写打开不重建 —— 追加的事件落在损坏字节之后，
# 而之后每次读取只认连续前缀：实测三份原始日志 100% 复现（例：追加前 3021 行 →
# 落盘 3022 行、10 处断号、重读仍是 2953 事件，新事件消失）。
# 这里在写打开时按完整事件流重建（先备份原始字节、再原子替换），
# 不依赖"物理行 ↔ 逻辑事件"的换算，任何折叠/替换语义都不会算错。
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """			const complete = scanner.checkpoint();
			if (complete.committedBytes !== complete.inputBytes) {
				/* Same reasoning as above: serve the complete prefix instead of refusing
				 * the whole log. */
				console.error("[session] torn JSONL record in the last complete frame; serving the intact prefix");
			}""",
    """			const complete = scanner.checkpoint();
			/* 完整帧里出现接不上的记录：另一个写入者把自己的 seq 追加进了同一份日志。
			 * 只服务连续前缀还不够 —— 必须让写入方知道要重建日志，否则新事件会追加在损坏
			 * 字节之后，而之后每次读取只认连续前缀，新事件就此永久消失（三份现场日志 100% 复现）。 */
			let salvaged = false;
			if (complete.committedBytes !== complete.inputBytes) {
				/* Same reasoning as above: serve the complete prefix instead of refusing
				 * the whole log. */
				salvaged = true;
				console.error("[session] torn JSONL record in the last complete frame; serving the intact prefix (the next write rebuilds the log)");
			}
""",
    "let salvaged = false;",
    "P16a 抢救时打标记",
)

patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """			if (tornStart === void 0) {
				const prefix = scanner.finish();
				return {
					meta: prefix.meta,
					inheritedEventCount: prefix.inheritedEventCount,
					events: prefix.events,
					tornTruncateTo: void 0,
					recoveredTail: []
				};
			}
			let recoveredPlaintext = Buffer.alloc(0);""",
    """			if (tornStart === void 0) {
				const prefix = scanner.finish();
				return {
					meta: prefix.meta,
					inheritedEventCount: prefix.inheritedEventCount,
					events: prefix.events,
					tornTruncateTo: void 0,
					recoveredTail: [],
					salvaged
				};
			}
			let recoveredPlaintext = Buffer.alloc(0);""",
    "salvaged\n\t\t\t\t};",
    "P16b 标记随返回值带出",
)

patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """			const prepared = await this.requireStoredLog(id, options?.signal);
			options?.signal?.throwIfAborted();
			let stored;
			if (prepared.status === "prepared") stored = await this.publishStoredMigration(id, prepared);
			else stored = prepared;
			options?.signal?.throwIfAborted();
			return this.tracker.adopt(new JsonlSessionHandle(this, id, stored.meta, "write", {""",
    """			const prepared = await this.requireStoredLog(id, options?.signal);
			options?.signal?.throwIfAborted();
			let stored;
			if (prepared.status === "prepared") stored = await this.publishStoredMigration(id, prepared);
			else stored = prepared;
			options?.signal?.throwIfAborted();
			/* 抢救过的日志里还留着损坏尾部：先按完整事件流重建，再让句柄追加，
			 * 否则新事件会落在损坏字节之后而永久读不回来。 */
			if (stored.salvaged === true) await this.rewriteSalvagedLog(stored);
			return this.tracker.adopt(new JsonlSessionHandle(this, id, stored.meta, "write", {""",
    "rewriteSalvagedLog(stored)",
    "P16c 写打开时重建日志",
)

patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """	async repair(meta, offset) {
		const path = logPath(this.root, meta.cwd, meta.id, this.compression);
		await truncate(path, offset);""",
    """	/**
	* 把"被抢救过"的日志重建成单一连续事件流：先留证据，再原子替换。
	* 不依赖物理行与逻辑事件的换算，任何折叠/替换语义都不会算错截断点。
	* @param stored - the salvaged stored log to rebuild from.
	*/
	async rewriteSalvagedLog(stored) {
		const meta = stored.meta;
		const path = logPath(this.root, meta.cwd, meta.id, this.compression);
		const stamp = new Date().toISOString().replace(/[-:]/g, "").replace(/\..+/, "");
		await copyFile(path, `${path}.corrupt-${stamp}.bak`).catch(() => {});
		const headerValue = sessionFormatCatalog.encodeCurrentHeader(meta, stored.inheritedEventCount);
		const headerLine = Buffer.from(`${stringifyJson(headerValue, "session header")}\n`);
		const parts = [this.compression === "zstd" ? await compressZstdFrame(headerLine) : headerLine];
		for (let offset = 0; offset < stored.events.length; offset += 512) parts.push(await this.encodeEventBatch(stored.events.slice(offset, offset + 512)));
		const staged = `${path}.repair-${process.pid}.tmp`;
		await writeFile(staged, Buffer.concat(parts));
		await rename(staged, path);
		this.coldLogMemo.delete(meta.id);
		console.error(`[session] session "${meta.id}": rebuilt the log from its ${stored.events.length}-event intact prefix; damaged bytes kept as .corrupt-${stamp}.bak`);
	}
	async repair(meta, offset) {
		const path = logPath(this.root, meta.cwd, meta.id, this.compression);
		/* 自动修复会永久删掉损坏字节：先留一份证据（两次事故都是靠它定位的）。 */
		const stamp = new Date().toISOString().replace(/[-:]/g, "").replace(/\..+/, "");
		await copyFile(path, `${path}.corrupt-${stamp}.bak`).catch(() => {});
		await truncate(path, offset);""",
    # 原条目写的是 "rebuilds the log"，但那个字符串只出现在 P16a 的 replacement 里，
    # 这一条的 replacement 实际写的是 "rebuilt the log from its ... intact prefix"：
    # 假 note 会让它永远不被识别成「已打过」（第二次运行只会报「锚点不匹配」），
    # 而且 P16a 一旦先落盘，这个假 note 又会被 P16a 的文本误命中 → 本条被静默跳过。
    # 这里只把 note 换成 replacement 里真实存在的子串，anchor 与 replacement 一字未动。
    "rebuilt the log from its",
    "P16d/P17 重建方法 + 修复前备份",
)

# ── P5: 图片处理不依赖原生 sharp（备用路径）────────────────────────────────
# 说明：完整的纯 JS 头部解析实现较长，这里只做「不因缺少 sharp 而整体失败」的兜底；
# 若需要完整的图片能力，请参考 docs/PITFALLS.md 第 5 条自行补全解析器。
patch(
    "@deepseek-ai/dsh-attachment-local/lib/index.js",
    """async function syncDirectory(path) {
	/* v8 ignore next -- Windows cannot open directory handles; NTFS metadata journaling owns entry durability there. */
	if (process.platform === "win32") return;
	/* v8 ignore start -- Windows cannot exercise directory fsync; POSIX behavior tests enforce this peer. */
	const handle = await open(path, constants.O_RDONLY);
	try {
		await handle.sync();
	} finally {
		await handle.close();
	}
	/* v8 ignore stop */
}""",
    """async function syncDirectory(path) {
	/* v8 ignore next -- Windows cannot open directory handles; NTFS metadata journaling owns entry durability there. */
	if (process.platform === "win32") return;
	/* v8 ignore start -- Windows cannot exercise directory fsync; POSIX behavior tests enforce this peer. */
	try {
		const handle = await open(path, constants.O_RDONLY);
		try {
			await handle.sync();
		} finally {
			await handle.close();
		}
	} catch (error) {
		/*
		 * Android: the durability walk reaches ancestors above the app's own data
		 * directory, which the sandbox does not let the app open. Entry durability
		 * there is best effort; the file content is synced by its writer.
		 */
		const code = error?.code;
		if (code !== "EACCES" && code !== "EPERM" && code !== "ENOTSUP" && code !== "EOPNOTSUPP" && code !== "ENOENT" && code !== "EBADF") throw error;
	}
	/* v8 ignore stop */
}""",
    "the durability walk reaches ancestors above the app",
    "P4  目录 fsync 容忍权限错误",
)

# ── P9: 裸包名必须按 profile 解析 ───────────────────────────────────────────
patch(
    "@deepseek-ai/cordis-plugin-loader/lib/index.js",
    """			else return await import(__rewriteRelativeImportExtension(
				/* @vite-ignore */
				name
			));""",
    """			else {
				/*
				 * Android: without Node's internal loader, `import(name)` resolves a
				 * bare specifier against THIS file -- the harness installation -- so a
				 * plugin installed into the profile is not found.
				 */
				const resolved = resolveSpecifierFromBase(name, this.ctx.baseUrl);
				return await import(__rewriteRelativeImportExtension(
					/* @vite-ignore */
					resolved
				));
			}""",
    "resolveSpecifierFromBase",
    "P9  裸包名按 profile 解析",
)

# ── P11: 文件工具的「新建」在本平台必然失败 ────────────────────────────────
# link(2) 被 app 域拒绝（P3 记的就是这件事），但 writeFileAtomic 在「新建且不许覆盖」
# 时用硬链接做发布，于是 Write 工具在任何路径上都创建不了新文件；覆盖已存在文件走
# rename，所以坏的只有「新建」——症状很像权限问题，实际是选错了系统调用。
# 修法与 P3 同源：锁文件给独占，rename 给原子，两者都不放弃。
patch(
    "@deepseek-ai/dsh-fs-local/lib/index.js",
    """		if (createIfAbsent !== void 0) try {
			await linkFile(tempPath, absolutePath);
		} catch (error) {
			await throwGuardedCreateFailure(error, absolutePath, createIfAbsent.displayPath, inspectPublicationTarget);
		}""",
    """		/*
		 * Android: an app domain is denied link(2), so the no-replace publication
		 * above can never succeed there. A lock file keeps the exclusivity and the
		 * rename keeps the atomicity -- the same serialization
		 * dsh-session-persistence-jsonl uses for its logs. copyFile would keep
		 * neither: it truncates the target and fills it over time.
		 */
		if (createIfAbsent !== void 0) try {
			await linkFile(tempPath, absolutePath);
		} catch (error) {
			const code = error?.code;
			if (code !== "EACCES" && code !== "EPERM" && code !== "ENOTSUP" && code !== "EOPNOTSUPP" && code !== "EXDEV") {
				await throwGuardedCreateFailure(error, absolutePath, createIfAbsent.displayPath, inspectPublicationTarget);
			}
			const lockPath = `${absolutePath}.dsh-create.lock`;
			const STALE_LOCK_MS = 30000;
			let lock;
			for (let attempt = 0; ; attempt += 1) {
				try {
					lock = await open(lockPath, "wx", 384);
					break;
				} catch (lockError) {
					if (lockError?.code !== "EEXIST") {
						throw lockError;
					}
					/*
					 * A lock left behind by a killed process would otherwise make this
					 * filename permanently uncreatable -- and the report would blame the
					 * target, which does not exist. Break a lock that is old enough that
					 * no live writer can still hold it (the retry window is 500 ms).
					 */
					try {
						const age = Date.now() - (await stat(lockPath)).mtimeMs;
						if (age > STALE_LOCK_MS) {
							console.error(`[dsh-fs] breaking a stale create-lock (${Math.round(age / 1000)}s old): ${lockPath}`);
							await rm(lockPath, { force: true });
							continue;
						}
					} catch {
						continue;
					}
					if (attempt >= 20) {
						/*
						 * The lock is held. Only bypass it when it is old enough that no live
						 * writer can still be inside the (tiny) inspect-and-rename window --
						 * otherwise two writers would both rename and the later one would
						 * silently overwrite the earlier, which is precisely what link(2)
						 * exclusivity prevented.
						 */
						let heldMs = 0;
						try {
							heldMs = Date.now() - (await stat(lockPath)).mtimeMs;
						} catch {
							heldMs = 0;
						}
						let present;
						try {
							present = await inspectPublicationTarget(absolutePath);
						} catch (inspectError) {
							if (!isENOENT(inspectError) && !isENOTDIR(inspectError)) throw inspectError;
						}
						if (present !== void 0) {
							await throwGuardedCreateFailure(lockError, absolutePath, createIfAbsent.displayPath, inspectPublicationTarget);
						}
						if (heldMs < 2000) {
							/* Fresh lock, absent target: someone is mid-publish. Say so clearly
							 * instead of guessing, and don't touch their lock. */
							const busy = new Error(`EBUSY: another writer holds ${lockPath} (${Math.round(heldMs)} ms)`);
							busy.code = "EBUSY";
							throw busy;
						}
						console.error(`[dsh-fs] create-lock abandoned for ${Math.round(heldMs / 1000)}s; taking it over: ${absolutePath}`);
						await rm(lockPath, { force: true });
						try {
							await rename(tempPath, absolutePath);
						} finally {
							/* Rename consumed the staging file; the lock is gone too, so the
							 * next writer starts clean. */
						}
						return;
					}
					await new Promise((resolve) => setTimeout(resolve, 25));
				}
			}
			try {
				let existing;
				try {
					existing = await inspectPublicationTarget(absolutePath);
				} catch (inspectError) {
					if (!isENOENT(inspectError) && !isENOTDIR(inspectError)) throw inspectError;
				}
				if (existing !== void 0) {
					const conflict = new Error(`EEXIST: ${absolutePath} already exists`);
					conflict.code = "EEXIST";
					await throwGuardedCreateFailure(conflict, absolutePath, createIfAbsent.displayPath, inspectPublicationTarget);
				}
				await rename(tempPath, absolutePath);
			} finally {
				await lock.close().catch(() => {});
				await rm(lockPath, { force: true }).catch(() => {});
			}
		}""",
    "dsh-create.lock",
    "P11 新建文件改用锁 + rename 发布",
)

# ── P19: 开机动画客户端在「页面重载瞬间」没有可重试的失败处理 ──────────────
# 现场（/sdcard/DSH/work/client-retry-fix/HANDOFF.md、P18-P19.md）：
#   resume/重载瞬间 /dsh-boot-animation/media/... 与 /plugins/events 各失败 1~2 次。
#   空闲时两条端点实测都是健康的（媒体 200 + 1856280 B，SSE text/event-stream），
#   所以不是缺路由、不是错 id —— 是页面重载瞬间的网络竞态。
#   真缺陷在这里：媒体只有 25s「卡住超时」，没有 error 重试 → 首帧竞态会让开机动画整段跳过。
# 交付：/sdcard/DSH/work/fixes-for-packaging/（成品 dsh-boot-animation-client.js，
#       md5 99bb868ce27639e58c4ca771207b7138；原始 md5 30f60c790f4a3099dce2b16d041d8157）。
# 说明：她的 blocks-P19.py 里 note 写的是中文标签（人读的），而本脚本的幂等判据是
#       `if note in text:` —— 假 note 会让第二次运行重复插入（replacement 以锚点开头，
#       锚点仍在文本里）。这里 anchor / replacement 一字未动，只把 note 换成各自
#       replacement 里真实存在的子串（见 P16d 那条的同类说明）。
#       P19d-1/2/3（fetchWithRetry）她的 blocks 文件里没有，是从她的 .orig→.patched
#       逐行 diff 里原样取出的，没有改动她的任何逻辑。
patch(
    "dsh-boot-animation/lib/client.js",
    """		/** How long an error stays readable before the overlay closes itself. */
		const ERROR_LINGER_MS = 8e3;""",
    """		/** How long an error stays readable before the overlay closes itself. */
		const ERROR_LINGER_MS = 8e3;
		/*
		 * Delays for retrying the clip's first media request. Measured on device: a page
		 * load can fail this request at t+1003ms AND at t+3630ms before it succeeds, so a
		 * short window is not enough -- this schedule covers ~9.5s in total.
		 */
		const MEDIA_RETRY_DELAYS_MS = [300, 700, 1500, 3000, 4000];""",
    "const MEDIA_RETRY_DELAYS_MS = [300, 700, 1500, 3000, 4000];",
    "P19a 媒体首帧竞态：新增退避表 [300,700,1500,3000,4000]ms",
)
patch(
    "dsh-boot-animation/lib/client.js",
    """				video.addEventListener("playing", onPlaying);""",
    """				video.addEventListener("playing", onPlaying);
				/*
				 * The first media request can race a page reload and fail at the network layer
				 * although the route is healthy (measured: 200 + 1856280 B via curl, while the
				 * WebView failed twice per load). Retry with backoff instead of waiting for the
				 * 25s stall timeout, which skipped the whole clip.
				 */
				let mediaRetries = 0;
				const onMediaError = () => {
					if (closedRef.current || mediaRetries >= MEDIA_RETRY_DELAYS_MS.length) return;
					const delay = MEDIA_RETRY_DELAYS_MS[mediaRetries];
					mediaRetries += 1;
					report("media error, retry " + String(mediaRetries) + " in " + String(delay) + "ms");
					window.setTimeout(() => {
						if (closedRef.current) return;
						video.src = url;
						video.load();
						const again = video.play();
						if (again !== void 0 && typeof again.catch === "function") again.catch(() => {});
					}, delay);
				};
				video.addEventListener("error", onMediaError);""",
    "const onMediaError = () => {",
    "P19b 媒体 error 时按退避表重试 5 次（覆盖 ~9.5s）",
)
patch(
    "dsh-boot-animation/lib/client.js",
    """					video.removeEventListener("playing", onPlaying);""",
    """					video.removeEventListener("playing", onPlaying);
					video.removeEventListener("error", onMediaError);""",
    'video.removeEventListener("error", onMediaError);',
    "P19c 卸载时移除 error 监听",
)
patch(
    "dsh-boot-animation/lib/client.js",
    """		const BASE = "/dsh-boot-animation";""",
    """		const BASE = "/dsh-boot-animation";
		/*
		 * Fetch with retries: a page load can reach the host before its loopback connection
		 * is ready, and the first attempt then fails although the route is healthy (measured:
		 * 200 + 1856280 B via curl while the WebView failed twice per load).
		 */
		async function fetchWithRetry(url, options, delays) {
			const schedule = delays ?? [300, 700, 1500, 3000, 4000];
			let last;
			for (let attempt = 0; attempt <= schedule.length; attempt++) {
				try {
					return await fetch(url, options);
				} catch (error) {
					last = error;
					if (attempt === schedule.length) break;
					await new Promise((resolve) => setTimeout(resolve, schedule[attempt]));
				}
			}
			throw last;
		}""",
    "async function fetchWithRetry(url, options, delays) {",
    "P19d-1 目录/解析请求走带退避的 fetchWithRetry",
)
patch(
    "dsh-boot-animation/lib/client.js",
    """					const response = await fetch(this.#listUrl(), { cache: "no-store" });""",
    """					const response = await fetchWithRetry(this.#listUrl(), { cache: "no-store" });""",
    'await fetchWithRetry(this.#listUrl(), { cache: "no-store" })',
    "P19d-2 目录清单改走 fetchWithRetry",
)
patch(
    "dsh-boot-animation/lib/client.js",
    """					const response = await fetch(`${RESOLVE_URL}?mode=${mode}${param === "" ? "" : "&" + param}`, { cache: "no-store" });""",
    """					const response = await fetchWithRetry(`${RESOLVE_URL}?mode=${mode}${param === "" ? "" : "&" + param}`, { cache: "no-store" });""",
    "await fetchWithRetry(`${RESOLVE_URL}?mode=${mode}",
    "P19d-3 resolve 请求改走 fetchWithRetry",
)


# ── P12: Grep / Glob 在 android-arm64 上找不到 ripgrep ─────────────────────
# @vscode/ripgrep 按 `@vscode/ripgrep-${process.platform}-${arch}` 解析二进制，
# 在 Android 上就是 ripgrep-android-arm64——npm 不发布这个平台包，import 直接抛错，
# resolveRgPath() 跟着 reject，于是 grep / glob 每次调用都报 "ripgrep launch failed"。
# 这里把载荷里已有的 arm64 rg 放成那个缺失的平台包（自带 bionic rg 优先）。
# 注意：必须建在真实的 node_modules/@vscode 下——node 按包的 realpath 解析。
def _elf_machine(path):
    """ELF e_machine，或 None（非 ELF / 读不到）。183 = AArch64。"""
    try:
        with open(path, "rb") as fh:
            head = fh.read(20)
    except OSError:
        return None
    if len(head) < 20 or head[:4] != b"\x7fELF":
        return None
    return head[18] | (head[19] << 8)


def _is_bionic(path):
    """ELF 是否链接到 bionic（Android 的 libc）。

    只看 e_machine 是不够的：`@vscode/ripgrep-linux-arm64` 也是 AArch64。
    它其实**能跑**（实测：递归搜索 exit=0，命中 3283 个文件，`--version` 正常）——
    所以「非 bionic 就会被 seccomp 杀掉」这个因果是错的：判据是**是否注册 rseq**，
    不是 libc 品牌（ripgrep 不碰 rseq；musl 的 node 死在 V8 初始化）。
    这里仍然优先选 bionic，因为它是**已被本机验证过**的那一份，属于保守选择，
    不是因为另一份一定跑不起来。
    """
    try:
        with open(path, "rb") as fh:
            data = fh.read(200000)
    except OSError:
        return False
    return b"/system/bin/linker64" in data or b"/system/bin/linker" in data


def install_ripgrep_platform_package():
    vendor = os.path.join(root, "@vscode")
    target = os.path.join(vendor, "ripgrep-android-arm64")
    if os.path.isdir(target):
        skipped.append("P12 ripgrep android 平台包（已存在）")
        return
    if not os.path.isdir(vendor):
        skipped.append("P12 ripgrep android 平台包（没有 @vscode 目录）")
        return
    source = None
    # 优先用载荷自带的 bionic rg：它已被真机验证能在这个沙箱里跑。
    for rel in ("../usr/bin/rg", "../../usr/bin/rg"):
        candidate = os.path.normpath(os.path.join(root, rel))
        if _elf_machine(candidate) == 183 and _is_bionic(candidate):
            source = candidate
            break
    # 其次才是 @vscode/ripgrep-* 里的平台包，而且同样要求是 bionic。
    if source is None:
        for name in sorted(os.listdir(vendor)):
            if not name.startswith("ripgrep-"):
                continue
            candidate = os.path.join(vendor, name, "bin", "rg")
            if _elf_machine(candidate) == 183 and _is_bionic(candidate):
                source = candidate
                break
    # 最后才退而求其次：只要 AArch64 就用（可能跑不起来，明确警告）。
    if source is None:
        for name in sorted(os.listdir(vendor)):
            if not name.startswith("ripgrep-"):
                continue
            candidate = os.path.join(vendor, name, "bin", "rg")
            if _elf_machine(candidate) == 183:
                source = candidate
                print("  警告  P12 用的是非 bionic 的 rg（%s），在设备上可能被 seccomp 杀掉"
                      % name)
                break
    if source is None:
        skipped.append("P12 ripgrep android 平台包（没找到 arm64 的 rg；grep / glob 仍不可用）")
        return
    os.makedirs(os.path.join(target, "bin"))
    link_path = os.path.join(target, "bin", "rg")
    # 符号链接而不是复制：载荷里已经有一份同名二进制，复制等于再存一遍（APK 会大 ~5 MB）。
    # 注意不能用硬链接（link(2) 在本平台被拒绝，见 P3/P11）。
    try:
        os.symlink(os.path.relpath(source, os.path.dirname(link_path)), link_path)
    except OSError:
        with open(source, "rb") as src, open(link_path, "wb") as dst:
            dst.write(src.read())
        os.chmod(link_path, 0o755)
    # 不写 exports 字段：子路径 @vscode/ripgrep-android-arm64/bin/rg 必须保持可解析。
    with io.open(os.path.join(target, "package.json"), "w", encoding="utf-8") as fh:
        fh.write('{\n  "name": "@vscode/ripgrep-android-arm64",\n  "version": "0.0.0",\n  "private": true\n}\n')
    applied.append("P12 ripgrep android 平台包（源：" + os.path.relpath(source, root) + "）")


install_ripgrep_platform_package()

print()
for line in applied:
    print(f"  已应用  {line}")
for line in skipped:
    print(f"  跳过    {line}")
print()
print(f"完成：{len(applied)} 项已应用，{len(skipped)} 项跳过。")
print("注意：P1（原生 addon 降级）、P5（纯 JS 图片解析）、P10（bionic coreutils）")
print("      需要改动内嵌负载的其他部分，见 docs/PITFALLS.md 对应条目。")
PY
