package tv.live

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 与分组栏、频道栏一致的焦点配色 */
private const val FOCUS_BG = 0xCC0A5C99.toInt()
private const val FOCUS_BG_DIM = 0x990E4C7D.toInt()

class EpgAdapter(
    private val list: MutableList<EpgProgram>,
    var selected: Int = 0,
    var rowHeightPx: Int = 0,
    var textSizePx: Float = 0f,
    var focused: Boolean = true,
    private val onClick: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<EpgAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.epgTime)
        val title: TextView = v.findViewById(R.id.epgTitle)
        val badge: TextView = v.findViewById(R.id.epgBadge)
    }

    private val hm = SimpleDateFormat("HH:mm", Locale.CHINA)

    private fun isNow(p: EpgProgram): Boolean {
        val now = System.currentTimeMillis()
        return now >= p.startMillis && now < p.endMillis
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_epg, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (rowHeightPx > 0) holder.itemView.layoutParams.height = rowHeightPx
        if (textSizePx > 0f) {
            holder.title.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx * 0.92f)
            holder.time.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx * 0.70f)
        }
        val p = list[position]
        holder.time.text = hm.format(Date(p.startMillis))
        holder.title.text = p.title
        val now = isNow(p)
        holder.badge.text = if (now) "正在播出" else ""
        holder.title.setTextColor(
            when {
                now -> 0xFF7FE3FF.toInt()
                position == selected && focused -> 0xFFFFFFFF.toInt()
                else -> 0xFFE0E0E0.toInt()
            }
        )
        // 只给“当前焦点”画底色：正在播出用文字色+角标标注，不再留常亮底色（否则看着像光标没跟走）
        holder.itemView.setBackgroundColor(
            if (position == selected) { if (focused) FOCUS_BG else FOCUS_BG_DIM } else 0x00000000
        )
        holder.itemView.setOnClickListener { onClick?.invoke(position) }
        holder.itemView.isFocusable = true
    }

    override fun getItemCount() = list.size
}
