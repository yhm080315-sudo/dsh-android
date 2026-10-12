#!/usr/bin/env bash
#
# 判定一个二进制能不能在 Android 应用沙箱里跑。
#
# 用法：
#   bash tools/check-runtime.sh /path/to/node
#   bash tools/check-runtime.sh /path/to/gcc-built-binary   # 任何二进制
#
# 背景：Android 应用的 seccomp 过滤器拒绝 rseq 系统调用，而 glibc 在
# __libc_start_main 早期就会注册它——结果是二进制在 main() 之前就被杀，
# 退出码 159（128 + SIGSYS），连一行错误信息都没有。
#
# musl 更隐蔽：--version 之类的路径能过，一旦执行 JS（V8 初始化）才死。
# 所以对 Node 这类运行时，光看 --version 是不够的，必须再跑一段 JS。
#
set -uo pipefail

BIN="${1:-}"
if [ -z "$BIN" ] || [ ! -x "$BIN" ]; then
    echo "用法: $0 <可执行文件路径>" >&2
    exit 2
fi

echo "检查: $BIN"
echo

# 1) 启动是否被 seccomp 打断
VERSION_OUT="$("$BIN" --version 2>&1)"
RC=$?
if [ "$RC" -eq 159 ]; then
    echo "  ✗ 启动即被杀（exit 159 = 128+31 SIGSYS）"
    echo "    这个运行时的 libc 会注册 rseq，Android 应用沙箱不允许。"
    echo "    结论：glibc 系（静态/动态都一样）无法使用。"
    exit 1
elif [ "$RC" -ne 0 ]; then
    echo "  ? --version 退出码 $RC（不一定是 seccomp，先看输出）"
    echo "    输出：$VERSION_OUT"
else
    echo "  ✓ 启动正常：$VERSION_OUT"
fi

# 2) 是否是真 Node：再跑一段 JS（musl 就死在这一步）
if "$BIN" --version 2>/dev/null | grep -q "^v[0-9]"; then
    JS_OUT="$("$BIN" -e 'console.log("JS_OK " + process.version + " " + process.platform)' 2>&1)"
    JS_RC=$?
    if [ "$JS_RC" -eq 159 ]; then
        echo "  ✗ 执行 JavaScript 时被杀（exit 159）"
        echo "    --version 能过、一跑 JS 就死 —— 这是 musl 的典型症状（V8 初始化触发 rseq）。"
        echo "    结论：musl 构建无法使用。"
        exit 1
    elif [ "$JS_RC" -ne 0 ]; then
        echo "  ✗ 执行 JavaScript 失败（退出码 $JS_RC）"
        echo "    $JS_OUT" | head -3
        exit 1
    fi
    echo "  ✓ 执行 JavaScript 正常：$JS_OUT"

    # 3) 模块加载（原生 addon 缺失会在这里暴露）
    MOD_OUT="$("$BIN" -e 'require("node:fs"); console.log("REQUIRE_OK")' 2>&1)"
    if [ "$?" -eq 0 ]; then
        echo "  ✓ 模块加载正常"
    else
        echo "  ? 模块加载异常：$(echo "$MOD_OUT" | head -1)"
    fi
fi

echo
echo "结论：这个二进制可以在 Android 应用沙箱里运行。"
echo
echo "参考：Termux 的 bionic 构建是已知可用的来源："
echo "  https://github.com/termux/termux-packages"
