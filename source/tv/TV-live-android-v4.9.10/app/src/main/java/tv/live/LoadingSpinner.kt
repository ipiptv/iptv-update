package tv.live

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View

/**
 * 自绘加载圈：用 Handler 定时改角度重绘。
 * 不走系统 indeterminate 动画，因此即使电视系统把动画时长设成 0，也会一直转。
 */
class LoadingSpinner @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var angle = 0f
    private var running = false
    private val handler = Handler(Looper.getMainLooper())

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF7FE3FF.toInt()
    }

    private val tick = object : Runnable {
        override fun run() {
            angle = (angle + 9f) % 360f
            invalidate()
            if (running) handler.postDelayed(this, 26)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        paint.strokeWidth = (minOf(w, h) * 0.13f).coerceAtLeast(3f)
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) / 2f - paint.strokeWidth
        // 三段圆弧，透明度递减，转起来才有方向感
        val alphas = intArrayOf(255, 190, 120)
        for (i in 0 until 3) {
            paint.color = android.graphics.Color.argb(
                alphas[i], 0x7F, 0xE3, 0xFF
            )
            canvas.drawArc(
                cx - r, cy - r, cx + r, cy + r,
                angle + i * 120f, 82f, false, paint
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startSpin()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopSpin()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == VISIBLE) startSpin() else stopSpin()
    }

    fun startSpin() {
        if (running) return
        running = true
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun stopSpin() {
        running = false
        handler.removeCallbacks(tick)
    }
}
