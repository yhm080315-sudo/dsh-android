/**
 * dsh-speech-sherpa —— DSH on Android 的本机语音识别 provider。
 *
 * 为什么要有这个插件：官方 provider（@deepseek-ai/dsh-experimental-speech-to-text-sensevoice）
 * 用 sherpa-onnx-node，只有 glibc 的 linux-arm64 预编译，Android 的 bionic 加载不了，
 * 于是 App 里那个语音按钮报「准备失败：Local speech is unavailable for android-arm64」。
 * App 侧现在自带 sherpa-onnx 的 arm64-v8a JNI（/device/asr），这个插件只做两件事：
 * 把 16k WAV 转给 App，把 App 的模型下载进度包装成 Host 的 preparation 快照。
 *
 * 数据不出设备：桥是 127.0.0.1 上的 token 鉴权端点，模型一次性下载到 App 私有目录，
 * 之后每次识别都是纯本地推理。
 *
 * 只依赖 node 内置模块（http/fs/path）—— 手机上没有 npm，装不了额外依赖。
 */
import { request } from 'node:http';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

export const name = 'speech-sherpa';

/** 服务由 @deepseek-ai/dsh-experimental-speech-to-text 提供，没有它就不要激活。 */
export const inject = ['speechToText'];

/** SenseVoice 接受的语言标签；auto 让它自己判断。 */
const LANGUAGES = ['auto', 'zh', 'en', 'yue', 'ja', 'ko'];

/** 与 App 侧 SpeechBridge.MODEL_URL 指向同一个文件（163,002,883 B）。 */
const MODEL_URL = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/'
	+ 'sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2';

const DEFAULTS = {
	/** 一次识别的上限：两分钟的中文，够长了；再长说明录音出问题。 */
	timeoutMs: 120000,
	/** 首次准备（下载 163MB + 校验 + 解包）。 */
	prepareTimeoutMs: 1800000,
	/** 轮询 App 下载状态的间隔。 */
	pollIntervalMs: 700,
	/** 显式指定桥端口；0 表示读 device.port，再探测 3090-3096。 */
	port: 0,
	/**
	 * 显式指定桥 token；空字符串表示按 $DSH_HOME/.device-token 的约定去找。
	 * 留这个口子只为了救急与离线测试：默认路径变了也不用改代码。
	 */
	token: '',
};

/** App 的 device bridge 探针端口表，与 DeviceServer.PORTS 一致。 */
const PORTS = [3090, 3091, 3092, 3093, 3094, 3095, 3096];

/**
 * DSH_HOME 由 App 的 NodeServer 设成 `<files>/home/.dsh`。这里不要在启动阶段抛错：
 * 一个拼错的 home 应该变成一次失败的请求，而不是让整个 profile 加载不起来。
 */
function dshHome() {
	return process.env.DSH_HOME || '/data/user/0/com.dsh.android/files/home/.dsh';
}

/** token 在 `<files>/.device-token`，也就是 DSH_HOME 往上两级。 */
function readToken() {
	if (process.env.DSH_DEVICE_TOKEN) return process.env.DSH_DEVICE_TOKEN;
	const candidates = [
		join(dshHome(), '.device-token'),
		join(dshHome(), '..', '..', '.device-token'),
	];
	for (const path of candidates) {
		try {
			const token = readFileSync(path, 'utf8').trim();
			if (token) return token;
		} catch {
			// 下一个候选
		}
	}
	return '';
}

/** device.port 由 DeviceServer 在绑定成功后写入；读不到再探测。 */
function readPort() {
	try {
		const raw = readFileSync(join(dshHome(), 'device.port'), 'utf8').trim();
		const port = Number.parseInt(raw, 10);
		if (Number.isInteger(port) && port > 0 && port < 65536) return port;
	} catch {
		// 没有文件就走探测
	}
	return 0;
}

/**
 * 一次 HTTP 调用。
 *
 * token 放 X-Device-Token 头而不是 query：URL 会进日志，头不会。
 * `agent: false` 是必须的 —— 桥回 Connection: close，复用池里一个坏掉的 socket
 * 会让下一次识别莫名超时。
 */
function call(options) {
	const { method = 'GET', path, body = null, port, token, timeoutMs, signal } = options;
	return new Promise((resolve, reject) => {
		const req = request({
			host: '127.0.0.1',
			port,
			method,
			path,
			agent: false,
			headers: {
				'X-Device-Token': token,
				...(body === null ? {} : {
					'Content-Type': 'application/octet-stream',
					'Content-Length': String(body.length),
				}),
			},
		}, (res) => {
			const chunks = [];
			res.on('data', (chunk) => chunks.push(chunk));
			res.on('end', () => resolve({
				status: res.statusCode ?? 0,
				headers: res.headers,
				body: Buffer.concat(chunks),
			}));
			res.on('error', reject);
		});
		req.setTimeout(timeoutMs ?? DEFAULTS.timeoutMs, () => {
			req.destroy(new Error(`ASR_BRIDGE_TIMEOUT: 127.0.0.1:${port}${path}`));
		});
		req.on('error', reject);
		if (signal) {
			if (signal.aborted) {
				req.destroy(new Error('cancelled'));
				return;
			}
			signal.addEventListener('abort', () => req.destroy(new Error('cancelled')), { once: true });
		}
		if (body !== null) req.write(body);
		req.end();
	});
}

