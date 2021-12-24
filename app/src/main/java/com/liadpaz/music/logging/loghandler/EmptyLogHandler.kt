package com.liadpaz.music.logging.loghandler

import com.liadpaz.music.logging.LogHandler
import com.liadpaz.music.logging.LogLevel

object EmptyLogHandler : LogHandler() {
    override fun log(logLevel: LogLevel, tag: String, message: String?, e: Throwable?) = Unit
}