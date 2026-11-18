package tv.live

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 应用内更新：下载新 APK → 比对版本号 → 调系统安装器覆盖安装 */
object UpdateChecker {

    /**
     * 版本清单地址：三个源同时读，取 versionCode 最大的那个。
     * jsDelivr 的 @main 最多缓存 12 小时，单独用它会读到旧清单，所以只当兜底。
     */
    private val FEEDS = listOf(
        "https://raw.githubusercontent.com/ipiptv/iptv-update/main/update/tv-live/version.json",
        "https://gh-proxy.com/raw.githubusercontent.com/ipiptv/iptv-update/main/update/tv-live/version.json",
        "https://cdn.jsdelivr.net/gh/ipiptv/iptv-update@main/update/tv-live/version.json"
    )

    /** 远程版本信息 */
    data class Remote(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val note: String,
        val force: Boolean
    )

    /** 读取远程版本清单：并发读多个源，取版本号最大的那份，全部失败返回 null */
    fun fetchRemote(): Remote? {
        var best: Remote? = null
        for (feed in FEEDS) {
            try {
                val conn = URL(feed).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "TV/1.0")
                conn.instanceFollowRedirects = true
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val jo = JSONObject(text)
                val code = jo.optInt("versionCode", jo.optInt("latestVersionCode", 0))
                val name = jo.optString("versionName", jo.optString("latestVersionName", ""))
                val apk = jo.optString("apkUrl", "")
                if (code <= 0 || apk.isEmpty()) continue
                val r = Remote(code, name, apk, jo.optString("note", jo.optString("updateLog", "")), jo.optBoolean("isForce", false))
                if (best == null || r.versionCode > best!!.versionCode) best = r
            } catch (e: Throwable) {
                // 换下一个地址再试
            }
        }
        return best
    }

    /** 当前已安装的 versionCode */
    fun currentVersionCode(ctx: Context): Int {
        val pm = ctx.packageManager
        return if (Build.VERSION.SDK_INT >= 28)
            pm.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
        else pm.getPackageInfo(ctx.packageName, 0).versionCode
    }


    /** 下载进度回调（0~100） */
    fun checkAndInstall(ctx: Context, apkUrl: String, onStatus: (String) -> Unit): Boolean {
        return try {
            val apk = File(ctx.cacheDir, "update_new.apk")
            if (apk.exists()) apk.delete()
            onStatus("正在下载新版本…")
            val conn = URL(apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "TV/1.0")
            conn.instanceFollowRedirects = true
            conn.inputStream.use { input ->
                apk.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
            }
            if (apk.length() <= 0) {
                onStatus("下载失败：文件为空")
                return false
            }
            // 读出下载包的版本号
            val pm = ctx.packageManager
            val info = pm.getPackageArchiveInfo(apk.absolutePath, 0)
            if (info == null || info.packageName != ctx.packageName) {
                onStatus("安装包无效（不是本应用的安装包）")
                apk.delete()
                return false
            }
            val newCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
            val curCode = if (Build.VERSION.SDK_INT >= 28)
                pm.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
            else pm.getPackageInfo(ctx.packageName, 0).versionCode
            if (newCode <= curCode) {
                onStatus("已经是最新版本（当前 $curCode）")
                apk.delete()
                return false
            }
            onStatus("下载完成，正在打开安装器（v$newCode）")
            install(ctx, apk)
            true
        } catch (e: Throwable) {
            onStatus("更新失败：" + (e.message ?: e.javaClass.simpleName))
            false
        }
    }

    /** 手机扫码上传到电视的安装包：本地校验后直接拉起系统安装器 */
    fun installLocalFile(ctx: Context, apk: File, onStatus: (String) -> Unit): Boolean {
        return try {
            if (!apk.exists() || apk.length() <= 0) {
                onStatus("安装包为空")
                return false
            }
            val pm = ctx.packageManager
            val info = pm.getPackageArchiveInfo(apk.absolutePath, 0)
            if (info == null || info.packageName != ctx.packageName) {
                onStatus("安装包无效（不是本应用的安装包）")
                apk.delete()
                return false
            }
            val newCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
            val newName = info.versionName ?: "?"
            onStatus("已收到 ${apk.name}（v$newName），正在打开安装器")
            install(ctx, apk)
            true
        } catch (e: Throwable) {
            onStatus("安装失败：" + (e.message ?: e.javaClass.simpleName))
            false
        }
    }

    private fun install(ctx: Context, apk: File) {
        val uri: Uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(ctx, "tv.live.fileprovider", apk)
        } else Uri.fromFile(apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(intent)
    }
}
