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
