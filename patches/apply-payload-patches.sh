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
# label -> 跳过原因（"missing" / "done" / "anchor"），供「必成清单」判定用。
skip_kind = {}


def resolve_profile_boot():
    """dsh/lib 里 profile-boot 的文件名带内容哈希（每次发版都变），按前缀定位。"""
    lib = os.path.join(root, "@deepseek-ai/dsh/lib")
    try:
        names = sorted(n for n in os.listdir(lib)
                       if n.startswith("profile-boot-") and n.endswith(".js"))
    except OSError:
        names = []
    if not names:
        return "@deepseek-ai/dsh/lib/profile-boot.js"
    return "@deepseek-ai/dsh/lib/" + names[0]


PROFILE_BOOT = resolve_profile_boot()

import difflib


def nearest_region(anchor, text):
    """在 text 里找与 anchor 最接近的行区间，用来展示失配处的上游原文。"""
    a = anchor.splitlines()
    t = text.splitlines()
    if not a or not t:
        return None
    match = difflib.SequenceMatcher(None, a, t, autojunk=False).find_longest_match(0, len(a), 0, len(t))
    k = match.b - match.a if match.size else 0
    k = max(0, min(k, len(t) - 1))
    lo = max(0, k - 2)
    hi = min(len(t), k + len(a) + 2)
    return lo, t[lo:hi], match.size


def report_mismatch(rel, anchor, text):
    """失配时把「期望的锚点」和「上游现在长什么样」一起打出来。

    只报一句「锚点不匹配」的话，升级的人还得自己去 150 KB 的产物里翻原文。
    """
    print(f"       ! 锚点失配：{rel}")
    near = nearest_region(anchor, text)
    if near is None:
        print("         上游：文件为空，无法定位")
        return
    lo, lines, size = near
    print(f"         上游原文（第 {lo + 1}-{lo + len(lines)} 行，最长连续匹配 {size} 行）：")
    for i, line in enumerate(lines):
        print(f"           +| {lo + i + 1:5d}| " + line)


def patch(rel, anchor, replacement, note, label):
    """Replace `anchor` with `replacement` in `rel`, idempotently."""
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        skip_kind[label] = "missing"
        skipped.append(f"{label}（文件不存在：{rel}）")
        return
    with io.open(path, encoding="utf-8") as fh:
        text = fh.read()
    if note in text:
        skip_kind[label] = "done"
        skipped.append(f"{label}（已打过）")
        return
    if anchor not in text:
        skip_kind[label] = "anchor"
        skipped.append(f"{label}（锚点不匹配，可能上游已变更）")
        report_mismatch(rel, anchor, text)
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
# 0.2.1-rc.2 把发布拆成两个入口：
#   materializePosix()        -> link(tmp, finalPath)
#   publishCurrentExclusive() -> internals.fs.link(staged, currentPath)
# 两处都直接调 link(2)，而 app 域对 link(2) 返回 EACCES/EPERM，所以两处都得换。
#
# 关键：`publishExclusive` 这个 helper 在 0.2.0-rc.2 与 0.2.1-rc.2 的**上游原文里
# 都不存在** —— 它是本项目自己加的，而旧脚本只改了调用点、没记这个 helper。
# 于是 P3a 在新树上会把 link(...) 换成对一个不存在函数的调用（运行期 ReferenceError）。
# 这里把它补成 P3-helper 条目；helper 自带「记录持锁 pid + 破陈旧锁」，
# 旧 P3c（按 30s 年龄判断）的意图已被它覆盖，故 P3c 条目删除。
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """			await link(tmp, finalPath);""",
    """			await publishExclusive(tmp, finalPath);""",
    "publishExclusive(tmp, finalPath)",
    "P3a 会话发布改用原子发布",
)
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """async function publishCurrentExclusive(staged, currentPath, internals) {""",
    """/**
 * Publish `staged` as `target` without overwriting an existing file.
 *
 * POSIX uses a hard link, which some Android app-storage volumes refuse
 * (EACCES/EPERM/ENOTSUP). The fallback must keep BOTH properties the link gave
 * us, because session logs are read while they are written:
 *
 *   - exclusive: an existing target means "someone else got there first";
 *   - atomic: a reader must never observe a half-written log.
 *
 * A plain copy keeps neither: it truncates the target and fills it over time, so
 * an interrupted publish leaves a log with a hole in it. Serializing on a lock
 * file and then renaming satisfies both -- rename is atomic, and the lock makes
 * the existence check exclusive.
 *
 * @returns true when the target was created, false when it already existed.
 */
const LINK_UNSUPPORTED = new Set(["EACCES", "EPERM", "ENOTSUP", "EOPNOTSUPP", "EXDEV"]);
async function publishExclusive(staged, target) {
	try {
		await link(staged, target);
		return true;
	} catch (error) {
		if (error?.code === "EEXIST") return false;
		if (!LINK_UNSUPPORTED.has(error?.code)) throw error;
	}
	const lock = `${target}.publish.lock`;
	let handle;
	for (let attempt = 0;; attempt += 1) {
		try {
			handle = await open(lock, "wx", 384);
			/* Record who holds it. The age alone cannot answer "is the writer still
			 * alive?": under low-battery throttling a live writer can sit silent for
			 * longer than any threshold, and taking its lock then produces exactly the
			 * two-writer interleaving that corrupts the log. */
			try {
				await handle.writeFile(String(process.pid));
			} catch {
			}
			break;
		} catch (error) {
			if (error?.code !== "EEXIST") throw error;
			try {
				const age = Date.now() - (await stat(lock)).mtimeMs;
				let holder = 0;
				try {
					holder = Number.parseInt((await readFile(lock, "utf8")).trim(), 10) || 0;
				} catch {
					holder = 0;
				}
				let alive = false;
				if (holder > 0) {
					try {
						process.kill(holder, 0);
						alive = true;
					} catch (probe) {
						/* EPERM means the process exists but belongs to someone else. */
						alive = probe?.code === "EPERM";
					}
				}
				if ((holder > 0 && !alive) || (holder === 0 && age > 30000)) {
					console.error(`[session] breaking an abandoned publish-lock (holder=${holder || "unknown"}, ${Math.round(age / 1000)}s old): ${lock}`);
					await rm(lock, { force: true });
					continue;
				}
			} catch {
				continue;
			}
			/* v8 ignore next -- the lock only exists while another publish is mid-flight. */
			if (attempt >= 20) return false;
			await new Promise((resolve) => setTimeout(resolve, 25));
		}
	}
	try {
		try {
			await stat(target);
			return false;
		} catch (error) {
			if (error?.code !== "ENOENT") throw error;
		}
		await rename(staged, target);
		return true;
	} finally {
		await handle.close().catch(() => {});
		await rm(lock, { force: true }).catch(() => {});
	}
}
async function publishCurrentExclusive(staged, currentPath, internals) {""",
    "const LINK_UNSUPPORTED = new Set(",
    "P3-helper 发布 helper（锁 + rename + 破陈旧锁）",
)
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """	try {
		await internals.fs.link(staged, currentPath);
	} catch (error) {
		/* v8 ignore else -- a non-collision filesystem error propagates unchanged. */
		if (isEEXIST(error)) return false;
		/* v8 ignore next -- the filesystem error is already complete. */
		throw error;
	}""",
    """	if (!await publishExclusive(staged, currentPath)) return false;""",
    "if (!await publishExclusive(staged, currentPath)) return false;",
    "P3b 当前代发布改用原子发布",
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
    PROFILE_BOOT,
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
    PROFILE_BOOT,
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
# 读取侧能服务连续前缀，但写打开不重建 —— 追加的事件落在损坏字节之后，
# 而之后每次读取只认连续前缀：实测三份原始日志 100% 复现（例：追加前 3021 行 →
# 落盘 3022 行、10 处断号、重读仍是 2953 事件，新事件消失）。
# 这里在写打开时按完整事件流重建（先备份原始字节、再原子替换），
# 不依赖"物理行 ↔ 逻辑事件"的换算，任何折叠/替换语义都不会算错。
#
# 0.2.1-rc.2 的上游改动：读取侧不再「服务连续前缀」，而是直接抛错 ——
#     throw new Error("corrupt Zstandard session log: complete frame contains a torn JSONL record");
# 旧锚点（console.error + "Same reasoning as above" 注释块）于是整段消失。
# 这里按新锚点重贴，语义不变：记一行、置 salvaged，交给写打开重建。
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """			const complete = scanner.checkpoint();
			if (complete.committedBytes !== complete.inputBytes) throw new Error("corrupt Zstandard session log: complete frame contains a torn JSONL record");""",
    """			const complete = scanner.checkpoint();
			/* 完整帧里出现接不上的记录：另一个写入者把自己的 seq 追加进了同一份日志。
			 * 0.2.1-rc.2 在这里改成直接抛错；本平台保留「服务连续前缀」的语义，并让写入方
			 * 知道要重建日志 —— 否则新事件会追加在损坏字节之后，而之后每次读取只认连续前缀，
			 * 新事件就此永久消失（三份现场日志 100% 复现）。 */
			let salvaged = false;
			if (complete.committedBytes !== complete.inputBytes) {
				console.error("[session] torn JSONL record in the last complete frame; serving the intact prefix (the next write rebuilds the log)");
				salvaged = true;
			}""",
    "let salvaged = false;",
    "P16a 抢救时打标记（0.2.1 起上游改抛错：贴回服务连续前缀）",
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

# ── P25: P3a/P16d 要用 rename/copyFile/writeFile，上游 import 里一个都没有 ────
# 第 4 行的导入清单是上游自己算的：上游代码根本不用这三个符号，所以它们不在里面。
# 但本项目后来加的两个函数要用：
#     P3a   publishExclusive()      -> await rename(staged, target);
#     P16d  rewriteSalvagedLog()    -> await writeFile(staged, ...) / await rename(staged, path);
#     P16d  repair()                -> await copyFile(path, `${path}.corrupt-*.bak`)
# 0.2.1-rc.2 的原文（干净树上核对过）是：
#     import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from "node:fs/promises";
# 注意：`handle.writeFile(...)` 是 FileHandle 的方法，不是这里的导入符号 —— 别被它骗了。
# 后果不是"发布不够快"，而是**新建会话的第一条消息直接发不出去**：
#     ReferenceError: rename is not defined
# 这个错不是 LlmError，会被上层包成 TRANSPORT 并重试 5 次，真机上表现成
# "新会话发不出消息"。所以它是独立条目：P3a/P16d 只改函数体，不会顺手把符号带进来。
# 这条在「必成清单」里：失配就等于产物不可用，必须让构建停下。
# （note 必须是 replacement 里真实存在的子串，本脚本的幂等判断就是 `if note in text`。）
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from "node:fs/promises";""",
    """import { copyFile, link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rename, rm, stat, truncate, writeFile } from "node:fs/promises";""",
    "realpath, rename",
    "P25 fs/promises import 补 rename/copyFile/writeFile（P3a/P16d 要用，上游没有）",
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


# ── P20: 会话写租约在没有内核仲裁者（Android）时改用 mkdir 仲裁 ─────────────
# 现场：Android 上 tryLockExclusive 是空实现（P2），于是两个进程可以同时「拿到」
# 同一个会话的写权；实测两个进程各写一份、日志被撕裂。
#
# 做法：把租约从 `session.lock`（flock）换成同目录下的 `session.lock.d`（mkdir）。
# mkdir 的「创建 / EEXIST」跨进程原子，正好充当仲裁者；持锁方把 pid 写进
# `session.lock.d/pid`。判断存活只看 pid 是否还在，**故意不设时间阈值**：
# 低电量限流下活着的写入方可能长时间沉默，按时间抢锁正是撕裂日志的那种交错。
#
# 这一段原先只存在于载荷文件里（手工改的，脚本没有），本轮升级补成条目，
# 下次上游再动这段就能直接重放。非 Android 平台行为完全不变（仍走 flock）。
#
# 注：原先的 P20a「fs/promises 补 copyFile / writeFile」已并入 P25。
# 两条抢同一行 import：P20a 补了 copyFile/writeFile 却漏了 rename，而 P16d 与
# P3a 都要 rename —— 分开写既救不了新会话，又会把彼此的锚点吃掉（后执行的那条
# 只能报「锚点不匹配」）。现在这一行由 P25 独占，一次补齐三个符号。
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """* immediate success: it is single-process, so the in-process write claim
* already excludes every writer.
* @module @deepseek-ai/dsh-session-persistence-jsonl/lease""",
    """* immediate success: it is single-process, so the in-process write claim
* already excludes every writer.
* Where no kernel arbiter exists the lease becomes a directory beside the log
* (`session.lock.d`) and `mkdir` is the arbiter: creation either wins or fails
* `EEXIST`, atomically across processes, and the holder records its pid inside
* it. A recorded pid that still exists keeps the lease — deliberately with no
* age threshold, for the throttling reason above — while a pid that is
* provably gone is broken out of, so a crashed holder blocks only until the
* next acquire. Android takes this path because its native flock entry is a
* deliberate no-op, which let two processes "acquire" one session at once.
* @module @deepseek-ai/dsh-session-persistence-jsonl/lease""",
    "Where no kernel arbiter exists the lease becomes a directory",
    "P20b 租约模块注释补充目录式租约",
)
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """/** Base name of the kernel lock file inside a session's directory. */
const LEASE_FILENAME = "session.lock";
/** Whether a flock failure means another descriptor holds the lock. */
function isLockContention(error) {
	const code = error?.code;
	return code === "EAGAIN" || code === "EWOULDBLOCK";
}
""",
    """/** Base name of the kernel lock file inside a session's directory. */