/** 找到能应答的桥端口：先信 device.port，再按端口表探一遍。 */
async function locate(env, timeoutMs = 4000) {
	if (env.port > 0) return env.port;
	const preferred = readPort();
	const candidates = preferred > 0 ? [preferred, ...PORTS.filter((p) => p !== preferred)] : PORTS;
	for (const port of candidates) {
		try {
			const res = await call({ path: '/device/status', port, token: env.token, timeoutMs });
			if (res.status === 200 && res.body.toString('utf8').includes('dsh device bridge')) {
				env.port = port;
				return port;
			}
		} catch {
			// 换下一个端口
		}
	}
	throw new Error('ASR_BRIDGE_UNAVAILABLE: App 的 device bridge 没有应答（App 没在前台？）');
}

/** 读 App 的 /device/asr/status。 */
async function readStatus(env, signal) {
	const port = await locate(env);
	const res = await call({ path: '/device/asr/status', port, token: env.token, timeoutMs: 8000, signal });
	if (res.status !== 200) {
		throw new Error(`ASR_STATUS_${res.status}: ${res.body.toString('utf8').trim()}`);
	}
	return JSON.parse(res.body.toString('utf8'));
}

/** 把 WAV 交给 App，把结果文字带回来。 */
function createTranscribe(env, config) {
	return async function transcribe({ audio, language }, signal) {
		const bytes = audio instanceof Uint8Array
			? Buffer.from(audio.buffer, audio.byteOffset, audio.byteLength)
			: Buffer.from(audio);
		if (bytes.length < 44 || bytes.toString('ascii', 0, 4) !== 'RIFF') {
			throw new Error('ASR_AUDIO_NOT_WAV: 期望 16k 单声道 PCM16 WAV');
		}
		const lang = LANGUAGES.includes(language) ? language : 'auto';
		const port = await locate(env);
		const res = await call({
			method: 'POST',
			path: `/device/asr?language=${encodeURIComponent(lang)}`,
			body: bytes,
			port,
			token: env.token,
			timeoutMs: config.timeoutMs,
			signal,
		});
		const text = res.body.toString('utf8').trim();
		if (res.status === 200) {
			// 远程协议要求 {text, audioSeconds, inferenceSeconds}；桥的头里都有。
			const ms = Number(res.headers['x-asr-ms'] ?? 0);
			const audioMs = Number(res.headers['x-asr-audio-ms'] ?? 0);
			return {
				text,
				audioSeconds: Number.isFinite(audioMs) ? audioMs / 1000 : 0,
				inferenceSeconds: Number.isFinite(ms) ? ms / 1000 : 0,
			};
		}
		if (res.status === 503) {
			// 引擎/模型没准备好：把 App 的原话带上去，UI 会提示去"准备"。
			throw new Error(`ASR_NOT_READY: ${text}`);
		}
		throw new Error(`ASR_BRIDGE_${res.status}: ${text}`);
	};
}

/**
 * preparation：Host 侧看到的"准备进度"。
 *
 * 官方 provider 自己算哈希、自己下文件；这里全部委托给 App，插件只做状态翻译，
 * 因为模型必须落在 App 私有目录（DSH 进程写不进去，也不该写）。
 */
