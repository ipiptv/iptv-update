package tv.live

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

data class EpgProgram(
    val startMillis: Long,
    val endMillis: Long,
    val title: String
)

object EpgRepository {

    const val EPG_URL = "https://iptv-4us.pages.dev/epg/51zmt.xml"

    private val data = HashMap<String, List<EpgProgram>>()

    @Volatile
    private var loaded = false

    private val timeFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)

    fun isLoaded() = loaded

    /** 按频道名归一化匹配节目单 */
    fun programsFor(channelName: String): List<EpgProgram> {
        if (!loaded || data.isEmpty()) return emptyList()
        val n = normalize(channelName)
        if (n.isEmpty()) return emptyList()
        synchronized(data) {
            data[n]?.let { return it }
            var bestKey: String? = null
            for (key in data.keys) {
                if (key.isEmpty()) continue
                val hit = n.contains(key) || key.contains(n)
                if (!hit) continue
                // 避免过短名称误匹配
                if (n.length < 3 || key.length < 3) continue
                if (bestKey == null || key.length > bestKey.length) bestKey = key
            }
            return bestKey?.let { data[it] } ?: emptyList()
        }
    }

    /** 当前正在播 + 接下来一条，给底部信息条用 */
    fun nowNext(channelName: String): Pair<EpgProgram?, EpgProgram?> {
        val list = programsFor(channelName)
        if (list.isEmpty()) return null to null
        val now = System.currentTimeMillis()
        val idx = list.indexOfFirst { now >= it.startMillis && now < it.endMillis }
        if (idx < 0) {
            val upcoming = list.firstOrNull { it.startMillis > now }
            return null to upcoming
        }
        return list[idx] to list.getOrNull(idx + 1)
    }

    private val hmFormat = SimpleDateFormat("HH:mm", Locale.US)

    fun timeText(millis: Long): String = hmFormat.format(Date(millis))

    fun normalize(raw: String): String {
        var s = raw.uppercase()
        listOf("高清", "超清", "蓝光", "4K", "HD", "频道", "电视台", "台").forEach { s = s.replace(it, "") }
        s = s.replace(" ", "")
            .replace("-", "")
            .replace("_", "")
            .replace("·", "")
            .replace("(", "").replace(")", "")
            .replace("（", "").replace("）", "")
            .replace("「", "").replace("」", "")
            .replace("+", "+")
        return s
    }

    private fun todayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /** 后台加载：优先读当天缓存，否则联网拉取 */
    fun load(ctx: Context, onDone: () -> Unit) {
        Thread {
            // 低优先级，避免解析时抢占视频解码CPU
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                val dir = ctx.filesDir
                val today = todayKey()
                val f = File(dir, "epg_$today.xml")
                val xml: String
                if (f.exists() && f.length() > 0) {
                    xml = f.readText()
                } else {
                    // 清理过期缓存
                    dir.listFiles()?.forEach { old ->
                        if (old.name.startsWith("epg_") && old.name != f.name) old.delete()
                    }
                    xml = fetch(EPG_URL)
                    f.writeText(xml)
                }
                val parsed = parse(xml)
                synchronized(data) {
                    data.clear()
                    data.putAll(parsed)
                }
                loaded = parsed.isNotEmpty()
            } catch (e: Exception) {
                // 失败时若仍有内存数据则保持
            }
            Handler(Looper.getMainLooper()).post { onDone() }
        }.start()
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "TV/1.0")
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun parseTime(raw: String?): Long {
        if (raw == null) return 0L
        return try {
            timeFormat.parse(raw.trim())?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun parse(xml: String): HashMap<String, List<EpgProgram>> {
        val result = HashMap<String, MutableList<EpgProgram>>()
        val idToName = HashMap<String, String>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        var event = parser.eventType
        var currentChannelId: String? = null

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "channel" -> currentChannelId = parser.getAttributeValue(null, "id")
                        "display-name" -> {
                            val text = runCatching { parser.nextText() }.getOrDefault("")
                            val id = currentChannelId
                            if (id != null && text.isNotEmpty()) idToName[id] = normalize(text)
                        }
                        "programme" -> {
                            val chId = parser.getAttributeValue(null, "channel")
                            val start = parseTime(parser.getAttributeValue(null, "start"))
                            val stop = parseTime(parser.getAttributeValue(null, "stop"))
                            var title = ""
                            val depth = parser.depth
                            while (true) {
                                val e = parser.next()
                                if (e == XmlPullParser.END_DOCUMENT) break
                                if (e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "programme") break
                                if (e == XmlPullParser.START_TAG && parser.name == "title") {
                                    title = runCatching { parser.nextText() }.getOrDefault("")
                                }
                            }
                            if (chId != null && start > 0L && title.isNotEmpty()) {
                                val name = idToName[chId]
                                if (name != null) {
                                    result.getOrPut(name) { mutableListOf() }
                                        .add(EpgProgram(start, stop, title.trim()))
                                }
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }

        // 按开始时间排序
        result.values.forEach { it.sortBy { p -> p.startMillis } }
        val out = HashMap<String, List<EpgProgram>>()
        result.forEach { (k, v) -> out[k] = v }
        return out
    }
}
