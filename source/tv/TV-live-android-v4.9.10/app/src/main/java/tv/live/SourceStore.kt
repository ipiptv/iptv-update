package tv.live

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SourceItem(
    val id: String,
    var name: String,
    var url: String,
    var updatedAt: Long = 0L
)

object SourceStore {

    const val DEFAULT_SOURCE_ID = "default"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)

    fun defaultSource() = SourceItem(DEFAULT_SOURCE_ID, "默认源", MainActivity.DEFAULT_URL)

    private var cachedList: MutableList<SourceItem>? = null

    fun invalidate() { cachedList = null }

    fun load(ctx: Context): MutableList<SourceItem> {
        cachedList?.let { return it }
        val list = mutableListOf<SourceItem>()
        val raw = sp(ctx).getString("sources_json", null)
        if (raw.isNullOrEmpty()) {
            list.add(defaultSource())
            // 兼容旧版只存了单个地址的情况
            val old = sp(ctx).getString("source_url", null)
            if (!old.isNullOrEmpty() && old != MainActivity.DEFAULT_URL) {
                list.add(SourceItem("s_old", "我的源", old))
            }
        } else {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        SourceItem(
                            id = o.optString("id"),
                            name = o.optString("name"),
                            url = o.optString("url"),
                            updatedAt = o.optLong("updatedAt")
                        )
                    )
                }
            } catch (e: Exception) {
                list.clear()
            }
        }
        if (list.none { it.id == DEFAULT_SOURCE_ID }) list.add(0, defaultSource())
        // 默认源地址统一用代码里的常量（换镜像后老数据自动跟上）
        list.firstOrNull { it.id == DEFAULT_SOURCE_ID }?.let {
            if (it.url != MainActivity.DEFAULT_URL) it.url = MainActivity.DEFAULT_URL
        }
        // 默认源的地址统一用代码里的常量（换了镜像地址后老数据自动跟上）
        list.firstOrNull { it.id == DEFAULT_SOURCE_ID }?.let { if (it.url != MainActivity.DEFAULT_URL) it.url = MainActivity.DEFAULT_URL }
        cachedList = list
        return list
    }

    fun save(ctx: Context, list: List<SourceItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("url", it.url)
                    .put("updatedAt", it.updatedAt)
            )
        }
        sp(ctx).edit().putString("sources_json", arr.toString()).apply()
        cachedList = list.toMutableList()
    }

    fun currentId(ctx: Context): String =
        sp(ctx).getString("current_source_id", DEFAULT_SOURCE_ID) ?: DEFAULT_SOURCE_ID

    fun setCurrentId(ctx: Context, id: String) {
        sp(ctx).edit().putString("current_source_id", id).apply()
    }

    fun currentUrl(ctx: Context): String {
        val list = load(ctx)
        return list.firstOrNull { it.id == currentId(ctx) }?.url ?: MainActivity.DEFAULT_URL
    }

    fun newId(): String = "s" + System.currentTimeMillis()

    /** 每个源独立的频道缓存文件 */
    fun cacheFile(ctx: Context): java.io.File = cacheFileFor(ctx, currentId(ctx))

    /** 指定源的缓存文件（切源时用来读取该源自己保留的列表） */
    fun cacheFileFor(ctx: Context, id: String): java.io.File =
        java.io.File(ctx.filesDir, "channels_" + id + ".m3u")
}

object GroupStore {

    private fun sp(ctx: Context) = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)

    fun loadOrder(ctx: Context): List<String> {
        val raw = sp(ctx).getString("group_order", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) out.add(arr.getString(i))
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveOrder(ctx: Context, order: List<String>) {
        val arr = JSONArray()
        order.forEach { arr.put(it) }
        sp(ctx).edit().putString("group_order", arr.toString()).apply()
    }

    fun loadHidden(ctx: Context): MutableSet<String> {
        val raw = sp(ctx).getString("group_hidden", null) ?: return mutableSetOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableSetOf<String>()
            for (i in 0 until arr.length()) out.add(arr.getString(i))
            out
        } catch (e: Exception) {
            mutableSetOf()
        }
    }

    fun saveHidden(ctx: Context, hidden: Set<String>) {
        val arr = JSONArray()
        hidden.forEach { arr.put(it) }
        sp(ctx).edit().putString("group_hidden", arr.toString()).apply()
    }

    /** 按保存顺序重排分组，隐藏的不进入列表 */
    fun apply(ctx: Context, allGroups: List<String>): List<String> {
        val hidden = loadHidden(ctx)
        val order = loadOrder(ctx)
        val result = mutableListOf<String>()
        order.forEach { g -> if (allGroups.contains(g) && !hidden.contains(g)) result.add(g) }
        allGroups.forEach { g -> if (!order.contains(g) && !hidden.contains(g)) result.add(g) }
        return result
    }
}
