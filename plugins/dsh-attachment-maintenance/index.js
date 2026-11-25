/**
 * dsh-attachment-maintenance —— DSH on Android 的附件库维护工具。
 *
 * 为什么要有这个插件：附件是内容寻址的，会话日志里只存引用
 * `{"type":"image","attachment":{"attachmentId":"sha256:<64hex>"}}`，本体在
 * `~/.dsh/attachments/v1/{objects,file-objects}/<前两位>/<sha256>`。`dsh-attachment-local`
 * 既没有引用计数也没有 GC，于是两种损耗会一直累积：
 *
 *   1. 孤儿：上传了但没进任何消息、或被编辑掉的历史对象 —— 只有写入，没有回收；
 *   2. 重复：`files/<sha256>/<name>` 本意是给规范对象加一个"以真实文件名结尾"的别名，
 *      但 Android 的 SELinux 不允许 app 私有目录里 link(2)
 *      （`avc: denied { link } ... tclass=file permissive=0`），于是它一路回退成复制，
 *      同一份字节在盘上存两遍。
 *
 * 这个插件只做两件**不改变语义**的事：
 *
 *   - dedupe：把 `files/<h>/<name>` 这个纯别名换成指向 `file-objects/<h>` 的链接
 *     （先比 size 再比 sha256，两侧都等于 h 才换）。读侧按路径打开，行为不变。
 *   - delete：只删**没有任何会话引用**的 `objects/` 与 `file-objects/` 对象。
 *
 * 安全边界（比"能删多少"重要）：
 *
 *   - 默认 dry-run，返回统计不改盘；
 *   - 引用集合来自 `~/.dsh/sessions/**` 的**每一个** zstd 帧（多帧文件逐帧解压），
 *     外加 home 下其它非缓存文件里的 `sha256:<64hex>`（保守：多留不算错，删错不可逆）；
 *   - `files/<h>` 目录存在时，`<h>` 一律视为被引用（别名不能变成悬空链接）；
 *   - 只碰 64 位小写十六进制名字，`tmp/`、`files/` 里其它名字一律不动；
 *   - 删除前把清单（hash / 大小 / 时间 / 路径）写进 `~/.dsh/attachments/gc-<时间>.json`。
 *
 * 只依赖 node 内置模块 + `@deepseek-ai/dsh-tools`（profile 里已有），手机上没有 npm。
 */
import { createHash } from 'node:crypto';
import {
	closeSync,
	linkSync,
	lstatSync,
	mkdirSync,
	openSync,
	readFileSync,
	readdirSync,
	readSync,
	renameSync,
	rmSync,
	statSync,
	symlinkSync,
	unlinkSync,
	writeFileSync,
} from 'node:fs';
import { basename, dirname, join, relative } from 'node:path';
import { zstdDecompressSync } from 'node:zlib';
import { defineTool } from '@deepseek-ai/dsh-tools';

export const name = 'attachment-maintenance';

/** 工具注册表由 @deepseek-ai/dsh-tools 的服务提供。 */
export const inject = ['tools'];

/** 会话日志里的附件引用形状：`sha256:` + 64 位小写十六进制。 */
const HASH_IN_TEXT = /sha256:([0-9a-f]{64})/g;

/** 内容寻址对象的名字必须是 64 位小写十六进制 —— 这条判据同时保护 tmp/。 */
const HEX64 = /^[0-9a-f]{64}$/;

/** zstd 帧魔数（小端 0xFD2FB528）。 */
const ZSTD_MAGIC = 4247762216;

/** 这些 errno 说明"这个平台/文件系统不给做硬链接"，可以退到符号链接。 */
const LINK_REFUSED = new Set(['EACCES', 'EPERM', 'ENOSYS', 'ENOTSUP', 'EOPNOTSUPP', 'EXDEV', 'EMLINK', 'EPROTO']);

/** home 下这些目录不参与"额外引用源"扫描：缓存/二进制/就是附件库本身。 */
const SKIP_DIRS = new Set(['attachments', 'cache', 'node_modules', 'screen-frames', 'logs']);

/** 单文件摘要的读块大小。 */
const READ_CHUNK = 512 * 1024;

