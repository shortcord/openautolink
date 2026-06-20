package com.openautolink.companion.diagnostics

import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

class TcpSyslogSink(
    private val appName: String,
) {
    companion object {
        private const val HOST = "ns2.owo.systems"
        private const val PORT = 514
        private const val MAX_QUEUE_BYTES = 5 * 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val WRITE_TIMEOUT_MS = 3000
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val monitor = java.lang.Object()  // wait/notifyAll needed for producer-consumer
    private val queue = LinkedList<ByteArray>()
    private var queuedBytes = 0
    private var droppedMessages = 0L
    @Volatile private var running = false
    private var worker: Thread? = null
    private val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    fun start() {
        synchronized(monitor) {
            if (running) return
            running = true
            worker = Thread({ runWorker() }, "OAL-Companion-Syslog").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        synchronized(monitor) {
            running = false
            monitor.notifyAll()
        }
        worker?.join(500)
        worker = null
    }

    fun enqueue(level: Char, tag: String, message: String) {
        val payload = format(level, tag, message).toByteArray(Charsets.UTF_8)
        synchronized(monitor) {
            if (!running) return
            queue.addLast(payload)
            queuedBytes += payload.size
            while (queuedBytes > MAX_QUEUE_BYTES && queue.isNotEmpty()) {
                val dropped = queue.removeFirst()
                queuedBytes -= dropped.size
                droppedMessages++
            }
            monitor.notifyAll()
        }
    }

    fun status(): String {
        synchronized(monitor) {
            return if (!running) "disabled" else "enabled queued=${queue.size} dropped=$droppedMessages"
        }
    }

    private fun runWorker() {
        var socket: Socket? = null
        var backoffMs = 1000L
        while (running) {
            val payload = synchronized(monitor) {
                while (running && queue.isEmpty()) monitor.wait(500)
                if (!running) return
                queue.removeFirstOrNull()?.also { queuedBytes -= it.size }
            } ?: continue

            try {
                val s = socket
                if (s == null || s.isClosed || !s.isConnected) {
                    socket = Socket().apply {
                        soTimeout = WRITE_TIMEOUT_MS
                        connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
                    }
                    backoffMs = 1000L
                }
                socket!!.getOutputStream().write(payload)
                socket!!.getOutputStream().write('\n'.code)
                socket!!.getOutputStream().flush()
            } catch (_: Exception) {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                Thread.sleep(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
                synchronized(monitor) {
                    queue.addFirst(payload)
                    queuedBytes += payload.size
                    while (queuedBytes > MAX_QUEUE_BYTES && queue.isNotEmpty()) {
                        val dropped = queue.removeFirst()
                        queuedBytes -= dropped.size
                        droppedMessages++
                    }
                }
            }
        }
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun format(level: Char, tag: String, message: String): String {
        val pri = when (level) {
            'D' -> 7
            'I' -> 6
            'W' -> 4
            else -> 3
        }
        return "<${16 * 8 + pri}>${ts.format(Date())} $appName $tag: $message"
    }
}