const LEASE_FILENAME = "session.lock";
/** Suffix of the directory-form lease (`session.lock.d`) used where no kernel arbiter exists. */
const LEASE_DIR_SUFFIX = ".d";
/** Name of the holder record inside a directory-form lease. */
const LEASE_PID_FILENAME = "pid";
/**
* Whether `tryLockExclusive` can arbitrate on this platform. Android has no
* prebuilt binding and its native entry deliberately resolves to a no-op, so a
* syscall failure there can never report contention — both writers would
* "acquire" the same session. That platform takes the atomic-`mkdir` lease
* instead; every other platform keeps the kernel lock unchanged.
*/
const LEASE_USES_KERNEL_FLOCK = process.platform !== "android";
/** Whether a lease failure means another holder keeps the lock. */
function isLockContention(error) {
	const code = error?.code;
	return code === "EAGAIN" || code === "EWOULDBLOCK";
}
/**
* Synthesize the contention failure the kernel would have raised. `EAGAIN` is
* the errno the caller matches on, so the directory lease reports contention
* exactly as `flock` does.
* @returns the contention error.
*/
function leaseContention() {
	const error = /* @__PURE__ */ new Error("EAGAIN: the session write lease is held by another process");
	error.code = "EAGAIN";
	error.errno = 11;
	error.syscall = "mkdir";
	return error;
}
/**
* Whether a recorded holder pid still names a running process.
* @param pid - the recorded holder pid.
* @returns true while that process exists.
*/
function isProcessAlive(pid) {
	try {
		process.kill(pid, 0);
		return true;
	} catch (error) {
		/* EPERM means the process exists but belongs to someone else. */
		return error?.code === "EPERM";
	}
}
/**
* Read the holder pid recorded inside a directory-form lease.
* @param dir - the lease directory.
* @returns the pid, or 0 when no usable record exists.
*/
async function readLeaseHolder(dir) {
	try {
		return Number.parseInt((await readFile(join(dir, LEASE_PID_FILENAME), "utf8")).trim(), 10) || 0;
	} catch {
		return 0;
	}
}
/**
* Whether `dir` is still the exact directory a lease observation captured. A
* lease is identified by inode, not by path, so a lease a successor already
* broke and recreated is never mistaken for the observed one.
* @param dir - the lease directory path.
* @param inode - the `dev`/`ino` pair captured at creation.
* @returns true when the path still names that directory.
*/
async function isSameLeaseDirectory(dir, inode) {
	try {
		const current = await stat(dir, { bigint: true });
		return current.dev === inode.dev && current.ino === inode.ino;
	} catch {
		return false;
	}
}
/**
* Create `dir` as this process's lease. `mkdir` is the arbiter: it either
* creates the directory or fails `EEXIST`, and that decision is atomic across
* processes. A recorded holder that still exists keeps the lease — there is
* deliberately no age threshold, because a writer throttled by the platform can
* stay silent longer than any timeout, and expropriating it is exactly the
* two-writer interleaving that tears a log. Only a recorded pid that provably
* no longer exists is broken out of.
* @param dir - the lease directory to create.
* @returns the `dev`/`ino` capture of the created directory.
* @throws a contention failure (EAGAIN) while another holder keeps the lease; a
*   broken dead holder's lease is reported the same way with `retry` set, so the
*   caller re-runs the `mkdir` that decides the next owner.
*/
async function createLeaseDirectory(dir) {
	try {
		await mkdir(dir, { mode: 448 });
	} catch (error) {
		if (error?.code !== "EEXIST") throw error;
		const observed = await stat(dir, { bigint: true });
		const holder = await readLeaseHolder(dir);
		if (holder === 0) {
			/* An absent or malformed record is not proof of death: refuse rather than risk a second writer. */
			console.error(`[session] refusing the session write lease: no readable holder record in ${join(dir, LEASE_PID_FILENAME)} (${dir})`);
			throw leaseContention();
		}
		if (isProcessAlive(holder)) throw leaseContention();
		console.error(`[session] breaking an abandoned session write lease (holder=${holder}, that pid is gone): ${dir}`);
		/* Break it only while the observed record and inode still identify that dead
		 * holder: a lease a successor already replaced is the successor's, not ours. */
		if ((await readLeaseHolder(dir)) === holder && await isSameLeaseDirectory(dir, observed)) await rm(dir, { recursive: true, force: true });
		const retry = leaseContention();
		retry.retry = true;
		throw retry;
	}
	const inode = await stat(dir, { bigint: true });
	try {
		await writeFile(join(dir, LEASE_PID_FILENAME), String(process.pid));
	} catch (error) {
		await rm(dir, { recursive: true, force: true }).catch(() => {});
		throw error;
	}
	return inode;
}
""",
    "/** Suffix of the directory-form lease",
    "P20c 目录式租约常量与 helper",
)
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """		/* v8 ignore stop */
		for (let attempt = 0; attempt < 3; attempt += 1) {
			const handle = await open(path, "w");""",
    """		/* v8 ignore stop */
		/* v8 ignore start -- only Android lacks a kernel arbiter; Linux and Windows coverage exercise the other two kinds. */
		if (!LEASE_USES_KERNEL_FLOCK) {
			const leaseDir = `${path}${LEASE_DIR_SUFFIX}`;
			for (let attempt = 0; attempt < 4; attempt += 1) {
				try {
					const inode = await createLeaseDirectory(leaseDir);
					return new SessionWriteLease({
						kind: "dir",
						dir: leaseDir,
						inode
					});
				} catch (error) {
					if (!isLockContention(error)) throw error;
					/* A broken dead holder only asks for another `mkdir`; a live holder owns the session. */
					if (error.retry !== true) throw new SessionAlreadyOwnedError(id);
				}
			}
			throw new SessionAlreadyOwnedError(id);
		}
		/* v8 ignore stop */
		for (let attempt = 0; attempt < 3; attempt += 1) {
			const handle = await open(path, "w");""",
    "only Android lacks a kernel arbiter",
    "P20d acquire 走 mkdir 租约",
)
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """		/* v8 ignore stop */
		await this.held.handle.close();""",
    """		/* v8 ignore stop */
		/* v8 ignore start -- only Android takes the directory lease; Linux and Windows coverage exercise the other two kinds. */
		if (this.held.kind === "dir") {
			/* Remove the lease only while it is still this holder's: a successor that
			 * already broke and replaced it owns that path now. */
			if (await isSameLeaseDirectory(this.held.dir, this.held.inode)) await rm(this.held.dir, { recursive: true, force: true }).catch(() => {});
			return;
		}
		/* v8 ignore stop */
		await this.held.handle.close();""",
    "only Android takes the directory lease",
    "P20e release 移除目录式租约",
)

# ── P22: 纯 JS sharp 编不了 WebP，0.2.1 的图片管线却对 alpha 图强制 WebP ─────
# 0.2.1 的 dsh-attachment-local 里，请求期缩图走 encodingLadder()：
#   hasAlpha ? "image/webp" : "image/jpeg"
# 而载荷里的 sharp 是 P5 的纯 JS 实现（0.35.5-purejs），它明确不实现 WebP 编码：
#   sharp-js: WebP pixel encoding is not implemented ...
# 安卓截图是 RGBA（PNG color type 6），metadata().hasAlpha 为 true，于是每一次
# "带截图的模型请求"都会在 prepareImages -> readImageRequest -> createRequestImage
# 抛错；该错误不是 LlmError，被 generate() 兜底包成
#   DeepSeek Messages transport failed [TRANSPORT]
# 并按可重试错误重放，表现就是"5 次重试、几秒内全灭"，0.2.0 则因为有
# "resize unavailable, sending the original" 的兜底而正常。
#
# 修法：不实现 WebP 编码（那是另一个量级的工程），而是让梯队具备退化路径：
#   P22a alpha 图在 WebP 之后追加 PNG 档（无损、保 alpha）；
#   P22b 某个候选抛错时跳过它，而不是整条梯队失败；
#   P22c 请求期缩图最终无编码器可用时，回落到已存字节（0.2.0 的行为）；
#   P22d 入库归一化同理，存原字节而不是拒绝上传。
patch(
    "@deepseek-ai/dsh-attachment-local/lib/index.js",
    r'''function encodingLadder(prepared, hasAlpha) {
	const mediaType = hasAlpha ? "image/webp" : "image/jpeg";
	return IMAGE_ENCODING_QUALITIES.map((quality) => (() => encode(prepared.clone(), mediaType, quality)));
}
''',
    r'''async function encodePng(pipeline, compressionLevel) {
	const { data, info } = await pipeline.png({
		compressionLevel
	}).toBuffer({ resolveWithObject: true });
	return {
		data: new Uint8Array(data),
		mediaType: "image/png",
		width: info.width,
		height: info.height
	};
}
function encodingLadder(prepared, hasAlpha) {
	const mediaType = hasAlpha ? "image/webp" : "image/jpeg";
	const attempts = IMAGE_ENCODING_QUALITIES.map((quality) => (() => encode(prepared.clone(), mediaType, quality)));
	/*
	 * Android: the payload's pure-JS sharp cannot encode WebP, and 0.2.1 selects
	 * WebP for every image that carries alpha -- an Android screenshot is RGBA,
	 * so that is the ordinary case rather than an exotic one. Keep the WebP rungs
	 * (a host with real libvips still gets them) and add PNG rungs that preserve
	 * alpha when WebP is unavailable.
	 */
	if (!hasAlpha) return attempts;
	return [...attempts, ...[6, 9].map((level) => () => encodePng(prepared.clone(), level))];
}
''',
    "and add PNG rungs that preserve",
    "P22a alpha 图追加 PNG 编码档",
)
patch(
    "@deepseek-ai/dsh-attachment-local/lib/index.js",
    r'''async function encodeFirstWithinLimit(attempts, maxBytes) {
	const [first, ...remaining] = attempts;
	if (first === void 0) throw new Error("image encoding requires at least one candidate");
	let smallest = await first();
	if (smallest.data.byteLength <= maxBytes) return smallest;
	for (const attempt of remaining) {
		const candidate = await attempt();
		if (candidate.data.byteLength <= maxBytes) return candidate;
		if (candidate.data.byteLength < smallest.data.byteLength) smallest = candidate;
	}
	return { smallest };
}
''',
    r'''async function encodeFirstWithinLimit(attempts, maxBytes) {
	if (attempts.length === 0) throw new Error("image encoding requires at least one candidate");
	let smallest;
	let firstError;
	for (const attempt of attempts) {
		let candidate;
		try {
			candidate = await attempt();
		} catch (error) {
			/* Android: an unavailable codec skips its rungs instead of failing the whole ladder. */
			firstError ??= error;
			continue;
		}
		if (candidate.data.byteLength <= maxBytes) return candidate;
		if (smallest === void 0 || candidate.data.byteLength < smallest.data.byteLength) smallest = candidate;
	}
	if (smallest !== void 0) return { smallest };
	throw firstError ?? new Error("image encoding produced no candidate");
}
''',
    "an unavailable codec skips its rungs",
    "P22b 编码候选失败可跳过",
)
patch(
    "@deepseek-ai/dsh-attachment-local/lib/index.js",
    r'''	const encodedVersion = await encodeFirstWithinLimit(encodingLadder(pipeline(attachment, target), hasAlpha), target.maxBytes);
	return isExhaustedEncoding(encodedVersion) ? encodedVersion.smallest : encodedVersion;
''',
    r'''	try {
		const encodedVersion = await encodeFirstWithinLimit(encodingLadder(pipeline(attachment, target), hasAlpha), target.maxBytes);
		return isExhaustedEncoding(encodedVersion) ? encodedVersion.smallest : encodedVersion;
	} catch (error) {
		/*
		 * Android: with no working encoder the stored bytes are still a valid image
		 * for the provider. 0.2.0 sent exactly those; doing the same keeps the model
		 * request alive instead of failing the turn as TRANSPORT.
		 */
		console.error("[attachment] no usable image encoder, sending the stored image: " + (error && error.message ? error.message : String(error)));
		return {
			data: attachment.data,
			mediaType: attachment.ref.mediaType,
			width: attachment.ref.width,
			height: attachment.ref.height
		};
	}
''',
    "no usable image encoder, sending the stored image",
    "P22c 请求期缩图回落到已存字节",
)
patch(
    "@deepseek-ai/dsh-attachment-local/lib/index.js",
    r'''	} catch (error) {
		if (error instanceof AttachmentError) throw error;
		throw new AttachmentError(`The ${detected.mediaType === "image/png" && detected.depth !== "uchar" ? `${detected.depth === "ushort" ? "16-bit" : detected.depth} PNG` : `${detected.depth} ${detected.mediaType.slice(6).toUpperCase()}`} could not be converted to the normalized 8-bit sRGB form.`, "ATTACHMENT_WRITE_FAILED", { cause: error });
	}
''',
    r'''	} catch (error) {
		if (error instanceof AttachmentError) throw error;
		/* Android: no working encoder -- store the admitted bytes rather than refuse the upload. */
		console.error("[attachment] no usable image encoder, storing the original: " + (error && error.message ? error.message : String(error)));
		return {
			data,
			mediaType: detected.mediaType,
			width: detected.width,
			height: detected.height
		};
	}
''',
    "no usable image encoder, storing the original",
    "P22d 归一化回落到原字节",
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

# ── P21: node-addon-require-builtin 没有 android 预编译 → JS 替身 ───────────
# 0.2.1 起 dsh-app-boot 在 host preparation 阶段硬 require 这个原生包：
#   internalModules() 拿 Node 的 ESM 内部 loader 实例，把 resolveSync 换成
#   profile 路由版本；cordis-plugin-loader 走 v2 分支时还要调用
#   loader.internal.import(specifier, baseUrl, {})。
# npm 只发布 darwin/linux/win32 的可选包，android-arm64 必然 "No usable native
# binding found"，而加载器没有 JS 兜底 → 启动直接崩在 host preparation。
#
# 替身不碰 app-boot 的任何代码，只把同一个包的 lib/index.js 换成纯 JS：
#   - cjs/loader   → require("node:module")（公开 API 就是 Module 类本身），
#                    它的 _resolveFilename / _nodeModulePaths 是真货，
#                    app-boot 的 CJS 路由因此真实生效；
#   - esm/loader   → facade：resolveSync 用 createRequire(parent).resolve 解成
#                    file: URL，import() 再动态 import；getOrCreateModuleJob
#                    只是占位（app-boot 只做 `in` 检测，从不调用它）；
#   - conditions   → 实测 bionic node v24.18.0：
#                    ESM = node,node-addons,import,module-sync
#                    CJS = node,node-addons,require,module-sync。
# 效果：`dsh web` 能正常 boot 并监听端口（修前是 host preparation failed）。
REQUIRE_BUILTIN_SHIM = r'''"use strict";
/*
 * JS stand-in for node-addon-require-builtin (Android / no prebuilt binding).
 *
 * Shape is identical to the published CJS entry:
 *   requireBuiltin(id), isAllowedInternalId(id), getBindingInfo(), default
 *
 * The only consumer that matters at boot is @deepseek-ai/dsh-app-boot's
 * internalModules(), which asks for five internal ids and then patches
 *   - cjsModule.Module._resolveFilename   (works: public node:module)
 *   - esmLoader.resolveSync              (patched, not called by the host)
 * plus reads getCjsConditions()/getDefaultConditions()/defaultResolve.
 *
 * Conditions were probed against the target runtime (bionic node v24.18.0):
 *   ESM: node, node-addons, import, module-sync
 *   CJS: node, node-addons, require, module-sync
 */
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.requireBuiltin = requireBuiltin;
exports.isAllowedInternalId = isAllowedInternalId;
exports.getBindingInfo = getBindingInfo;

const Module = require("node:module");
const { pathToFileURL, fileURLToPath } = require("node:url");

const ESM_CONDITIONS = new Set(["node", "node-addons", "import", "module-sync"]);
const CJS_CONDITIONS = new Set(["node", "node-addons", "require", "module-sync"]);

// --- ESM resolver -----------------------------------------------------------
// Synchronous best-effort ESM resolution. Only reached through the host's
// routing adapter; plain `node_modules` resolution is what actually runs on the
// flat Android payload, so this exists to keep the adapter honest if called.
function toRequireAnchor(parentURL) {
    const parent = parentURL ?? pathToFileURL(process.cwd() + "/").href;
    if (parent.startsWith("node:")) return pathToFileURL(process.cwd() + "/anchor.cjs").href;
    if (parent.startsWith("file:")) return parent;
    return pathToFileURL(parent).href;
}

function resolveFromParent(specifier, parentURL) {
    if (typeof specifier !== "string") throw new TypeError("specifier must be a string");
    if (specifier.startsWith("node:")) return { url: specifier, format: "builtin" };
    if (specifier.startsWith("file:")) return { url: specifier };
    if (specifier.startsWith("/")) return { url: pathToFileURL(specifier).href };
    const anchor = toRequireAnchor(parentURL);
    const req = Module.createRequire(anchor);
    const resolved = req.resolve(specifier);
    if (resolved.startsWith("node:")) return { url: resolved, format: "builtin" };
    return { url: pathToFileURL(resolved).href };
}

function defaultResolve(specifier, context) {
    return resolveFromParent(specifier, context?.parentURL);
}

const esmLoader = {
    version: "v2",
    resolveSync(parent, request) {
        // Host calls: resolveSync(parentURL, { specifier, attributes })
        const specifier = request !== null && typeof request === "object" && "specifier" in request
            ? request.specifier
            : request;
        return resolveFromParent(specifier, parent);
    },
    // cordis-plugin-loader v2 path: loader.internal.import(specifier, baseUrl, attributes)
    async import(specifier, parentURL, attributes) {
        const resolve = esmLoader.resolveSync;
        const resolved = resolve.call(esmLoader, parentURL, { specifier, attributes: attributes ?? {} });
        return await import(resolved.url);
    },
    // Present so the host takes the "modern" Node 24 branch.
    getOrCreateModuleJob() {
        throw new Error("node-addon-require-builtin (js shim): ESM module job creation is not available without the native binding");
    },
    getModuleJobForImport() {
        throw new Error("node-addon-require-builtin (js shim): ESM module job creation is not available without the native binding");
    },
};

function getCjsConditions() { return CJS_CONDITIONS; }
function getDefaultConditions() { return ESM_CONDITIONS; }

const INTERNALS = {
    "internal/modules/cjs/loader": { Module },
    "internal/modules/esm/loader": {
        getOrInitializeCascadedLoader() { return esmLoader; },
        default: esmLoader,
    },
    "internal/modules/helpers": { getCjsConditions },
    "internal/modules/esm/utils": { getDefaultConditions },
    "internal/modules/esm/resolve": { defaultResolve },
};

function requireBuiltin(moduleId) {
    const found = INTERNALS[moduleId];
    if (found !== undefined) return found;
    throw new Error("node-addon-require-builtin (js shim): unsupported internal module " + String(moduleId));
}

function isAllowedInternalId() { return true; }

function getBindingInfo() {
    return {
        packageName: "node-addon-require-builtin",
        variant: "js-shim",
        requested: "android-arm64",
        backend: "js",
        optionalPackage: "node-addon-require-builtin-android-arm64",
        native: false,
    };
}

exports.default = {
    requireBuiltin,
    isAllowedInternalId,
    getBindingInfo,
};
'''


def install_require_builtin_shim():
    pkg = os.path.join(root, "node-addon-require-builtin")
    target = os.path.join(pkg, "lib", "index.js")
    if not os.path.isdir(pkg):
        skipped.append("P21 require-builtin JS 替身（树上没有这个包）")
        return
    try:
        with io.open(target, "r", encoding="utf-8") as fh:
            current = fh.read()
    except OSError:
        current = ""
    if "js-shim" in current:
        skipped.append("P21 require-builtin JS 替身（已打过）")
        return
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with io.open(target, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(REQUIRE_BUILTIN_SHIM)
    applied.append("P21 require-builtin JS 替身（node-addon-require-builtin/lib/index.js）")


install_require_builtin_shim()

# ── P23: 跨副本的 LlmError 被当成 TRANSPORT（真机只看到 TRANSPORT）─────────
# 0.2.1 把「模型请求失败」的中转放在 dsh-llm-deepseek 的 generate() 里：
#     if (error instanceof LlmError) throw error;
#     throw new LlmError("DeepSeek Messages transport failed", "TRANSPORT", ...)
# 载荷/profile 一旦解析出两份 @deepseek-ai/dsh-llm（runtime 一份、
# $DSH_HOME/node_modules 一份），instanceof 跨 realm 恒为 false，于是真正的
# AUTH / MISSING_CREDENTIAL / ACCOUNT_SIGN_IN_REQUIRED 全被改写成 TRANSPORT，
# 连带被 llm-retry 重试 5 次、并在界面上显示成"网络问题"。
# 这里按 LlmError 自己的品牌（name + code）识别跨副本实例，不吞真正的传输异常
# （fetch 失败是 TypeError: fetch failed，没有 name/code 这对组合）。
patch(
    "@deepseek-ai/dsh-llm-deepseek/lib/index.js",
    """\t\t\t\tif (error instanceof LlmError) throw error;
\t\t\t\tthrow new LlmError("DeepSeek Messages transport failed", "TRANSPORT", { cause: error });""",
    """\t\t\t\tif (error instanceof LlmError) throw error;
\t\t\t\t/*
\t\t\t\t * Android: a second physical copy of @deepseek-ai/dsh-llm (runtime vs
\t\t\t\t * $DSH_HOME/node_modules) makes `instanceof` fail across realms, which
\t\t\t\t * relabels every real LlmError -- AUTH, MISSING_CREDENTIAL,
\t\t\t\t * ACCOUNT_SIGN_IN_REQUIRED -- as TRANSPORT and retries it five times.
\t\t\t\t * Recognize the brand the class sets on itself; a genuine fetch failure
\t\t\t\t * carries neither that name nor a stable code.
\t\t\t\t */
\t\t\t\tif (typeof error === "object" && error !== null && error.name === "LlmError" && typeof error.code === "string" && error.code.length > 0) throw error;
\t\t\t\tthrow new LlmError("DeepSeek Messages transport failed", "TRANSPORT", { cause: error });""",
    "Recognize the brand the class sets on itself",
    "P23 跨副本 LlmError 不再被改写成 TRANSPORT",
)

# ── P24: API Key 只认 credentials 服务，服务缺位就整条路由不可用 ────────────
# 0.2.1 的 llm-deepseek-api-key 取凭据只有两条路：ctx.get("credentials") 或
# 进程环境（且 credentials 存在时环境变量根本不被看）。0.2.1 又把托管文档
# $DSH_HOME/.credentials.yaml 提到环境之上。于是只要 credentials 服务没挂上
# （headless profile 默认就 disabled）、或 resolve() 抛错，路由直接
# MISSING_CREDENTIAL —— 尽管钥匙就在磁盘上、同一个账号也能用。
# 这里在两条路都没结果时，直接读托管文档的 refs:（只读，不进环境）。
patch(
    "@deepseek-ai/dsh-llm-deepseek-api-key/lib/index.js",
    """import { credentialRef } from "@deepseek-ai/dsh-credentials";""",
    """import { credentialRef } from "@deepseek-ai/dsh-credentials";
import { readFileSync } from "node:fs";
/*
 * Android: last-resort credential read straight from the managed document.
 * The credentials service is composition-dependent (the headless profile ships
 * it disabled) and the harness home is fixed by the launcher, so the file --
 * not the service -- is the one thing that is always there. Read-only: nothing
 * here is materialized into the environment.
 */
function managedCredential(ref) {
\ttry {
\t\tconst home = process.env.DSH_HOME ?? `${process.env.HOME ?? ""}/.dsh`;
\t\tconst text = readFileSync(`${home}/.credentials.yaml`, "utf8");
\t\tconst wanted = String(ref);
\t\tlet inRefs = false;
\t\tfor (const raw of text.split("\\n")) {
\t\t\tconst trimmed = raw.trim();
\t\t\tif (trimmed.length === 0 || trimmed.startsWith("#")) continue;
\t\t\tif (raw.charAt(0) !== " " && raw.charAt(0) !== "\\t") {
\t\t\t\tinRefs = trimmed === "refs:";
\t\t\t\tcontinue;
\t\t\t}
\t\t\tif (!inRefs) continue;
\t\t\tconst hit = raw.match(/^\\s+([A-Za-z0-9_]+)\\s*:\\s*(.+?)\\s*$/u);
\t\t\tif (hit === null || hit[1] !== wanted) continue;
\t\t\tconst value = hit[2].replace(/^["']|["']$/gu, "");
\t\t\tif (value.length > 0) return value;
\t\t}
\t\treturn void 0;
\t} catch (_unreadable) {
\t\treturn void 0;
\t}
}""",
    "last-resort credential read straight from the managed document",
    "P24 托管凭据文档直读（import）",
)
patch(
    "@deepseek-ai/dsh-llm-deepseek-api-key/lib/index.js",
    """\t\tif (credentials !== void 0) {
\t\t\tconst hit = await credentials.resolve(ref);
\t\t\tif (hit !== void 0) return assertUsableApiKey(hit.value, "llm-deepseek", ref);
\t\t} else {
\t\t\tconst ambient = launchEnvironmentOf(ctx).get(ref);
\t\t\tif (ambient !== void 0 && ambient.value.length > 0) return assertUsableApiKey(ambient.value, "llm-deepseek", ref);
\t\t}
\t\tthrow new LlmError(`llm-deepseek: no API key""",
    """\t\tif (credentials !== void 0) {
\t\t\tlet hit;
\t\t\ttry {
\t\t\t\thit = await credentials.resolve(ref);
\t\t\t} catch (error) {
\t\t\t\tconsole.warn("[llm-deepseek] credentials service failed for " + String(ref) + ": " + (error && error.message ? error.message : String(error)));
\t\t\t}
\t\t\tif (hit !== void 0) return assertUsableApiKey(hit.value, "llm-deepseek", ref);
\t\t}
\t\tconst ambient = launchEnvironmentOf(ctx).get(ref);
\t\tif (ambient !== void 0 && ambient.value.length > 0) return assertUsableApiKey(ambient.value, "llm-deepseek", ref);
\t\tconst managed = managedCredential(ref);
\t\tif (managed !== void 0) return assertUsableApiKey(managed, "llm-deepseek", ref);
\t\tthrow new LlmError(`llm-deepseek: no API key""",
    "const managed = managedCredential(ref);",
    "P24 托管凭据文档直读（回退链）",
)

# ── P26: 老 WebView 内核缺新 API，客户端永远停在「重新连接中」──────────────
# 现象：华为平板 BKY-W00（鸿蒙 4.2 / Android 12）上，首次解压正常、端口 3220 在跑、
#   诊断页 running: true，但页面一直显示「重新连接中」。内核 < Chrome 100，
#   而 0.2 的客户端在**模块顶层**就调用 Promise.withResolvers / AbortSignal.any /
#   signal.throwIfAborted；这些是 undefined，于是抛 "x is not a function"，
#   连接模块整个死在 import 阶段 —— 服务器是好的，是浏览器端根本没跑起来。
#
# 位置：@deepseek-ai/dsh-web-frontend/dist/index.html（`dsh web` 实际服务的入口，
#   dsh-web-app 用 require.resolve 定位这个包，路径即上面的 dist/index.html）。
#
# 做法：在 <title> 之后插一段内联 <script>，只补「这些 bundle 里真的用到」的 API，
#   每个都 if 判断、Object.defineProperty 成不可枚举，遇到新内核等于空操作。
#   不引外部文件（少一个 404 面），CSP 已确认为空（index.html 无 meta，host 只发
#   content-type，没有 Content-Security-Policy 头），所以内联脚本不会被拦。
#
# 覆盖范围按实测用到为准（扫 index-*.js / vendor-*.js 与各 client.js）：
#   Promise.withResolvers(119) · AbortSignal.any(116) · throwIfAborted(100)
#   Object.hasOwn(93) · Array/String.prototype.at(92) · findLast/findLastIndex(97)
#   structuredClone(98) · toSorted/toReversed(110)
# 已知残留（本补丁不覆盖）：Intl.Segmenter(87) —— 若内核低于 87，chat 模块会在
#   import 期抛错，那是另一个修复，不在本次范围。
P26_JS = """<script>
/* dsh-android-polyfill v1 */
(function () {
  'use strict';
  var define = function (target, name, value) {
    try {
      Object.defineProperty(target, name, { value: value, writable: true, configurable: true, enumerable: false });
    } catch (error) {
      target[name] = value;
    }
  };
  var toInteger = function (value) {
    var n = Number(value);
    if (n !== n || n === 0) return 0;
    if (n === Infinity || n === -Infinity) return n;
    return n < 0 ? Math.ceil(n) : Math.floor(n);
  };
  var resolveIndex = function (length, index) {
    var k = toInteger(index);
    if (k < 0) k += length;
    return k < 0 || k >= length ? -1 : k;
  };

  /* Promise.withResolvers -- Chrome 119. The gateway/connection client calls it
     while wiring the socket, so its absence is the "reconnecting" hang itself. */
  if (typeof Promise.withResolvers !== 'function') {
    define(Promise, 'withResolvers', function withResolvers() {
      var resolve;
      var reject;
      var promise = new Promise(function (res, rej) { resolve = res; reject = rej; });
      return { promise: promise, resolve: resolve, reject: reject };
    });
  }

  /* AbortSignal.any -- Chrome 116. Reason of the first aborted input wins. */
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.any !== 'function') {
    define(AbortSignal, 'any', function any(signals) {
      var controller = new AbortController();
      var list = Array.prototype.slice.call(signals);
      var i;
      for (i = 0; i < list.length; i++) {
        if (list[i].aborted) {
          controller.abort(list[i].reason);
          return controller.signal;
        }
      }
      var relay = function (signal) {
        signal.addEventListener('abort', function () {
          controller.abort(signal.reason);
        }, { once: true });
      };
      for (i = 0; i < list.length; i++) relay(list[i]);
      return controller.signal;
    });
  }

  /* AbortSignal.prototype.throwIfAborted -- Chrome 100. */
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.prototype.throwIfAborted !== 'function') {
    define(AbortSignal.prototype, 'throwIfAborted', function throwIfAborted() {
      if (!this.aborted) return;
      if (this.reason !== undefined) throw this.reason;
      var error;
      try {
        error = new DOMException('signal is aborted without reason', 'AbortError');
      } catch (missing) {
        error = new Error('signal is aborted without reason');
        error.name = 'AbortError';
      }
      throw error;
    });
  }

  /* Object.hasOwn -- Chrome 93. */
  if (typeof Object.hasOwn !== 'function') {
    define(Object, 'hasOwn', function hasOwn(object, property) {
      if (object === null || object === undefined) throw new TypeError('Cannot convert undefined or null to object');
      return Object.prototype.hasOwnProperty.call(Object(object), property);
    });
  }

  /* Array.prototype.at / String.prototype.at -- Chrome 92. */
  if (typeof Array.prototype.at !== 'function') {
    define(Array.prototype, 'at', function at(index) {
      if (this === null || this === undefined) throw new TypeError('Array.prototype.at called on null or undefined');
      var object = Object(this);
      var k = resolveIndex(object.length >>> 0, index);
      return k < 0 ? undefined : object[k];
    });
  }
  if (typeof String.prototype.at !== 'function') {
    define(String.prototype, 'at', function at(index) {
      if (this === null || this === undefined) throw new TypeError('String.prototype.at called on null or undefined');
      var text = String(this);
      var k = resolveIndex(text.length, index);
      return k < 0 ? undefined : text.charAt(k);
    });
  }

  /* Array.prototype.findLast / findLastIndex -- Chrome 97. */
  if (typeof Array.prototype.findLast !== 'function') {
    define(Array.prototype, 'findLast', function findLast(predicate, thisArg) {
      if (this === null || this === undefined) throw new TypeError('Array.prototype.findLast called on null or undefined');
      if (typeof predicate !== 'function') throw new TypeError('predicate must be a function');
      var object = Object(this);
      for (var i = (object.length >>> 0) - 1; i >= 0; i--) {
        var value = object[i];
        if (predicate.call(thisArg, value, i, object)) return value;
      }
      return undefined;
    });
  }
  if (typeof Array.prototype.findLastIndex !== 'function') {
    define(Array.prototype, 'findLastIndex', function findLastIndex(predicate, thisArg) {
      if (this === null || this === undefined) throw new TypeError('Array.prototype.findLastIndex called on null or undefined');
      if (typeof predicate !== 'function') throw new TypeError('predicate must be a function');
      var object = Object(this);
      for (var i = (object.length >>> 0) - 1; i >= 0; i--) {
        if (predicate.call(thisArg, object[i], i, object)) return i;
      }
      return -1;
    });
  }

  /* Array.prototype.toSorted / toReversed -- Chrome 110. Both copy first:
     the whole point is that the receiver is left alone. */
  if (typeof Array.prototype.toSorted !== 'function') {
    define(Array.prototype, 'toSorted', function toSorted(compareFn) {
      if (this === null || this === undefined) throw new TypeError('Array.prototype.toSorted called on null or undefined');
      var copy = Array.prototype.slice.call(Object(this));
      copy.sort(compareFn);
      return copy;
    });
  }
  if (typeof Array.prototype.toReversed !== 'function') {
    define(Array.prototype, 'toReversed', function toReversed() {
      if (this === null || this === undefined) throw new TypeError('Array.prototype.toReversed called on null or undefined');
      return Array.prototype.slice.call(Object(this)).reverse();
    });
  }

  /* structuredClone -- Chrome 98. The settings screens clone their state tree
     before saving; a JSON-ish deep copy covers what they hold. */
  if (typeof structuredClone !== 'function') {
    var cloneOne = function (value, seen) {
      if (value === null || typeof value !== 'object') return value;
      if (seen.has(value)) return seen.get(value);
      var tag = Object.prototype.toString.call(value);
      var copy;
      var keys;
      var i;
      if (tag === '[object Date]') return new Date(value.getTime());
      if (tag === '[object RegExp]') return new RegExp(value.source, value.flags);
      if (tag === '[object ArrayBuffer]') return value.slice(0);
      if (tag === '[object Map]') {
        copy = new Map();
        seen.set(value, copy);
        value.forEach(function (entryValue, entryKey) {
          copy.set(cloneOne(entryKey, seen), cloneOne(entryValue, seen));
        });
        return copy;
      }
      if (tag === '[object Set]') {
        copy = new Set();
        seen.set(value, copy);
        value.forEach(function (entry) { copy.add(cloneOne(entry, seen)); });
        return copy;
      }
      if (ArrayBuffer.isView(value)) {
        var buffer = cloneOne(value.buffer, seen);
        if (tag === '[object DataView]') return new DataView(buffer, value.byteOffset, value.byteLength);
        return new value.constructor(buffer, value.byteOffset, value.length);
      }
      copy = Array.isArray(value) ? new Array(value.length) : Object.create(Object.getPrototypeOf(value));
      seen.set(value, copy);
      keys = Object.keys(value);
      for (i = 0; i < keys.length; i++) copy[keys[i]] = cloneOne(value[keys[i]], seen);
      return copy;
    };
    define(window, 'structuredClone', function structuredClone(value) {
      return cloneOne(value, new Map());
    });
  }
})();
</script>
"""

patch(
    "@deepseek-ai/dsh-web-frontend/dist/index.html",
    """    <title>DeepSeek Harness</title>
""",
    """    <title>DeepSeek Harness</title>
""" + P26_JS,
    "dsh-android-polyfill v1",
    "P26 老 WebView 的 polyfill",
)


# ── P27: 附件发布用 link(2)，而 app 私有存储拒绝硬链接 ───────────────────────
# 现场（Issue #4：OPPO PKM110 / Android 16、未 root、1.0.19）：
#   发图片 → 「图片发送失败 (ATTACHMENT_WRITE_FAILED)」；
#   agent 读图 → 「Unable to persist attachment.」；
#   发 txt / pdf / zip → 同样 ATTACHMENT_WRITE_FAILED。
#   与格式、尺寸、磁盘（195 GB 可用）无关；staging 可写、objects 下可建目录；
#   fsync / chmod / rename 全部正常，只有 link(2) 返回 EACCES。
#   App 自己在每次启动时就探测过这条限制（files/dsh.log 第 10 行
#   「hard link: FAILED -> java.nio.file.AccessDeniedException」），只是附件这条路没走回落。
# 版本背景：0.2.0 的发布路径用 rename，所以 1.0.18 没事；0.2.1 改成
#   「link() 建不可变对象 + unlink() 删 staging」，于是在这台机器上撞墙。
#
# 同一文件里有两处 link()，回落方式**不同**，不能照抄 —— 这是本补丁的重点：
#   P27a publishStagedObject()  staging 与目标同分区 → 回落 rename()（同样原子，
#        staged 名字直接消失，所以后面那次 unlink 要跳过，否则 ENOENT）
#   P27b publishImmutableAlias() source 是 canonical object，别的 alias 还在引用它，
#        移走会破坏别人 → 回落「读字节再写」，并用 wx 保住 link 原有的独占创建语义
# errno 判据照用户在本机验证过的补丁：EEXIST 仍走原来的 digest 校验；
#   EACCES / EPERM / ENOSYS / ENOTSUP / EXDEV 回落；其它错误照旧抛出。
#
# 注：`rename` / `readFile` / `writeFile` 都已在 attachment-local 的 import 里（第 7 行），
# 不像 P25 那样需要补符号。
patch(
    "@deepseek-ai/dsh-attachment-local/lib/index.js",
    """		try {
			await link(staged.path, target);
		} catch (error) {
			/* v8 ignore next -- Private same-filesystem directories make EEXIST the only recoverable link race. */
			if (!(error instanceof Error && "code" in error && error.code === "EEXIST")) throw error;
			if (await digestFile(target) !== staged.sha256) throw new AttachmentError("Stored attachment failed integrity verification.", "ATTACHMENT_CORRUPT");
		}
		await unlink(staged.path);
""",
    """		/* Android's app-private storage refuses link(2) with EACCES -- the app probes this on
		 * every boot ("hard link: FAILED -> AccessDeniedException") while fsync, chmod and
		 * rename all work there. Staging lives on the same filesystem as the target, so
		 * rename(2) publishes just as atomically and consumes the staged name: that is why the
		 * trailing unlink is skipped once rename took the file. */
		let stagedConsumed = false;
		try {
			await link(staged.path, target);
		} catch (error) {
			const code = error instanceof Error && "code" in error ? error.code : void 0;
			if (code === "EEXIST") {
				if (await digestFile(target) !== staged.sha256) throw new AttachmentError("Stored attachment failed integrity verification.", "ATTACHMENT_CORRUPT");
			} else if (code === "EACCES" || code === "EPERM" || code === "ENOSYS" || code === "ENOTSUP" || code === "EXDEV") {
				await rename(staged.path, target);
				stagedConsumed = true;
			} else throw error;
		}
		if (!stagedConsumed) await unlink(staged.path);
""",
    "let stagedConsumed = false;",
    "P27a 附件对象发布：link 被拒时回落 rename",
)
patch(
    "@deepseek-ai/dsh-attachment-local/lib/index.js",
    """		try {
			await link(source, target);
		} catch (error) {
			/* v8 ignore next -- Private same-filesystem directories make EEXIST the only recoverable link race. */
			if (!(error instanceof Error && "code" in error && error.code === "EEXIST")) throw error;
			if (await digestFile(target) !== sha256) throw new AttachmentError("Stored attachment failed integrity verification.", "ATTACHMENT_CORRUPT");
		}
""",
    """		/* Same platform limit as publishStagedObject(), different fallback: `source` is the
		 * canonical object and other aliases still reference it, so it must stay where it is --
		 * rename would break every one of them. Copy the bytes instead; "wx" keeps the
		 * exclusive-create semantics link(2) gave us, and mode 384 (0o600) matches the patch
		 * this was verified with on the device. */
		try {
			await link(source, target);
		} catch (error) {
			const code = error instanceof Error && "code" in error ? error.code : void 0;
			if (code === "EEXIST") {
				if (await digestFile(target) !== sha256) throw new AttachmentError("Stored attachment failed integrity verification.", "ATTACHMENT_CORRUPT");
			} else if (code === "EACCES" || code === "EPERM" || code === "ENOSYS" || code === "ENOTSUP" || code === "EXDEV") {
				await writeFile(target, await readFile(source), { mode: 384, flag: "wx" });
			} else throw error;
		}
""",
    'await writeFile(target, await readFile(source), { mode: 384, flag: "wx" });',
    "P27b 附件别名发布：link 被拒时读字节再写（不能 rename）",
)


# ── P27c: alias 的 copy 回落也要保住「独占创建 + EEXIST 去重」───────────────
# 这条是容器验证抓出来的：`storedFilePath()` 的 target 也是内容寻址的
# （files/<sha[:2]>/<sha>/<name>），所以**同一个文件发第二次**（或重试一次）会
# 落到同一个 target 上。用 link 时第二次是 EEXIST → 走 digest 校验 → 正常去重；
# 而 P27b 的 copy 回落写死了 flag:"wx"，EEXIST 会直接抛出去 →
# 表现成「同一个文件发第二次报 ATTACHMENT_WRITE_FAILED」，是 P27 会引入的新回归。
# 判据不新增：copy 拿到 EEXIST 时，接回**上面已有的** digest 校验分支，
# 与 link 的 EEXIST 分支完全一致。只在 Android 的回落路径上生效。
patch(
    "@deepseek-ai/dsh-attachment-local/lib/index.js",
    """			} else if (code === "EACCES" || code === "EPERM" || code === "ENOSYS" || code === "ENOTSUP" || code === "EXDEV") {
				await writeFile(target, await readFile(source), { mode: 384, flag: "wx" });
			} else throw error;
""",
    """			} else if (code === "EACCES" || code === "EPERM" || code === "ENOSYS" || code === "ENOTSUP" || code === "EXDEV") {
				try {
					await writeFile(target, await readFile(source), { mode: 384, flag: "wx" });
				} catch (copyError) {
					/* The copy keeps the exclusivity link(2) had: a target that appeared in between
					 * is deduplicated exactly like the EEXIST branch above instead of failing the
					 * save. Stored file paths are content-addressed, so saving one attachment a
					 * second time (or retrying one upload) lands here. */
					const copyCode = copyError instanceof Error && "code" in copyError ? copyError.code : void 0;
					if (copyCode !== "EEXIST") throw copyError;
					if (await digestFile(target) !== sha256) throw new AttachmentError("Stored attachment failed integrity verification.", "ATTACHMENT_CORRUPT");
				}
			} else throw error;
""",
    'if (copyCode !== "EEXIST") throw copyError;',
    "P27c alias 回落的 EEXIST 接回 digest 去重",
)

# ── P28: 会话写租约的僵尸锁（pid 被复用时 EPERM 被当成「还活着」）─────────────
# 现场（Issue #4 顺带 A）：客户端报「当前会话已被占用，可能是其他正在运行的 DSH 导致的」
# （session/writer-held）；冷启动后旧进程已死，会话依然打不开。
# P20 的目录式租约（session.lock.d + pid 判活）只记 pid，而判活是：
#     kill(pid, 0) 成功 -> 活；EPERM -> 「进程存在但属于别人」-> 也算活
# 旧 PID 一旦被别的 uid 的进程复用，kill 就永远回 EPERM，于是租约被判「仍存活」，
# 这个会话就被永久锁死。用户实测两次清理时锁里的 PID 都指向已死进程，其中一次 kill -0 = EPERM。
#
# 修法（保守优先，宁可让人等一次，也不能让两个写者同时写）：
#   1. 租约里除 pid 再记 `/proc/<pid>/stat` 的 starttime（field 22）作为**身份**。
#      pid 相同 + starttime 相同 = 同一个进程 → 持有；不相同 = 内核把 pid 回收给了别人 → 立刻破锁。
#      这一步就解决用户报的那种场景，不用等。
#   2. 身份读不出来（/proc 不可读、异 uid）时，EPERM **不**再单独当作存活证据；
#      只有租约年龄超过很长阈值（默认 30 分钟，DSH_SESSION_LEASE_TAKEOVER_MS 可调，低于 10 分钟拒绝）
#      才接管，并且**打日志**说明「被判为不可识别、按超时接管」。
#   3. 其余不确定情形一律维持原判（持锁），因为两写者撕裂日志的代价比多等一次大。
# 破锁前仍复核 pid + starttime + inode，接替者换过的新租约不会被误删。
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """/** Name of the holder record inside a directory-form lease. */
const LEASE_PID_FILENAME = "pid";
""",
    """/** Name of the holder record inside a directory-form lease. */
const LEASE_PID_FILENAME = "pid";
/** Name of the holder identity record inside a directory-form lease (`/proc/<pid>/stat` field 22). */
const LEASE_START_FILENAME = "start";
/**
* How long an unidentifiable holder may keep a lease before a successor takes it
* over. A live writer throttled by the platform can stay silent for a long time,
* and expropriating it interleaves two writers over one log, so this is a last
* resort and deliberately long: thirty minutes by default, ten minutes is the
* lowest a setting may go (`DSH_SESSION_LEASE_TAKEOVER_MS`).
*/
const LEASE_TAKEOVER_MS = (() => {
	const configured = Number.parseInt(process.env.DSH_SESSION_LEASE_TAKEOVER_MS ?? "", 10);
	return Number.isSafeInteger(configured) && configured >= 600000 ? configured : 1800000;
})();
""",
    "const LEASE_TAKEOVER_MS = (() => {",
    "P28a 租约增加身份记录文件名与超时接管阈值",
)
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """/**
* Whether a recorded holder pid still names a running process.
* @param pid - the recorded holder pid.
* @returns true while that process exists.
*/
function isProcessAlive(pid) {
	try {
		process.kill(pid, 0);
		return true;
	} catch (error) {
		/* EPERM means the process exists but belongs to someone else. */
		return error?.code === "EPERM";
	}
}
""",
    """/**
* Read one process's start time: field 22 of `/proc/<pid>/stat`, in clock ticks
* since boot. A pid is not an identity -- the kernel hands the same number to a
* later process -- so the holder records this token and a successor compares it.
* The field list is split after the last `)`: `comm` (field 2) is parenthesised
* and may itself contain spaces and parentheses, which puts state (field 3) at
* index 0 and starttime (field 22) at index 19.
* @param pid - the process id to identify.
* @returns the start-time token, or undefined when procfs cannot answer.
*/
async function readProcessStartTime(pid) {
	try {
		const stat = await readFile(`/proc/${pid}/stat`, "utf8");
		const starttime = stat.slice(stat.lastIndexOf(")") + 1).trim().split(" ")[19];
		return starttime !== undefined && /^[0-9]+$/.test(starttime) ? starttime : undefined;
	} catch {
		return undefined;
	}
}
/**
* How a recorded holder pid answers a zero-signal probe. `EPERM` is kept apart
* from `ESRCH` on purpose: it says the pid exists under another uid, which is the
* recycled-pid case, not proof that our writer is still there.
* @param pid - the recorded holder pid.
* @returns `"running"`, `"gone"`, `"denied"` or `"unknown"`.
*/
function probeProcess(pid) {
	try {
		process.kill(pid, 0);
		return "running";
	} catch (error) {
		const code = error?.code;
		if (code === "ESRCH") return "gone";
		if (code === "EPERM") return "denied";
		return "unknown";
	}
}
/**
* Judge a recorded directory-lease holder.
*
* `EPERM` alone is not proof of life: an app whose old pid was handed to a process
* under another uid answers `EPERM` forever, which is the zombie lease that locked
* a session out for good. Identity comes from the start time instead -- same pid
* and same start time is the same process, a different start time is a recycled
* pid -- and only when neither side can be read does the long takeover threshold
* decide. Two writers over one log is the outcome worth waiting for, so every
* unclear answer keeps the lease.
* @param record - the observed holder record and its age.
* @returns `"held"`, `"gone"`, `"reused"` or `"stale"`.
*/
async function leaseHolderVerdict(record) {
	const current = await readProcessStartTime(record.pid);
	if (record.start !== undefined && current !== undefined) return current === record.start ? "held" : "reused";
	if (probeProcess(record.pid) === "gone") return "gone";
	return record.ageMs >= LEASE_TAKEOVER_MS ? "stale" : "held";
}
""",
    "async function leaseHolderVerdict(record) {",
    "P28b 判活改看 pid + /proc starttime 身份（EPERM 不再等于存活）",
)
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """	try {
		return Number.parseInt((await readFile(join(dir, LEASE_PID_FILENAME), "utf8")).trim(), 10) || 0;
	} catch {
		return 0;
	}
}
""",
    """	try {
		return Number.parseInt((await readFile(join(dir, LEASE_PID_FILENAME), "utf8")).trim(), 10) || 0;
	} catch {
		return 0;
	}
}
/**
* Read the holder identity token recorded inside a directory-form lease.
* @param dir - the lease directory.
* @returns the start-time token, or undefined when no usable record exists.
*/
async function readLeaseStart(dir) {
	try {
		const token = (await readFile(join(dir, LEASE_START_FILENAME), "utf8")).trim();
		return /^[0-9]+$/.test(token) ? token : undefined;
	} catch {
		return undefined;
	}
}
/**
* Read everything a verdict needs about a directory-form lease: the holder pid, the
* identity token it wrote, and how long the lease has existed. The directory is
* created and then written once, so its mtime is that moment; an unreadable mtime
* yields age 0, which can only keep a lease, never break one.
* @param dir - the lease directory.
* @param observed - the `dev`/`ino`/`mtime` capture taken when the lease was seen.
* @returns the holder record.
*/
async function readLeaseRecord(dir, observed) {
	const pid = await readLeaseHolder(dir);
	const born = Number(observed.mtimeMs);
	return {
		pid,
		start: await readLeaseStart(dir),
		ageMs: Number.isFinite(born) ? Date.now() - born : 0
	};
}
""",
    "async function readLeaseRecord(dir, observed) {",
    "P28c 读租约记录（pid + 身份 + 年龄）",
)
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """* Create `dir` as this process's lease. `mkdir` is the arbiter: it either
* creates the directory or fails `EEXIST`, and that decision is atomic across
* processes. A recorded holder that still exists keeps the lease — there is
* deliberately no age threshold, because a writer throttled by the platform can
* stay silent longer than any timeout, and expropriating it is exactly the
* two-writer interleaving that tears a log. Only a recorded pid that provably
* no longer exists is broken out of.
""",
    """* Create `dir` as this process's lease. `mkdir` is the arbiter: it either