/**
 * DSH_HOME 由 App 的 NodeServer 设成 `<files>/home/.dsh`。
 * 不要在加载阶段抛错：拼错的 home 应该变成一次失败的调用，而不是让 profile 加载不起来。
 * @param {string|undefined} override - 插件配置里的显式 home。
 * @returns {string} 生效的 DSH_HOME。
 */
function resolveHome(override) {
	if (typeof override === 'string' && override !== '') return override;
	return process.env.DSH_HOME || '/data/user/0/com.dsh.android/files/home/.dsh';
}

/**
 * 把一段文本里的附件引用收进集合。
 * @param {string} text - 解压后的会话 JSONL 文本或任意文本文件内容。
 * @param {Set<string>} into - 收集目标。
 */
function collectHashes(text, into) {
	for (const match of text.matchAll(HASH_IN_TEXT)) into.add(match[1]);
}

/**
 * 一个 zstd 文件可能是**多帧**（会话持久化每次追加都写一帧），
 * 所以不能只用 zstdDecompressSync 解整个 buffer —— 它只认第一帧。
 * 这里按 RFC 8878 的帧头逐帧切分，返回 [start, end) 列表。
 * @param {Buffer} buffer - 整个文件的字节。
 * @returns {Array<[number, number]>} 每帧的区间。
 */
function scanZstdFrames(buffer) {
	const frames = [];
	let offset = 0;
	while (offset < buffer.length) {
		const start = offset;
		if (buffer.length - offset < 4 || buffer.readUInt32LE(offset) !== ZSTD_MAGIC) break;
		offset += 4;
		if (offset >= buffer.length) break;
		const descriptor = buffer.readUInt8(offset++);
		if ((descriptor & 24) !== 0) break; // reserved 位非零：不是合法帧头
		const contentSizeFlag = descriptor >>> 6;
		const singleSegment = (descriptor & 32) !== 0;
		const checksum = (descriptor & 4) !== 0;
		const dictFlag = descriptor & 3;
		const dictBytes = dictFlag === 3 ? 4 : dictFlag;
		const contentSizeBytes = contentSizeFlag === 0 ? (singleSegment ? 1 : 0) : 1 << contentSizeFlag;
		const remain = (singleSegment ? 0 : 1) + dictBytes + contentSizeBytes;
		if (buffer.length - offset < remain) break;
		offset += remain;
		for (;;) {
			if (buffer.length - offset < 3) return frames;
			const blockHeader = buffer.readUIntLE(offset, 3);
			offset += 3;
			const last = (blockHeader & 1) !== 0;
			const type = (blockHeader >>> 1) & 3;
			const size = blockHeader >>> 3;
			if (type === 3) return frames; // reserved block type
			offset += type === 1 ? 1 : size; // RLE 块的数据只有 1 字节
			if (offset > buffer.length) return frames;
			if (last) break;
		}
		if (checksum) {
			if (buffer.length - offset < 4) return frames;
			offset += 4;
		}
		frames.push([start, offset]);
	}
	return frames;
}

/**
 * 递归列出目录下的**文件**（不跟随目录符号链接）。
 * 打不开的路径直接跳过：维护工具不该因为一个坏目录整次失败。
 * @param {string} dir - 起始目录。
 * @param {string[]} out - 结果累加。
 * @param {number} depth - 当前深度（防符号链接环）。
 * @returns {string[]} 文件路径列表。
 */
function walkFiles(dir, out = [], depth = 0) {
	if (depth > 12) return out;
	let entries;
	try {
		entries = readdirSync(dir, { withFileTypes: true });
	} catch {
		return out;
	}
	for (const entry of entries) {
		const path = join(dir, entry.name);
		if (entry.isDirectory()) walkFiles(path, out, depth + 1);
		else out.push(path);
	}
	return out;
}

/**
 * 收集引用：会话日志逐帧解压，再补一遍 home 下其它文件。
 * @param {string} home - DSH_HOME。
 * @param {{ extraSources?: boolean }} options - 是否补扫其它引用源。
 * @returns {object} 引用集合与扫描统计。
 */
