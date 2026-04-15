package com.openautolink.app

import android.app.Application
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application class that installs a global UncaughtExceptionHandler
 * to capture crash stack traces to a file before the process dies.
 *
 * Crash logs are written to the app's external files directory:
 *   /sdcard/Android/data/com.openautolink.app/files/Documents/oal-crash.txt
 *
 * Retrievable via: adb pull /sdcard/Android/data/com.openautolink.app/files/Documents/oal-crash.txt
 */
class OalApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("=== OpenAutoLink Crash Report ===")
                pw.println("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
                pw.println("Thread: ${thread.name}")
                pw.println("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                pw.println("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                pw.println("SoC: ${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}")
                pw.println()
                throwable.printStackTrace(pw)
                pw.flush()

                val report = sw.toString()

                // Log to logcat (visible via adb logcat)
                Log.e("OAL-CRASH", report)

                // Write to app's external Documents directory (no permissions needed)
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                if (dir != null) {
                    dir.mkdirs()
                    File(dir, "oal-crash.txt").writeText(report)
                }

                // Also try internal files dir as fallback
                File(filesDir, "oal-crash.txt").writeText(report)
            } catch (_: Throwable) {
                // Crash handler must not throw
            }

            // Chain to default handler (shows system crash dialog / kills process)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
