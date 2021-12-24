package com.liadpaz.music.logging.loghandler

import com.liadpaz.music.logging.LogHandler
import com.liadpaz.music.logging.LogLevel
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class FileLogHandler(private val logFile: File) : LogHandler() {
    override fun log(logLevel: LogLevel, tag: String, message: String?, e: Throwable?): Unit =
        synchronized(this) {
            val currentTime = timeFormatter.format(LocalDateTime.now())
            logFile.appendText("$currentTime ${getLogLevelName(logLevel)}/$tag: $message\n${getStackTrace(e)}")

        }

    private fun getLogLevelName(logLevel: LogLevel) = when (logLevel) {
        LogLevel.DEBUG -> "D"
        LogLevel.INFO -> "I"
        LogLevel.WARNING -> "W"
        LogLevel.ERROR -> "E"
    }

    private fun getStackTrace(e: Throwable?): String = e?.stackTraceToString() ?: ""

    companion object {
        private val timeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS")
    }
}