* creates the directory or fails `EEXIST`, and that decision is atomic across
* processes. A recorded holder that is still the same process keeps the lease —
* identity is pid plus its `/proc/<pid>/stat` start time, because `kill(pid, 0)`
* answering `EPERM` for a recycled pid used to read as alive and lock the session
* for good. A recycled pid or a provably absent one is broken out of at once; a
* holder that cannot be identified at all is taken over only past
* {@link LEASE_TAKEOVER_MS}, since a throttled writer that is still alive must
* never lose exclusion.
""",
    "answering `EPERM` for a recycled pid used to read as alive",
    "P28d createLeaseDirectory 注释同步新判活规则",
)
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """		const observed = await stat(dir, { bigint: true });
		const holder = await readLeaseHolder(dir);
		if (holder === 0) {
			/* An absent or malformed record is not proof of death: refuse rather than risk a second writer. */
			console.error(`[session] refusing the session write lease: no readable holder record in ${join(dir, LEASE_PID_FILENAME)} (${dir})`);
			throw leaseContention();
		}
		if (isProcessAlive(holder)) throw leaseContention();
		console.error(`[session] breaking an abandoned session write lease (holder=${holder}, that pid is gone): ${dir}`);
		/* Break it only while the observed record and inode still identify that dead
		 * holder: a lease a successor already replaced is the successor's, not ours. */
		if ((await readLeaseHolder(dir)) === holder && await isSameLeaseDirectory(dir, observed)) await rm(dir, { recursive: true, force: true });
