# Shizuku 提权通道（非 root 设备）

`screencap` / `input` 需要 shell 或 root 身份，普通应用调不了。这里记录应用怎样在**没有 root**的
条件下拿到这两样能力，以及"提权来源"在代码里的位置。

## 1. 抽象：按能力路由，而不是一个全局提权开关

代码：`app/src/com/dsh/android/DeviceShell.java`

```
capture  → Backend.capture()             screencap（今天）；MediaProjection（以后）
input    → Backend.tap/swipe/key/text    input（今天）；AccessibilityService（以后）
shell    → Backend.exec(command)         任意命令（只有 Shizuku / su 能做）
```

`DeviceShell` 持有三张路由表（capture / input / shell），每个能力独立解析到后端：

| 能力 | 后端方法 | 现在谁来做 |
|---|---|---|
| 看屏幕 | `capture()` | Shizuku / su 跑 `screencap -p` |
| 点按、滑动、按键、输入 | `tap/swipe/key/text()` | Shizuku / su 跑 `input ...` |
| 任意命令（`dumpsys`、`am`、`wm` …） | `exec()` | Shizuku / su |

优先级：**Shizuku（已授权且服务在跑）→ su/root → 无**。都不可用时，操作返回
`Result.failure = NO_PRIVILEGE: ...`，文案直接告诉用户去装/授权 Shizuku 或给 root，
不会退化成 `ENOENT`、`Permission denied` 这种看不懂的错误。

状态行（日志、`/device/status`、控制台同一句）：

```
device: capture=screencap via Shizuku (adb uid=2000) | input=input via Shizuku (adb uid=2000) | shell=Shizuku (adb uid=2000)
device: capture=screencap via su (uid=0) | input=input via su (uid=0) | shell=su (uid=0)
device: capture=none (no Shizuku, no root) | input=none (no Shizuku, no root) | shell=none (no Shizuku, no root)
```

以后挂 MediaProjection / 无障碍时，只加一个实现 `capture()` 或 `tap/swipe/text()` 的 Backend，
插到路由表最前面，`DeviceServer` 与插件一行都不用改。

诊断开关：应用私有目录下的 `device-shell-force` 文件，内容 `shizuku` / `su` / `none`，
可以在一台有 root 的机器上强制走"无提权"分支验证错误文案；日志会注明 `[forced by ...]`。

## 2. 依赖怎么进来（没有 Gradle）

用的是 Shizuku 官方 Maven 构件，版本 **13.1.0**：

```
dev.rikka.shizuku:api      13.1.0   → Shizuku / ShizukuRemoteProcess / 监听器
dev.rikka.shizuku:provider 13.1.0   → ShizukuProvider（binder 握手）
dev.rikka.shizuku:aidl     13.1.0   → IShizukuService / IRemoteProcess（AIDL stub）
dev.rikka.shizuku:shared   13.1.0   → ShizukuApiConstants
```

四个 AAR 解出 `classes.jar` 放在 `app/libs/shizuku-*.jar`，构建时：

1. `javac -classpath ...:shizuku-*.jar`（编应用代码）
2. **D8** 把应用 class 与四个 jar 一起打成**一个** `classes.dex`

为什么不用 `dx`：`dalvik-exchange` 不 desugar，遇到 Shizuku jar 里的 Java 8 lambda
直接报 `invalid opcode ba - invokedynamic requires --min-sdk-version >= 26`。
把 minSdk 抬到 26 会让 Android 7 设备装不上，所以改用 D8（`--min-api 24` 真正 desugar）：

```
java -cp r8.jar com.android.tools.r8.D8 --min-api 24 --lib android.jar \
     --output dex/ app-classes.jar shizuku-api.jar shizuku-aidl.jar \
     shizuku-shared.jar shizuku-provider.jar
```

细节与踩坑记录见 `docs/PITFALLS.md` 第 17 条。

为什么是 13.1.0 而不是最新的 13.1.5：`Shizuku.newProcess(...)` 在 13.1.4 起从公开 API 移除，
而本项目的"跑一条 shell 命令"就靠它。服务端的 AIDL
（`moe.shizuku.server.IShizukuService.newProcess`）在 13.1.5 里仍然存在，说明 Shizuku 13.x
服务端继续支持这个 transaction；13.1.0 的客户端库与 13.x 服务端兼容。

