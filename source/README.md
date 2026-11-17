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

当前归档目录中**尚未包含电视版源码**。本机上仅有早期 v1.6 的 Kotlin/Gradle 工程（产物 `TV直播-v1.x.apk`），
最新的 `TV-v4.9.10` 等版本只找到 APK 发布产物（见 `update/tv-live/`），对应源码工程未在本机找到。
