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
    if replacement[:80] in text or note in text:
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
