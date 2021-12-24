package com.liadpaz.music.logging.loghandler

import com.liadpaz.music.logging.LogHandler
import com.liadpaz.music.logging.LogLevel

class MultipleLogHandlers(private vararg val logHandlers: LogHandler) : LogHandler() {
    override fun log(logLevel: LogLevel, tag: String, message: String?, e: Throwable?) =
        logHandlers.forEach { logHandler -> logHandler.log(logLevel, tag, message, e) }
}