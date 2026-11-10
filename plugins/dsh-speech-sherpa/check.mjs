/**
 * dsh-speech-sherpa 的离线验证（不装 App、不碰真机）。
 *
 * 用一个本地假桥复现 App 的 /device/status、/device/asr/status、/device/asr/model、
 * /device/asr 四个端点，然后：
 *   1. 检查 apply() 注册出来的 provider 形状（id / location / languages / setupEstimate）；
 *   2. 检查 transcribe() 真的把 WAV POST 到桥、并把正文原样带回；
 *   3. 检查 preparation 的状态机走完 unprepared → downloading → ready；
 *   4. 检查 token 走的是头而不是 query（App 侧 authorized() 再看 URL 就晚了）。
 *
 * 跑法：node check.mjs
 */
import { createServer } from 'node:http';
import { apply as applyPlugin } from './index.js';

const TOKEN = 'test-token-0123456789';
const failures = [];
const check = (condition, label, detail = '') => {
	if (condition) {
		console.log(`  ok: ${label}`);
	} else {
		failures.push(label);
		console.log(`  FAIL: ${label}${detail ? ` — ${detail}` : ''}`);
	}
};

/** 一段合法的 16k 单声道 PCM16 WAV，300 个样本（够 transcribe 的 RIFF 校验）。 */
function tinyWav() {
	const samples = 300;
	const buffer = Buffer.alloc(44 + samples * 2);
	buffer.write('RIFF', 0, 'ascii');
	buffer.writeUInt32LE(buffer.length - 8, 4);
	buffer.write('WAVE', 8, 'ascii');
	buffer.write('fmt ', 12, 'ascii');
	buffer.writeUInt32LE(16, 16);
	buffer.writeUInt16LE(1, 20);
	buffer.writeUInt16LE(1, 22);
	buffer.writeUInt32LE(16000, 24);
	buffer.writeUInt32LE(32000, 28);
	buffer.writeUInt16LE(2, 32);
	buffer.writeUInt16LE(16, 34);
	buffer.write('data', 36, 'ascii');
	buffer.writeUInt32LE(samples * 2, 40);
	for (let i = 0; i < samples; i++) {
		buffer.writeInt16LE(Math.round(Math.sin(i / 8) * 6000), 44 + i * 2);
	}
	return buffer;
}

const state = {
	modelPresent: false,
	downloadPhase: 'idle',
	received: 0,
	asrCalls: [],
	tokenInQuery: false,
};

const server = createServer((req, res) => {
	const url = new URL(req.url, 'http://127.0.0.1');
	if (url.searchParams.has('token')) state.tokenInQuery = true;
	const chunks = [];
	req.on('data', (chunk) => chunks.push(chunk));
	req.on('end', () => {
		const body = Buffer.concat(chunks);
		const json = (payload) => {
			res.writeHead(200, { 'Content-Type': 'application/json' });
			res.end(JSON.stringify(payload));
		};
		if (req.headers['x-device-token'] !== TOKEN) {
			res.writeHead(401, { 'Content-Type': 'text/plain' });
			res.end('unauthorized: bad or missing token\n');
			return;
		}
		if (url.pathname === '/device/status') {
			res.writeHead(200, { 'Content-Type': 'text/plain' });
			res.end('dsh device bridge\nport: 3090\nendpoints:\n  POST /device/asr\n');
			return;
		}
		if (url.pathname === '/device/asr/status') {
			json({
				engine: state.modelPresent ? 'ready' : 'idle',
				model: { present: state.modelPresent, totalBytes: 163002883 },
				languages: ['auto', 'zh', 'en', 'yue', 'ja', 'ko'],
				download: {
					phase: state.downloadPhase,
					received: state.received,
					total: 163002883,
					startedAt: Date.now(),
				},
			});
			return;
		}
		if (url.pathname === '/device/asr/model') {
			if (url.searchParams.get('action') === 'download') {
				state.downloadPhase = 'downloading';
				state.received = 40_000_000;
				// 下一轮轮询就"下完"：把 163MB 的等待压成一次 tick，验证状态机而不是网速。
				setTimeout(() => {
					state.downloadPhase = 'done';
					state.modelPresent = true;
				}, 50);
			}
			json({ phase: state.downloadPhase, received: state.received, total: 163002883 });
			return;
		}
		if (url.pathname === '/device/asr') {
			state.asrCalls.push({
				language: url.searchParams.get('language'),
				bytes: body.length,
				riff: body.toString('ascii', 0, 4),
			});
			res.writeHead(200, {
				'Content-Type': 'text/plain; charset=utf-8',
				'X-Asr-Lang': '<|zh|>',
				'X-Asr-Ms': '97',
			});
			res.end('今天天气怎么样。\n');
			return;
		}
		res.writeHead(404);
		res.end('unknown endpoint\n');
	});
});

await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const port = server.address().port;
console.log(`假桥: http://127.0.0.1:${port}/  token=${TOKEN}`);

