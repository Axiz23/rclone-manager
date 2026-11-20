package app.rclonemgr

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

class SquareImageView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : AppCompatImageView(c, a) {
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
}
