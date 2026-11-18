TV直播 电视版（Android TV）源码  v4.9.10  versionCode 498
=====================================================

包名 tv.live，minSdk 21 / targetSdk 28，Kotlin + Gradle 工程。

用 Android Studio 直接打开本目录即可；或命令行编译：
    ./gradlew assembleRelease
产物在 app/build/outputs/apk/release/。

签名：工程根目录 tv.keystore（别名 tv，密码 123456），与已发布 APK 一致，可覆盖安装。

主要文件：
    app/src/main/java/tv/live/MainActivity.kt    主界面、遥控器按键、面板与设置
    app/src/main/java/tv/live/ChannelAdapter.kt  频道列表
    app/src/main/java/tv/live/GroupAdapter.kt    分组列表
    app/src/main/java/tv/live/M3UParser.kt       订阅源解析（同名多线路合并）
    app/src/main/java/tv/live/SourceStore.kt     多订阅源管理
    app/src/main/java/tv/live/GroupStore.kt      分组显示/排序
    app/src/main/res/layout                     界面布局
