package app.rclonemgr

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import androidx.appcompat.widget.AppCompatImageView

/** ImageView with a fixed 16:9 aspect ratio (grid thumbnails). */
class ThumbImageView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : AppCompatImageView(c, a) {
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY)
        )
    }
}
