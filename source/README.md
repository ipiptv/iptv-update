# 源码归档 / Source archive

本目录用于存放「TV直播」各端源码，与 `update/` 下的发布产物分开管理。

## pc-desktop —— 电脑版（macOS）

| 文件 | 说明 |
| --- | --- |
| `TV直播-电脑版-源码.zip` | 完整工程源码（Swift 原生外壳 + 透明 WKWebView 界面） |
| `TV直播-电脑版-mac.zip` | 编译好的成品 App 压缩包 |

源码结构：

```
mac/main.swift              原生外壳：AVPlayerLayer 视频层、透明 WKWebView、窗口/标题栏/键盘转发
mac/Resources/index.html    界面结构（左侧频道栏、右侧设置面板、底部状态栏）
mac/Resources/style.css     样式
mac/Resources/renderer.js   交互逻辑（拉源、播放、EPG、键盘、面板唤出）
build.sh                    一键编译（swiftc 直编，无需 Xcode 工程）
icon.png                    应用图标
```

编译：

```bash
cd TV直播-电脑版-源码
./build.sh          # 产物在 build/TV直播.app
```

依赖：macOS 自带 swiftc（Command Line Tools），无第三方依赖。

主要交互：

- `↑` / `↓` 上一个 / 下一个频道（当前分组内循环）
- `←` / `→` 上一个 / 下一个线路
- `A` 左侧频道栏，`S` 设置面板，`B` 底部状态栏，`F` 全屏，`Esc` 全部收起
- 鼠标换台已移除；面板只在左/右/底区域的中间段唤出，四角与左上角系统按钮区不误触

## tv —— 电视版（Android TV）

| 文件 | 说明 |
| --- | --- |
| `TV-live-android-source-v4.9.10.zip` | 电视版工程源码，versionName 4.9.10 / versionCode 498，对应 `update/tv-live/TV-v4.9.10-设置单行与拉源修复.apk` |

Kotlin + Gradle 工程，包名 `tv.live`，minSdk 21 / targetSdk 28。用 Android Studio 打开，或 `./gradlew assembleRelease` 编译。
签名证书随包（`tv.keystore`，别名 tv，密码 123456），与已发布 APK 一致，可覆盖安装。

主要文件：

```
app/src/main/java/tv/live/MainActivity.kt     主界面、遥控器按键、面板与设置
app/src/main/java/tv/live/ChannelAdapter.kt   频道列表
app/src/main/java/tv/live/GroupAdapter.kt     分组列表
app/src/main/java/tv/live/M3UParser.kt        订阅源解析（同名多线路合并）
app/src/main/java/tv/live/SourceStore.kt      多订阅源管理
app/src/main/java/tv/live/GroupStore.kt       分组显示 / 排序
```