function collectReferences(home, options = {}) {
	const hashes = new Set();
	let sessionFiles = 0;
	let sessionBytes = 0;
	let frames = 0;
	let undecodable = 0;

	for (const file of walkFiles(join(home, 'sessions'))) {
		if (!file.endsWith('.zstd') && !file.endsWith('.jsonl')) continue;
		let raw;
		try {
			raw = readFileSync(file);
		} catch {
			continue;
		}
		sessionFiles += 1;
		sessionBytes += raw.length;
		if (file.endsWith('.jsonl')) {
			collectHashes(raw.toString('utf8'), hashes);
			continue;
		}
		const frameRanges = scanZstdFrames(raw);
		if (frameRanges.length === 0) {
			// 帧切分失败（上游改了封装之类）：整包试一次，宁可多留。
			undecodable += 1;
			try {
				collectHashes(zstdDecompressSync(raw).toString('utf8'), hashes);
			} catch {
				// 真的解不开：留给报告，不猜
			}
			continue;
		}
		for (const [from, to] of frameRanges) {
			frames += 1;
			let text;
			try {
				text = zstdDecompressSync(raw.subarray(from, to)).toString('utf8');
			} catch {
				undecodable += 1;
				continue;
			}
			collectHashes(text, hashes);
		}
	}

	let extraFiles = 0;
	let extraBytes = 0;
	const extraRoots = [];
	if (options.extraSources !== false) {
		let entries = [];
		try {
			entries = readdirSync(home, { withFileTypes: true });
		} catch {
			entries = [];
		}
		for (const entry of entries) {
			if (entry.name === 'sessions' || SKIP_DIRS.has(entry.name)) continue;
			extraRoots.push(entry.name);
		}
		for (const root of extraRoots) {
			for (const file of walkFiles(join(home, root))) {
				let raw;
				try {
					raw = readFileSync(file);
				} catch {
					continue;
				}
				extraFiles += 1;
				extraBytes += raw.length;
				// latin1：任何字节都能成字符串，十六进制引用也不会被 UTF-8 解码打断。
				collectHashes(raw.toString('latin1'), hashes);
			}
		}
	}

	return { hashes, sessionFiles, sessionBytes, frames, undecodable, extraFiles, extraBytes, extraRoots };
}

/**
 * 扫一个对象库（objects/ 或 file-objects/），把对象分成"被引用"和"孤儿"。
 * @param {string} store - 绝对目录。
 * @param {Set<string>} referenced - 引用集合。
 * @param {Set<string>} aliasHeld - 被 files/ 别名占用的 hash（这些一律不删）。
 * @param {number} olderThanDays - 只把 N 天前的孤儿算作可删。
 * @returns {object} 统计与待删清单。
 */
function scanStore(store, referenced, aliasHeld, olderThanDays) {
	const now = Date.now();
	const result = {
		store: basename(store),
		total: 0,
		totalBytes: 0,
		referenced: 0,
		referencedBytes: 0,
		orphans: [],
		orphanBytes: 0,
		keptYoung: 0,
		keptYoungBytes: 0,
		keptByAlias: 0,
		keptByAliasBytes: 0,
	};
	for (const file of walkFiles(store)) {
		const hash = basename(file);
		if (!HEX64.test(hash)) continue;
		let info;
		try {
			info = lstatSync(file);
		} catch {
			continue;
		}
		if (!info.isFile()) continue;
		result.total += 1;
		result.totalBytes += info.size;
		if (referenced.has(hash)) {
			result.referenced += 1;
			result.referencedBytes += info.size;
			continue;
		}
		if (aliasHeld.has(hash)) {
			result.keptByAlias += 1;
			result.keptByAliasBytes += info.size;
			continue;
		}
		if (olderThanDays > 0 && (now - info.mtimeMs) / 86400000 < olderThanDays) {
			result.keptYoung += 1;
			result.keptYoungBytes += info.size;
			continue;
		}
		result.orphans.push({
			hash,
			store: result.store,
			path: file,
			bytes: info.size,
			mtime: new Date(info.mtimeMs).toISOString(),
		});
		result.orphanBytes += info.size;
	}
	return result;
}

