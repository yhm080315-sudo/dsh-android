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
