# dsh-speech-sherpa 0.1.0

DSH on Android 的本机语音识别 provider。音频不出设备，识别不产生任何云端调用费。

## 它解决什么

`@deepseek-ai/dsh-experimental-speech-to-text-sensevoice` 走 `sherpa-onnx-node`，
官方只发布 glibc 的 `linux-arm64` 预编译，Android 的 bionic 加载不了，于是语音按钮
直接报：

```
准备失败：Local speech is unavailable for android-arm64
```

App（`com.dsh.android`）从 1.0.25 起自带 sherpa-onnx 官方 AAR 的 arm64-v8a JNI，
并暴露 `POST /device/asr`。本插件把 Host 的 `speechToText` 服务接到那个端点上。

## 链路

```
WebView 录音 (16k mono PCM16 WAV, encodeWave)
  → api/speechToText Remote
  → SpeechToText 服务 (resolve → provider.transcribe)
  → 本插件: POST http://127.0.0.1:<port>/device/asr?language=zh
  → App SpeechBridge → libsherpa-onnx-jni.so → SenseVoiceSmall int8
```

## 桥的定位方式

| 项 | 来源 |
|---|---|
| 端口 | `$DSH_HOME/device.port` → 3090–3096 探测 |
| token | `$DSH_HOME/.device-token` 或 `<files>/.device-token`（HTTP 头 `X-Device-Token`） |

## 配置

```yaml
- id: speech-sherpa
  name: 'dsh-speech-sherpa'
  config:
    port: 0                 # 0 = 自动（读 device.port，再探测 3090–3096）
    token: ''               # 空 = 读 $DSH_HOME/.device-token；只在默认路径变了时显式给
    timeoutMs: 120000       # 单次识别上限
    prepareTimeoutMs: 1800000
    pollIntervalMs: 700
```

## 模型

`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2`（163,002,883 B，
sha256 `7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e`），
由 App 首次准备时下载到 `<files>/models/sensevoice/`，用系统自带的 `toybox tar -xjf` 解开。
中文/英语/日语/韩语/粤语 + 自动语种识别 + 自带标点与数字归一化。

## 排查

```sh
PORT=$(cat /data/data/com.dsh.android/files/home/.dsh/device.port)
TOKEN=$(cat /data/data/com.dsh.android/files/.device-token)
curl -s "http://127.0.0.1:$PORT/device/asr/status" -H "X-Device-Token: $TOKEN"
curl -s --data-binary @test.wav "http://127.0.0.1:$PORT/device/asr?language=auto" \
     -H "X-Device-Token: $TOKEN"
```
