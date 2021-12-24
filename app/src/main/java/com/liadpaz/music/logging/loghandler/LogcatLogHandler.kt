package com.liadpaz.music.logging.loghandler

import android.util.Log
import com.liadpaz.music.logging.LogHandler
import com.liadpaz.music.logging.LogLevel

object LogcatLogHandler : LogHandler() {
    override fun log(logLevel: LogLevel, tag: String, message: String?, e: Throwable?) {
        Log.println(getLogcatLogLevel(logLevel), tag, "$message${getStackTrace(e)}")
    }

    private fun getLogcatLogLevel(logLevel: LogLevel) = when (logLevel) {
        LogLevel.DEBUG -> Log.DEBUG
        LogLevel.INFO -> Log.INFO
        LogLevel.WARNING -> Log.WARN
        LogLevel.ERROR -> Log.ERROR
    }

    private fun getStackTrace(e: Throwable?): String = e?.let {
        "\n${it.stackTraceToString()}"
    } ?: ""
}