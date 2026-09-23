package com.jev.probe.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small app-private diagnostic log. Never write keys or chat content here. */
object AppLog {
    private const val FILE_NAME = "runtime.log"
    private const val MAX_BYTES = 128 * 1024
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file == null) file = File(context.applicationContext.filesDir, FILE_NAME)
    }

    @Synchronized
    fun i(area: String, message: String) = append("INFO", area, message)

    @Synchronized
    fun e(area: String, message: String, error: Throwable? = null) {
        val detail = error?.let { " | " + it.javaClass.simpleName + ": " + safe(it.message) } ?: ""
        append("ERROR", area, message + detail)
    }

    @Synchronized
    fun read(): String = runCatching {
        file?.takeIf { it.exists() }?.readText(Charsets.UTF_8).orEmpty()
    }.getOrDefault("")

    @Synchronized
    fun clear() {
        runCatching { file?.writeText("", Charsets.UTF_8) }
    }

    private fun append(level: String, area: String, message: String) {
        val target = file ?: return
        runCatching {
            val line = timestamp() + " [" + level + "] [" + area + "] " + safe(message) + "\n"
            if (target.exists() && target.length() > MAX_BYTES) {
                val tail = target.readText(Charsets.UTF_8).takeLast(MAX_BYTES / 2)
                target.writeText("--- 日志已自动截断 ---\n" + tail, Charsets.UTF_8)
            }
            target.appendText(line, Charsets.UTF_8)
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun safe(value: String?): String = value.orEmpty()
        .replace(Regex("(?i)(bearer|key|token)\\s+[A-Za-z0-9._-]+"), "\$1 ***")
        .replace(Regex("[\\r\\n]+"), " ")
        .take(500)
}
