# TV直播 · 源码归档

安卓电视端 + macOS 电脑端，同一套「订阅源 → 频道 → 多线路」直播播放器，界面与交互保持一致。

- **电视版**：Kotlin / Gradle，原生 `VideoView`，遥控器操作，开机直达直播
- **电脑版**：Swift 原生外壳（`AVPlayerLayer` 视频层）+ 透明 `WKWebView` 界面层，键盘操作

两端共用同一份 m3u 订阅源、同名多线路合并规则、EPG 节目单，所以同一个源在两端看到的频道和线路序号是对应的。

---

## 一、目录

| 目录 | 内容 |
| --- | --- |
| `source/pc-desktop/TV直播-电脑版-源码.zip` | 电脑版完整工程源码 |
| `source/pc-desktop/TV直播-电脑版-mac.zip` | 电脑版编译好的成品 App |
| `source/tv/TV-live-android-source-v4.9.15.zip` | 电视版工程源码（v4.9.15 / versionCode 503） |
| `source/tv/TV-v4.9.15-android-tv.apk` | 电视版安装包（v4.9.15，与 `update/tv-live/` 里同版，命名简化便于直接下载安装） |
| `update/tv-live/` | 电视版历史发布 APK（带改动说明的原名文件） |
| `update/version.json` | 电视版 App 内「检测更新」读取的版本信息 |

---

## 二、电脑版（macOS）

### 结构

```
mac/main.swift              原生外壳：AVPlayerLayer 视频层、透明 WKWebView、窗口 / 标题栏 / 键盘转发
mac/Resources/index.html    界面结构（左侧频道栏、右侧设置面板、底部状态栏）
mac/Resources/style.css     样式（含按窗口比例缩放的字号体系）
mac/Resources/renderer.js   交互逻辑（拉源、播放、EPG、键盘、面板唤出）
build.sh                    一键编译（swiftc 直编，无需 Xcode 工程）
icon.png                    应用图标
```

### 编译

```bash
cd TV直播-电脑版-源码
./build.sh          # 产物 build/TV直播.app
```

依赖只有 macOS 自带的 `swiftc`（Command Line Tools），不依赖第三方库、不需要 CocoaPods。

### 操作

| 按键 | 作用 |
| --- | --- |
| `↑` / `↓` | 上一个 / 下一个频道（只在当前分组内循环） |
| `←` / `→` | 上一个 / 下一个线路（同一频道换源） |
| `A` | 唤出 / 收起左侧频道栏 |
| `S` | 唤出 / 收起右侧设置面板 |
| `B` | 唤出 / 收起底部状态栏 |
| `F` | 全屏 / 退出全屏 |
| `Esc` | 全部收起 |

鼠标换台已移除。面板只在「左 / 右 / 底」三个区域的中间段唤出，四角和左上角系统按钮区不误触，鼠标移开自动收起；键盘唤出的面板不会被鼠标移走收掉。

---

## 三、电视版（Android TV）

### 编译

用 Android Studio 直接打开工程；或命令行：

```bash
./gradlew assembleRelease     # 产物 app/build/outputs/apk/release/
```

环境要求：**JDK 17**（AGP 8 强制要求，JDK 11 会直接编译失败）、Android SDK（`local.properties` 里的 `sdk.dir` 指向本机 SDK 路径）。

### 签名

工程根目录 `tv.keystore`，别名 `tv`，密码 `123456`，与已发布 APK 同一张证书，改完可以直接覆盖安装到电视上，不用卸载旧版。

### 主要文件

```
app/src/main/java/tv/live/MainActivity.kt     主界面、遥控器按键、面板与设置
app/src/main/java/tv/live/ChannelAdapter.kt   频道列表
app/src/main/java/tv/live/GroupAdapter.kt     分组列表
app/src/main/java/tv/live/M3UParser.kt        订阅源解析（同名频道多条线路合并）
app/src/main/java/tv/live/SourceStore.kt      多订阅源管理
app/src/main/java/tv/live/GroupStore.kt       分组显示 / 排序
app/src/main/res/layout                       界面布局
```

---

## 四、注意事项

**关于直播源**

- 内置源、第三方源都是别人的服务器，**随时可能失效、限速、只有声音没有画面**，这不是 App 的 bug。截图报问题时请说明是哪个源、哪个频道。
- 组播源（`igmp://`、`rtp://`）只有在和光猫同一网段时才通，普通宽带环境一定播不出来。
- 部分频道是 HEVC / AVS 编码，老设备硬解不支持，会出现「有声无画」，换线路或换源即可。

**改代码时的坑**

- 电视版：`versionCode` / `versionName` 改完记得同步更新 `update/tv-live/` 里的 APK 和 `update/version.json`，否则 App 内的检测更新会判断错版本。
- 电脑版：`main.swift` 里的键盘转发靠 `NSEvent.addLocalMonitorForEvents`，**不要再加 `firstResponder` 判断**，加了之后页面会收不到按键，键盘直接失灵。
- 电脑版：WKWebView 用 `.nonPersistent()` 数据存储，规避磁盘缓存；设置项全部存 `localStorage`（`tvdesk2_*` 前缀），重装/清缓存会丢设置。
- 电脑版 `build.sh` 产出的是**未公证的本地签名 App**，别人第一次打开需要右键 → 打开（或系统设置里允许）。
- 不要把 `node_modules`、`build/`、`.gradle/`、`local.properties` 提交进仓库；电脑版根目录残留的旧 Electron 文件（`main.js` / `preload.js` / `package.json`）已废弃，Electron 方案因被判恶意软件永久弃用，不要回退。

**关于「库」的使用**

- `update/` 是发布通道，改动会影响 App 内的更新检查；源码归档只放 `source/`，两边不要混。
