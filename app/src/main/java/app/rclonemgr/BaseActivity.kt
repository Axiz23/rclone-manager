package app.rclonemgr

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors

open class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        edgeToEdge()
    }

    override fun onStart() {
        super.onStart()
        Ui.started()
        RcServer.ensureRunning(this)
    }

    override fun onStop() {
        super.onStop()
        Ui.stopped()
        if (!Ui.visible) RcServer.stopLater() // free the rclone server when the app is in the background
    }
}
