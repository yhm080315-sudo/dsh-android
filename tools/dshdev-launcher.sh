#!/system/bin/sh
# dshdev 启动器：/usr/bin/env 在 Android 上不存在，所以 node 脚本不能靠 shebang 直接执行。
# 与 NodeServer 里的路径约定一致（应用私有目录下的内嵌运行时）。
exec /data/user/0/com.dsh.android/files/runtime/usr/bin/node \
     /data/user/0/com.dsh.android/files/runtime/usr/bin/dshdev.js "$@"
