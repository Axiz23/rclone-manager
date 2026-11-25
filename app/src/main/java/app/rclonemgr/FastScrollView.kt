package app.rclonemgr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

/**
 * Thin vertical line with a draggable handle on the right edge of a RecyclerView. Shows while scrolling and
 * fades out afterwards. Drag the handle to scroll quickly through long lists (works for list and grid).
 */
class FastScrollView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private var rv: RecyclerView? = null
    private val d = c.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbH = 52 * d
    private var fraction = 0f
    private var dragging = false
    private var grab = 0f
    private val rect = RectF()
    private val hideRun = Runnable { if (!dragging) animate().alpha(0f).setDuration(300).start() }

    init {
        alpha = 0f
        line.color = ColorUtils.setAlphaComponent(
            MaterialColors.getColor(c, com.google.android.material.R.attr.colorOnSurface, Color.GRAY), 70
        )
        thumb.color = MaterialColors.getColor(c, com.google.android.material.R.attr.colorPrimary, Color.BLUE)
    }

    fun attach(r: RecyclerView) {
        rv = r
        r.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0 && !dragging) { sync(); flash() }
            }
        })
        r.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sync() }
    }

    private fun scrollable(): Boolean {
        val r = rv ?: return false
        return r.computeVerticalScrollRange() > r.computeVerticalScrollExtent() * 1.3f
    }

    private fun sync() {
        val r = rv ?: return
        val max = r.computeVerticalScrollRange() - r.computeVerticalScrollExtent()
        fraction = if (max > 0) (r.computeVerticalScrollOffset().toFloat() / max).coerceIn(0f, 1f) else 0f
        invalidate()
    }

    private fun flash() {
        if (!scrollable()) return
        animate().cancel()
        alpha = 1f
        removeCallbacks(hideRun)
        postDelayed(hideRun, 1200)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width - 12 * d
        val lw = if (dragging) 1.5f * d else d
        canvas.drawRect(cx - lw, 0f, cx + lw, height.toFloat(), line)
        val tw = (if (dragging) 10 else 6) * d
        val top = fraction * (height - thumbH)
        rect.set(cx - tw / 2, top, cx + tw / 2, top + thumbH)
        canvas.drawRoundRect(rect, tw / 2, tw / 2, thumb)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val r = rv ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (alpha < 0.1f || !scrollable()) return false
                val top = fraction * (height - thumbH)
                if (e.y < top - 12 * d || e.y > top + thumbH + 12 * d) return false
                dragging = true
                grab = e.y - top
                parent?.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(hideRun)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val f = ((e.y - grab) / (height - thumbH)).coerceIn(0f, 1f)
                val max = r.computeVerticalScrollRange() - r.computeVerticalScrollExtent()
                r.scrollBy(0, (f * max).toInt() - r.computeVerticalScrollOffset())
                fraction = f
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                invalidate()
                postDelayed(hideRun, 1200)
                return true
            }
        }
        return dragging
    }
}
