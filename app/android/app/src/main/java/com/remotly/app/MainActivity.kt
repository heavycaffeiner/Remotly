package com.remotly.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.remotly.app.ui.RemotlyApp

/**
 * The single activity. Every screen is a Compose destination inside it.
 *
 * `singleTask` in the manifest keeps one instance, so a terminal session and
 * its native view survive the user leaving and returning through the
 * launcher. Configuration changes are handled by the activity rather than by
 * a recreate, for the same reason: recreating tears down the terminal view
 * and the scrollback goes with it.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { RemotlyApp() }
    }
}
