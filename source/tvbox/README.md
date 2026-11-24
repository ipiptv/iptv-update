# 看电视（TVBox 定制版）源码

- 源码包：`看电视_V2_源码.zip`（47,185,633 字节，949 个文件，解压约 96MB）
- 内层目录：`TVBoxOS_kanTV_V2/`（TVBoxOS Java 分支，Android 工程，Gradle）
- 对应版本：versionCode 3 / versionName 1.0.0-V2

## 编译

```
export JAVA_HOME=<jdk11>
export ANDROID_HOME=<android-sdk>
./gradlew assembleJava32Release --no-daemon -x lint
```

产物：`app/build/outputs/apk/java32/release/TVBox_release-java32.apk`

## 主要改动文件

- 新增 `app/src/main/java/com/github/tvbox/osc/ui/view/RingSpinnerView.java`（启动页 / 直播遮罩共用的圆环加载动画，含 `targetSizePx()` 尺寸统一与 `sPhaseOrigin` 相位同步）
- 新增 `app/src/main/java/com/github/tvbox/osc/util/ToastUtil.java`（自绘提示浮层，替代系统 Toast）
- 修改 `ui/activity/BootActivity.java`、`ui/activity/LivePlayActivity.java`（左右键切换线路、遮罩显隐）
- 修改 `res/layout/activity_boot_splash.xml`、`res/values/styles.xml`（启动窗口纯黑 + 单圆环）、`res/layout/loadsir_loading_layout.xml`
- 修改 `app/build.gradle`（versionCode 3 / versionName 1.0.0-V2）

源码包已排除 `app/build`、`build`、`.gradle`、`*.apk`、`*.iml`。
