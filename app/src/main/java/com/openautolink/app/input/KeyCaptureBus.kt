package com.openautolink.app.input

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

    /** Returns true if the event was consumed by capture. */
    fun handle(event: KeyEvent): Boolean {
        val cb = listener ?: return false
        // Consume both DOWN and UP so the press doesn't leak to the projection
        // pipeline. Report the keycode on the first event we see.
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                cb(event.keyCode)
            }
            return true
        }
        return false
    }
}
