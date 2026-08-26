package cn.oneachina.zombierun.v2.support

import java.util.logging.Logger

class V2Logger(private val logger: Logger) {

    /** 由装配点根据 settings.debug 设置；false 时 debug() 静默。 */
    @Volatile
    var debugEnabled: Boolean = false

    fun info(message: String) = logger.info(message)
    fun warn(message: String) = logger.warning(message)
    fun severe(message: String) = logger.severe(message)
    fun debug(channel: String, message: String) {
        if (debugEnabled) logger.info("[debug:$channel] $message")
    }
}
