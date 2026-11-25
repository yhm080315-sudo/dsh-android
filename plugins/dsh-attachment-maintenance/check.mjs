/**
 * dsh-attachment-maintenance 的离线自测：假数据、无网络、不碰真实 ~/.dsh。
 *
 * 覆盖四件事（正是这次 bug 的判据）：
 *   1. 默认 dry-run 一个字节都不改（不删、不建链接）；
 *   2. delete 只删"没有任何会话引用"的对象，被引用的、被 files/ 别名占用的、缺引用的都不动；
 *   3. dedupe 只折叠内容相同（size + 两侧 sha256 都等于 hash）的别名，内容不同的一个都不碰；
 *   4. 没有 sessions/、没有 attachments/ 的 home 不崩，报空格。
 *
 * 跑法（设备上，App 的 node）：
 *   node check.mjs
 * 退出码 0 = 全过。
 */
import { createHash } from 'node:crypto';
import {
	mkdirSync,
	mkdtempSync,
	readFileSync,
	readdirSync,
	rmSync,
	statSync,
	lstatSync,
	writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { zstdCompressSync } from 'node:zlib';
import { __test, apply, runMaintenance } from './index.js';

const sha = (data) => createHash('sha256').update(data).digest('hex');
let failed = 0;
let passed = 0;

function check(condition, good, bad) {
	if (condition) {
		passed += 1;
		console.log('  ok:', good);
	} else {
		failed += 1;
		console.log('  FAIL:', bad);
	}
}

/** 造一个假的 DSH_HOME：对象、别名、会话多帧 zstd 各就各位。 */
function buildHome() {
	const home = mkdtempSync(join(tmpdir(), 'dsh-att-'));
	const att = join(home, 'attachments', 'v1');
	const put = (relativePath, data) => {
		const path = join(att, relativePath);
		mkdirSync(join(path, '..'), { recursive: true });
		writeFileSync(path, data);
		return path;
	};

	// 被引用的图片对象
	const referenced = Buffer.from('referenced-image-bytes');
	const referencedHash = sha(referenced);
	put(join('objects', referencedHash.slice(0, 2), referencedHash), referenced);
	// 孤儿图片对象
	const orphan = Buffer.from('orphan-image-bytes');
	const orphanHash = sha(orphan);
	put(join('objects', orphanHash.slice(0, 2), orphanHash), orphan);
	// 被 files/ 别名占用、但会话里没引用的对象：不许删（别名会悬空）
	const aliasOnly = Buffer.from('alias-only-bytes');
	const aliasOnlyHash = sha(aliasOnly);
	put(join('file-objects', aliasOnlyHash.slice(0, 2), aliasOnlyHash), aliasOnly);
	put(join('files', aliasOnlyHash.slice(0, 2), aliasOnlyHash, '别名.bin'), aliasOnly);
	// 内容相同、可以折叠的别名（file-objects + files 两份拷贝）
	const dup = Buffer.from('duplicate-bytes-'.repeat(64));
	const dupHash = sha(dup);
	put(join('file-objects', dupHash.slice(0, 2), dupHash), dup);
	put(join('files', dupHash.slice(0, 2), dupHash, 'report.pdf'), dup);
	// 内容不同、但目录名仍是同一个 hash 的别名：一个都不许碰
	const lyingHash = duplieHash();
	put(join('file-objects', lyingHash.slice(0, 2), lyingHash), Buffer.from('real-object'));
	put(join('files', lyingHash.slice(0, 2), lyingHash, 'stale.bin'), Buffer.from('stale-different-bytes'));
	// tmp/ 里的东西永不动
	put(join('tmp', 'staging-uuid'), Buffer.from('staging'));

	// 会话日志：多帧 zstd，引用只出现在第二帧
	const sessions = join(home, 'sessions', '--workspace--');
	mkdirSync(sessions, { recursive: true });
	const frameOne = zstdCompressSync(Buffer.from(`${JSON.stringify({ type: 'turn/start' })}\n`));
	const frameTwo = zstdCompressSync(Buffer.from(`${JSON.stringify({
		type: 'user/message',
		attachments: [{ attachmentId: `sha256:${referencedHash}`, name: 'a.png' }],
	})}\n`));
	writeFileSync(join(sessions, 'session-0001.jsonl.zstd'), Buffer.concat([frameOne, frameTwo]));

	return { home, att, referencedHash, orphanHash, aliasOnlyHash, dupHash, lyingHash };
}

/** 一个"内容与目录名不符"的合法 hex 名字，避免和别的用例撞。 */
function duplieHash() {
	return sha(Buffer.from('pretend-hash-name'));
}

function listAll(dir, out = []) {
	let entries;
	try {
		entries = readdirSync(dir, { withFileTypes: true });
	} catch {
		return out;
	}
	for (const entry of entries) {
		const path = join(dir, entry.name);
		if (entry.isDirectory()) listAll(path, out);
		else out.push(path);
	}
	return out;
}

console.log('== 工具注册（defineTool 的 schema 必须能编译）==');
{
	let registered = null;
	apply({ tools: { register: (tool) => { registered = tool; } } });
	check(registered !== null, 'apply() 注册了工具', 'apply() 没有注册任何工具');
	check(registered?.name === 'attachments_gc', '工具名 attachments_gc', `工具名不对：${registered?.name}`);
	const props = registered?.parameters?.properties ?? {};
	check(props.dryRun?.type === 'boolean' && props.delete?.type === 'boolean' && props.dedupe?.type === 'boolean'
		&& props.olderThanDays?.type === 'integer', '参数形状 {dryRun,delete,dedupe,olderThanDays} 齐全',
	`参数形状不对：${JSON.stringify(props)}`);
	check(Array.isArray(registered?.parameters?.required) === false || registered?.parameters?.required === undefined,
		'四个参数都是可选的（required 为空）', '参数被标成必填了');
	const rendered = registered?.output?.render({}, { summary: 'x' });
	check(Array.isArray(rendered) && rendered[0]?.text === 'x', 'output.render 可调用', 'output.render 坏了');
}

console.log('== 用例 1：默认 dry-run 不改盘 ==');
const A = buildHome();
{
	const before = listAll(A.home).sort();
	const beforeStats = before.map((path) => `${statSync(path).size}:${statSync(path).mtimeMs}`);
	const report = runMaintenance({ dshHome: A.home });
	const after = listAll(A.home).sort();
	const afterStats = after.map((path) => `${statSync(path).size}:${statSync(path).mtimeMs}`);
	check(report.dryRun === true, '默认 dryRun = true', '默认不是 dry-run');
	check(JSON.stringify(before) === JSON.stringify(after), '文件集合没变', 'dry-run 动了文件集合');
	check(JSON.stringify(beforeStats) === JSON.stringify(afterStats), '文件大小/时间没变', 'dry-run 改了文件');
	check(report.referenced === 1, `引用对象数 = 1（实际 ${report.referenced}）`, `引用对象数不对：${report.referenced}`);
	check(report.orphans === 1, `孤儿数 = 1（实际 ${report.orphans}）`, `孤儿数不对：${report.orphans}`);
	// 两对：dup（纯重复拷贝）与 aliasOnly（同样内容相同，只是它还顶着文件别名）
	check(report.dedupePairs === 2, `重复别名 = 2（实际 ${report.dedupePairs}）`, `重复别名数不对：${report.dedupePairs}`);
	check(report.mismatched === 1, '内容与目录名不符的别名被识别为 mismatched', 'mismatch 没被识别');
	check(report.keptByAlias >= 1, '被 files/ 占用的对象被保护', 'alias-only 对象没被保护');
	check(report.manifest === null, 'dry-run 不写清单', 'dry-run 写了清单');
	const images = readdirSync(join(A.att, 'tmp'));
	check(images.includes('staging-uuid'), 'tmp/ 原样保留', 'tmp/ 被动过');
}

console.log('== 用例 2：delete 只删无引用的对象 ==');
{
	const report = runMaintenance({ dshHome: A.home, dryRun: false, delete: true });
	const has = (hash) => {
		try {
			statSync(join(A.att, 'objects', hash.slice(0, 2), hash));
			return true;
		} catch {
			return false;
		}
	};
	check(report.deleted === 1, `删了 1 个（实际 ${report.deleted}）`, `删除数量不对：${report.deleted}`);
	check(has(A.referencedHash) === true, '被引用的对象还在', '被引用的对象被删了');
	check(has(A.orphanHash) === false, '孤儿对象删掉了', '孤儿对象没删');
	check(statSync(join(A.att, 'file-objects', A.aliasOnlyHash.slice(0, 2), A.aliasOnlyHash)).size > 0,
		'被 files/ 别名占用的对象还在', '别名占用的对象被删了，别名会悬空');
	check(typeof report.manifest === 'string' && report.manifest.includes('gc-'),
		'删除前写了清单', '没有清单');
	const manifest = JSON.parse(readFileSync(report.manifest, 'utf8'));
	check(manifest.orphans.length === 1 && manifest.orphans[0].hash === A.orphanHash,
		'清单里记了 hash/大小/时间', '清单内容不对');
}

console.log('== 用例 3：dedupe 只折叠内容相同的别名 ==');
{
	const aliasPath = join(A.att, 'files', A.dupHash.slice(0, 2), A.dupHash, 'report.pdf');
	const objectPath = join(A.att, 'file-objects', A.dupHash.slice(0, 2), A.dupHash);
	const report = runMaintenance({ dshHome: A.home, dryRun: false, dedupe: true });
	check(report.linked === 2, `转换 2 个（实际 ${report.linked}）`, `转换数量不对：${report.linked}`);
	const info = lstatSync(aliasPath);
	const sameInode = info.ino === statSync(objectPath).ino;
	check(info.isSymbolicLink() || sameInode, '别名换成了链接（符号链接或硬链接）', '别名还是独立拷贝');
	check(sha(readFileSync(aliasPath)) === A.dupHash, '别名读出来的仍是同一份字节', '别名内容变了');
	const stale = readFileSync(join(A.att, 'files', A.lyingHash.slice(0, 2), A.lyingHash, 'stale.bin'));
	check(stale.toString() === 'stale-different-bytes', '内容不符的别名一个字节没动', '内容不符的别名被改了');
	const again = runMaintenance({ dshHome: A.home, dryRun: false, dedupe: true });
	check(again.linked === 0 && again.alreadyLinked >= 1, '第二遍幂等（已链接的会跳过）', '不幂等');
}

console.log('== 用例 4：缺目录不崩 ==');
{
	const empty = mkdtempSync(join(tmpdir(), 'dsh-att-empty-'));
	const report = runMaintenance({ dshHome: empty });
	check(report.referenced === 0 && report.orphans === 0 && report.dedupePairs === 0,
		'没有 sessions/attachments 时返回全零', '缺目录时统计不为零');
	let survived = true;
	let report2 = null;
	try {
		report2 = runMaintenance({ dshHome: join(empty, '不存在') });
	} catch {
		survived = false;
	}
	check(survived && report2.referenced === 0 && report2.orphans === 0,
		'不存在的 home 也不抛，统计为 0', '不存在的 home 抛了');
	rmSync(empty, { recursive: true, force: true });
}

console.log('== 用例 5：参数校验（模型传错类型会被拦住）==');
{
	let registered = null;
	apply({ tools: { register: (tool) => { registered = tool; } } });
	let threw = false;
	try {
		await registered.execute({ dryRun: 'yes' }, {});
	} catch (error) {
		threw = String(error?.message ?? error).includes('invalid arguments');
	}
	check(threw, 'dryRun 传字符串被参数校验拒绝', '参数校验没拦住错误类型');
}

rmSync(A.home, { recursive: true, force: true });
console.log(`\n${passed} 通过 / ${failed} 失败`);
process.exit(failed === 0 ? 0 : 1);
