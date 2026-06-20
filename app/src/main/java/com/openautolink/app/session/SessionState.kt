package com.openautolink.app.session

import com.openautolink.app.transport.ConnectionState

/**
 * Session states — maps the full lifecycle from idle to streaming.
 */
enum class SessionState {
    IDLE,              // No connection, waiting for phone
    CONNECTING,        // Phone connecting (AA handshake in progress)
    CONNECTED,         // AA handshake complete, channels opening
    STREAMING,         // Video and/or audio actively flowing
    ERROR              // Unrecoverable error (shows message, user can retry)
}

/**
 * Maps transport ConnectionState to session-level state.
 */
fun ConnectionState.toSessionState(): SessionState = when (this) {
    ConnectionState.DISCONNECTED -> SessionState.IDLE
    ConnectionState.CONNECTING -> SessionState.CONNECTING
    ConnectionState.CONNECTED -> SessionState.CONNECTED
}
