package tv.live

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

/** 与分组栏同一套焦点配色 */
private const val FOCUS_BG = 0xCC0A5C99.toInt()
private const val FOCUS_BG_DIM = 0x990E4C7D.toInt()

class ChannelAdapter(
    private val list: MutableList<Channel>,
    var selected: Int = 0,
    var rowHeightPx: Int = 0,
    var textSizePx: Float = 0f,
    var focused: Boolean = false,
    /** 取当前频道正在播的节目名；没有节目单时返回 null */
    var programProvider: ((String) -> String?)? = null,
    /** 线路信息行，例如「源 2/5」；返回 null 则不显示 */
    var sourceInfoProvider: ((Channel) -> String?)? = null,
    private val onClick: ((Int) -> Unit)? = null,
    private val onLongClick: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.channelNum)
        val icon: ImageView = v.findViewById(R.id.channelIcon)
        val name: TextView = v.findViewById(R.id.channelName)
        val programRow: View = v.findViewById(R.id.programRow)
        val programName: TextView = v.findViewById(R.id.programName)
        val sourceInfo: TextView = v.findViewById(R.id.sourceInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (rowHeightPx > 0) {
            holder.itemView.layoutParams.height = rowHeightPx
            val iconSize = (rowHeightPx * 0.26f).toInt()
            val lp = holder.icon.layoutParams
            lp.width = iconSize
            lp.height = iconSize
            holder.icon.layoutParams = lp
        }
        if (textSizePx > 0f) {
            holder.num.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx * 0.62f)
            holder.name.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
        }
        val c = list[position]
        holder.num.text = if (c.number > 0) c.number.toString() else (position + 1).toString()
        holder.name.text = c.name

        // 频道名过长时按可用宽度自动缩字号，保证完整显示
        holder.name.post {
            val avail = holder.name.width
            if (avail > 0 && textSizePx > 0f) {
                val measured = holder.name.paint.measureText(c.name)
                if (measured > avail) {
                    val scaled = textSizePx * (avail.toFloat() / measured) - 1f
                    holder.name.setTextSize(TypedValue.COMPLEX_UNIT_PX, max(scaled, textSizePx * 0.5f))
                } else {
                    holder.name.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
                }
            }
        }

        // 第二行：正在播放 + 当前节目（短名静止，超宽跑马灯）
        val prog = programProvider?.invoke(c.name)
        if (prog.isNullOrEmpty()) {
            holder.programRow.visibility = View.INVISIBLE
            holder.programName.text = ""
            holder.programName.isSelected = false
        } else {
            holder.programRow.visibility = View.VISIBLE
            holder.programName.text = prog
            holder.programName.post {
                val avail = holder.programName.width
                if (avail > 0) {
                    val need = holder.programName.paint.measureText(prog)
                    // 超出可用宽度才滚动，短名保持静止，省性能
                    holder.programName.isSelected = need > avail
                    holder.programName.ellipsize = if (need > avail)
                        android.text.TextUtils.TruncateAt.MARQUEE
                    else android.text.TextUtils.TruncateAt.END
                }
            }
        }

        val srcInfo = sourceInfoProvider?.invoke(c)
        if (srcInfo.isNullOrEmpty()) {
            holder.sourceInfo.visibility = View.GONE
            holder.sourceInfo.text = ""
        } else {
            holder.sourceInfo.visibility = View.VISIBLE
            holder.sourceInfo.text = srcInfo
        }

        holder.name.alpha = if (focused) 1f else 0.88f
        holder.itemView.setBackgroundColor(
            if (position == selected) {
                if (focused) FOCUS_BG else FOCUS_BG_DIM
            } else 0x00000000
        )
        holder.itemView.setOnClickListener { onClick?.invoke(position) }
        holder.itemView.setOnLongClickListener {
            onLongClick?.invoke(position)
            true
        }
        holder.itemView.isFocusable = true
    }

    override fun getItemCount() = list.size
}
