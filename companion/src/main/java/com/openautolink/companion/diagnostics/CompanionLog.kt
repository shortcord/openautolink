package com.openautolink.companion.diagnostics

import android.util.Log

/**
 * Version-prefixed log wrapper for the companion app.
 * Mirrors the car app's OalLog — all log lines include the version so you
 * never have to guess which build produced a log.
 *
 * When [CompanionFileLogger] is active, every log line is also written to disk.
 */
object CompanionLog {
    private var _prefix: String = "[companion ?] "

    /** Call once from Application.onCreate() or service start. */
    fun init(versionName: String) {
        _prefix = "[companion $versionName] "
    }

    @Volatile
    var fileLogger: CompanionFileLogger? = null

    @Volatile
    var remoteSyslogSink: TcpSyslogSink? = null

    fun d(tag: String, msg: String) {
        Log.d(tag, _prefix + msg)
        fileLogger?.writeLog('D', tag, msg)
        remoteSyslogSink?.enqueue('D', tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, _prefix + msg)
        fileLogger?.writeLog('I', tag, msg)
        remoteSyslogSink?.enqueue('I', tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, _prefix + msg)
        fileLogger?.writeLog('W', tag, msg)
        remoteSyslogSink?.enqueue('W', tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable) {
        Log.w(tag, _prefix + msg, tr)
        val line = "$msg: ${tr.message}"
        fileLogger?.writeLog('W', tag, line)
        remoteSyslogSink?.enqueue('W', tag, line)
    }

    fun e(tag: String, msg: String) {
        Log.e(tag, _prefix + msg)
        fileLogger?.writeLog('E', tag, msg)
        remoteSyslogSink?.enqueue('E', tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable) {
        Log.e(tag, _prefix + msg, tr)
        val line = "$msg: ${tr.message}"
        fileLogger?.writeLog('E', tag, line)
        remoteSyslogSink?.enqueue('E', tag, line)
    }
}