## 3. 清单改了什么

```xml
<uses-permission android:name="moe.shizuku.manager.permission.API_V23" />

<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="com.dsh.android.shizuku"
    android:multiprocess="false"
    android:enabled="true"
    android:exported="true"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />

<meta-data android:name="moe.shizuku.client.V3_SUPPORT" android:value="true" />
```

- `authorities` 必须是 `<applicationId>.shizuku`。
- `INTERACT_ACROSS_USERS_FULL` 是给 provider 自己加的保护：只有 Shizuku 管理器（持该权限）能把
  binder 递进来。
- `V3_SUPPORT` 来自 provider 库自带的 manifest；本项目没有 AAR manifest 合并，所以手写。
- `targetSdk=28`，所以 `exported` 显式写出（不为兼容 Android 12 而加，只是明确意图）。

## 4. 授权流程

1. 应用启动 → `ShizukuProvider` 初始化 → binder 到达时 `DeviceShell` 的 sticky 监听器收到通知并重新探测。
2. 控制台按钮「提权通道（Shizuku / root）」→ `DeviceShell.requestShizukuPermission()`：
   - `Shizuku.pingBinder()` 为 false → 告诉用户"Shizuku 服务没在运行，先启动服务"；
   - 已有权限 → 直接刷新状态；
   - 否则 `Shizuku.requestPermission(4201)`，Shizuku 弹出允许/拒绝。
3. 结果通过 `Shizuku.addRequestPermissionResultListener` 回到应用 → 重新探测 → 状态行与按钮文案更新。
4. 没有装 Shizuku：provider 收不到 binder，`pingBinder()` 一直是 false，探测捕获所有 `Throwable`，
   自动落到 `su` 或 `none`；不会崩、不会卡启动。

## 5. 设备桥的变化

- 原有 `/device/tap|swipe|key|text|start|top|shot|ocr` 全部改走 `DeviceShell`，不再直接 `su -c`。
- 截图改成 `screencap -p` 走 stdout，由应用进程写文件：Shizuku 是 uid 2000，
  写不了应用私有目录，只有 root 才能写——stdout 对两种后端都成立。
- 新增 `/device/shell?cmd=...&timeout=SEC`：跑**白名单首命令**（screencap/input/wm/dumpsys/am/cmd/pm/settings/…），
  返回原始 stdout + `X-Exit-Code` / `X-Stderr-B64` 头。这是给 `dsh-screen-operate` 插件用的，
  权限判断留在应用里。访问控制与原来的 su 通道一致：只绑 127.0.0.1、拒绝浏览器来源、每次安装的 token。
- OCR 仍然要 root：proot 运行时在 `com.dsh.client` 的私有目录，shell uid 读不到。

## 6. 插件为什么改走设备桥

`dsh-screen-operate` 原来每条命令都是 `execFile('/system/bin/su', ['-c', cmd])`。
现在 `runSu()` 先问设备桥，桥不在才回落到本地 `su`：

- 插件不需要知道提权来源，非 root 设备上用 Shizuku 也能用；
- 插件逻辑（坐标、降采样、手势、循环）一行没动，`check.mjs` 的 171 项离线检查全过；
- 兜底：老版本应用没有 `/device/shell`（404）时回落本地 su，行为与旧版一致；
  桥明确回答"没有提权"（503）时**不**回落，直接把应用的指引文案报上去。

## 7. 手工验证三态

装好 APK 后，控制台会打印 `device shell: ...` 与 `device: ...` 两行；也可：

```
# 应用私有目录下的 token（也可在控制台「复制日志」里看到端口）
TOKEN=$(cat /data/data/com.dsh.android/files/.device-token)
curl -s -H "X-Device-Token: $TOKEN" http://127.0.0.1:3091/device/status
```

| 状态 | 怎么造 | 期望 |
|---|---|---|
| root | 有 root 且不给 Shizuku 权限 | `device shell: su (uid=0)` |
| Shizuku | 装 Shizuku 13.6.0，用 root 启动服务，应用里点授权并允许 | `device shell: Shizuku (adb uid=2000)` |
| 都没有 | `su -c 'echo none > /data/data/com.dsh.android/files/device-shell-force'` 后重启应用 | `device shell: none (no Shizuku, no root) [forced by ...]`；`/device/shot` 返回 500 + `NO_PRIVILEGE` |
