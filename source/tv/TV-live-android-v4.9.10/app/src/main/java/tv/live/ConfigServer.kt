package tv.live

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 局域网配置服务：电视上开一个极简 HTTP 服务，
 * 手机扫码打开网页即可添加订阅源 / 直接上传新版安装包。
 */
class ConfigServer(
    private val context: Context,
    private val port: Int = 8888,
    private val onSourceUpdated: (name: String, url: String, setCurrent: Boolean) -> Unit,
    /** 手机端点了“立即检查更新”：让电视马上去下载安装 */
    private val onUpdateNow: () -> Unit = {},
    /** 手机把 APK 直接传到电视上：参数为落地的本地文件 */
    private val onApkUploaded: (File) -> Unit = {}
) {

    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                while (running) {
                    val client = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break
                    }
                    try {
                        handle(client)
                    } catch (e: Exception) {
                        // 单个请求异常不影响服务
                    } finally {
                        try { client.close() } catch (e: Exception) { }
                    }
                }
            } catch (e: Exception) {
                // 端口占用等
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (e: Exception) { }
        serverSocket = null
        thread?.interrupt()
        thread = null
    }

    private fun sourceListText(): String {
        val list = SourceStore.load(context)
        val cur = SourceStore.currentId(context)
        return list.joinToString("") { s ->
            val mark = if (s.id == cur) "● " else "○ "
            "<div class='cur'>$mark<b>${escape(s.name)}</b> · ${escape(s.url)}</div>"
        }
    }

    private fun currentUpdateUrl(): String =
        context.getSharedPreferences("tv", Context.MODE_PRIVATE).getString("update_url", "") ?: ""

    private fun escape(t: String): String = t
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun handle(client: Socket) {
        val input = client.getInputStream()

        // 按字节读请求头，避免二进制 body 被字符流吞掉
        val headBuf = ByteArrayOutputStream()
        var last4 = 0
        var b = input.read()
        while (b >= 0 && headBuf.size() < 16 * 1024) {
            headBuf.write(b)
            last4 = (last4 shl 8) or (b and 0xFF)
            if (last4 == 0x0D0A0D0A) break // \r\n\r\n
            b = input.read()
        }
        val head = String(headBuf.toByteArray(), Charsets.ISO_8859_1)
        val lines = head.split("\r\n")
        if (lines.isEmpty()) return
        val parts = lines[0].split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1]

        var contentLength = 0
        var contentType = ""
        lines.drop(1).forEach { l ->
            val lower = l.lowercase()
            if (lower.startsWith("content-length:")) contentLength = l.substringAfter(":").trim().toIntOrNull() ?: 0
            if (lower.startsWith("content-type:")) contentType = l.substringAfter(":").trim()
        }

        val body: ByteArray = if (method == "POST" && contentLength > 0) {
            readFully(input, contentLength)
        } else ByteArray(0)

        val out = client.getOutputStream()

        when {
            method == "POST" && path.startsWith("/save") && !path.startsWith("/saveUpdate") -> {
                val bodyText = String(body, Charsets.UTF_8)
                val url = parseField(bodyText, "url")
                val name = parseField(bodyText, "name")?.takeIf { it.isNotBlank() } ?: "我的源"
                val setCurrent = bodyText.contains("current=on") || bodyText.contains("current=1")
                if (url.isNullOrBlank() || !url.startsWith("http")) {
                    writeHtml(out, page("保存失败", "地址无效，请填写以 http 开头的 m3u 链接"))
                } else {
                    onSourceUpdated(name, url, setCurrent)
                    writeHtml(out, page("添加成功", "已加入订阅源列表：${escape(name)}"))
                }
            }
            method == "POST" && path.startsWith("/saveUpdate") -> {
                val bodyText = String(body, Charsets.UTF_8)
                val apk = parseField(bodyText, "apkurl")
                if (apk.isNullOrBlank() || !apk.startsWith("http")) {
                    writeHtml(out, page("保存失败", "安装包地址无效，请填写以 http 开头的直链", updateUrl = apk ?: ""))
                } else {
                    context.getSharedPreferences("tv", Context.MODE_PRIVATE)
                        .edit().putString("update_url", apk.trim()).apply()
                    val trigger = bodyText.contains("now=on") || bodyText.contains("now=1")
                    if (trigger) {
                        onUpdateNow()
                        writeHtml(out, page("已开始更新", "电视正在下载新版本，稍后会弹出安装确认，请在电视上点「安装」", updateUrl = apk.trim()))
                    } else {
                        writeHtml(out, page("已保存", "安装包地址已保存，可在电视「设置 → 关于 → 检查更新」里直接下载安装", updateUrl = apk.trim()))
                    }
                }
            }
            method == "POST" && path.startsWith("/uploadApk") -> {
                val boundary = contentType.substringAfter("boundary=", "").trim().trim('"')
                val picked = if (contentType.contains("multipart/form-data") && boundary.isNotEmpty())
                    extractFilePart(body, boundary) else null
                if (picked == null) {
                    writeHtml(out, page("上传失败", "没有收到安装包，请重新选择文件再上传", updateUrl = currentUpdateUrl()))
                } else {
                    val (fileName, bytes) = picked
                    if (bytes.size <= 0) {
                        writeHtml(out, page("上传失败", "文件为空，请重新选择", updateUrl = currentUpdateUrl()))
                    } else {
                        val safe = fileName.substringAfterLast('/').substringAfterLast('\\')
                            .replace(Regex("[^0-9A-Za-z._\\u4e00-\\u9fa5-]"), "_")
                        val target = File(context.cacheDir, if (safe.lowercase().endsWith(".apk")) safe else "upload.apk")
                        target.writeBytes(bytes)
                        onApkUploaded(target)
                        writeHtml(out, page(
                            "已收到安装包",
                            "已收到 ${escape(target.name)}（${bytes.size / 1024} KB），正在打开安装器，请在电视上点「安装」",
                            updateUrl = currentUpdateUrl()
                        ))
                    }
                }
            }
            else -> writeHtml(out, page("TV 订阅源设置", "", updateUrl = currentUpdateUrl()))
        }
    }

    /** 严格读满 contentLength 字节（二进制安全） */
    private fun readFully(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var total = 0
        while (total < length) {
            val n = input.read(buf, total, length - total)
            if (n <= 0) break
            total += n
        }
        return if (total == length) buf else buf.copyOf(total)
    }

    /** 从 multipart body 里取出文件字段：返回（文件名，文件字节） */
    private fun extractFilePart(body: ByteArray, boundary: String): Pair<String, ByteArray>? {
        val fnMark = "filename=\"".toByteArray(Charsets.ISO_8859_1)
        val fnIdx = indexOf(body, fnMark) ?: return null
        val nameStart = fnIdx + fnMark.size
        var nameEnd = nameStart
        while (nameEnd < body.size && body[nameEnd] != '"'.code.toByte()) nameEnd++
        val fileName = String(body, nameStart, nameEnd - nameStart, Charsets.UTF_8)

        val dataStart = (indexOf(body, "\r\n\r\n".toByteArray(Charsets.ISO_8859_1), fnIdx) ?: return null) + 4
        val endMark = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
        val dataEnd = indexOf(body, endMark, dataStart) ?: body.size
        if (dataEnd <= dataStart) return null
        val data = body.copyOfRange(dataStart, dataEnd)
        return Pair(fileName, data)
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int = 0): Int? {
        if (needle.isEmpty() || hay.size < needle.size) return null
        var i = from.coerceAtLeast(0)
        while (i <= hay.size - needle.size) {
            var j = 0
            while (j < needle.size && hay[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return null
    }

    private fun parseField(body: String, key: String): String? {
        val pair = body.split("&").firstOrNull { it.startsWith("$key=") } ?: return null
        return try {
            URLDecoder.decode(pair.substringAfter("$key="), "UTF-8").trim()
        } catch (e: Exception) {
            null
        }
    }

    private fun writeHtml(out: OutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun page(title: String, message: String, updateUrl: String = ""): String {
        val msgBlock = if (message.isEmpty()) "" else """<div class="msg">$message</div>"""
        return """<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$title</title>
<style>
  body{margin:0;padding:24px;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
       background:#0f1115;color:#e8eaed;}
  h1{font-size:20px;margin:0 0 6px;}
  .sub{color:#9aa0a6;font-size:14px;margin-bottom:20px;}
  .msg{background:#1f6feb22;border:1px solid #1f6feb55;color:#8ab4f8;
       padding:12px;border-radius:10px;font-size:14px;margin-bottom:18px;line-height:1.5;}
  label{display:block;font-size:14px;color:#9aa0a6;margin:14px 0 8px;}
  input[type=text]{width:100%;box-sizing:border-box;padding:14px;border-radius:10px;
       border:1px solid #333;background:#1a1d23;color:#fff;font-size:15px;outline:none;}
  input[type=text]:focus{border-color:#1f6feb;}
  input[type=file]{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;
       border:1px dashed #3a3f47;background:#161a20;color:#c8ccd2;font-size:14px;}
  .chk{display:flex;align-items:center;gap:10px;margin-top:16px;font-size:14px;color:#c8ccd2;}
  .chk input{width:18px;height:18px;}
  button{width:100%;margin-top:18px;padding:14px;border:0;border-radius:10px;
       background:#1f6feb;color:#fff;font-size:16px;font-weight:600;}
  .list-title{margin-top:26px;font-size:14px;color:#9aa0a6;}
  .cur{margin-top:8px;font-size:13px;color:#b8bcc2;line-height:1.7;word-break:break-all;}
  .hint{margin-top:8px;font-size:12px;color:#7c8288;line-height:1.6;}
</style>
</head>
<body>
  <h1>TV 订阅源设置</h1>
  <div class="sub">手机和电视连同一个 WiFi，即可直接操作</div>
  $msgBlock
  <form method="post" action="/save">
    <label>源名称</label>
    <input type="text" name="name" value="" placeholder="例如：我的源">
    <label>订阅源地址（m3u / m3u8 列表）</label>
    <input type="text" name="url" value="" placeholder="https://example.com/list.m3u">
    <div class="chk"><input type="checkbox" name="current" checked><span>立即切换使用这个源</span></div>
    <button type="submit">添加到电视</button>
  </form>
  <div class="list-title">电视上已有的源</div>
  ${sourceListText()}

  <div class="list-title">安装新版本（推荐）</div>
  <div class="sub" style="margin-top:6px">手机里先下载好 APK，选文件直接传到电视安装</div>
  <form method="post" action="/uploadApk" enctype="multipart/form-data">
    <label>选择安装包（.apk）</label>
    <input type="file" name="apk">
    <button type="submit">上传到电视并安装</button>
  </form>
  <div class="hint">上传后电视会弹出系统安装确认，用遥控器点「安装」即可完成覆盖。</div>

  <div class="list-title">或填安装包地址（备用）</div>
  <form method="post" action="/saveUpdate">
    <label>新版安装包地址（.apk 直链）</label>
    <input type="text" name="apkurl" value="${escape(updateUrl)}" placeholder="https://example.com/TV-latest.apk">
    <div class="chk"><input type="checkbox" name="now" checked><span>保存后让电视立即下载安装</span></div>
    <button type="submit">保存并检查更新</button>
  </form>
</body>
</html>"""
    }
}
