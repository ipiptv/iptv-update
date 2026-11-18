package tv.live

data class Channel(
    val name: String,
    val group: String = "未分组",
    val urls: MutableList<String> = mutableListOf()
) {
    /** 全局连续频道编号，构建列表时统一赋值 */
    var number: Int = 0
}
