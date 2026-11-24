package app.rclonemgr

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors

open class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        Api.init(this)
        edgeToEdge()
    }

    override fun onStart() {
        super.onStart()
        Ui.started()
    }

    override fun onStop() {
        super.onStop()
        Ui.stopped()
    }
}
