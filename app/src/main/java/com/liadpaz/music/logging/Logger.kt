package com.liadpaz.music.logging

class Logger(
    private val logHandler: LogHandler,
    private val loggerTag: String = APP_TAG
) {
    fun getTag(tag: String) = "$loggerTag:[$tag]"

    fun debug(tag: String, message: String? = null, e: Throwable? = null) =
        logHandler.debug(getTag(tag), message, e)

    fun info(tag: String, message: String? = null, e: Throwable? = null) =
        logHandler.info(getTag(tag), message, e)

    fun warning(tag: String, message: String? = null, e: Throwable? = null) =
        logHandler.warning(getTag(tag), message, e)

    fun error(tag: String, message: String? = null, e: Throwable? = null) =
        logHandler.error(getTag(tag), message, e)

    companion object {
        const val APP_TAG = "MusicApp"
    }
}