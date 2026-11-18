package tv.live

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 管理页条目类型 */
object ManageType {
    const val SOURCE = 0      // 订阅源
    const val ADD = 1         // 添加新源
    const val GROUP = 2       // 分组
    const val NAV = 3         // 设置入口（导航）
    const val INFO = 4        // 只读说明（关于页）
}

data class ManageItem(
    val title: String,
    val subtitle: String,
    val type: Int,
    val checked: Boolean = false,
    val payload: String = ""
)

/** 与三栏一致的焦点配色 */
private const val FOCUS_BG = 0xCC0A5C99.toInt()

class ManageAdapter(
    private val list: MutableList<ManageItem>,
    var selected: Int = 0,
    var rowHeightPx: Int = 0,
    var textSizePx: Float = 0f,
    private val onClick: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<ManageAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.manageTitle)
        val sub: TextView = v.findViewById(R.id.manageSub)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_manage, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = list[position]
        if (item.type == ManageType.INFO) {
            // 说明条目按内容自适应高度，允许换行
            holder.itemView.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        } else if (rowHeightPx > 0) {
            holder.itemView.layoutParams.height = rowHeightPx
        }
        if (textSizePx > 0f) {
            holder.title.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx * 0.86f)
            holder.sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx * 0.72f)
        }
        holder.title.text = when {
            item.type == ManageType.SOURCE && item.checked -> "● ${item.title}"
            item.type == ManageType.GROUP && item.checked -> "◉ ${item.title}"
            item.type == ManageType.GROUP -> "○ ${item.title}"
            else -> item.title
        }
        holder.sub.text = item.subtitle
        // 精简后的设置项只显示名字；只有“关于”这种说明页才用副标题
        holder.sub.visibility = if (item.type == ManageType.INFO && item.subtitle.isNotEmpty()) View.VISIBLE else View.GONE

        holder.title.setTextColor(
            when {
                position == selected -> 0xFFFFFFFF.toInt()
                item.type == ManageType.SOURCE && item.checked -> 0xFF66DDAA.toInt()
                item.type == ManageType.GROUP && item.checked -> 0xFFFFFFFF.toInt()
                item.type == ManageType.GROUP -> 0xFF7A8A96.toInt()   // 已隐藏：暗灰
                item.type == ManageType.SOURCE && !item.checked -> 0xFF8E9AA4.toInt() // 非当前源：暗
                else -> 0xFFE8E8E8.toInt()
            }
        )
        holder.itemView.setBackgroundColor(
            if (position == selected) FOCUS_BG else 0x00000000
        )
        holder.itemView.setOnClickListener { onClick?.invoke(position) }
        holder.itemView.isFocusable = true
    }

    override fun getItemCount() = list.size
}
