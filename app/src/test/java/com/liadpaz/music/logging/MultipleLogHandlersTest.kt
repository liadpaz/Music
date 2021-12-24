package com.liadpaz.music.logging

import com.liadpaz.music.logging.loghandler.MultipleLogHandlers
import com.liadpaz.music.utils.relaxedMockk
import io.mockk.verifySequence
import org.junit.jupiter.api.Test

class MultipleLogHandlersTest {

    private val logHandlers = arrayOf<LogHandler>(
        relaxedMockk(),
        relaxedMockk(),
        relaxedMockk()
    )
    private val multipleLogHandlers = MultipleLogHandlers(*logHandlers)
    private val logger = Logger(multipleLogHandlers)

    @Test
    fun `MultipleLogHandlers log calls all underlying log handlers`() {
        logger.debug(TEST_TAG, TEST_MESSAGE, TEST_EXCEPTION)
        logger.info(TEST_TAG, TEST_MESSAGE, TEST_EXCEPTION)
        logger.warning(TEST_TAG, TEST_MESSAGE, TEST_EXCEPTION)
        logger.error(TEST_TAG, TEST_MESSAGE, TEST_EXCEPTION)

        logHandlers.forEach { logHandler ->
            verifySequence {
                logHandler.log(LogLevel.DEBUG, logger.getTag(TEST_TAG), TEST_MESSAGE, TEST_EXCEPTION)
                logHandler.log(LogLevel.INFO, logger.getTag(TEST_TAG), TEST_MESSAGE, TEST_EXCEPTION)
                logHandler.log(LogLevel.WARNING, logger.getTag(TEST_TAG), TEST_MESSAGE, TEST_EXCEPTION)
                logHandler.log(LogLevel.ERROR, logger.getTag(TEST_TAG), TEST_MESSAGE, TEST_EXCEPTION)
            }
        }
    }

    companion object {
        private const val TEST_TAG = "Test"
        private const val TEST_MESSAGE = "This Is A Test"
        private val TEST_EXCEPTION = Exception()
    }
}