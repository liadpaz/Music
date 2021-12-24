package com.liadpaz.music.logging

abstract class LogHandler {
    abstract fun log(logLevel: LogLevel, tag: String, message: String?, e: Throwable?)

    fun debug(tag: String, message: String?, e: Throwable?) =
        log(LogLevel.DEBUG, tag, message, e)

    fun info(tag: String, message: String?, e: Throwable?) =
        log(LogLevel.INFO, tag, message, e)

    fun warning(tag: String, message: String?, e: Throwable?) =
        log(LogLevel.WARNING, tag, message, e)

    fun error(tag: String, message: String?, e: Throwable?) =
        log(LogLevel.ERROR, tag, message, e)
}