package app.rclonemgr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.min

/** Pinch-to-zoom, pan, double-tap zoom; horizontal fling (when not zoomed) calls onSwipe(+1 next / -1 previous). */
class ZoomImageView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : AppCompatImageView(c, a) {
    private val m = Matrix()
    private val vals = FloatArray(9)
    private var minScale = 1f
    var onSwipe: ((Int) -> Unit)? = null

    private val sd = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomBy(d.scaleFactor, d.focusX, d.focusY); return true
        }
    })

    private val gd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale() > minScale * 1.05f) fit() else zoomBy(minScale * 3f / scale(), e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale() > minScale * 1.01f) {
                m.postTranslate(-dx, -dy); clamp(); imageMatrix = m
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scale() <= minScale * 1.01f && abs(vx) > abs(vy) * 1.5f && abs(vx) > 1000f) {
                onSwipe?.invoke(if (vx < 0) 1 else -1); return true
            }
            return false
        }
    })

    init { scaleType = ImageView.ScaleType.MATRIX }

    private fun scale(): Float { m.getValues(vals); return vals[Matrix.MSCALE_X] }

    private fun zoomBy(f: Float, fx: Float, fy: Float) {
        val cur = scale()
        val target = (cur * f).coerceIn(minScale, minScale * 6f)
        val k = target / cur
        m.postScale(k, k, fx, fy); clamp(); imageMatrix = m
    }

    private fun clamp() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        var dx = 0f; var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2 - r.left
        else if (r.left > 0) dx = -r.left
        else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2 - r.top
        else if (r.top > 0) dy = -r.top
        else if (r.bottom < height) dy = height - r.bottom
        m.postTranslate(dx, dy)
    }

    fun fit() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat(); val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0 || width == 0 || height == 0) return
        val s = min(width / dw, height / dh)
        m.reset(); m.postScale(s, s); m.postTranslate((width - dw * s) / 2, (height - dh * s) / 2)
        minScale = s; imageMatrix = m
    }

    fun setBitmapAndReset(b: Bitmap) { setImageBitmap(b); fit() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        sd.onTouchEvent(e)
        if (!sd.isInProgress) gd.onTouchEvent(e)
        return true
    }
}
