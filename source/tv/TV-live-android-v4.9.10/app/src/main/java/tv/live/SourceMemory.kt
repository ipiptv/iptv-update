package tv.live

import android.content.Context

/**
 * 每个频道「上次能播的源」记忆：group + name → 0 基源序号。
 * 列表刷新（重新拉源）时整体清空，其余情况一直沿用。
 */
object SourceMemory {
    private const val KEY = "src_mem"
    private const val SEP = '\u0001'

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)

    private fun load(ctx: Context): HashMap<String, Int> {
        val raw = prefs(ctx).getString(KEY, null) ?: return HashMap()
        val map = HashMap<String, Int>()
        if (raw.isEmpty()) return map
        for (line in raw.split('\n')) {
            val i = line.lastIndexOf('=')
            if (i <= 0) continue
            val v = line.substring(i + 1).toIntOrNull() ?: continue
            map[line.substring(0, i)] = v
        }
        return map
    }

    private fun save(ctx: Context, map: Map<String, Int>) {
        val sb = StringBuilder()
        for ((k, v) in map) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(k).append('=').append(v)
        }
        prefs(ctx).edit().putString(KEY, sb.toString()).apply()
    }

    private fun keyOf(group: String, name: String) = group + SEP + name

    fun get(ctx: Context, group: String, name: String): Int? = load(ctx)[keyOf(group, name)]

    fun put(ctx: Context, group: String, name: String, idx: Int) {
        if (idx < 0) return
        val map = load(ctx)
        val k = keyOf(group, name)
        if (map[k] == idx) return
        map[k] = idx
        save(ctx, map)
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }
}