let registered = null;
const disposers = [];
const ctx = {
	logger: { warn: (...args) => console.log('  [warn]', ...args), info: () => {} },
	effect(callback) {
		disposers.push(callback());
	},
	speechToText: {
		register(provider) {
			registered = provider;
			return async () => {};
		},
	},
};

applyPlugin(ctx, { port, token: TOKEN, timeoutMs: 5000, pollIntervalMs: 20, prepareTimeoutMs: 30000 });
await new Promise((resolve) => setTimeout(resolve, 100));

console.log('== provider 形状 ==');
check(registered !== null, 'apply() 调用了 speechToText.register');
check(registered?.info?.id === 'sherpa-android', 'provider id 是 sherpa-android', registered?.info?.id);
check(registered?.info?.location === 'host-local', 'location 是 host-local（本地模型，不上云）');
check(JSON.stringify(registered?.info?.languages) === JSON.stringify(['auto', 'zh', 'en', 'yue', 'ja', 'ko']),
	'languages 与 SenseVoice 一致', JSON.stringify(registered?.info?.languages));
check(typeof registered?.preparation?.snapshot === 'function', 'preparation.snapshot 存在');
check(typeof registered?.preparation?.subscribe === 'function', 'preparation.subscribe 存在');
check(typeof registered?.preparation?.prepare === 'function', 'preparation.prepare 存在');
check(typeof registered?.transcribe === 'function', 'transcribe 存在');
check((registered?.info?.setupEstimate?.recommendedDiskBytes ?? 0) > 1_000_000_000,
	'setupEstimate 声明了 ~1.8GB 磁盘');

console.log('== 初始状态 ==');
check(registered?.preparation?.snapshot()?.phase === 'unprepared',
	'模型不在盘上时是 unprepared', JSON.stringify(registered?.preparation?.snapshot()));

console.log('== transcribe 转发 ==');
const wav = tinyWav();
const text = await registered.transcribe({ audio: new Uint8Array(wav), language: 'zh' }, new AbortController().signal);
check(text === '今天天气怎么样。', '正文原样返回（去掉尾部换行）', JSON.stringify(text));
check(state.asrCalls.length === 1, '桥收到一次 /device/asr', String(state.asrCalls.length));
check(state.asrCalls[0]?.riff === 'RIFF', '请求体是 WAV（RIFF 头）', state.asrCalls[0]?.riff);
check(state.asrCalls[0]?.bytes === wav.length, 'WAV 字节数一致', `${state.asrCalls[0]?.bytes} vs ${wav.length}`);
check(state.asrCalls[0]?.language === 'zh', 'language 参数传到了桥', state.asrCalls[0]?.language);
check(state.tokenInQuery === false, 'token 只在 X-Device-Token 头里，不在 URL');

console.log('== 不支持的语言降级 ==');
state.asrCalls.length = 0;
await registered.transcribe({ audio: new Uint8Array(wav), language: 'klingon' }, new AbortController().signal);
check(state.asrCalls[0]?.language === 'auto', '未知语言退回 auto', state.asrCalls[0]?.language);

console.log('== 非 WAV 输入 ==');
let rejected = false;
try {
	await registered.transcribe({ audio: new Uint8Array([1, 2, 3, 4, 5]), language: 'zh' }, new AbortController().signal);
} catch (error) {
	rejected = String(error?.message ?? error).includes('ASR_AUDIO_NOT_WAV');
}
check(rejected, '5 字节输入被拒（ASR_AUDIO_NOT_WAV）');

console.log('== 准备状态机 ==');
const seen = [];
const unsubscribe = registered.preparation.subscribe(() => {
	seen.push(registered.preparation.snapshot().phase);
});
await registered.preparation.prepare();
unsubscribe();
check(seen.includes('downloading'), '观察到了 downloading 中间态', JSON.stringify(seen));
check(registered.preparation.snapshot().phase === 'ready'
	|| registered.preparation.snapshot().phase === 'standby',
	'准备结束后是 ready/standby', JSON.stringify(registered.preparation.snapshot()));

console.log('== 桥不可达 ==');
const offline = createServer();
await new Promise((resolve) => offline.listen(0, '127.0.0.1', resolve));
const deadPort = offline.address().port;
await new Promise((resolve) => offline.close(resolve));
let registered2 = null;
applyPlugin({
	logger: { warn: () => {}, info: () => {} },
	effect: (callback) => { callback(); },
	speechToText: { register: (provider) => { registered2 = provider; return async () => {}; } },
}, { port: deadPort, timeoutMs: 800 });
await new Promise((resolve) => setTimeout(resolve, 300));
check(registered2 !== null, '桥不通时插件仍然注册（不拖垮 profile）');
check(['failed', 'unprepared'].includes(registered2.preparation.snapshot().phase),
	'桥不通时状态是 failed/unprepared（不是崩溃）', JSON.stringify(registered2.preparation.snapshot()));

server.close();
await Promise.all(disposers.map((disposer) => (typeof disposer === 'function' ? disposer() : null)));

console.log('');
if (failures.length === 0) {
	console.log('CHECK PASS');
	process.exit(0);
}
console.log(`CHECK FAIL: ${failures.length} 项`);
process.exit(1);