""",
    """		const observed = await stat(dir, { bigint: true });
		const record = await readLeaseRecord(dir, observed);
		if (record.pid === 0) {
			/* An absent or malformed record is not proof of death: refuse rather than risk a second writer. */
			console.error(`[session] refusing the session write lease: no readable holder record in ${join(dir, LEASE_PID_FILENAME)} (${dir})`);
			throw leaseContention();
		}
		const verdict = await leaseHolderVerdict(record);
		if (verdict === "held") throw leaseContention();
		if (verdict === "reused") console.error(`[session] breaking a session write lease whose pid was recycled: holder=${record.pid} recorded-start=${record.start} current-start=${await readProcessStartTime(record.pid)} probe=${probeProcess(record.pid)} (${dir})`);
		else if (verdict === "stale") console.error(`[session] taking over an unidentifiable session write lease past the ${Math.round(LEASE_TAKEOVER_MS / 1000)}s threshold: holder=${record.pid} recorded-start=${record.start ?? "unknown"} probe=${probeProcess(record.pid)} age=${Math.round(record.ageMs / 1000)}s (${dir}) — a live writer behind that pid would lose exclusion, so this only fires when nothing identifies it`);
		else console.error(`[session] breaking an abandoned session write lease (holder=${record.pid}, that pid is gone): ${dir}`);
		/* Break it only while the observed record, identity, and inode still name that
		 * dead holder: a lease a successor already replaced is the successor's, not ours. */
		if ((await readLeaseHolder(dir)) === record.pid && (await readLeaseStart(dir)) === record.start && await isSameLeaseDirectory(dir, observed)) await rm(dir, { recursive: true, force: true });