/**
 * 哪些 hash 在 files/ 下还有别名目录。别名指向的对象不能被回收，
 * 否则别名会变成悬空链接 —— 那正是这次要消灭的那类历史故障。
 * @param {string} attachments - attachments/ 绝对目录。
 * @returns {Set<string>} hash 集合。
 */
function collectAliasHeld(attachments) {
	const held = new Set();
	const filesRoot = join(attachments, 'files');
	let shards;
	try {
		shards = readdirSync(filesRoot, { withFileTypes: true });
	} catch {
		return held;
	}
	for (const shard of shards) {
		if (!shard.isDirectory()) continue;
		let hashes;
		try {
			hashes = readdirSync(join(filesRoot, shard.name), { withFileTypes: true });
		} catch {
			continue;
		}
		for (const entry of hashes) if (entry.isDirectory() && HEX64.test(entry.name)) held.add(entry.name);
	}
	return held;
}

/**
 * 单文件 sha256（流式，512 KB 一块，不把 200 MB 的对象整个读进内存）。
 * @param {string} path - 文件路径。
 * @returns {string} 十六进制摘要。
 */
function sha256File(path) {
	const hash = createHash('sha256');
	const info = statSync(path);
	if (info.size <= READ_CHUNK) return hash.update(readFileSync(path)).digest('hex');
	const handle = openSync(path, 'r');
	const buffer = Buffer.allocUnsafe(READ_CHUNK);
	try {
		for (;;) {
			const read = readSync(handle, buffer, 0, READ_CHUNK, null);
			if (read <= 0) break;
			hash.update(buffer.subarray(0, read));
		}
	} finally {
		closeSync(handle);
	}
	return hash.digest('hex');
}

/**
 * 找出"files/ 里那份是 file-objects/ 的重复拷贝"的所有别名。
 * 判据：同一 hash、size 相同、两侧 sha256 都等于 hash。任何一条不满足都不换。
 * @param {string} attachments - attachments/ 绝对目录。
 * @returns {object} pairs 与各类跳过计数。
 */
function planDedupe(attachments) {
	const filesRoot = join(attachments, 'files');
	const pairs = [];
	let alreadyLinked = 0;
	let skipped = 0;
	let missingObject = 0;
	let mismatched = 0;
	for (const alias of walkFiles(filesRoot)) {
		const hash = basename(dirname(alias));
		if (!HEX64.test(hash)) {
			skipped += 1;
			continue;
		}
		let aliasInfo;
		try {
			aliasInfo = lstatSync(alias);
		} catch {
			skipped += 1;
			continue;
		}
		const objectPath = join(attachments, 'file-objects', hash.slice(0, 2), hash);
		let objectInfo;
		try {
			objectInfo = statSync(objectPath);
		} catch {
			missingObject += 1;
			continue;
		}
		if (aliasInfo.isSymbolicLink()) {
			alreadyLinked += 1;
			continue;
		}
		if (!aliasInfo.isFile() || !objectInfo.isFile()) {
			skipped += 1;
			continue;
		}
		if (aliasInfo.ino === objectInfo.ino) {
			alreadyLinked += 1; // 已经是硬链接
			continue;
		}
		if (aliasInfo.size !== objectInfo.size) {
			mismatched += 1;
			continue;
		}
		if (sha256File(alias) !== hash || sha256File(objectPath) !== hash) {
			mismatched += 1;
			continue;
		}
		pairs.push({ alias, hash, bytes: aliasInfo.size });
	}
	return { pairs, alreadyLinked, skipped, missingObject, mismatched };
}

/**
 * 把一条别名换成指向规范对象的链接：先硬链接，平台拒绝就做**相对**符号链接。
 * 名字先在临时名上备好，再用 rename 原子替换 —— 中途失败不会留下一个空名字。
 * @param {{alias: string, hash: string, bytes: number}} pair - 待换的别名。
 * @param {string} attachments - attachments/ 绝对目录。
 * @returns {'link'|'symlink'} 实际使用的模式。
 */
