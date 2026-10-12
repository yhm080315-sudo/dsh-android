#!/usr/bin/env bash
#
# 查看运行中的 App 状态。
#
# 三种方式，按可用性回退：
#   1) 应用自带的只读诊断口（127.0.0.1:3097-3099）—— 仅在同一网络命名空间内可达
#   2) 外置日志（App 不运行也能读）
#   3) adb 查包信息与进程
#
# adb 用法：
#   ADB="adb"                   # USB
#   ADB="adb -s 192.168.1.5"    # 网络连接
#
set -uo pipefail

PKG="com.dsh.android"
LOG="/sdcard/Android/data/$PKG/files/dsh.log"
ADB="${ADB:-adb}"

for port in 3097 3098 3099; do
    if out=$(curl -s --max-time 5 "http://127.0.0.1:$port/" 2>/dev/null) && [ -n "$out" ]; then
        echo "--- 应用诊断（端口 $port）---"
        echo "$out"
        exit 0
    fi
done

echo "== 安装信息 =="
$ADB shell "dumpsys package $PKG" 2>/dev/null \
    | grep -E "versionCode|versionName|lastUpdateTime|firstInstallTime" | head -4

echo
echo "== 进程 =="
$ADB shell "ps -A -o USER,PID,STAT,NAME" 2>/dev/null \
    | grep -E "dsh|node" | head -5

echo
echo "== 外置日志（最后 20 行）=="
if [ -f "$LOG" ]; then
    tail -20 "$LOG"
else
    $ADB shell "tail -20 $LOG" 2>/dev/null || echo "（读不到，App 可能从未运行）"
fi

echo
echo "提示：日志里这几行是健康标志——"
echo "  profile node_modules: N bundled packages linked"
echo "  page check 1: 3xxxx bytes bootstrap-tag=true [ready]   （3709 字节 = 插件图为空）"
echo "  dsh web: http://127.0.0.1:3220/?token=..."
