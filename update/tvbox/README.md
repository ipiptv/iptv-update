# 看电视（TVBox 定制版）安装包

- 安装包：`看电视_V2.apk`
- 包名：`com.github.tvbox.osc.jun`（与 V1 相同，可直接覆盖升级）
- 版本：versionCode 3 / versionName 1.0.0-V2
- 架构：armeabi-v7a（32 位），适配 Android TV / 盒子
- 入口：`com.github.tvbox.osc.jun/com.github.tvbox.osc.ui.activity.BootActivity`
- 大小：19,881,148 字节

## V2 相对 V1 的改动

1. 启动页只保留一个圆环加载动画，尺寸由 `RingSpinnerView.targetSizePx()` 按真实像素统一计算（屏幕短边 18.5%），启动页与直播页遮罩共用同一相位原点，不再有"大圈变小圈"和反向跳帧；系统启动窗口改为纯黑，冷启动过渡无横线、无第二段转圈。
2. 屏蔽影视接口 jar 弹出的"正在加载远程配置"进度条与"本接口免费分享，切勿上传"公告。
3. 直播页左右键切换同一频道上一条 / 下一条线路（源 x/178），切换结果写回线路记忆。
4. 应用内提示改为自绘浮层（`util/ToastUtil.java`），在系统 Toast 被禁用的前提下仍能正常提示。

## 装机备注

设备层需关闭本包系统 Toast 才能彻底屏蔽 jar 的公告（关闭后 App 内提示由自绘浮层接管）：

```
adb shell appops set com.github.tvbox.osc.jun TOAST_WINDOW deny
```
