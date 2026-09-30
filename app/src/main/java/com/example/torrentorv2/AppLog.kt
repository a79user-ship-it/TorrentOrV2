package com.example.torrentorv2

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A qBittorrent-style "Execution Log": a persistent, timestamped log of notable app events
 * (engine start, torrents added/removed, RSS feed refresh results and auto-downloads, search
 * downloads, setting changes). Backed by a plain text file in the app's own internal storage
 * (Context.filesDir — not the cache dir, and not cleared when the app process dies), so
 * entries survive the app being fully closed and reopened, same as qBittorrent's log does —
 * no database needed for something this small.
 *
 * Thread-safe via a single lock; every write is also capped so the file can't grow forever.
 */
object AppLog {
    private const val MAX_FILE_BYTES = 256 * 1024
    private const val TRIM_TO_LINES = 400

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val lock = Any()
    private var logFile: File? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (logFile == null) {
                logFile = File(context.applicationContext.filesDir, "torrentor_log.txt")
            }
        }
    }

    fun info(message: String) = write("INFO", message)
    fun warning(message: String) = write("WARNING", message)
    fun error(message: String) = write("ERROR", message)

    private fun write(level: String, message: String) {
        val file = synchronized(lock) { logFile } ?: return

        try {
            val line = "${dateFormat.format(Date())} [$level] $message\n"
            file.appendText(line)

            if (file.length() > MAX_FILE_BYTES) {
                synchronized(lock) {
                    val trimmed = file.readLines().takeLast(TRIM_TO_LINES)
                    file.writeText(trimmed.joinToString("\n") + "\n")
                }
            }
        } catch (e: Throwable) {
            // Logging is best-effort — never let a log write crash the caller.
        }
    }

    /** Chronological order, oldest first — so a log viewer that auto-scrolls to the bottom
     *  always lands on the most recent entry, the same convention qBittorrent's log uses. */
    fun readAll(): List<String> {
        val file = synchronized(lock) { logFile } ?: return emptyList()
        return try {
            if (!file.exists()) emptyList() else file.readLines()
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun clear() {
        val file = synchronized(lock) { logFile } ?: return
        try {
            file.writeText("")
        } catch (e: Throwable) {
        }
    }
}