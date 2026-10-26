#!/system/bin/sh
#
# 在外部应用里打开一个文件。
#
# Android 上没有桌面。「用外部程序打开」＝ 系统分享面板：把路径交给本 App
# （com.dsh.android）的 /device/open，由它用 FileProvider 生成 content:// URI
# 并拉起选择器，用户挑编辑器 / WPS / 看图器。
#
# 两个曾经的坑（都实测过）：
#   1. 端口别写死。DeviceServer 从 3090 起挨个试第一个能绑上的口，每台机器
#      可能不同（3090 被别的 App 占着就落到 3091 了）；按下面三级解析取。
#   2. 不要用 /root —— 应用沙箱里没有这个目录，令牌在本 App 的 HOME 下。
#
# 目录没有特例：它同样交给 /device/open，App 会明确回一个不可分享的错误，
# 下面按响应体判定，以非零退出 —— 静默"成功"比失败更糟。
#
# 用法：dsh-open <绝对路径>
#
set -u
TARGET="${1:-}"
[ -n "$TARGET" ] || exit 3
[ -e "$TARGET" ] || exit 4

HOME_DIR="${DSH_HOME:-${HOME:-}}"
TOKEN_FILE="$HOME_DIR/.device-token"
[ -f "$TOKEN_FILE" ] || { echo "dsh-open: no device token at $TOKEN_FILE" >&2; exit 5; }
TOKEN="$(cat "$TOKEN_FILE")"

# 端口三级解析：环境变量 → $HOME/device.port → 探测 3090–3096。
# 说明：App 与 harness 目前都不写 device.port（DeviceServer.currentPort() 只是知道
# 端口，没落盘），这一级留给外部接管、也给以后写它的人；真正兜底的是下面的探测。
# 探测认响应体里的 "device bridge"，不认「TCP 连上了」—— 3090 可能是别的
# App 的桥，也可能被完全无关的服务占着。
# 1、2 两级一旦给了值就不再探测：写错了要直接失败，不能悄悄换个端口混过去。
PORT="${DSH_DEVICE_PORT:-}"
if [ -z "$PORT" ] && [ -f "$HOME_DIR/device.port" ]; then PORT="$(cat "$HOME_DIR/device.port")"; fi
if [ -z "$PORT" ]; then
  for p in 3090 3091 3092 3093 3094 3095 3096; do
    if node -e 'const[b,t]=process.argv.slice(1);fetch(b,{headers:{"x-device-token":t},signal:AbortSignal.timeout(1200)}).then(async r=>{const s=await r.text();process.exit(r.ok&&s.includes("device bridge")?0:1)}).catch(()=>process.exit(1))' "http://127.0.0.1:$p/device/status" "$TOKEN" 2>/dev/null; then PORT="$p"; break; fi
  done
fi
[ -n "$PORT" ] || { echo "dsh-open: no device bridge found" >&2; exit 7; }

# curl 只要连上就 exit 0，而 harness 只按退出码判成败 —— 不检查响应体的话，
# 界面会显示「已打开」而实际什么都没发生（复审者实测到过这个状态）。
# 不依赖 curl（载荷里没有 curl）：用 node 自带的 fetch；参数走位置参数，避免路径里的空格/中文被 shell 拆开。
RESP="$(
  node -e '
const [base, token, target] = process.argv.slice(1);
const url = new URL(base);
url.searchParams.set("path", target);
fetch(url, { headers: { "x-device-token": token }, signal: AbortSignal.timeout(10000) })
  .then(async (r) => {
    const body = await r.text();
    if (!r.ok) { console.error("dsh-open: HTTP " + r.status + " " + body); process.exit(7); }
    process.stdout.write(body);
  })
  .catch((e) => { console.error("dsh-open: " + ((e && e.message) || e)); process.exit(7); });
' "http://127.0.0.1:$PORT/device/open" "$TOKEN" "$TARGET"
)" || exit 7
case "$RESP" in
    *'"error"'*)
        echo "dsh-open: $RESP" >&2
        exit 6
        ;;
esac
printf '%s\n' "$RESP"
exit 0