""",
    "breaking a session write lease whose pid was recycled",
    "P28e 接管判据：身份/超时，破锁前复核 pid + 身份 + inode",
)
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """	const inode = await stat(dir, { bigint: true });
	try {
		await writeFile(join(dir, LEASE_PID_FILENAME), String(process.pid));
""",
    """	const inode = await stat(dir, { bigint: true });
	try {
		await writeFile(join(dir, LEASE_PID_FILENAME), String(process.pid));
		/* Record this process's kernel identity beside the pid: without it a successor cannot
		 * tell "the same writer" from "a stranger the kernel handed this pid to", which is the
		 * zombie lease. Best effort only -- a missing token falls back to the long takeover
		 * threshold, never to a blind break. */
		const start = await readProcessStartTime(process.pid);
		if (start !== undefined) await writeFile(join(dir, LEASE_START_FILENAME), start);
""",
    "const start = await readProcessStartTime(process.pid);",
    "P28f 建租约时写入身份 token",
)
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """* it. A recorded pid that still exists keeps the lease — deliberately with no
* age threshold, for the throttling reason above — while a pid that is
* provably gone is broken out of, so a crashed holder blocks only until the
* next acquire. Android takes this path because its native flock entry is a
""",
    """* it. The record carries the holder's pid and its `/proc/<pid>/stat` start time, so
* "still exists" means "still that process": a pid the kernel recycled is broken out
* of at once, where `EPERM` for a stranger's pid used to read as alive and lock the
* session for good. A holder that cannot be identified at all is taken over only
* past a long threshold, because a throttled writer that is still alive must never
* lose exclusion. Android takes this path because its native flock entry is a
""",
    '"still exists" means "still that process"',
    "P28g 租约模块注释同步 pid+starttime 身份",
)

# ── P29: 交付物卡片的「桌面动作」在 Android 上恒报「无法读取主机桌面信息」────
# 现场（Issue #4 顺带 B）：卡片上出现「无法读取主机桌面信息 · 重试」（locale key
# presented.hostError），文件本身正常。
# 判定链（本轮重新查过，结论和上一轮的 P14/P15/P16 不同）：
#   P15/P16 已经让服务端认了 android ——
#     nativeFileManager('android') -> "android"，canOpenNativePath('android') -> true，
#     openNativePathWithIntent / revealNativePath 都改走 dsh-open
#     （→ App 的 /device/open → FileProvider + 系统分享面板）。
#   于是 /api/present.host 返回 { name, available: true, fileManager: "android" }。
#   但客户端 isPresentedHost() 的 fileManager 白名单只有 null/finder/explorer/directory，
#   "android" 被当成非法响应 → readHost 落 host = "error" → 卡片渲染 presented.hostError。
# 也就是说：这个动作在 Android 上**不是**必然失败，是客户端把合法回答判成了坏数据。
# 所以不做「隐藏按钮」，而是让白名单认 "android"：两个动作随后都落到 dsh-open，
# 也就是系统分享面板 —— 那正是 Android 上「用外部应用打开」的形态。
# 客户端是产线产物，改动面控制在这一行判断上，不动渲染与文案。
# 已知残留（本轮不改，属文案）：卡片状态会沿用 presented.revealed
# （「已请求在文件管理器中显示」），在 Android 上实际是拉起分享面板。
patch(
    "@deepseek-ai/dsh-client-ui-deliverables/lib/client.js",
    """			return typeof host.name === "string" && typeof host.available === "boolean" && (host.fileManager === null || host.fileManager === "finder" || host.fileManager === "explorer" || host.fileManager === "directory");
""",
    """			/* "android" is a real answer, not a malformed one: there is no desktop file manager,
			 * but open and reveal both reach the system share sheet (dsh-open -> /device/open),
			 * so leaving it out of this whitelist turned every delivery card into
			 * presented.hostError with a retry button that could never succeed. */
			return typeof host.name === "string" && typeof host.available === "boolean" && (host.fileManager === null || host.fileManager === "finder" || host.fileManager === "explorer" || host.fileManager === "directory" || host.fileManager === "android");
""",
    '"android" is a real answer, not a malformed one',
    "P29 交付卡片认 android 的 fileManager（否则恒 hostError）",
)


# ── P30: 同一个坑的另一处 —— 会话日志的发布锁（P3-helper）也只看 pid ──────────
# 本轮验证 P28 时顺手在这份文件里查「EPERM 当存活」的同类判据，发现 publishExclusive()
# （P3-helper，本项目自己加的）里还有一份：
#     process.kill(holder, 0) 成功 -> 活；catch 里 EPERM -> 活
# 危害比租约小（发布锁只在一次 rename 发布期间存在），但同样是「pid 被回收后
# 永久没人破」：`.publish.lock` 一直留在日志旁边，之后每次 materialize 都会在
# 500ms 后走 `return false`（当成「别人先到」），新会话的日志就再也建不出来。
#
# 修法与 P28 同源、但**更保守**：只有 /proc 证明「这个 pid 已经是另一个进程」时才破，
# 其余不确定情形一律维持原判（EPERM 仍算活）。不加任何按年龄抢占 —— 抢一个活着的
# 发布者就是在同一份日志上交错两个写者。新建锁时顺带把 starttime 写在 pid 后面，
# 第一段仍是纯整数，旧版本照样按 pid 解析。
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """			try {
				await handle.writeFile(String(process.pid));
			} catch {
			}
""",
    """			try {
				/* pid plus this process's kernel identity, so a later publisher can tell "the
				 * same writer" from "a stranger the kernel handed this pid to". The first token
				 * stays a bare integer: older builds parse this file as a pid. */
				const start = await readProcessStartTime(process.pid);
				await handle.writeFile(start === undefined ? String(process.pid) : `${process.pid} ${start}`);
			} catch {
			}
""",
    "await handle.writeFile(start === undefined ? String(process.pid) :",
    "P30a 发布锁写入身份 token（pid + starttime）",
)
patch(
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    """				let holder = 0;
				try {
					holder = Number.parseInt((await readFile(lock, "utf8")).trim(), 10) || 0;
				} catch {
					holder = 0;
				}
				let alive = false;
				if (holder > 0) {
					try {
						process.kill(holder, 0);
						alive = true;
					} catch (probe) {
						/* EPERM means the process exists but belongs to someone else. */
						alive = probe?.code === "EPERM";
					}
				}
""",
    """				let holder = 0;
				let start = 0;
				try {
					const fields = (await readFile(lock, "utf8")).trim().split(" ");
					holder = Number.parseInt(fields[0] ?? "", 10) || 0;
					start = Number.parseInt(fields[1] ?? "", 10) || 0;
				} catch {
					holder = 0;
					start = 0;
				}
				/* Same judgement as the session write lease (P28): a pid is not an identity, and
				 * `EPERM` used to read as "alive" here as well, so a lock whose pid the kernel
				 * recycled belonged to a stranger that nobody could ever break. Only a proven
				 * recycled pid is broken -- every unclear answer keeps the lock, because
				 * expropriating a live publisher interleaves two writers over one log. */
				let alive = false;
				if (holder > 0) {
					const recordedStart = start > 0 ? String(start) : undefined;
					const currentStart = recordedStart === undefined ? undefined : await readProcessStartTime(holder);
					if (recordedStart !== undefined && currentStart !== undefined) alive = currentStart === recordedStart;
					else alive = probeProcess(holder) !== "gone";
				}
""",
    "const recordedStart = start > 0 ? String(start) : undefined;",
    "P30b 发布锁判活也看身份（EPERM 不再单独等于存活）",
)


# ── P31: 图片类型只认浏览器给的 MIME，Android 截图被当成「不是图片」 ──────────
# 现场（真机）：选手机截图（1200×2608，内容正常）发送 → 界面报
#   「仅支持 PNG、JPG、WebP、GIF 格式的图片」（locale key image.unsupportedType）。
# 判定链全在客户端；服务端只做「声明的 mediaType 必须是四种之一」+「字节识别结果
# 必须等于声明的 mediaType」，并没有另一套格式白名单可放宽：
#   a) intakeFiles（输入框的收文件闸门）→ addFiles → createDrafts 用
#      isImageMediaType(file.type) 决定「图片草稿 / 普通文件上传」；
#   b) encodeImage → imageMediaType(file.type)，把类型写进 wire 的 mediaType；
#   c) 服务端入库时 dsh-attachment-local/inspectMetadata 用 sharp 按**字节**识别，
#      与声明的 mediaType 不等就抛 IMAGE_TYPE_MISMATCH，客户端把 INVALID_IMAGE /
#      IMAGE_TYPE_MISMATCH 都渲染成同一句 image.unsupportedType。
# Android 的相册/文件选择器给出空 type（或 application/octet-stream）时，三层都
# 只有「浏览器给的字符串」这一个信息源，而那个字符串在 Android 上不可信。
# 修法：只有 type 不在四个里时才读前 12 字节按 magic 判；认出来就把这个类型补到
#   File 上，让 a/b 两层都用它（否则 b 层送出去的 mediaType 仍是空/八位字节流，
#   服务端按字节复核还是会拒）。认不出原样保留：普通文件照旧走文件上传路径，
#   真被当成图片时 encodeImage 仍抛原来的 UnsupportedImageMediaTypeError。
# 快路径保留：type 已在四个里就直接返回，一个字节都不读（有测试守这条）。
# 服务端不改：它接受的就是这四个值，我们只会送检测出来的这四个之一。
patch(
    "@deepseek-ai/dsh-client-ui-conversation/lib/client.js",
    """		function isImageMediaType(value) {
			return value === "image/png" || value === "image/jpeg" || value === "image/webp" || value === "image/gif";
		}
""",
    """		function isImageMediaType(value) {
			return value === "image/png" || value === "image/jpeg" || value === "image/webp" || value === "image/gif";
		}
		/**
		 * Android's picker hands the browser an empty `type` -- or
		 * `application/octet-stream` -- for perfectly valid images, so a draft that
		 * trusted the declared MIME alone refused a plain screenshot as an
		 * unsupported type. Twelve bytes are enough to let the signature decide; a
		 * declared type that is already accepted stays the fast path and reads
		 * nothing at all.
		 */
		const IMAGE_HEAD_BYTES = 12;
		/**
		 * Identify the supported image format of one file signature.
		 * @param bytes - the first 12 bytes of the file.
		 * @returns the detected media type, or undefined for any other bytes.
		 */
		function mediaTypeFromImageHeader(bytes) {
			const length = bytes.length;
			if (length >= 8 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47 && bytes[4] === 0x0d && bytes[5] === 0x0a && bytes[6] === 0x1a && bytes[7] === 0x0a) return "image/png";
			if (length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "image/jpeg";
			if (length >= 4 && bytes[0] === 0x47 && bytes[1] === 0x49 && bytes[2] === 0x46 && bytes[3] === 0x38) return "image/gif";
			if (length >= 12 && bytes[0] === 0x52 && bytes[1] === 0x49 && bytes[2] === 0x46 && bytes[3] === 0x46 && bytes[8] === 0x57 && bytes[9] === 0x45 && bytes[10] === 0x42 && bytes[11] === 0x50) return "image/webp";
			return void 0;
		}
		/**
		 * Media type of one browser file: the declared type when it is one of the
		 * accepted four, otherwise the type its own signature declares.
		 * @param file - the browser file (Blob-shaped; only `slice` is relied on).
		 * @returns the media type, or undefined when neither source identifies one.
		 */
		async function resolveImageMediaType(file) {
			if (isImageMediaType(file.type)) return file.type;
			let head;
			try {
				head = new Uint8Array(await file.slice(0, IMAGE_HEAD_BYTES).arrayBuffer());
			} catch {
				return void 0;
			}
			return mediaTypeFromImageHeader(head);
		}
		/**
		 * The same file carrying the media type its own signature declares, so every
		 * later consumer agrees: `createDrafts` picks the image draft path from
		 * `File.type`, `imageLimits` counts images by it, and `encodeImage` writes it
		 * on the wire as the mediaType the host re-checks against the bytes.
		 * @param file - the browser file.
		 * @returns the original file, or a re-typed view of it when detection won.
		 */
		async function adoptImageMediaType(file) {
			const mediaType = await resolveImageMediaType(file);
			if (mediaType === void 0 || mediaType === file.type) return file;
			return new File([file], file.name, {
				type: mediaType,
				lastModified: file.lastModified
			});
		}
		/**
		 * Wire media type of one image file. An unidentifiable file throws the same
		 * error the declared-type whitelist threw, so its rejection is unchanged.
		 * @param file - the browser file.
		 * @returns the media type its declaration or its bytes select.
		 */
		async function imageMediaTypeOf(file) {
			const mediaType = await resolveImageMediaType(file);
			if (mediaType === void 0) throw new UnsupportedImageMediaTypeError(file.type);
			return mediaType;
		}
""",
    "Android's picker hands the browser an empty",
    "P31a 图片类型：空/未知 MIME 按 magic 判（12 字节）",
)
patch(
    "@deepseek-ai/dsh-client-ui-conversation/lib/client.js",
    """			async encodeImage(file) {
				return {
					mediaType: imageMediaType(file.type),
""",
    """			async encodeImage(file) {
				return {
					mediaType: await imageMediaTypeOf(file),
""",
    "mediaType: await imageMediaTypeOf(file)",
    "P31b 上传用的 mediaType 跟着检测结果走（不然服务端按字节复核仍会对不上）",
)
patch(
    "@deepseek-ai/dsh-client-ui-conversation/lib/client.js",
    """			const intakeFiles = (0, react.useCallback)((files, directories) => {
				if (locked || machineBusy || subagent !== null && !continuable || addFiles === void 0 || files.length === 0) return;
""",
    """			const intakeFiles = (0, react.useCallback)(async (files, directories) => {
				if (locked || machineBusy || subagent !== null && !continuable || addFiles === void 0 || files.length === 0) return;
				/* Everything below classifies synchronously from `File.type` -- `addFiles`
				 * starts the image draft path, the limits count images, the continuable gate
				 * rejects non-images -- so the picker's wrong MIME is corrected once, here,
				 * before any of them runs. Nothing is read when the type is already good. */
				const adopted = await Promise.all(files.map(adoptImageMediaType));
				if (adopted.some((file, index) => file !== files[index])) {
					if (directories !== void 0) directories = new Set([...directories].map((entry) => adopted[files.indexOf(entry)] ?? entry));
					files = adopted;
				}
""",
    "const adopted = await Promise.all(files.map(adoptImageMediaType));",
    "P31c 收文件闸门先补类型（addFiles / 数量限制 / 可续会话判据都读 File.type）",
)

# ── P32: 载荷里的纯 JS sharp 解不开真机拍的 JPEG（相机 / MIUI 截图）────────────
# 现场（真机 1.0.20-beta1）：相机照片和 MIUI 截图发不出去，界面报
#   「仅支持 PNG、JPG、WebP、GIF 格式的图片」；PNG 截图正常。
# 上一轮（report-p31-ineffective.md）已确认 P31 没问题、文案来自 host 侧：
#   dsh-attachment-local 的 inspectMetadata → detectImage 会真的把整张图解码一遍
#   （metadata + raw().toBuffer()），用的是载荷里手工换上的纯 JS sharp
#   （0.35.5-purejs，原版在旁路 sharp.glibc-original）。它抛错就变成
#   INVALID_IMAGE，客户端再把 INVALID_IMAGE 渲染成 image.unsupportedType，
#   于是「解码器解不开」被说成「类型不支持」，误导了两轮排查。
#
# 实测（真机样张，直接调宿主 prepareImageFile）：
#   小米相机 3072×4096/4096×3072 → INVALID_IMAGE cause=JPEG: missing quantisation table 1
#   MIUI 截图 1200×2608/2608×1200（DRI=75/163）→ INVALID_IMAGE cause=JPEG: invalid Huffman code in entropy data
#   同一批文件 ImageMagick/libjpeg 全部正常解码 → 文件没问题，是解码器的两个 bug：
#
# A. DQT 段只读了第一张表。小米相机把 tq=0（亮度）和 tq=1（色度）塞进**同一个**
#    DQT 段（段长 130 = 2 + 2×(1+64)），旧代码只 buildQuantTable(seg) 一次，
#    tq=1 永远没注册 → 扫描开始时 "missing quantisation table 1"。
# B. restart 边界没有强制重同步。旧 BitReader.restart() 只在「reader 恰好已经
#    撞见 RST 标记」时才跳过它；如果某个 restart 区间的最后一个 MCU 正好落在
#    字节边界上，reader 从没 peek 过标记，于是复位被跳过、FF D0-D7 被当成熵
#    数据喂进 Huffman 解码器 → "invalid Huffman code in entropy data"。
#    本机 2178 个基线 JPEG 里有 884 个带 DRI，这条不修等于四成的图发不出去。
#
# 验证（16 张真机样张，修前/修后都跟 libjpeg 逐像素比）：
#   修前 14/16 FAIL，修后 16/16 OK，与 libjpeg 的 MAE 0.01-0.32（只是 IDCT/上采样
#   的取整差），12 个无 DRI 文件的解码结果与修前**逐字节相同**（没有回归）。
patch(
    "sharp/jpeg.js",
    """    if (marker === 0xdb) {
      const q = buildQuantTable(seg);
      quantTables[q.tq] = q.table;
    } else if (marker === 0xc4) {
""",
    """    if (marker === 0xdb) {
      /* P32a: one DQT segment may carry *every* quantisation table. Xiaomi's camera puts
       * luma (tq=0) and chroma (tq=1) into a single 128-byte segment, so reading seg[0]
       * alone left tq=1 unregistered and the scan died with "missing quantisation table 1"
       * -- on a file libjpeg decodes fine. Walk the whole segment instead: one pq/tq byte,
       * then 64 (8-bit) or 128 (16-bit) values per table. */
      let qoff = 0;
      while (qoff < seg.length) {
        const tableBytes = (seg[qoff] >> 4) === 1 ? 128 : 64;
        if (qoff + 1 + tableBytes > seg.length) throw new Error('JPEG: truncated quantisation table segment');
        const q = buildQuantTable(seg.subarray(qoff));
        quantTables[q.tq] = q.table;
        qoff += 1 + tableBytes;
      }
    } else if (marker === 0xc4) {
""",
    "P32a: one DQT segment may carry",
    "P32a DQT 段逐表读完（小米相机把两张表塞进同一个 DQT 段）",
)
patch(
    "sharp/jpeg.js",
    """  /** Drop the partial byte and skip a restart marker if one is pending. */
  restart() {
    this.bitCnt = 0;
    this.bitBuf = 0;
    if (this.marker) {
      const m = this.marker;
      this.marker = 0;
      this.pos += 2;
      this.eof = false;
      void m;
      return true;
    }
    return false;
  }
""",
    """  /**
   * P32b: drop the padding bits and consume the RSTn marker that must sit at the next byte
   * boundary. The encoder pads the last byte of an interval with 1-bits and then writes
   * FF D0-D7, but whether fill() already peeked at that marker depends on where the last
   * MCU happened to end. When it ended exactly on the byte boundary nothing was ever
   * peeked, the old code skipped the resync entirely, and the marker bytes were handed to
   * the Huffman decoder as entropy data -- "invalid Huffman code in entropy data" on every
   * MIUI screenshot (DRI 75/163) and on camera frames that carry a DRI.
   * Returns the consumed marker number, or 0 when no marker was found.
   */
  restart() {
    this.bitCnt = 0;
    this.bitBuf = 0;
    if (this.marker) {
      const m = this.marker;
      this.marker = 0;
      this.pos += 2; // fill() backed up to the 0xFF, so neither marker byte was consumed
      this.eof = false;
      return m;
    }
    let p = this.pos;
    // Repeated 0xFF bytes before a marker are legal fill bytes, not entropy data.
    while (p + 1 < this.data.length && this.data[p] === 0xff && this.data[p + 1] === 0xff) p++;
    if (p + 1 < this.data.length && this.data[p] === 0xff && this.data[p + 1] >= 0xd0 && this.data[p + 1] <= 0xd7) {
      const m = this.data[p + 1];
      this.pos = p + 2;
      this.eof = false;
      return m;
    }
    return 0;
  }
""",
    "P32b: drop the padding bits and consume the RSTn marker",
    "P32b restart 边界强制字节对齐并吃掉 FF D0-D7（只有 peek 到标记才跳的老逻辑）",
)
patch(
    "sharp/jpeg.js",
    """  for (mcuIndex = 0; mcuIndex < totalMcus; mcuIndex++) {
    if (resetInterval && mcuIndex > 0 && mcuIndex % resetInterval === 0) {
      if (br.restart()) {
        for (const c of scanComponents) c.pred = 0;
        eobrun = 0;
      }
    }
""",
    """  for (mcuIndex = 0; mcuIndex < totalMcus; mcuIndex++) {
    if (resetInterval && mcuIndex > 0 && mcuIndex % resetInterval === 0) {
      /* P32b: the interval ends here whether or not the reader ever peeked at the marker,
       * so the resync must not be conditional: byte-align, consume RSTn, then reset the DC
       * predictors and the EOB run -- that is the whole point of a restart interval
       * (T.81 F.2.2.4 / G.1.2.3). Dn is accepted as-is: those numbers are interchangeable
       * for decoding, and a few encoders restart the counter on every scan. */
      br.restart();
      for (const c of scanComponents) c.pred = 0;
      eobrun = 0;
    }
""",
    "P32b: the interval ends here whether or not the reader ever peeked",
    "P32b 每个 restart 边界无条件复位 DC 预测 / EOB run",
)

# ── P33: 「解码失败」和「类型不支持」共用一句文案，把人往错的地方带 ────────────
# 上一轮排查最大的误导源：host 的 INVALID_IMAGE（解码器说这张图不合法）和客户端
# 自己的类型白名单拒绝（image.unsupportedType）显示的是**一模一样**的一句话，
# 于是「相机照片类型正确也报这句」看起来像客户端闸门坏了，实际是解码器。
# 这里只改文案映射，不动任何校验：解码失败单独一句、中英文都补；类型不支持
# 保持原句（它才是真的在说支持哪四种格式）。IMAGE_TYPE_MISMATCH 仍归到类型那句
# —— 声明类型和字节对不上，用户能做的还是「换一张/重选」。
patch(
    "@deepseek-ai/dsh-client-ui-conversation/lib/client.js",
    """			"image.unsupportedType": "仅支持 PNG、JPG、WebP、GIF 格式的图片",
""",
    """			"image.unsupportedType": "仅支持 PNG、JPG、WebP、GIF 格式的图片",
			"image.decodeFailed": "图片解码失败（格式异常），请换一张图或重新导出后再试",
""",
    "图片解码失败（格式异常）",
    "P33a 解码失败的中文文案（与「类型不支持」分开）",
)
patch(
    "@deepseek-ai/dsh-client-ui-conversation/lib/client.js",
    """			"image.unsupportedType": "Only PNG, JPG, WebP, and GIF images are supported",
""",
    """			"image.unsupportedType": "Only PNG, JPG, WebP, and GIF images are supported",
			"image.decodeFailed": "Image could not be decoded (unexpected format); try another image or re-export it",
""",
    "Image could not be decoded (unexpected format)",
    "P33b 解码失败的英文文案",
)
patch(
    "@deepseek-ai/dsh-client-ui-conversation/lib/client.js",
    """				case "INVALID_IMAGE":
				case "IMAGE_TYPE_MISMATCH": return t("image.unsupportedType");
""",
    """				/* P33: a decode failure and a type rejection used to share one sentence, so a
				 * camera JPEG the host decoder choked on was reported as "only PNG/JPG/WebP/GIF
				 * are supported" -- which is exactly what sent the last investigation after the
				 * wrong gate. Say which of the two actually happened. */
				case "INVALID_IMAGE": return t("image.decodeFailed");
				case "IMAGE_TYPE_MISMATCH": return t("image.unsupportedType");
""",
    'case "INVALID_IMAGE": return t("image.decodeFailed");',
    "P33c INVALID_IMAGE 改用解码失败文案（IMAGE_TYPE_MISMATCH 保持类型那句）",
)

# ── P34: Android 上没有「在文件管理器中显示」的落点 —— 这个动作要隐藏，不是置灰 ──
# 现场（Issue #4 第 7 条，1.0.20 只修好一半）：卡片渲染正常了（P29 让客户端认
# fileManager "android"），点卡片本体也能预览；但点「显示文件位置」/文件夹图标仍然
# toast「无法显示文件位置，请重试」+ 卡片红字「无法在文件管理器中显示，请重试」。
# 底层 GET /device/open?path=… 返回 200 并弹出系统分享面板 —— 说明「打开」这条已经
# 落到 dsh-open，缺的是 reveal 在 Android 上根本没有落点：没有文件管理器可开，
# App 也没有 reveal intent。P15b 把 reveal 也接去 dsh-open 只能拉起分享面板，
# 语义不对，失败时还照样按 revealError 报错。
# 选择（用户定的方案 1）：Android 上**不渲染** reveal，只保留「打开」。
# 置灰不算修 —— 一个永远点不动的按钮比没有这个按钮更糟。
#
# 动作到底在哪渲染（读代码的结论，不是猜的）：
#   dsh-client-ui-deliverables  只把 actions 交给插槽：
#       renderSlot("deliverables.file.actions", { actionUrl, available, pending, onAction })
#   dsh-client-ui-open-in-app    插槽的渲染方 FileRouteAction -> OpenTargetButton(kind:"file")：
#       菜单项 = applications.map(「用 X 打开」) + footer 的 reveal；
#       没有可用应用时 reveal 就是主按钮（桌面上那个文件夹图标）。
# 所以 P34 只能是这两处的最小改动：
#   a) deliverables 把 P29 已经拿到的判据（host.fileManager）作为 reveal:false 传下去；
#   b) open-in-app 按这个标记**只**去掉 reveal 那一项：主按钮退回「打开」，
#      应用列表、列表失败重试、chevron 都照旧。
# 不在客户端新造探测（不看 UA、不看平台名）—— fileManager 是唯一判据。
patch(
    "@deepseek-ai/dsh-client-ui-deliverables/lib/client.js",
    """							actions: renderSlot("deliverables.file.actions", {
								actionUrl: presentedFileUrl(sessionId, file.seq, file.index),
								available: host !== null && host !== "error" && host.available,
""",
    """							/* P34: android hides reveal -- host.fileManager is the judgement P29 already
							 * established ("android" is a real answer, not a malformed one). There is no
							 * file manager to reveal into on Android, and the App exposes no reveal intent,
							 * so the slot is told to drop that one action instead of drawing a folder button
							 * whose every click ends in revealError. Nothing else on the card changes. */
							actions: renderSlot("deliverables.file.actions", {
								actionUrl: presentedFileUrl(sessionId, file.seq, file.index),
								available: host !== null && host !== "error" && host.available,
								reveal: host === null || host === "error" ? true : host.fileManager !== "android",
""",
    'P34: android hides reveal',
    "P34a 交付卡片把 fileManager 判据传给动作插槽（android 时 reveal:false）",
)
patch(
    "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js",
    """			const hasMenu = props.loading === true || props.failed || applications.length + (kind === "file" ? 1 : 0) > 1;
			const revealDefault = kind === "file" && preferred === void 0 && props.loading !== true;
			const primaryLabel = preferred === void 0 ? t("path.reveal") : t("open.title", { app: preferred.name });
""",
    """			/* P34: android hides reveal -- callers pass reveal:false when the Host reports
			 * fileManager "android". Only the reveal entry goes away: the application list,
			 * its retry entry, and the plain "open" fallback keep rendering. */
			const revealOffered = kind !== "file" || props.reveal !== false;
			const hasMenu = props.loading === true || props.failed || applications.length + (kind === "file" && revealOffered ? 1 : 0) > 1;
			const revealDefault = kind === "file" && revealOffered && preferred === void 0 && props.loading !== true;
			const primaryLabel = preferred === void 0 ? t(revealOffered ? "path.reveal" : "path.open") : t("open.title", { app: preferred.name });
""",
    'P34: android hides reveal',
    "P34b 渲染层：revealOffered 决定主按钮是「显示文件位置」还是「打开」，并把它计入菜单项数",
)
patch(
    "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js",
    """				footer: kind === "file" ? [{
					id: "reveal",
""",
    """				footer: kind === "file" && revealOffered ? [{
					id: "reveal",
""",
    'footer: kind === "file" && revealOffered ? [{',
    "P34c 菜单 footer 里的 reveal 项按 revealOffered 决定是否出现",
)
patch(
    "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js",
    """			const association = useFileApplications(props.actionUrl, queryRoute, props.available);
			if (!props.available) return null;
			return (0, react_jsx_runtime.jsx)(OpenTargetButton, {
				kind: "file",
				applications: association.apps,
""",
    """			const association = useFileApplications(props.actionUrl, queryRoute, props.available);
			if (!props.available) return null;
			return (0, react_jsx_runtime.jsx)(OpenTargetButton, {
				kind: "file",
				reveal: props.reveal !== false,
				applications: association.apps,
""",
    'reveal: props.reveal !== false,',
    "P34d 卡片动作把 reveal 标记交给渲染层（其它调用方不传 = 保持原样）",
)

# ── P34e: review 面板那处也没传 reveal —— 同一个插槽，同一个按钮 ────────────
# beta1 的残留（Issue #4 第 7 条只修到卡片那处）：review 面板的动作插槽叫
# deliverables.review.file.actions，渲染方仍是 open-in-app 的同一个 FileRouteAction
# （client.js 里两个名字都注册到它），但 ReviewTab 只传了 actionUrl/available/
# pending/onAction —— 没有 reveal。于是 open-in-app 的 props.reveal 是 undefined，
# P34b 的 revealOffered 退化成 true，review 面板的主按钮照旧写「显示文件位置」，
# 点下去还是同一个 revealError。用户报的「同一插槽」边界就是这里。
# 判据与 P34a 同源：ReviewTab 顶部已经为 `native` 读过一次 host，这里直接用它，
# 不在客户端新造探测。
patch(
    "@deepseek-ai/dsh-client-ui-deliverables/lib/client.js",
    """										available: native,
										pending: phase === "opening" || phase === "revealing",
										onAction: (action, application) => openChanged(sessionId, seq, index, action, application)
""",
    """										available: native,
										/* P34e: android hides reveal -- the review pane renders the same slot as the card, so it
										 * takes the same judgement: host.fileManager is the Host's own answer, already read here
										 * for `native`, and "android" means there is no file manager to reveal into. No probe. */
										reveal: host === null || host === "error" ? true : host.fileManager !== "android",
										pending: phase === "opening" || phase === "revealing",
										onAction: (action, application) => openChanged(sessionId, seq, index, action, application)
""",
    "P34e: android hides reveal",
    "P34e review 面板把同一判据传给同一个插槽（android 时 reveal:false）",
)

# ── P35: 预览页的 reveal 仍在 / Android 的「打开」失败说不清是哪一步 ─────────
# Issue #4 第二轮（1.0.21 验完）报的两处，加上一条同族的静默失败：
#   a) dsh-client-ui-open-in-app 的 FileOpenTarget 是 OpenTargetButton 的第三个调用方，
#      只有它没转发 reveal（FileRouteAction 有、OpenTargetButton 自带判据）。于是
#      props.reveal 恒 undefined，判据 `props.reveal !== false` 永远成立，文档预览
#      顶栏的文件夹按钮照旧渲染 —— 点一次错一次。OpenPathEmptyAction 是
#      jsx(FileOpenTarget, {...props, empty: true})，修好 a 就自动跟上。
#   b) 调用方（sidebar-documentpreview 的 TextPreview）拿不到 host 对象：它 inject 的
#      是 slots/locale/sidebarRightTabs/remote/remote.workspaceFiles/configForms/resources，
#      没有 host，也没有 workspaceDesktop 这个 Remote（只有 canOpenWorkspacePath 一个
#      布尔）。所以判据放在渲染方：open-in-app 自己读一次的 present.host（P29/P34 用的
#      同一个路由、同一个 fileManager 字段），不在客户端新造探测；显式传 reveal 的调用方
#      仍然优先。
#   c) Android 上「打开」= 本 App 的系统分享面板：present.open -> openWorkspacePath ->
#      openNativePathWithIntent 的 android 分支 -> usr/bin/dsh-open -> /device/open。
#      这条链本身是通的（容器内实测 POST /api/present.open 四个坐标全 204），但任何一步
#      失败都被压成同一个 500 +「打开失败，请重试」——连 dsh-open 的退出码都丢了。
#      P35c 只做一件桌面端看不见的事：Android 分支的失败带上失败步骤，宿主回 502 说明
#      停在哪一步，客户端照实说。桌面上 open/reveal 的命令、路径、顺序一个字没动。
#
# 顺带查明、本轮不修的：openNativeFileApplication 的 android 分支会走 `open -a`（macOS
# 的命令），但 Android 上 applications 列表恒为空（queryFileApplications 对 android 返回
# []），这条分支今天不可达，故只记在这里，不扩大改动面。
patch(
    "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js",
    """			return (0, react_jsx_runtime.jsx)(OpenTargetButton, {
				kind: "file",
				applications: apps,
""",
    """			return (0, react_jsx_runtime.jsx)(OpenTargetButton, {
				kind: "file",
				/* P35a: forward the reveal decision. FileOpenTarget is the third caller of
				 * OpenTargetButton and the only one that dropped it, so props.reveal stayed undefined
				 * here and the guard in OpenTargetButton (`props.reveal !== false`) never fired --
				 * which is why the document-preview header kept its folder button on Android. */
				reveal: props.reveal !== false,
				applications: apps,
""",
    'P35a: forward the reveal decision',
    "P35a 文档预览的 FileOpenTarget 把 reveal 转给 OpenTargetButton",
)
patch(
    "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js",
    """		/** Page-lifetime desktop availability and the open/reveal carrier shared by every path control. */
""",
    """		/* P35b: the Host's destination metadata, browser-relative. This is the same route --
		 * and the same `fileManager` field -- P29/P34 read for delivery cards; the document
		 * preview receives no Host object (its plugin injects no host/desktop service and the
		 * Session Remote exposes canOpenWorkspacePath, a boolean, not the destination), so the
		 * renderer asks the Host instead of inventing a probe. The value is the same
		 * browser-relative form deliverables derives with PRESENT_HOST_PATH.slice(1) -- drop the
		 * "api/" prefix and the read 404s, which silently restores the old behaviour. */
		const PRESENT_HOST_ROUTE = "api/present.host";
		/** Page-lifetime desktop availability and the open/reveal carrier shared by every path control. */
""",
    "P35b: the Host's destination metadata",
    "P35b 预览页的判据来源：宿主自己的 present.host（不在客户端新造探测）",
)
patch(
    "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js",
    """			/** Whether the Host desktop can open paths; null until the Host answered, false also after a failed read. */
			desktop = (0, _deepseek_ai_dsh_client_store.createSnapshotStore)(null);
""",
    """			/** Whether the Host desktop can open paths; null until the Host answered, false also after a failed read. */
			desktop = (0, _deepseek_ai_dsh_client_store.createSnapshotStore)(null);
			/* P35b: the Host's file-manager answer ("android" means there is no file manager to
			 * reveal into). null = not answered yet or the route is not served here. */
			fileManager = (0, _deepseek_ai_dsh_client_store.createSnapshotStore)(null);
""",
    "P35b: the Host's file-manager answer",
    "P35b 渲染方保存宿主的 fileManager（null = 未答/无此路由）",
)
patch(
    "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js",
    """			async run() {
				let available = false;
				try {
					const result = await this.remote.session.canOpenWorkspacePath();
					available = result.ok && result.value;
				} catch {}
				this.desktop.set(available);
			}
""",
    """			async run() {
				let available = false;
				try {
					const result = await this.remote.session.canOpenWorkspacePath();
					available = result.ok && result.value;
				} catch {}
				this.desktop.set(available);
				/* P35b: read the destination in the same page-lifetime pass. A missing route
				 * (deliverables not deployed) leaves it null, and null keeps the old behaviour. */
				let manager = null;
				try {
					const response = await fetch(PRESENT_HOST_ROUTE);
					if (response.ok) {
						const destination = await response.json();
						if (destination !== null && typeof destination === "object" && typeof destination.fileManager === "string") manager = destination.fileManager;
					}
				} catch {}
				this.fileManager.set(manager);
			}
""",
    'P35b: read the destination in the same page-lifetime pass',
    "P35b 控制器一次读里同时取回 fileManager",
)
patch(
    "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js",
    """			const pathInjected = () => ({
				hooks: { openInAppDesktop: paths.desktop },
""",
    """			const pathInjected = () => ({
				/* P35b: the document-preview slots get the same two hooks the card slots get, so the
				 * reveal decision is made where the button is rendered. */
				hooks: { openInAppDesktop: paths.desktop, openInAppFileManager: paths.fileManager },
""",
    'P35b: the document-preview slots get the same two hooks',
    "P35b 文档预览插槽多注入一个 fileManager hook",
)
patch(
    "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js",
    """		function FileOpenTarget(props) {
			const desktop = props.useOpenInAppDesktop((value) => value);
""",
    """		function FileOpenTarget(props) {
			const desktop = props.useOpenInAppDesktop((value) => value);
			const fileManager = props.useOpenInAppFileManager((value) => value);
""",
    'const fileManager = props.useOpenInAppFileManager((value) => value);',
    "P35b FileOpenTarget 读宿主的 fileManager",
)
patch(
    "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js",
    """				reveal: props.reveal !== false,
				applications: apps,
""",
    """				/* P35b: an explicit caller still wins (the delivery card passes false on Android);
				 * otherwise the Host's own answer decides, and only "android" drops reveal. */
				reveal: props.reveal !== false && fileManager !== "android",
				applications: apps,
""",
    'reveal: props.reveal !== false && fileManager !== "android",',
    "P35b 预览页按宿主判据决定 reveal（android 时不渲染）",
)
patch(
    "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js",
    """			async openPath(path, action, application) {
				const request = action === "reveal" ? {
					path,
					action
				} : {
					path,
					...application === void 0 ? {} : { application }
				};
				let ok = false;
				try {
					ok = (await this.remote.session.openWorkspacePath(request)).ok;
				} catch {}
				return ok ? null : action === "open" ? "openError" : "revealError";
			}
""",
    """			async openPath(path, action, application) {
				const request = action === "reveal" ? {
					path,
					action
				} : {
					path,
					...application === void 0 ? {} : { application }
				};
				let ok = false;
				try {
					ok = (await this.remote.session.openWorkspacePath(request)).ok;
				} catch {}
				/* P35c: the preview header has no present.open coordinates, so this call is its only
				 * route to the opener. On Android that opener IS the share handoff (dsh-open ->
				 * /device/open); a failure is not a missing Host desktop and "try again" is not the
				 * advice to give. Desktop keeps its own two failure kinds. */
				const shareFailed = action === "open" && this.fileManager.getSnapshot() === "android";
				return ok ? null : action === "open" ? shareFailed ? "openShareFailed" : "openError" : "revealError";
			}
""",
    'shareFailed ? "openShareFailed" : "openError"',
    "P35c 预览页的打开失败同样不再说「请重试」",
)
patch(
    "@deepseek-ai/dsh-native-command/lib/index.js",
    """/** Dispatch one shell-free platform command for the requested open intent. */
""",
    """/**
* Report a failed usr/bin/dsh-open handoff with the step that failed.
*
* dsh-open is the Android launcher: it hands the path to /device/open, which starts the
* system share sheet. Its exit codes are the only place that says *where* the handoff
* stopped (3 no argument, 4 the path is not visible to the app, 5 no device token,
* 6 the device bridge refused it, 7 the HTTP call itself failed), and execFile turns
* them into a numeric `code` with the script's stderr attached. Dropping that here is
* what made every Android open failure surface as one opaque "open failed".
* @param intent - "open" or "reveal", the gesture that was attempted.
* @param path - absolute path handed to the launcher.
* @param error - the execFile failure.
* @returns an Error whose message names the step and whose `androidStage` is machine-readable.
*/
function androidHandoffFailure(intent, path, error) {
	const code = error !== null && typeof error === "object" && "code" in error ? error.code : void 0;
	const stderr = error !== null && typeof error === "object" && "stderr" in error && typeof error.stderr === "string" ? error.stderr.trim() : "";
	const stage = code === "ENOENT" ? "launcher" : code === 3 ? "args" : code === 4 ? "missing" : code === 5 ? "token" : code === 6 || code === 7 ? "bridge" : typeof code === "number" ? "launcher" : "unknown";
	const exit = code === void 0 ? "no exit code" : typeof code === "number" ? `exit ${code}` : String(code);
	const head = stderr.split("\\n")[0];
	const failure = new Error(`android ${intent}: dsh-open failed (${exit}) for ${path}${head === "" ? "" : `: ${head}`}`, { cause: error });
	failure.androidStage = stage;
	return failure;
}
/** Dispatch one shell-free platform command for the requested open intent. */
""",
    'androidHandoffFailure',
    "P35c dsh-open 失败带上「哪一步」（退出码 → stage）",
)
patch(
    "@deepseek-ai/dsh-native-command/lib/index.js",
    """	if (platform === "android") {
		/* dsh-open-open */
		await run("dsh-open", [path], signal, "hidden");
		return;
	}
""",
    """	if (platform === "android") {
		/* dsh-open-open */
		/* P35c: the launcher is the whole Android handoff -- keep its exit code and its
		 * stderr instead of collapsing them into the caller's generic failure. */
		try {
			await run("dsh-open", [path], signal, "hidden");
		} catch (error) {
			signal.throwIfAborted();
			throw androidHandoffFailure("open", path, error);
		}
		return;
	}
""",
    'P35c: the launcher is the whole Android handoff',
    "P35c Android 打开：保留 dsh-open 的退出码与 stderr",
)
patch(
    "@deepseek-ai/dsh-native-command/lib/index.js",
    """	if (manager === "android") {
		/* dsh-open-reveal */
		await run("dsh-open", [path], signal, "hidden");
		return;
	}
""",
    """	if (manager === "android") {
		/* dsh-open-reveal */
		/* P35c: same handoff, same diagnosis -- reveal is hidden on Android since P34, but
		 * the branch stays reachable from other surfaces and must not lie either. */
		try {
			await run("dsh-open", [path], signal, "hidden");
		} catch (error) {
			signal.throwIfAborted();
			throw androidHandoffFailure("reveal", path, error);
		}
		return;
	}
""",
    'P35c: same handoff, same diagnosis',
    "P35c Android reveal：同一套诊断",
)
patch(
    "@deepseek-ai/dsh-client-ui-deliverables/lib/index.js",
    """	const application = new URL(request.url).searchParams.get("application");
	await ctx.sessionController.openWorkspacePath({
		path,
		...action === "reveal" ? { action } : application === null ? {} : { application }
	}, request.signal);
	return new Response(null, {
		status: 204,
		headers: { "cache-control": "no-store" }
	});
""",
    """	const application = new URL(request.url).searchParams.get("application");
	/* P35c: on Android the opener is usr/bin/dsh-open -> the App's device bridge -> the
	 * system share sheet. A failure there is not "the Host desktop failed", and it used to
	 * surface as one opaque 500 (the browser could only say "open failed, try again").
	 * Answer 502 with the step that failed so the card can name it, and log the detail.
	 * Desktop behaviour is untouched: every other platform rethrows, and the 500/409/404
	 * handlings downstream are byte-identical. */
	try {
		await ctx.sessionController.openWorkspacePath({
			path,
			...action === "reveal" ? { action } : application === null ? {} : { application }
		}, request.signal);
	} catch (error) {
		request.signal.throwIfAborted();
		if (ctx.sessionController.workspaceDesktop().fileManager !== "android") throw error;
		const cause = error !== null && typeof error === "object" && "cause" in error ? error.cause : void 0;
		const stage = cause !== null && typeof cause === "object" && "androidStage" in cause && typeof cause.androidStage === "string" ? cause.androidStage : "unknown";
		const detail = cause instanceof Error ? cause.message : error instanceof Error ? error.message : String(error);
		console.warn("open-in-app: android " + action + " handoff failed (" + stage + "): " + detail);
		return Response.json({
			step: "android-open",
			stage,
			detail
		}, {
			status: 502,
			headers: { "cache-control": "no-store" }
		});
	}
	return new Response(null, {
		status: 204,
		headers: { "cache-control": "no-store" }
	});
""",
    'P35c: on Android the opener is usr/bin/dsh-open',
    "P35c 宿主侧：Android 打开失败回 502 + 失败步骤（桌面端仍走原路）",
)
patch(
    "@deepseek-ai/dsh-client-ui-deliverables/lib/client.js",
    """					if (!response.ok) phase = response.status === 422 ? "nativeUnavailable" : failure;
""",
    """					if (!response.ok) phase = response.status === 422 ? "nativeUnavailable" : failure;
					/* P35c: 502 carries the Android handoff step (see the Host half). Two outcomes are
					 * worth telling apart: the path is gone, or the share handoff itself was refused. */
					if (response.status === 502) {
						const refusal = await response.json().catch(() => void 0);
						if (refusal !== void 0 && refusal.step === "android-open") phase = refusal.stage === "missing" ? "androidOpenMissing" : "androidOpenFailed";
					}
""",
    'P35c: 502 carries the Android handoff step',
    "P35c 客户端：读 502 的失败步骤（missing / 其它）",
)
patch(
    "@deepseek-ai/dsh-client-ui-deliverables/lib/client.js",
    """				return phase === "opened" || phase === "revealed" ? null : action === "reveal" ? "revealError" : "openError";
""",
    """				/* P35c: a failed Android handoff must not come back as "open failed, try again" --
				 * neither retry nor "Host desktop" describes what happened. */
				return phase === "opened" || phase === "revealed" ? null : phase === "androidOpenMissing" ? "openMissing" : phase === "androidOpenFailed" ? "openShareFailed" : action === "reveal" ? "revealError" : "openError";
""",
    'P35c: a failed Android handoff must not come back',
    "P35c 客户端：失败回具体的文案键（不再一律 openError）",
)
patch(
    "@deepseek-ai/dsh-client-ui-deliverables/lib/client.js",
    """			const status = phase === void 0 ? cardDescription(file.description, metadata) : t(reveal === "directory" && phase === "revealed" ? "presented.directoryOpened" : reveal === "directory" && phase === "revealing" ? "presented.directoryOpening" : reveal === "directory" && phase === "revealError" ? "presented.directoryError" : `presented.${phase}`);
""",
    """			const status = phase === void 0 ? cardDescription(file.description, metadata) : t(reveal === "directory" && phase === "revealed" ? "presented.directoryOpened" : reveal === "directory" && phase === "revealing" ? "presented.directoryOpening" : reveal === "directory" && phase === "revealError" ? "presented.directoryError" : phase === "androidOpenMissing" ? "presented.androidOpenMissing" : phase === "androidOpenFailed" ? "presented.androidOpenFailed" : `presented.${phase}`);
""",
    'phase === "androidOpenMissing" ? "presented.androidOpenMissing"',
    "P35c 卡片：Android 失败的两种文案（文件不在了 / 分享通道没接住）",
)
patch(
    "@deepseek-ai/dsh-client-ui-deliverables/lib/client.js",
    """								"data-error": phase === "error" || phase === "revealError" || phase === "nativeUnavailable" ? true : void 0,
""",
    """								"data-error": phase === "error" || phase === "revealError" || phase === "nativeUnavailable" || phase === "androidOpenFailed" || phase === "androidOpenMissing" ? true : void 0,
""",
    '"androidOpenFailed" || phase === "androidOpenMissing" ? true',
    "P35c 卡片：两种 Android 失败都按错误样式显示",
)
patch(
    "@deepseek-ai/dsh-client-ui-deliverables/lib/client.js",
    """			"presented.nativeUnavailable": "此文件没有可用的主机路径，请在侧边栏预览",
""",
    """			"presented.nativeUnavailable": "此文件没有可用的主机路径，请在侧边栏预览",
			/* P35c: Android 的打开是「本 App 的分享通道」，失败要说清停在哪一步。 */
			"presented.androidOpenMissing": "打开失败：文件已不在原路径",
			"presented.androidOpenFailed": "打开失败：系统分享面板没有接住这个文件",
""",
    '"presented.androidOpenFailed": "打开失败：系统分享面板没有接住这个文件"',
    "P35c 中文文案：Android 打开失败的两种",
)
patch(
    "@deepseek-ai/dsh-client-ui-deliverables/lib/client.js",
    """			"presented.nativeUnavailable": "This file has no available Host path. Preview it in the sidebar.",
""",
    """			"presented.nativeUnavailable": "This file has no available Host path. Preview it in the sidebar.",
			/* P35c: Android opens through this App's share sheet; name the step that failed. */
			"presented.androidOpenMissing": "Could not open: the file is no longer at that path",
			"presented.androidOpenFailed": "Could not open: the share sheet did not accept this file",
""",
    '"presented.androidOpenFailed": "Could not open: the share sheet did not accept this file"',
    "P35c 英文文案：Android 打开失败的两种",
)
patch(
    "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js",
    """			"path.openError": "打开失败，请重试",
""",
    """			"path.openError": "打开失败，请重试",
			/* P35c: Android handoff failures -- "try again" is wrong advice for both. */
			"path.openMissing": "打开失败：文件已不在原路径",
			"path.openShareFailed": "打开失败：系统分享面板没有接住这个文件",
""",
    '"path.openShareFailed": "打开失败：系统分享面板没有接住这个文件"',
    "P35c 中文 toast 文案：不再一律「请重试」",
)
patch(
    "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js",
    """			"path.openError": "Could not open. Try again.",
""",
    """			"path.openError": "Could not open. Try again.",
			/* P35c: Android handoff failures -- "try again" is wrong advice for both. */
			"path.openMissing": "Could not open: the file is no longer at that path",
			"path.openShareFailed": "Could not open: the share sheet did not accept this file",
""",
    '"path.openShareFailed": "Could not open: the share sheet did not accept this file"',
    "P35c 英文 toast 文案：不再一律 Try again",
)

# ── 必成清单：没落地就不是"少个功能"，而是产物直接不可用 ────────────────────
# patch() 的失败默认只是记一行「跳过」，脚本照样退出 0 —— 以前正是这样让坏产物
# 一路走到打包。这里列出「缺了就废」的条目：只要它不是"已打过"，就以非 0 退出。
# 「已打过」是合法的（第二遍复验就是这个状态），所以只拦 missing / anchor。
MUST_APPLY = {
    "P25 fs/promises import 补 rename/copyFile/writeFile（P3a/P16d 要用，上游没有）":
        "新建会话第一次发布日志就抛 ReferenceError: rename is not defined，"
        "它非 LlmError，被包成 TRANSPORT 重试 5 次 —— 表现成「新会话发不出消息」",
    "P26 老 WebView 的 polyfill":
        "内核低于 Chrome 100 的设备上，客户端在 import 阶段就抛 "
        "\"x is not a function\"，页面永远停在「重新连接中」——"
        "服务器明明在跑，用户却完全用不了",
    # P27：Android 拒绝硬链接，附件发布整条链路死。图片与非图片各一条，
    # 少一条就是「图片能发、txt 不能发」这种更难查的半死状态。
    "P27a 附件对象发布：link 被拒时回落 rename":
        "Android 的 app 私有存储对 link(2) 回 EACCES，图片收发与 agent 读图全部失败"
        "（ATTACHMENT_WRITE_FAILED / 「Unable to persist attachment.」）",
    "P27b 附件别名发布：link 被拒时读字节再写（不能 rename）":
        "非图片文件（txt/pdf/zip）走 publishImmutableAlias，同一限制下仍然 "
        "ATTACHMENT_WRITE_FAILED —— 修好图片会让人以为「只剩图片能发」",
    "P27c alias 回落的 EEXIST 接回 digest 去重":
        "同一份文件发第二次（或重试）会撞上内容寻址的 target，copy 的 wx 直接抛 "
        "EEXIST → 又变成 ATTACHMENT_WRITE_FAILED（容器验证抓到的新回归）",
    # P28：僵尸租约会永久锁死一个会话（session/writer-held），且用户只能手工删目录。
    # P28a/b/c/e/f 是代码；P28d/P28g 只动注释，不进必成清单。
    "P28a 租约增加身份记录文件名与超时接管阈值":
        "P28 其余条目引用的 LEASE_START_FILENAME / LEASE_TAKEOVER_MS 不存在 → "
        "建租约时 ReferenceError，任何会话都写不进去",
    "P28b 判活改看 pid + /proc starttime 身份（EPERM 不再等于存活）":
        "EPERM 仍被当成「还活着」→ pid 被复用时租约永久不破，"
        "该会话冷启动后照样打不开（session/writer-held）",
    "P28c 读租约记录（pid + 身份 + 年龄）":
        "createLeaseDirectory 没有可用的 holder 记录 → 判活抛 ReferenceError，会话打不开",
    "P28e 接管判据：身份/超时，破锁前复核 pid + 身份 + inode":
        "接管逻辑没落地 → 僵尸租约只能手工 rm；落地了但不复核，"
        "又会误删接替者刚建的新租约（两个写者）",
    "P28f 建租约时写入身份 token":
        "新租约不记 starttime → 下游全退化成「不可识别、等 30 分钟」，"
        "用户报的那种锁死要等半小时才自愈",
    # P30：同一个坑在发布锁里（查 P28 时发现的另一处，也是本项目自己的代码）。
    "P30a 发布锁写入身份 token（pid + starttime）":
        "发布锁只记 pid，pid 被回收后没人能证明它不是原持有者",
    "P30b 发布锁判活也看身份（EPERM 不再单独等于存活）":
        "pid 被别的 uid 复用时 EPERM 被当成「还活着」→ `.publish.lock` 永久留在日志旁，"
        "之后每次 materialize 都在 500ms 后当成「别人先到」，新会话的日志再也建不出来",
    # P31：客户端三条判据共用同一份「浏览器给的 MIME」，少一条都不成：
    #   没有 a → b/c 引用不存在的函数，整个客户端 bundle 在调用处 ReferenceError；
    #   有 a 没 b → 判断放宽了但上传的 mediaType 还是空 → 服务端按字节复核对不上；
    #   有 a/b 没 c → 闸门仍按空 type 把它当普通文件，图片路径根本没进。
    "P31a 图片类型：空/未知 MIME 按 magic 判（12 字节）":
        "Android 选择器给出空 type/octet-stream 时，截图被当成「不是图片」，"
        "用户看到「仅支持 PNG、JPG、WebP、GIF 格式的图片」而内容其实是 PNG",
    "P31b 上传用的 mediaType 跟着检测结果走（不然服务端按字节复核仍会对不上）":
        "只改判断不改 wire 字段 → 服务端 inspectMetadata 用 sharp 按字节识别，"
        "与声明的 mediaType 不等就抛 IMAGE_TYPE_MISMATCH，界面还是同一句错",
    "P31c 收文件闸门先补类型（addFiles / 数量限制 / 可续会话判据都读 File.type）":
        "闸门不同步补类型 → 空 type 的截图走普通文件上传，图片草稿路径不触发，"
        "「按内容判图」等于没接上",
    # P32：真机上的图片发不出去，根因在 host 侧解码（不是 P31 那条客户端闸门）。
    # P32a/P32b 各修一类 JPEG；A 管相机，B 管带 restart interval 的（本机 884/2178）。
    "P32a DQT 段逐表读完（小米相机把两张表塞进同一个 DQT 段）":
        "只读第一张表 → tq=1 未注册 → 相机 JPEG 全部 INVALID_IMAGE"
        "（cause=JPEG: missing quantisation table 1），真机相机照片一张都发不出去",
    "P32b restart 边界强制字节对齐并吃掉 FF D0-D7（只有 peek 到标记才跳的老逻辑）":
        "restart 边界不重同步 → 标记被当熵数据 → 带 DRI 的 JPEG 全部"
        "INVALID_IMAGE（cause=JPEG: invalid Huffman code in entropy data）",
    "P32b 每个 restart 边界无条件复位 DC 预测 / EOB run":
        "复位依赖 br.restart() 的返回值 → 没 peek 到标记时预测器不复位，"
        "就算不抛错也是错像素（T.81 要求每个区间边界都复位）",
    # P33 只影响文案，但缺了解码失败就又说成「类型不支持」，下一个人还得再查一轮。
    "P33a 解码失败的中文文案（与「类型不支持」分开）":
        "解码失败仍显示「仅支持 PNG、JPG、WebP、GIF」，"
        "看起来像类型白名单拒绝，把排查带偏",
    "P33b 解码失败的英文文案":
        "英文界面同样只有一句 unsupportedType，解码失败无法与类型问题区分",
    "P33c INVALID_IMAGE 改用解码失败文案（IMAGE_TYPE_MISMATCH 保持类型那句）":
        "映射不拆 → 新加的中英文文案永远显示不出来",
    # P34：Issue #4 第 7 条。缺任何一条都不是「少个按钮」，而是那个必然失败的
    # 文件夹图标还在（或者主按钮还写着「显示文件位置」）。
    "P34a 交付卡片把 fileManager 判据传给动作插槽（android 时 reveal:false）":
        "卡片拿不到「这是 android」的判据 → reveal 照旧渲染，点一下还是那句话，"
        "第 7 条等于没修",
    "P34b 渲染层：revealOffered 决定主按钮是「显示文件位置」还是「打开」，并把它计入菜单项数":
        "只藏菜单项、不换主按钮 → 没有可用应用的 Android 上主按钮仍写着"
        "「显示文件位置」（文件夹图标），点一次错一次",
    "P34c 菜单 footer 里的 reveal 项按 revealOffered 决定是否出现":
        "菜单里还挂着 reveal → 收起状态看不到，展开菜单就又能点到，等于没隐藏",
    "P34d 卡片动作把 reveal 标记交给渲染层（其它调用方不传 = 保持原样）":
        "标记没传下去 → 渲染层永远按「有 reveal」走，前三条全白做",
    "P34e review 面板把同一判据传给同一个插槽（android 时 reveal:false）":
        "review 面板那处拿不到标记 → 主按钮仍写着「显示文件位置」，"
        "点下去还是同一个错，第 7 条只修了卡片那一半",
    # P35：Issue #4 第二轮。a/b 是同一个按钮的第四处漏（预览页），c 是 Android 上
    # 「打开」失败时唯一能说明白的一句话——缺了它，失败又变回「打开失败，请重试」。
    "P35a 文档预览的 FileOpenTarget 把 reveal 转给 OpenTargetButton":
        "预览页的 props.reveal 恒 undefined → 文件夹图标照旧渲染，"
        "点一次 toast 一次「无法显示文件位置」，第 ③ 条等于没修",
    "P35b 预览页按宿主判据决定 reveal（android 时不渲染）":
        "判据接不上渲染方 → P35a 转发的是 undefined → 仍然「永远显示」",
    "P35b 控制器一次读里同时取回 fileManager":
        "没有 fileManager → 渲染方无从判断 Android，预览页的 reveal 照旧渲染",
    "P35c dsh-open 失败带上「哪一步」（退出码 → stage）":
        "退出码被 execFile 丢掉 → 宿主只能说「path open failed」，"
        "客户端只能回「打开失败，请重试」，用户与排查的人都看不出停在哪一步",
    "P35c 宿主侧：Android 打开失败回 502 + 失败步骤（桌面端仍走原路）":
        "失败仍是 500 → 客户端分不出「文件不在了」和「分享通道没接住」，"
        "第 ① 条的要求（能看出是哪一步失败）没落地",
    "P35c 客户端：读 502 的失败步骤（missing / 其它）":
        "不读 502 → 新加的两种文案永远显示不出来，等于白改",
    "P35c 中文 toast 文案：不再一律「请重试」":
        "toast 仍回「打开失败，请重试」→ 那句误导文案还在",
}
# 光记账不够：note 误命中（本脚本栽过三次）会让条目报「已打过」而其实没改。
# 所以必成条目还要对着落盘结果验一遍。
MUST_STATE = [
    (
        "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
        "import { copyFile, link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rename, rm, stat, truncate, writeFile } from \"node:fs/promises\";",
        "第 4 行 import 必须同时含 copyFile / rename / writeFile"
        "（P3a 发布用 rename，P16d 重建用 writeFile 与 rename，修复前备份用 copyFile）",
    ),
    (
        "@deepseek-ai/dsh-web-frontend/dist/index.html",
        "dsh-android-polyfill v1",
        "入口 HTML 里必须有 P26 的 polyfill 段",
        "缺了它，老 WebView（< Chrome 100）上客户端根本起不来",
    ),
    (
        "@deepseek-ai/dsh-web-frontend/dist/index.html",
        "Promise.withResolvers !== 'function'",
        "polyfill 段里的 withResolvers 守卫必须真的在",
        "只匹配到注释、没匹配到实现，等于没打",
    ),
    # P27 落盘形态：两处回落必须**不同**，抄错的典型是给 alias 也上 rename。
    (
        "@deepseek-ai/dsh-attachment-local/lib/index.js",
        "if (!stagedConsumed) await unlink(staged.path);",
        "publishStagedObject 必须有 rename 回落（stagedConsumed 置位的那个分支）",
        "缺了它图片发布仍然 EACCES → 全部附件收发失败（Issue #4）",
    ),
    (
        "@deepseek-ai/dsh-attachment-local/lib/index.js",
        "await writeFile(target, await readFile(source), { mode: 384, flag: \"wx\" });",
        "publishImmutableAlias 的回落必须是「读字节再写」，不是 rename",
        "写成 rename 会把 canonical object 搬走 —— 别的 alias 指向空，"
        "或更糟：文件在别处丢了内容",
    ),
    (
        "@deepseek-ai/dsh-attachment-local/lib/index.js",
        "if (copyCode !== \"EEXIST\") throw copyError;",
        "alias 回落的 copy 必须把 EEXIST 接回 digest 去重（P27c）",
        "不接的话，同一个文件发第二次会在 Android 上报 ATTACHMENT_WRITE_FAILED",
    ),
    # P28 落盘形态：三符号 + 身份 token 的写入点。
    (
        "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
        "const LEASE_START_FILENAME = \"start\";",
        "租约必须有身份记录文件名（P28a）",
        "没有它，判活回退成只看 pid —— 就是被 EPERM 卡死的那个 bug",
    ),
    (
        "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
        "async function leaseHolderVerdict(record) {",
        "租约判活必须走 leaseHolderVerdict（P28b）",
        "缺了它 isProcessAlive 的 EPERM 分支还在，僵尸租约照旧",
    ),
    (
        "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
        "await writeFile(join(dir, LEASE_START_FILENAME), start);",
        "建租约时必须写下 starttime 身份（P28f）",
        "不写 token，所有租约都只能按 30 分钟超时接管，等于把修复等到超时",
    ),
    # P30 落盘形态：发布锁也必须记身份、也必须按身份判活。
    (
        "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
        "const recordedStart = start > 0 ? String(start) : undefined;",
        "发布锁判活必须看身份（P30b）",
        "只看 pid 的话，pid 被回收后 `.publish.lock` 永久不破，新会话的日志建不出来",
    ),
    (
        "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
        "const start = await readProcessStartTime(process.pid);",
        "发布锁必须写下 starttime（P30a）",
        "不写身份，P30b 永远走「不可识别、维持原判」，僵尸锁照旧",
    ),
    # P29 落盘形态：客户端白名单。这条不进 MUST_APPLY（卡片文案级，
    # 缺了不会让产物不可用），但落盘要能一眼看出有没有打上。
    (
        "@deepseek-ai/dsh-client-ui-deliverables/lib/client.js",
        "host.fileManager === \"android\"",
        "客户端 isPresentedHost 必须认 android（P29）",
        "不认的话服务端答得再对，卡片也只显示「无法读取主机桌面信息 · 重试」",
    ),
    # P31 落盘形态：magic 判图要在，且三条接点都要真的接上。
    (
        "@deepseek-ai/dsh-client-ui-conversation/lib/client.js",
        "function mediaTypeFromImageHeader(bytes) {",
        "客户端必须能按 magic 判图（P31a）",
        "没有它，选择器给空 type 的截图仍然被判成「不支持的类型」",
    ),
    (
        "@deepseek-ai/dsh-client-ui-conversation/lib/client.js",
        "mediaType: await imageMediaTypeOf(file)",
        "上传的 mediaType 必须取检测结果（P31b）",
        "取 file.type 的话空 type 仍然是空/抛错，服务端按字节复核也过不去",
    ),
    (
        "@deepseek-ai/dsh-client-ui-conversation/lib/client.js",
        "const adopted = await Promise.all(files.map(adoptImageMediaType));",
        "收文件闸门必须先补类型（P31c）",
        "闸门按旧 type 分类，截图会走普通文件上传，图片路径不触发",
    ),
    # P32 落盘形态：两个解码 bug 各一条，外加「边界无条件复位」这条——
    # 只改 restart() 不改 decodeScan，标记会被吃掉但预测器不复位（错像素）。
    (
        "sharp/jpeg.js",
        "let qoff = 0;",
        "DQT 段必须逐表读完（P32a）",
        "只读首表的话，小米相机那种「两张表一个段」的文件仍然 missing quantisation table 1",
    ),
    (
        "sharp/jpeg.js",
        "const m = this.data[p + 1];",
        "restart 边界必须自己找 RST 标记（P32b）",
        "依赖 fill() 先撞见标记的话，字节对齐的边界仍会把 FF D0-D7 当熵数据",
    ),
    (
        "sharp/jpeg.js",
        "      br.restart();\n      for (const c of scanComponents) c.pred = 0;",
        "每个 restart 边界无条件复位预测器（P32b）",
        "复位挂在返回值上时，没撞见标记的边界不会清 DC 预测，解出来是错像素",
    ),
    # P33 落盘形态：两条文案 + 新的映射。中英文都要在，且映射要真的指向新键。
    (
        "@deepseek-ai/dsh-client-ui-conversation/lib/client.js",
        "\"image.decodeFailed\": \"图片解码失败（格式异常）",
        "中文要有「解码失败」文案（P33a）",
        "缺了这条，解码失败只能借用「仅支持 PNG/JPG/WebP/GIF」那句",
    ),
    (
        "@deepseek-ai/dsh-client-ui-conversation/lib/client.js",
        "\"image.decodeFailed\": \"Image could not be decoded",
        "英文要有「解码失败」文案（P33b）",
        "英文界面会退回显示 key 本身或沿用 unsupportedType",
    ),
    (
        "@deepseek-ai/dsh-client-ui-conversation/lib/client.js",
        "case \"INVALID_IMAGE\": return t(\"image.decodeFailed\");",
        "INVALID_IMAGE 必须映射到解码失败文案（P33c）",
        "映射没拆的话，两条新文案都显示不出来，用户看到的还是那句误导人的话",
    ),
    # P34 落盘形态：判据从卡片传到渲染层，且只挡 reveal 这一项（应用列表、
    # 列表失败重试、主按钮的「打开」回落都要照旧）。
    (
        "@deepseek-ai/dsh-client-ui-deliverables/lib/client.js",
        "reveal: host === null || host === \"error\" ? true : host.fileManager !== \"android\",",
        "卡片必须按 fileManager 决定是否给 reveal（P34a）",
        "不传这个标记，渲染层照旧画「显示文件位置」，Android 上点一次错一次",
    ),
    (
        "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js",
        "const revealOffered = kind !== \"file\" || props.reveal !== false;",
        "渲染层必须有 revealOffered 这一层判断（P34b）",
        "没有它，主按钮在 Android 上仍然是「显示文件位置」",
    ),
    (
        "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js",
        "reveal: props.reveal !== false,",
        "卡片动作必须把 reveal 标记交给渲染层（P34d）",
        "标记丢了等于前两条没接线",
    ),
    (
        "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js",
        "footer: kind === \"file\" && revealOffered ? [{",
        "菜单 footer 的 reveal 项必须跟 revealOffered 走（P34c）",
        "菜单里留着 reveal，展开菜单仍然能点到那个必然失败的动作",
    ),
    # P34e 落盘形态：同一插槽的第二处调用也要带判据，且必须紧跟在 `available: native`
    # 这行后面（review 面板与卡片传的 available 来源不同，位置本身就是证据）。
    (
        "@deepseek-ai/dsh-client-ui-deliverables/lib/client.js",
        "available: native,\n\t\t\t\t\t\t\t\t\t\t/* P34e: android hides reveal",
        "review 面板那处也必须把 fileManager 判据传给插槽（P34e）",
        "不传的话 review 面板的主按钮仍是「显示文件位置」，点一次错一次",
    ),
    # P35 落盘形态：预览页那一处必须转发并合成判据，且判据必须来自宿主自己
    # 的 present.host（不是新造探测）；Android 打开失败必须带上 stage 并回 502。
    (
        "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js",
        "const PRESENT_HOST_ROUTE = \"api/present.host\";",
        "预览页必须能读到宿主的 fileManager（P35b）",
        "没有它，预览页只能靠 canOpenWorkspacePath 那个布尔，"
        "分不出「有桌面」还是「有分享面板」",
    ),
    (
        "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js",
        "reveal: props.reveal !== false && fileManager !== \"android\",",
        "预览页的 reveal 必须按宿主判据决定（P35a + P35b）",
        "只转发不判据，Android 上照旧画那个必然失败的文件夹按钮",
    ),
    (
        "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js",
        "\"path.openShareFailed\"",
        "Android 打开失败必须有独立文案（P35c）",
        "没有它，toast 还是「打开失败，请重试」——重试永远没用",
    ),
    (
        "@deepseek-ai/dsh-client-ui-deliverables/lib/index.js",
        "\"android-open\",",
        "宿主侧必须把 Android 的失败步骤回给客户端（P35c）",
        "仍回 500 的话，客户端只能给出那句误导人的通用文案",
    ),
    (
        "@deepseek-ai/dsh-client-ui-deliverables/lib/client.js",
        "phase === \"androidOpenFailed\" ? \"openShareFailed\"",
        "卡片必须把失败步骤映射成具体文案键（P35c）",
        "映射不在，新文案显示不出来，toast 又回到 openError",
    ),
    (
        "@deepseek-ai/dsh-native-command/lib/index.js",
        "throw androidHandoffFailure(\"open\", path, error);",
        "Android 打开必须保留 dsh-open 的退出码（P35c）",
        "丢了退出码，「哪一步失败」就只剩猜测",
    ),
    (
        "@deepseek-ai/dsh-client-ui-open-in-app/lib/client.js",
        'shareFailed ? "openShareFailed" : "openError"',
        "预览页的打开失败也必须给出具体文案（P35c）",
        "不改的话预览页仍是「打开失败，请重试」——同一句话在两个界面上说法不一致",
    ),
]

print()
for line in applied:
    print(f"  已应用  {line}")
for line in skipped:
    print(f"  跳过    {line}")
print()
print(f"完成：{len(applied)} 项已应用，{len(skipped)} 项跳过。")

failed = []
for must_label, consequence in MUST_APPLY.items():
    kind = skip_kind.get(must_label)
    if kind is None or kind == "done":
        continue
    failed.append((must_label, kind, consequence))

for state_check in MUST_STATE:
    rel, needle, requirement = state_check[0], state_check[1], state_check[2]
    # 第 4 项可选：这条要求的后果（写进失败报告，省得读的人自己猜）。
    consequence = state_check[3] if len(state_check) > 3 else "落盘文本里没有这行 import"
    path = os.path.join(root, rel)
    try:
        with io.open(path, encoding="utf-8") as fh:
            text = fh.read()
    except OSError:
        failed.append((f"{rel}：{requirement}", "missing", consequence))
        continue
    if needle not in text:
        failed.append((f"{rel}：{requirement}", "state", consequence))

if failed:
    print()
    for must_label, kind, consequence in failed:
        reason = {
            "missing": "目标文件不存在",
            "anchor": "锚点不匹配（上游原文已变更）",
            "state": "落盘结果不符合要求",
        }.get(kind, kind)
        print(f"  必成失败  {must_label}")
        print(f"            原因：{reason}")
        print(f"            后果：{consequence}")
    print()
    print(f"构建中止：{len(failed)} 条必成条目没有落地（见上）。")
    sys.exit(1)
print("注意：P5（纯 JS 图片解析）、P10（bionic coreutils）")
print("      需要改动内嵌负载的其他部分，见 docs/PITFALLS.md 对应条目。")
PY
