package com.dsh.android;

import android.content.res.AssetManager;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 本机语音识别（sherpa-onnx + SenseVoiceSmall int8），完全不联网推理。
 *
 * <p>为什么要这一层：官方的 {@code @deepseek-ai/dsh-experimental-speech-to-text-sensevoice}
 * 走 {@code sherpa-onnx-node}，只有 glibc 的 linux-arm64 预编译，bionic 加载不了；
 * 所以识别搬到 App 进程里，用 sherpa-onnx 官方 AAR 的 JNI（arm64-v8a），
 * DSH 侧只保留一个把 WAV 转给这里的 provider。
 *
 * <p>两条路径：
 * <ul>
 *   <li>推理：{@link #transcribe(byte[], String)} —— WAV(PCM16) → 16k float → 文字；</li>
 *   <li>模型：{@link #modelAction(String)} —— 首次使用时下载 163MB 的
 *       {@code sherpa-onnx-sense-voice-...-int8.tar.bz2}，校验后用系统自带的
 *       {@code toybox tar -xjf} 解开（Android 没有 Java 的 bzip2 实现，toybox 有）。</li>
 * </ul>
 *
 * <p>线程模型：推理用一把 {@link ReentrantLock} 串行化（一个 recognizer 实例不是线程安全的，
 * 而且并发跑只会抢 CPU），下载在独立后台线程里，端点读 {@link DownloadState} 的 volatile
 * 字段，因此 163MB 的下载不会占住 device bridge 的请求线程。
 */
final class SpeechBridge {

    private static final String TAG = "DSH";

    /** 模型放 App 私有目录：不占共享存储、卸载即清，也不需要任何运行时权限。 */
    private static final String MODEL_SUBDIR = "models/sensevoice";
    private static final String MODEL_FILE = "model.int8.onnx";
    private static final String TOKENS_FILE = "tokens.txt";

    /**
     * 官方 release 的固定 URL。选 SenseVoiceSmall int8 而不是 paraformer-zh-small
     * 或 whisper-tiny：一个模型覆盖中/英/日/韩/粤并自带语种识别与标点，中文准确率
     * 明显好于 whisper-tiny，也不需要 paraformer 那样再挂一个标点模型。
     */
    static final String MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/"
            + "asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2";
    /** 发布时的字节数与 sha256，用来证明下载到的是同一份文件（构建期在 chroot 里算过）。 */
    static final long MODEL_BYTES = 163002883L;
    static final String MODEL_SHA256 =
            "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e";

    /** SenseVoice 认的语言标签；auto 让它自己判断。 */
    private static final String[] LANGUAGES = {"auto", "zh", "en", "yue", "ja", "ko"};

    /** 一次录音的上限：超过就别再浪费内存，直接拒绝。 */
    private static final int MAX_SAMPLES = 16000 * 120;

    private static volatile SpeechBridge instance;

    private final File filesDir;
    /** 推理串行化：一个 OfflineRecognizer 不是线程安全的。 */
    private final ReentrantLock inferLock = new ReentrantLock();
    /** 引擎的加载/释放都在这把锁里。 */
    private final Object engineLock = new Object();
    private volatile OfflineRecognizer engine;
    private volatile String engineFailure;
    private volatile long engineLoadMs = -1;
    private final DownloadState download = new DownloadState(this);

    private SpeechBridge(File filesDir) {
        this.filesDir = filesDir;
    }

    static SpeechBridge get(File filesDir) {
        SpeechBridge current = instance;
        if (current == null) {
            synchronized (SpeechBridge.class) {
                current = instance;
                if (current == null) {
                    current = new SpeechBridge(filesDir);
                    instance = current;
                }
            }
        }
        return current;
    }

    // ------------------------------------------------------------------ 模型

    private File modelDir() {
        return new File(filesDir, MODEL_SUBDIR);
    }

    private File modelFile() {
        return new File(modelDir(), MODEL_FILE);
    }

    private File tokensFile() {
        return new File(modelDir(), TOKENS_FILE);
    }

    boolean modelPresent() {
        File model = modelFile();
        File tokens = tokensFile();
        // 大小下限只是挡住 0 字节/半截文件；完整性由下载时的 sha256 与 EOCD 之外的
        // onnxruntime 加载结果共同保证，重复算 228MB 的 sha256 不值得。
        return model.isFile() && model.length() > 50L * 1024 * 1024
                && tokens.isFile() && tokens.length() > 1024;
    }

    // ----------------------------------------------------------------- 推理

    /** 识别结果：文字 + 语种 + 耗时，直接给 HTTP 头用。 */
    static final class Result {
        final String text;
        final String language;
        final long durationMs;
        final long inferenceMs;

        Result(String text, String language, long durationMs, long inferenceMs) {
            this.text = text;
            this.language = language;
            this.durationMs = durationMs;
            this.inferenceMs = inferenceMs;
        }
    }

    /** 引擎不可用（模型没下载 / JNI 加载失败）；DeviceServer 把它映射成 503。 */
    static final class Unavailable extends Exception {
        Unavailable(String message) {
            super(message);
        }
    }

    /**
     * 识别一段 WAV。
     *
     * @param wav      canonical PCM16 WAV（前端 encodeWave 的产物，16k/单声道；其它采样率也接）
     * @param language auto|zh|en|yue|ja|ko，null 当 auto
     */
    Result transcribe(byte[] wav, String language) throws Unavailable, IOException {
        String lang = language == null || language.isEmpty() ? "auto" : language;
        boolean known = false;
        for (String candidate : LANGUAGES) {
            if (candidate.equals(lang)) {
                known = true;
                break;
            }
        }
        if (!known) {
            throw new IOException("unsupported language: " + lang);
        }
        Wave wave = Wave.parse(wav);
        if (wave.sampleCount() == 0) {
            throw new IOException("audio has no samples");
        }
        if (wave.sampleCount() > MAX_SAMPLES) {
            throw new IOException("audio too long: " + wave.sampleCount() + " samples");
        }
        OfflineRecognizer recognizer = recognizer(lang);
        inferLock.lock();
        try {
            long started = System.currentTimeMillis();
            OfflineStream stream = recognizer.createStream();
            try {
                stream.acceptWaveform(wave.samples, wave.sampleRate);
                recognizer.decode(stream);
                OfflineRecognizerResult result = recognizer.getResult(stream);
                String text = result.getText() == null ? "" : result.getText().trim();
                String detected = result.getLang() == null ? lang : result.getLang();
                long ms = System.currentTimeMillis() - started;
                Log.i(TAG, "asr: " + wave.sampleCount() + " samples @" + wave.sampleRate
                        + " in " + ms + "ms -> " + (text.length() > 40 ? text.length() + " chars" : text));
                return new Result(text, detected, wave.durationMs(), ms);
            } finally {
                stream.release();
            }
        } catch (Throwable t) {
            // 不要把 recognizer 留下一个不明确的状态：下次重建。
            dropEngine("inference failed: " + t);
            throw new IOException("inference failed: " + t);
        } finally {
            inferLock.unlock();
        }
    }

    /**
     * 懒加载识别器。
     *
     * <p>AssetManager 传 null：模型在文件系统里，sherpa-onnx 会走 `newFromFile`
     * 分支，不碰 APK 的 assets。
     */
    private OfflineRecognizer recognizer(String language) throws Unavailable {
        OfflineRecognizer current = engine;
        if (current != null) {
            return current;
        }
        synchronized (engineLock) {
            if (engine != null) {
                return engine;
            }
            if (!modelPresent()) {
                throw new Unavailable("ASR_MODEL_MISSING: " + modelDir().getAbsolutePath()
                        + " 里没有 " + MODEL_FILE + "，先调用 /device/asr/model?action=download");
            }
            try {
                FeatureConfig feat = new FeatureConfig(16000, 80, 0f);
                OfflineSenseVoiceModelConfig senseVoice = new OfflineSenseVoiceModelConfig();
                senseVoice.setModel(modelFile().getAbsolutePath());
                senseVoice.setLanguage(language);
                senseVoice.setUseInverseTextNormalization(true);
                OfflineModelConfig model = new OfflineModelConfig();
                model.setSenseVoice(senseVoice);
                model.setTokens(tokensFile().getAbsolutePath());
                model.setModelType("sense-voice");
                // 2 线程在 chroot 实测最好（1 线程 158ms、2 线程 97ms、4 线程 109ms / 5.6s 音频）。
                model.setNumThreads(2);
                model.setDebug(false);
                model.setProvider("cpu");
                OfflineRecognizerConfig config = new OfflineRecognizerConfig();
                config.setFeatConfig(feat);
                config.setModelConfig(model);
                config.setDecodingMethod("greedy_search");
                long started = System.currentTimeMillis();
                OfflineRecognizer created =
                        new OfflineRecognizer((AssetManager) null, config);
                engineLoadMs = System.currentTimeMillis() - started;
                engine = created;
                engineFailure = null;
                Log.i(TAG, "asr engine loaded in " + engineLoadMs + "ms");
                return created;
            } catch (Throwable t) {
                engineFailure = describe(t);
                Log.w(TAG, "asr engine could not load", t);
                throw new Unavailable("ASR_ENGINE_UNAVAILABLE: " + engineFailure);
            }
        }
    }

    /** UnsatisfiedLinkError 的 message 常常只有一行，补上最可能的原因。 */
    private static String describe(Throwable t) {
        String message = String.valueOf(t);
        if (t instanceof UnsatisfiedLinkError) {
            return message + " (lib/arm64-v8a 里的 sherpa-onnx JNI 没被解压？)";
        }
        return message;
    }

    private void dropEngine(String why) {
        synchronized (engineLock) {
            OfflineRecognizer current = engine;
            engine = null;
            engineFailure = why;
            if (current != null) {
                try {
                    current.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ----------------------------------------------------------------- 状态

    /** JSON 片段：引擎 + 模型 + 下载。字段名稳定，插件和真机排查都读它。 */
    String statusJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"engine\":\"").append(engine != null ? "ready"
                : engineFailure == null ? "idle" : "error").append("\",");
        if (engineFailure != null) {
            sb.append("\"engineError\":").append(json(engineFailure)).append(',');
        }
        sb.append("\"engineLoadMs\":").append(engineLoadMs).append(',');
        sb.append("\"model\":{\"present\":").append(modelPresent())
                .append(",\"dir\":").append(json(modelDir().getAbsolutePath()))
                .append(",\"bytes\":").append(modelFile().length())
                .append(",\"tokens\":").append(tokensFile().length())
                .append(",\"url\":").append(json(MODEL_URL))
                .append(",\"totalBytes\":").append(MODEL_BYTES)
                .append(",\"sha256\":").append(json(MODEL_SHA256)).append("},");
        sb.append("\"languages\":[");
        for (int i = 0; i < LANGUAGES.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(LANGUAGES[i]).append('"');
        }
        sb.append("],");
        sb.append("\"threads\":2,");
        sb.append("\"download\":").append(download.json());
        sb.append('}');
        return sb.toString();
    }

    /**
     * {@code /device/asr/model?action=...}
     *
     * <p>download 立即返回（163MB 不能挂在请求线程上），cancel 只是置位让下载循环自己退出。
     */
    String modelAction(String action) {
        String act = action == null || action.isEmpty() ? "status" : action;
        if (act.equals("download")) {
            if (modelPresent()) {
                download.phase = "done";
                return download.json();
            }
            download.start();
        } else if (act.equals("cancel")) {
            download.cancel();
        } else if (!act.equals("status")) {
            return "{\"error\":\"unknown action: " + act.replace("\"", "") + "\"}";
        }
        return download.json();
    }

    // ----------------------------------------------------------- 下载状态机

    /**
     * 下载/解包的状态。
     *
     * <p>所有字段 volatile 而不是加锁：端点每秒可能被轮询十几次，读一个一致但不严格
     * 原子的快照足够，真需要互斥的是"谁来起线程"，那由 {@link #start()} 的同步块保证。
     */
    static final class DownloadState {
        /** 下载器要读回 owner 的模型路径与日志；DownloadState 是 static，所以显式持有。 */
        private final SpeechBridge owner;
        volatile String phase = "idle"; // idle|downloading|verifying|extracting|done|error|cancelled
        volatile long received;
        volatile long total = MODEL_BYTES;
        volatile String error;
        volatile long startedAt;
        volatile long finishedAt;
        volatile boolean cancelRequested;
        private Thread worker;

        DownloadState(SpeechBridge owner) {
            this.owner = owner;
        }

        synchronized void start() {
            if (worker != null && worker.isAlive()) {
                return;
            }
            cancelRequested = false;
            error = null;
            phase = "downloading";
            startedAt = System.currentTimeMillis();
            finishedAt = 0;
            received = 0;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        new Run(owner).execute();
                    } finally {
                        synchronized (DownloadState.this) {
                            worker = null;
                        }
                    }
                }
            }, "dsh-asr-model");
            thread.setDaemon(true);
            worker = thread;
            thread.start();
        }

        void cancel() {
            if (worker != null && worker.isAlive()) {
                cancelRequested = true;
                phase = "cancelled";
            }
        }

        String json() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"phase\":").append(SpeechBridge.json(phase))
                    .append(",\"received\":").append(received)
                    .append(",\"total\":").append(total)
                    .append(",\"startedAt\":").append(startedAt)
                    .append(",\"finishedAt\":").append(finishedAt);
            if (error != null) {
                sb.append(",\"error\":").append(SpeechBridge.json(error));
            }
            sb.append('}');
            return sb.toString();
        }
    }

    /** 一次完整的下载 + 校验 + 解包。独立类只是为了让 {@link DownloadState} 别太长。 */
    private static final class Run {
        private final SpeechBridge owner;
        private final DownloadState state;

        Run(SpeechBridge owner) {
            this.owner = owner;
            this.state = owner.download;
        }

        void execute() {
            File dir = owner.modelDir();
            File tar = new File(dir, "model.tar.bz2");
            File part = new File(dir, "model.tar.bz2.part");
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IOException("cannot create " + dir);
                }
                long existing = part.isFile() ? part.length() : 0;
                if (existing >= MODEL_BYTES) {
                    // 上一次其实已经下完，只是没来得及改名。
                    existing = 0;
                    part.delete();
                }
                if (!part.isFile() || existing == 0) {
                    state.received = 0;
                } else {
                    state.received = existing;
                }
                fetch(part, existing);
                if (state.cancelRequested) {
                    return;
                }
                state.phase = "verifying";
                long size = part.length();
                if (size != MODEL_BYTES) {
                    throw new IOException("size mismatch: " + size + " != " + MODEL_BYTES);
                }
                String digest = sha256(part);
                if (!MODEL_SHA256.equalsIgnoreCase(digest)) {
                    part.delete();
                    throw new IOException("sha256 mismatch: " + digest);
                }
                if (!part.renameTo(tar)) {
                    // 目标已存在时 renameTo 在某些实现上会失败：先删再试一次。
                    tar.delete();
                    if (!part.renameTo(tar)) {
                        throw new IOException("cannot publish " + tar);
                    }
                }
                if (state.cancelRequested) {
                    return;
                }
                state.phase = "extracting";
                extract(tar, dir);
                tar.delete();
                state.phase = "done";
                state.finishedAt = System.currentTimeMillis();
                DiagnosticServer.appendLog(owner.filesDir,
                        "asr model ready: " + owner.modelFile().getAbsolutePath()
                                + " (" + owner.modelFile().length() + " B)");
                Log.i(TAG, "asr model ready in " + (state.finishedAt - state.startedAt) + "ms");
            } catch (Throwable t) {
                if (state.cancelRequested) {
                    state.phase = "cancelled";
                } else {
                    state.phase = "error";
                    state.error = t.toString();
                    Log.w(TAG, "asr model download failed", t);
                    DiagnosticServer.appendLog(owner.filesDir, "asr model download failed: " + t);
                }
                state.finishedAt = System.currentTimeMillis();
            }
        }

        /**
         * 流式下载，支持断点续传。
         *
         * <p>GitHub release 会 302 到 objects.githubusercontent.com，HttpURLConnection 默认跟随；
         * 服务器给 206 就追加，给 200 就从零写（对端不支持 Range 时的正确行为）。
         */
        private void fetch(File part, long resumeFrom) throws IOException {
            HttpURLConnection conn = (HttpURLConnection) new URL(MODEL_URL).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "dsh-android-asr");
            if (resumeFrom > 0) {
                conn.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
            }
            int code = conn.getResponseCode();
            boolean append = code == 206 && resumeFrom > 0;
            if (code != 200 && code != 206) {
                throw new IOException("HTTP " + code + " from " + MODEL_URL);
            }
            long written = append ? resumeFrom : 0;
            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(part, append);
            try {
                byte[] buffer = new byte[64 * 1024];
                long lastReport = 0;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (state.cancelRequested) {
                        throw new IOException("cancelled");
                    }
                    out.write(buffer, 0, n);
                    written += n;
                    long now = System.currentTimeMillis();
                    if (now - lastReport > 250) {
                        lastReport = now;
                        state.received = written;
                    }
                }
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
                try {
                    in.close();
                } catch (IOException ignored) {
                }
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
            state.received = written;
        }

        /** 解包：toybox 的 tar 支持 -j（bzip2），不需要 Java 侧的 bz2 实现。 */
        private void extract(File tar, File dir) throws IOException, InterruptedException {
            File tmp = new File(dir, "unpack");
            deleteTree(tmp);
            if (!tmp.mkdirs()) {
                throw new IOException("cannot create " + tmp);
            }
            Process process = new ProcessBuilder("/system/bin/tar", "-xjf",
                    tar.getAbsolutePath())
                    .directory(tmp)
                    .redirectErrorStream(true)
                    .start();
            ByteArrayOutputStream log = new ByteArrayOutputStream();
            copy(process.getInputStream(), log);
            int code = process.waitFor();
            if (code != 0) {
                throw new IOException("tar exited " + code + ": " + log.toString("UTF-8").trim());
            }
            File[] roots = tmp.listFiles();
            File source = roots != null && roots.length == 1 ? roots[0] : tmp;
            File model = new File(source, MODEL_FILE);
            File tokens = new File(source, TOKENS_FILE);
            if (!model.isFile() || !tokens.isFile()) {
                throw new IOException("archive does not contain " + MODEL_FILE + " and " + TOKENS_FILE);
            }
            moveInto(model, owner.modelFile());
            moveInto(tokens, owner.tokensFile());
            deleteTree(tmp);
        }

        private void moveInto(File from, File to) throws IOException {
            if (to.exists() && !to.delete()) {
                throw new IOException("cannot replace " + to);
            }
            if (!from.renameTo(to)) {
                // 跨文件系统（解压目录与模型目录同盘，理论上不会）时退回复制。
                FileOutputStream out = new FileOutputStream(to);
                try {
                    InputStream in = new FileInputStream(from);
                    try {
                        copy(in, out);
                    } finally {
                        in.close();
                    }
                } finally {
                    out.close();
                }
                from.delete();
            }
        }

        private void deleteTree(File file) {
            if (file == null || !file.exists()) {
                return;
            }
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteTree(child);
                }
            }
            file.delete();
        }

        private static void copy(InputStream in, java.io.OutputStream out) throws IOException {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        }

        private static String sha256(File file) throws IOException {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                InputStream in = new FileInputStream(file);
                try {
                    byte[] buffer = new byte[256 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        digest.update(buffer, 0, n);
                    }
                } finally {
                    in.close();
                }
                StringBuilder hex = new StringBuilder();
                for (byte value : digest.digest()) {
                    hex.append(String.format(Locale.US, "%02x", value));
                }
                return hex.toString();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException(e);
            }
        }
    }

    // ------------------------------------------------------------------- WAV

    /** 最小 WAV 解析：只认 PCM16，返回交错转单声道后的 float 样本与原采样率。 */
    static final class Wave {
        final float[] samples;
        final int sampleRate;

        private Wave(float[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }

        long durationMs() {
            return sampleRate <= 0 ? 0 : (long) (samples.length * 1000L / sampleRate);
        }

        int sampleCount() {
            return samples.length;
        }

        static Wave parse(byte[] b) throws IOException {
            if (b == null || b.length < 44
                    || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F'
                    || b[8] != 'W' || b[9] != 'A' || b[10] != 'V' || b[11] != 'E') {
                throw new IOException("not a RIFF/WAVE file");
            }
            int pos = 12;
            int dataOffset = -1;
            int dataLength = 0;
            int channels = 1;
            int sampleRate = 16000;
            int bits = 16;
            while (pos + 8 <= b.length) {
                String id = new String(b, pos, 4, "US-ASCII");
                int size = (b[pos + 4] & 0xff) | ((b[pos + 5] & 0xff) << 8)
                        | ((b[pos + 6] & 0xff) << 16) | ((b[pos + 7] & 0xff) << 24);
                int body = pos + 8;
                if (size < 0) {
                    break;
                }
                if (id.equals("fmt ") && body + 16 <= b.length) {
                    channels = Math.max(1, (b[body + 2] & 0xff) | ((b[body + 3] & 0xff) << 8));
                    sampleRate = (b[body + 4] & 0xff) | ((b[body + 5] & 0xff) << 8)
                            | ((b[body + 6] & 0xff) << 16) | ((b[body + 7] & 0xff) << 24);
                    bits = (b[body + 14] & 0xff) | ((b[body + 15] & 0xff) << 8);
                } else if (id.equals("data")) {
                    dataOffset = body;
                    dataLength = Math.min(size, b.length - body);
                    break;
                }
                pos = body + size + (size & 1);
            }
            if (dataOffset < 0) {
                throw new IOException("WAV has no data chunk");
            }
            if (bits != 16) {
                throw new IOException("only PCM16 is supported, got " + bits + " bits");
            }
            if (sampleRate < 4000 || sampleRate > 192000) {
                throw new IOException("unreasonable sample rate: " + sampleRate);
            }
            int frames = dataLength / 2 / channels;
            float[] samples = new float[frames];
            int p = dataOffset;
            for (int i = 0; i < frames; i++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    int lo = b[p++] & 0xff;
                    int hi = b[p++] & 0xff;
                    sum += (short) ((hi << 8) | lo);
                }
                samples[i] = (sum / (float) channels) / 32768f;
            }
            return new Wave(samples, sampleRate);
        }
    }

    // ------------------------------------------------------------------ JSON

    static String json(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