function convertPair(pair, attachments) {
	const objectPath = join(attachments, 'file-objects', pair.hash.slice(0, 2), pair.hash);
	const staging = `${pair.alias}.gc-${process.pid}`;
	rmSync(staging, { force: true });
	let mode = 'link';
	try {
		linkSync(objectPath, staging);
	} catch (error) {
		const code = error instanceof Error && 'code' in error ? error.code : undefined;
		if (!LINK_REFUSED.has(code)) throw error;
		mode = 'symlink';
		symlinkSync(relative(dirname(pair.alias), objectPath), staging);
	}
	let staged;
	try {
		staged = statSync(staging);
	} catch (error) {
		rmSync(staging, { force: true });
		throw error;
	}
	if (staged.size !== pair.bytes) {
		rmSync(staging, { force: true });
		throw new Error(`attachments_gc: 链接后的字节数不对（${pair.alias}）`);
	}
	renameSync(staging, pair.alias);
	return mode;
}

/**
 * `20261118-232500`：给清单文件用的时间戳。
 * @returns {string} 时间戳。
 */
function stamp() {
	const now = new Date();
	const pad = (value) => String(value).padStart(2, '0');
	return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
		+ `-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
}

/**
 * 写删除清单。删除不可逆，所以清单必须在删之前落盘。
 * @param {string} home - DSH_HOME。
 * @param {object} payload - 清单内容。
 * @returns {string} 清单路径。
 */
function writeManifest(home, payload) {
	const dir = join(home, 'attachments');
	mkdirSync(dir, { recursive: true });
	const path = join(dir, `gc-${stamp()}.json`);
	writeFileSync(path, `${JSON.stringify(payload, null, 2)}\n`, { mode: 384 });
	return path;
}

/**
 * 一次维护：扫描 → （可选）去重 → （可选）删除孤儿。
 * @param {object} input - 见 attachments_gc 的参数，外加可选的 dshHome。
 * @returns {object} 报告（dry-run 时是"将会发生什么"）。
 */
export function runMaintenance(input = {}) {
	const startedAt = Date.now();
	const home = resolveHome(input.dshHome);
	const attachments = join(home, 'attachments', 'v1');
	const dryRun = input.dryRun !== false;
	const apply = dryRun === false;
	const wantDelete = input.delete === true;
	const wantDedupe = input.dedupe === true;
	const olderThanDays = Number.isFinite(input.olderThanDays) && input.olderThanDays > 0 ? input.olderThanDays : 0;
	const errors = [];

	const refs = collectReferences(home, { extraSources: input.extraSources !== false });
	const aliasHeld = collectAliasHeld(attachments);
	const objects = scanStore(join(attachments, 'objects'), refs.hashes, aliasHeld, olderThanDays);
	const fileObjects = scanStore(join(attachments, 'file-objects'), refs.hashes, aliasHeld, olderThanDays);
	const dedupePlan = planDedupe(attachments);

	const orphans = [...objects.orphans, ...fileObjects.orphans];
	const orphanBytes = objects.orphanBytes + fileObjects.orphanBytes;

	let dedupePairs = dedupePlan.pairs.length;
	let dedupeBytes = dedupePlan.pairs.reduce((sum, pair) => sum + pair.bytes, 0);
	let linked = 0;
	let symlinked = 0;
	if (wantDedupe && apply) {
		dedupePairs = 0;
		dedupeBytes = 0;
		for (const pair of dedupePlan.pairs) {
			try {
				const mode = convertPair(pair, attachments);
				dedupePairs += 1;
				dedupeBytes += pair.bytes;
				linked += 1;
				if (mode === 'symlink') symlinked += 1;
			} catch (error) {
				errors.push(`dedupe ${pair.alias}: ${error instanceof Error ? error.message : String(error)}`);
			}
		}
	}

	let deleted = 0;
	let deletedBytes = 0;
	let manifest = null;
	if (wantDelete && apply && orphans.length > 0) {
		manifest = writeManifest(home, {
			tool: 'dsh-attachment-maintenance',
			version: '0.1.0',
			generatedAt: new Date().toISOString(),
			dshHome: home,
			parameters: { dryRun, delete: wantDelete, dedupe: wantDedupe, olderThanDays },
			references: {
				hashes: refs.hashes.size,
				sessionFiles: refs.sessionFiles,
				sessionBytes: refs.sessionBytes,
				frames: refs.frames,
				undecodable: refs.undecodable,
				extraFiles: refs.extraFiles,
				extraBytes: refs.extraBytes,
				extraRoots: refs.extraRoots,
			},
			dedupe: wantDedupe ? { pairs: dedupePairs, bytes: dedupeBytes, linked, symlinked } : null,
			orphans,
		});
		for (const orphan of orphans) {
			try {
				unlinkSync(orphan.path);
				deleted += 1;
				deletedBytes += orphan.bytes;
			} catch (error) {
				errors.push(`delete ${orphan.path}: ${error instanceof Error ? error.message : String(error)}`);
			}
		}
	}

	const report = {
		dshHome: home,
		attachments,
		dryRun,
		applied: apply,
		referenced: objects.referenced + fileObjects.referenced,
		orphans: orphans.length,
		orphanBytes,
		dedupePairs,
		dedupeBytes,
		deleted,
		deletedBytes,
		linked,
		symlinked,
		refs: refs.hashes.size,
		sessionFiles: refs.sessionFiles,
		sessionBytes: refs.sessionBytes,
		frames: refs.frames,
		undecodable: refs.undecodable,
		extraFiles: refs.extraFiles,
		extraBytes: refs.extraBytes,
		objects: {
			total: objects.total,
			bytes: objects.totalBytes,
			referenced: objects.referenced,
			orphans: objects.orphans.length,
			orphanBytes: objects.orphanBytes,
			keptByAlias: objects.keptByAlias,
			keptYoung: objects.keptYoung,
		},
		fileObjects: {
			total: fileObjects.total,
			bytes: fileObjects.totalBytes,
			referenced: fileObjects.referenced,
			orphans: fileObjects.orphans.length,
			orphanBytes: fileObjects.orphanBytes,
			keptByAlias: fileObjects.keptByAlias,
			keptYoung: fileObjects.keptYoung,
		},
		alreadyLinked: dedupePlan.alreadyLinked,
		keptByAlias: objects.keptByAlias + fileObjects.keptByAlias,
		keptYoung: objects.keptYoung + fileObjects.keptYoung,
		mismatched: dedupePlan.mismatched,
		missingObject: dedupePlan.missingObject,
		manifest,
		actionsRequested: { delete: wantDelete, dedupe: wantDedupe },
		elapsedMs: Date.now() - startedAt,
		errors,
	};
	if ((wantDelete || wantDedupe) && !apply) {
		report.errors = [...errors, 'dryRun 没有关闭：没有改动任何文件。确认后传 dryRun:false。'];
	}
	return report;
}

/**
 * 报告压成一句话，给模型和 UI 用。
 * @param {object} report - runMaintenance 的返回值。
 * @returns {string} 摘要。
 */
export function summarize(report) {
	const mb = (bytes) => `${(bytes / 1048576).toFixed(1)} MB`;
	const where = report.dryRun ? 'dry-run（未改盘）' : '已应用';
	return `attachments_gc ${where}：引用 ${report.referenced} 个对象 / ${report.refs} 条引用（${report.frames} 帧），`
		+ `孤儿 ${report.orphans} 个 ${mb(report.orphanBytes)}（删除 ${report.deleted} 个 ${mb(report.deletedBytes)}），`
		+ `files/ 重复 ${report.dedupePairs} 对 ${mb(report.dedupeBytes)}（转换 ${report.linked} 个：`
		+ `硬链接 ${report.linked - report.symlinked} / 符号链接 ${report.symlinked}）。`;
}

/**
 * 注册 `attachments_gc`。
 * @param {object} ctx - 带 tools 注册表的上下文。
 * @param {object} config - 插件配置（可给出 dshHome）。
 */
export function apply(ctx, config = {}) {
	ctx.tools.register(defineTool({
		name: 'attachments_gc',
		description: 'Inspect and maintain the local attachment store (~/.dsh/attachments/v1): '
			+ 'find content-addressed objects that no session log references, and collapse duplicate '
			+ 'file aliases (files/<sha>/<name>) into links to their canonical object. '
			+ 'Never touches referenced objects, tmp/, or non-hash names. dryRun defaults to true and only '
			+ 'reports; pass dryRun:false together with delete or dedupe to apply. A deletion manifest is '
			+ 'written to ~/.dsh/attachments/gc-<time>.json before anything is unlinked.',
		parameters: {
			dryRun: {
				type: 'boolean',
				description: 'true (default): only report. false: apply the requested actions.',
			},
			delete: {
				type: 'boolean',
				description: 'Delete objects in objects/ and file-objects/ that no session references. Needs dryRun:false.',
			},
			dedupe: {
				type: 'boolean',
				description: 'Replace files/<sha>/<name> copies with a hard link (or, where the platform refuses link(2), a relative symlink) to file-objects/<sha>. Verified by size and sha256 first. Needs dryRun:false.',
			},
			olderThanDays: {
				type: 'integer',
				description: 'Only delete orphans whose mtime is at least this many days old. 0 (default): no age limit.',
			},
			extraSources: {
				type: 'boolean',
				description: 'Also scan non-cache files under DSH_HOME for sha256 references (default true). Turning it off can delete an object a draft still points at.',
			},
		},
		output: {
			schema: {
				type: 'object',
				additionalProperties: false,
				properties: {
					dshHome: { type: 'string', required: true },
					attachments: { type: 'string', required: true },
					dryRun: { type: 'boolean', required: true },
					applied: { type: 'boolean', required: true },
					referenced: { type: 'integer', required: true },
					orphans: { type: 'integer', required: true },
					orphanBytes: { type: 'integer', required: true },
					dedupePairs: { type: 'integer', required: true },
					dedupeBytes: { type: 'integer', required: true },
					deleted: { type: 'integer', required: true },
					deletedBytes: { type: 'integer', required: true },
					linked: { type: 'integer', required: true },
					symlinked: { type: 'integer', required: true },
					refs: { type: 'integer', required: true },
					frames: { type: 'integer', required: true },
					extraFiles: { type: 'integer', required: true },
					alreadyLinked: { type: 'integer', required: true },
					keptByAlias: { type: 'integer', required: true },
					keptYoung: { type: 'integer', required: true },
					mismatched: { type: 'integer', required: true },
					manifest: { type: 'string', required: true },
					elapsedMs: { type: 'integer', required: true },
					summary: { type: 'string', required: true },
				},
			},
			render: (_args, value) => [{ type: 'text', text: value.summary }],
		},
		execute(args) {
			const report = runMaintenance({
				dshHome: typeof config.dshHome === 'string' ? config.dshHome : undefined,
				dryRun: args.dryRun,
				delete: args.delete,
				dedupe: args.dedupe,
				olderThanDays: args.olderThanDays,
				extraSources: args.extraSources,
			});
			// 只回 schema 里声明过的字段；明细留在盘上的清单与 runMaintenance 的完整报告里。
			return Promise.resolve({
				dshHome: report.dshHome,
				attachments: report.attachments,
				dryRun: report.dryRun,
				applied: report.applied,
				referenced: report.referenced,
				orphans: report.orphans,
				orphanBytes: report.orphanBytes,
				dedupePairs: report.dedupePairs,
				dedupeBytes: report.dedupeBytes,
				deleted: report.deleted,
				deletedBytes: report.deletedBytes,
				linked: report.linked,
				symlinked: report.symlinked,
				refs: report.refs,
				frames: report.frames,
				extraFiles: report.extraFiles,
				alreadyLinked: report.alreadyLinked,
				keptByAlias: report.keptByAlias,
				keptYoung: report.keptYoung,
				mismatched: report.mismatched,
				manifest: report.manifest === null ? '' : report.manifest,
				elapsedMs: report.elapsedMs,
				summary: summarize(report)
					+ (report.errors.length > 0 ? ` 注意：${report.errors.join('；')}` : ''),
			});
		},
		presentCall: () => ({ card: 'generic', title: '附件库维护', kind: 'other' }),
	}));
}

export const __test = {
	collectHashes,
	scanZstdFrames,
	walkFiles,
	collectReferences,
	collectAliasHeld,
	planDedupe,
	convertPair,
	scanStore,
	sha256File,
	runMaintenance,
	summarize,
	resolveHome,
};
