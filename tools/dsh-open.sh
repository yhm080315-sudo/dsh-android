#!/system/bin/sh
#
# 在外部应用里打开一个文件。
#
# Android 上没有桌面。这里的「用外部程序打开」＝ 系统分享面板：
# 本进程把路径交给**本 App**（com.dsh.android）的 /device/open，
# 由它用 FileProvider 生成 content:// URI 并拉起选择器，用户挑编辑器 / WPS / 看图器。
#
# 两个曾经的坑（都实测过）：
#   1. 不要敲 3090 —— 那是另一个 App（com.dsh.client）的桥，还需要它的凭据文件；
#   2. 不要用 /root —— 应用沙箱里没有这个目录。令牌在本 App 的 HOME 下。
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

# 目录没法分享；交给 App 提示用户（用同样的通道，免得又是另一个不存在的端点）。
if [ -d "$TARGET" ]; then
    exec curl -s --max-time 10 -G "http://127.0.0.1:$PORT/device/open" \
        --data-urlencode "path=$TARGET" \
        -H "x-device-token: $TOKEN"
fi

exec curl -s --max-time 10 -G "http://127.0.0.1:$PORT/device/open" \
    --data-urlencode "path=$TARGET" \
    -H "x-device-token: $TOKEN"
