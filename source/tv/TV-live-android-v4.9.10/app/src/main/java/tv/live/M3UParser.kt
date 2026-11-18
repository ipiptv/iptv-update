package tv.live

import java.io.BufferedReader
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL

object M3UParser {

    /** 每个频道线路上限：只在防止异常大列表时兜底，正常源全收，显示真实源数 */
    const val MAX_URLS_PER_CHANNEL = 500

    /**
     * 解析M3U文本。同分组内同名频道合并多线路；跨分组同名频道各自保留。
     * 逐行手工取属性（不用正则），并限制每频道线路数量，老电视上也不占内存。
     */
    fun parse(text: String): List<Channel> = parse(text.reader())

    /** 流式解析：直接读文件/网络流，避免把整份 m3u 文本都拷进内存 */
    fun parse(reader: Reader): List<Channel> {
        val map = LinkedHashMap<String, Channel>()
        val br = if (reader is BufferedReader) reader else BufferedReader(reader)
        var name = ""
        var group = "未分组"

        while (true) {
            val raw = br.readLine() ?: break
            if (raw.isEmpty()) continue
            if (raw[0] == '#') {
                if (raw.startsWith("#EXTINF")) {
                    group = attr(raw, "group-title").ifEmpty { "未分组" }
                    name = raw.substringAfterLast(',').trim().ifEmpty { "未命名频道" }
                }
                continue
            }
            if (raw.startsWith("//")) continue
            val url = raw.trim()
            if (url.isEmpty()) continue
            if (name.isNotEmpty()) {
                val key = group + "\u0001" + name
                val existing = map[key]
                if (existing == null) {
                    map[key] = Channel(
                        name = name,
                        group = group,
                        urls = mutableListOf(url)
                    )
                } else if (existing.urls.size < MAX_URLS_PER_CHANNEL && url !in existing.urls) {
                    existing.urls.add(url)
                }
            }
            name = ""
            group = "未分组"
        }
        return map.values.toList()
    }

    /** 取 XX="值" 形式的属性值，找不到返回空串 */
    private fun attr(line: String, key: String): String {
        val k = key + "=\""
        val i = line.indexOf(k)
        if (i < 0) return ""
        val from = i + k.length
        val to = line.indexOf('"', from)
        if (to < 0) return ""
        return line.substring(from, to)
    }

    /** 打开连接并手动跟随 302/301（部分 CDN 会跳域名，HttpURLConnection 不一定跟） */
    private fun openFollowing(url: String, depth: Int = 0): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) TV/1.0")
        val code = c.responseCode
        if (code in 301..308 && depth < 4) {
            val loc = c.getHeaderField("Location") ?: return c
            c.disconnect()
            val next = if (loc.startsWith("http")) loc else URL(URL(url), loc).toString()
            return openFollowing(next, depth + 1)
        }
        return c
    }

    /** 联网拉取后直接流式解析，省一次整文本拷贝 */
    fun fetchAndParse(url: String): Pair<String, List<Channel>> {
        val conn = openFollowing(url)
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        if (conn.requestMethod.isNullOrEmpty()) conn.requestMethod = "GET"
        val sb = StringBuilder(1024)
        val ch = conn.inputStream.bufferedReader(Charsets.UTF_8)
        val channels: List<Channel>
        ch.use { reader ->
            val tee = object : Reader() {
                override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                    val n = reader.read(cbuf, off, len)
                    if (n > 0) sb.append(String(cbuf, off, n))
                    return n
                }
                override fun close() {}
            }
            channels = parse(tee)
        }
        return sb.toString() to channels
    }
}