function createPreparation(env, config, log) {
	let state = Object.freeze({ phase: 'unprepared' });
	const listeners = new Set();
	let running = null;

	const publish = (next) => {
		state = Object.freeze(next);
		for (const listener of [...listeners]) {
			try {
				listener();
			} catch (error) {
				log?.warn?.('speech-sherpa: preparation listener failed', error);
			}
		}
	};

	/** 把 App 的一次 status 翻译成 Host 的快照。 */
	const snapshotOf = (body, extra) => {
		const download = body?.download ?? {};
		const model = body?.model ?? {};
		const base = extra ?? {};
		if (download.phase === 'downloading') {
			const startedAt = download.startedAt || Date.now();
			return {
				phase: 'downloading',
				completedBytes: download.received ?? 0,
				totalBytes: download.total ?? model.totalBytes,
				resource: 'SenseVoiceSmall int8',
				startedAt,
				steps: [
					{ kind: 'check', status: 'done' },
					{ kind: 'model', status: 'running', startedAt },
				],
				...base,
			};
		}
		if (download.phase === 'verifying' || download.phase === 'extracting') {
			return {
				phase: 'checking',
				startedAt: download.startedAt || Date.now(),
				steps: [
					{ kind: 'model', status: 'done' },
					{ kind: download.phase === 'extracting' ? 'load' : 'verify', status: 'running', startedAt: Date.now() },
				],
				...base,
			};
		}
		if (download.phase === 'error') {
			return { phase: 'failed', message: `模型下载失败：${download.error ?? '未知原因'}`, ...base };
		}
		if (download.phase === 'cancelled') {
			return { phase: 'cancelled', ...base };
		}
		if (body?.engine === 'ready') {
			return { phase: 'ready', ...base };
		}
		if (model.present) {
			// 模型在盘上、引擎还没加载：录音时会自己唤醒，属于"可用"。
			return { phase: 'standby', ...base };
		}
		if (body?.engine === 'error') {
			return { phase: 'failed', message: `本机识别引擎不可用：${body.engineError ?? '未知原因'}`, ...base };
		}
		return { phase: 'unprepared', ...base };
	};

	/** 启动时探一次，让 UI 直接知道"要下载"还是"已就绪"。 */
	const refresh = async () => {
		try {
			publish(snapshotOf(await readStatus(env)));
		} catch (error) {
			// 桥还没起来很常见（App 刚启动）：保持失败但不抛。
			publish({ phase: 'failed', message: String(error?.message ?? error) });
		}
	};

	const preparation = {
		snapshot: () => state,
		subscribe(listener) {
			listeners.add(listener);
			return () => {
				listeners.delete(listener);
			};
		},
		/**
		 * 幂等：已经在准备时再点一次不会起第二条下载。
		 * 取消只停轮询并通知 App 停下载，已下好的部分（.part）保留，下次续传。
		 */
		async prepare() {
			if (running) return;
			const session = { aborted: false };
			running = session;
			const startedAt = Date.now();
			publish({
				phase: 'checking',
				startedAt,
				steps: [{ kind: 'check', status: 'running', startedAt }],
			});
			try {
				const first = await readStatus(env);
				if (first?.model?.present) {
					publish(snapshotOf(first, { startedAt }));
					return;
				}
				await call({
					method: 'POST',
					path: '/device/asr/model?action=download',
					port: await locate(env),
					token: env.token,
					timeoutMs: 15000,
				});
				const deadline = startedAt + config.prepareTimeoutMs;
				while (!session.aborted) {
					if (Date.now() > deadline) {
						throw new Error('ASR_PREPARE_TIMEOUT: 准备超时');
					}
					await new Promise((resolve) => setTimeout(resolve, config.pollIntervalMs));
					if (session.aborted) break;
					const next = snapshotOf(await readStatus(env), { startedAt });
					publish(next);
					if (next.phase === 'ready' || next.phase === 'standby'
						|| next.phase === 'failed' || next.phase === 'cancelled') {
						return;
					}
				}
			} catch (error) {
				if (!session.aborted) {
					publish({ phase: 'failed', message: String(error?.message ?? error), startedAt });
				}
			} finally {
				running = null;
			}
		},
		async cancel() {
			if (running) running.aborted = true;
			try {
				await call({
					method: 'POST',
					path: '/device/asr/model?action=cancel',
					port: await locate(env),
					token: env.token,
					timeoutMs: 8000,
				});
			} catch {
				// 取消是尽力而为：桥不通时没有可以停的东西。
			}
		},
	};
	// refresh 不是 provider 协议的一部分，挂成不可枚举属性，避免被当作接口遍历。
	Object.defineProperty(preparation, 'refresh', { value: refresh, enumerable: false });
	return preparation;
}

export function apply(ctx, config) {
	const settings = { ...DEFAULTS, ...(config ?? {}) };
	const env = { token: settings.token || readToken(), port: Number(settings.port) || 0 };
	const preparation = createPreparation(env, settings, ctx.logger);
	ctx.effect(() => {
		const unregister = ctx.speechToText.register({
			info: {
				id: 'sherpa-android',
				name: 'Sherpa-ONNX (Android 本机)',
				location: 'host-local',
				languages: LANGUAGES,
				downloadSources: ['https://github.com'],
				setupEstimate: {
					// 163MB 压缩包 + 解开后的 230MB，再留一点余量。
					recommendedDiskBytes: 1_800_000_000,
					expectedMemoryBytes: 800_000_000,
					minimumMinutes: 2,
					maximumMinutes: 20,
				},
			},
			preparation,
			transcribe: createTranscribe(env, settings),
		});
		// 注册完立刻探一次：设置页打开时不该先看到"需要下载"再跳成"已就绪"。
		void preparation.refresh();
		return async () => {
			await unregister();
		};
	});
}

export const __test = { locate, call, readToken, readPort, dshHome, LANGUAGES, MODEL_URL, createPreparation };
