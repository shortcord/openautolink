package com.openautolink.app.input

import com.openautolink.app.diagnostics.DiagnosticLog
import android.view.KeyEvent

/**
 * Global hook so the Settings "Assign key" dialog can capture hardware key
 * presses from the Activity's `dispatchKeyEvent`. Steering wheel / remote
 * buttons don't route through Compose focus chains (especially inside an
 * AlertDialog window), so we intercept at the Activity level.
 *
 * When `listener` is non-null, MainActivity consumes the next ACTION_UP
 * KeyEvent (after observing the matching ACTION_DOWN) and reports its
 * keycode here, suppressing forwarding to the projection / bridge.
 */
object KeyCaptureBus {
    @Volatile
    var listener: ((Int) -> Unit)? = null

    val isCapturing: Boolean
        get() = listener != null

    /** Returns true if the event was consumed by capture. */
    fun handle(event: KeyEvent): Boolean {
        val cb = listener ?: return false
        val keyName = KeyEvent.keyCodeToString(event.keyCode)
        DiagnosticLog.i(
            "input",
            "KeyCaptureBus received: action=${actionName(event.action)} keycode=${event.keyCode} " +
                    "($keyName) repeat=${event.repeatCount} source=0x${Integer.toHexString(event.source)} " +
                    "deviceId=${event.deviceId} scanCode=${event.scanCode} flags=0x${Integer.toHexString(event.flags)} " +
                    "meta=0x${Integer.toHexString(event.metaState)}"
        )
        // Consume DOWN, UP, and MULTIPLE so the press doesn't leak to the projection
        // pipeline. Report the keycode on the first DOWN event we see.
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP || event.action == KeyEvent.ACTION_MULTIPLE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                DiagnosticLog.i("input", "KeyCaptureBus reporting keycode=${event.keyCode} ($keyName)")
                cb(event.keyCode)
            } else {
                DiagnosticLog.d(
                    "input",
                    "KeyCaptureBus consumed without reporting: action=${actionName(event.action)} repeat=${event.repeatCount}"
                )
            }
            return true
        }
        DiagnosticLog.d("input", "KeyCaptureBus ignored unsupported action=${event.action}")
        return false
    }

    private fun actionName(action: Int): String = when (action) {
        KeyEvent.ACTION_DOWN -> "DOWN"
        KeyEvent.ACTION_UP -> "UP"
        KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
        else -> action.toString()
    }
}
