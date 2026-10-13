#!/system/bin/sh
#
# 在外部应用里打开一个文件。
#
# Android 上没有桌面。「用外部程序打开」＝ 系统分享面板：把路径交给本 App
# （com.dsh.android）的 /device/open，由它用 FileProvider 生成 content:// URI
# 并拉起选择器，用户挑编辑器 / WPS / 看图器。
#
# 两个曾经的坑（都实测过）：
#   1. 不要敲 3090 —— 那是另一个 App（com.dsh.client）的桥，还要它的凭据文件；
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

PORT="${DSH_DEVICE_PORT:-3091}"
HOME_DIR="${DSH_HOME:-${HOME:-}}"
TOKEN_FILE="$HOME_DIR/.device-token"
[ -f "$TOKEN_FILE" ] || { echo "dsh-open: no device token at $TOKEN_FILE" >&2; exit 5; }
TOKEN="$(cat "$TOKEN_FILE")"

# curl 只要连上就 exit 0，而 harness 只按退出码判成败 —— 不检查响应体的话，
# 界面会显示「已打开」而实际什么都没发生（复审者实测到过这个状态）。
RESP="$(curl -s --max-time 10 -G "http://127.0.0.1:$PORT/device/open" \
    --data-urlencode "path=$TARGET" \
    -H "x-device-token: $TOKEN")" || exit 7
case "$RESP" in
    *'"error"'*)
        echo "dsh-open: $RESP" >&2
        exit 6
        ;;
esac
printf '%s\n' "$RESP"
exit 0
