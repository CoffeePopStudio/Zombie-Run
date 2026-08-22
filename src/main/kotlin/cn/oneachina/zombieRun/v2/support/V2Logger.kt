package cn.oneachina.zombierun.v2.support

import java.util.logging.Logger

class V2Logger(private val logger: Logger) {

    fun info(message: String) = logger.info(message)
    fun warn(message: String) = logger.warning(message)
    fun severe(message: String) = logger.severe(message)
    fun debug(channel: String, message: String) {
        logger.info("[debug:$channel] $message")
    }
}
