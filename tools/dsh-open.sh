#!/system/bin/sh
#
# 在外部应用里打开一个文件。
#
# Android 上没有「桌面」，桌面端那套「用 VS Code / 访达打开」在这里不存在。
# 正确的对应动作是**分享到其它应用**：系统会弹出选择器，用户挑文本编辑器、WPS、
# 图片查看器……这一步由 App 的桥来完成（/app/share）。
#
# 用法：dsh-open <绝对路径>
# 退出码：0 已请求打开 / 2 不是文件（目录无法分享）/ 3 桥不可用
#
set -u
TARGET="${1:-}"
[ -n "$TARGET" ] || exit 3
if [ -d "$TARGET" ]; then
    # 目录没法「分享」，交给 App 提示用户去用文件管理器。
    exec curl -s -G "http://127.0.0.1:3090/app/toast" \
        --data-urlencode "text=文件夹请用设备上的文件管理器打开：$TARGET" \
        -H @/root/.dsh/.bridge_headers >/dev/null 2>&1
fi
[ -e "$TARGET" ] || exit 3
exec curl -s -G "http://127.0.0.1:3090/app/share" \
    --data-urlencode "path=$TARGET" \
    -H @/root/.dsh/.bridge_headers
