package t7helpers.voiceinbox.ui

import android.app.Activity
import android.os.Bundle

/**
 * Home-screen shortcuts and Record widgets: open the app straight into recording with one
 * [t7helpers.voiceinbox.Shortcut].
 * It only passes the tap on to [MainActivity] and closes. Living in a task of its own, it
 * reaches MainActivity on every tap, also when the app is already open; a shortcut to
 * MainActivity itself would just bring the open app to the front.
 */
class RecordActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(MainActivity.recordIntent(this, intent.getStringExtra(MainActivity.EXTRA_SHORTCUT)))
        finish()
    }
}
