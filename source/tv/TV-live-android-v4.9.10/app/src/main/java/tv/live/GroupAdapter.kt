package tv.live

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

/** 焦点/选中配色：整体加深，亮屏下也清楚 */
private const val FOCUS_BG = 0xCC0A5C99.toInt()
private const val FOCUS_BG_DIM = 0x990E4C7D.toInt()

class GroupAdapter(
    private val list: List<String>,
    var selected: Int = 0,
    var rowHeightPx: Int = 0,
    var textSizePx: Float = 0f,
    var focused: Boolean = false,
    var highlightColor: Int = 0xFFFFD54F.toInt(),
    private val onClick: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<GroupAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (rowHeightPx > 0) {
            holder.itemView.layoutParams.height = rowHeightPx
        }
        if (textSizePx > 0f) {
            holder.tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
        }
        val text = list[position]
        holder.tv.text = text
        holder.tv.gravity = Gravity.CENTER_VERTICAL or Gravity.START

        // 名称过长时按可用宽度自动缩字号，保证完整显示
        holder.tv.post {
            val avail = holder.tv.width - holder.tv.paddingLeft - holder.tv.paddingRight
            if (avail > 0 && textSizePx > 0f) {
                val measured = holder.tv.paint.measureText(text)
                if (measured > avail) {
                    val scaled = textSizePx * (avail.toFloat() / measured) - 1f
                    holder.tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, max(scaled, textSizePx * 0.5f))
                } else {
                    holder.tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
                }
            }
        }

        val sel = position == selected
        holder.tv.setTextColor(
            when {
                sel && focused -> 0xFFFFFFFF.toInt()
                sel -> highlightColor
                else -> 0xFFE6E6E6.toInt()
            }
        )
        holder.tv.setBackgroundColor(
            if (sel) {
                if (focused) FOCUS_BG else FOCUS_BG_DIM
            } else 0x00000000
        )
        holder.tv.setOnClickListener { onClick?.invoke(position) }
        holder.tv.isFocusable = true
    }

    override fun getItemCount() = list.size
}
