# 启动界面外观（换色 / 背景图）

App 启动时先显示的是**原生控制台**（标题、版本、`正在启动内置运行环境…`、
五个按钮、运行日志），等内嵌运行时起来之后才被 WebView 里的 DSH 界面顶掉。

这块界面是 `MainActivity` 用代码搭出来的原生 View，**不是网页**，所以它的配色
以前是写死在 Java 里的常量——想换个颜色就得改代码、重新打包、重装 APK。

现在它读一个手机上的文本文件：

```
/sdcard/DSH/launcher/theme.conf      ← 配色 + 背景图路径
/sdcard/DSH/launcher/bg.jpg          ← 零配置：放这儿就自动当背景
```

改完**下次启动生效，不用重新打包**。这对一个只在解包那几秒出现的界面来说，
才是能接受的迭代成本。

---

## 一、最快用法：只加背景图

把任意一张图存成 `/sdcard/DSH/launcher/bg.jpg`（也认 `bg.jpeg` / `bg.png` /
`bg.webp`），重启 App 即可。不需要写配置文件。

控制台会自动变成"毛玻璃"：面板、卡片、按钮都带一点透明度，让图透出来，
同时给整张图压一层暗色蒙版（`scrim`），保证白字仍然清楚。

日志里会出现一行确认：

```
launcher theme: config=/sdcard/DSH/launcher/theme.conf background=/sdcard/DSH/launcher/bg.jpg scrim=0.62 card_opacity=0.88
```

## 二、配置文件

App 首次运行会在同目录写一份带注释的 `theme.example.conf`（只是示例，
**不会被读取**）。想改配色就复制成 `theme.conf` 再编辑：

```bash
cp /sdcard/DSH/launcher/theme.example.conf /sdcard/DSH/launcher/theme.conf
```

格式：一行一个 `key = value`；**行首** `#` 是注释（颜色值里的 `#` 是值的一部分，
不受影响）；认不出来的键、解析不了的值都会被忽略——写错配置**不会让启动界面起不来**，
因为出问题时需要靠这个界面把日志显示出来。

```ini
# 背景图：绝对路径，或相对本目录的路径
background = /sdcard/Pictures/wallpaper.jpg

scrim        = 0.62     # 背景图整体不透明度（压暗蒙版），0..1
card_opacity = 0.88     # 面板/卡片不透明度，0..1（只在有背景图时生效）
radius       = 14       # 卡片圆角，dp
title        = DeepSeek Harness

bg           = #0B1220  # 窗口/状态栏/导航栏底色，也是图片的兜底色
card         = #16223C  # 卡片、按钮、日志框
card_pressed = #1E2C4A
stroke       = #2A3A5C  # 卡片描边
primary      = #4D6BFE  # 主按钮（DeepSeek 蓝）
text         = #EAF0FA  # 标题、按钮文字
dim          = #93A4BF  # 版本号、"运行日志"标签
ok           = #4ADE80  # "已就绪"
warn         = #FBBF24  # "正在启动…"
err          = #F87171  # "已退出"、更新失败
log          = #A8C0DC  # 日志正文
```

颜色写法：`#RRGGBB` 或 `#AARRGGBB`（也可以不带 `#`，或用 `0x` 前缀）。
透明度写法：`0..1` 的小数，或 `0..255` 的整数（大于 1 时按 255 处理）。

只想要纯色、不要图片：删掉 `background` 行，并确保目录里没有 `bg.*` 文件——
此时 `card_opacity` 会被忽略（面板保持不透明），效果和以前完全一样。

## 三、查找顺序

配置文件与"没写路径时的背景图"都按这个顺序找，第一个命中者胜出：

1. `/sdcard/DSH/launcher/` —— 共享存储，需要"授予文件访问"
2. `/sdcard/Android/data/com.dsh.android/files/launcher/` —— App 自己的外部目录，
   **不需要任何权限**，用来在授权之前也能验证功能

`background` 写相对路径时，相对于**读到配置的那个目录**解析。

## 四、权限与失败回退

共享存储要授权才能读（App 内"授予文件访问"按钮会打开系统的"所有文件访问"设置）。
没授权时：

- 界面照常显示，只是退回纯色底 —— 不会崩、不会白屏、不会卡住
- 日志里有一行 `launcher theme: background unreadable or undecodable (...)`
- 授权后**回到 App 会自动重试**（`onResume` 触发，最多 3 次，避免每切一次前台就解码一遍）

## 五、背景图的实现细节

- **自动降采样**：按屏幕宽度用 `inSampleSize` 解码，一张 1200 万像素的照片不会
  占掉几十 MB 堆内存
- **自动纠正方向**：手机拍的照片旋转信息在 EXIF 里，会按 EXIF 转正（否则竖拍照片
  会躺倒）；非 JPEG / 无 EXIF 时按像素原样使用
- **缩放**：`CENTER_CROP`（铺满、裁掉多余），不拉伸变形
- **状态栏 / 导航栏**：仍用 `bg` 纯色，不透明；要跟图融为一体得改窗口 inset 逻辑，
  不在这次范围内

## 六、排查

| 现象 | 先看什么 |
|---|---|
| 图没出现 | 日志里有没有 `launcher theme:` 那行：`background=none` 说明没找到图（路径 / 文件名 / 权限） |
| 有 `background=…` 但界面还是纯色 | 下一行会写 `unreadable or undecodable`：图损坏、格式不支持，或还没授权 |
| 颜色没变 | 值必须是 `#RRGGBB` 或 `#AARRGGBB`；写错的键/值会被静默忽略（这是故意的） |
| 界面一片白/浅色 | `bg`、`card` 给成了浅色，但 `text`/`log` 还是浅色 → 对比度不足，把文字色改深 |
| 改了没反应 | 配置在**启动时**读取一次，重启 App 生效 |

> 打包后想确认改动真的进了 APK（`dx` 有静默丢类的坑，见 `docs/PITFALLS.md` 第 7 条）：
>
> ```bash
> python3 -c "
> import zipfile
> d = zipfile.ZipFile('DSH-Android.apk').read('classes.dex')
> for s in [b'theme.conf', b'launcher theme: ', b'ExifInterface', b'bg.jpeg']:
>     print(('OK  ' if s in d else 'MISS'), s.decode())
> "
> ```

## 七、相关代码

| 文件 | 作用 |
|---|---|
| `app/src/com/dsh/android/LauncherTheme.java` | 读取配置、解析颜色/透明度、解码并转正背景图 |
| `app/src/com/dsh/android/MainActivity.java` | `buildUi()` 按主题搭界面；`applyBackgroundIfNeeded()` 异步加载背景 |
