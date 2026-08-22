package cn.oneachina.zombierun.v2.application.event

import java.util.concurrent.CopyOnWriteArrayList

/**
 * v2 应用内部事件总线：模块之间通过事件通信，不直接互相调用。
 * 与 Bukkit 事件解耦，便于 domain/application 单测。
 */
class ApplicationEventBus {

    @Suppress("UNCHECKED_CAST")
    private val handlers = HashMap<Class<*>, CopyOnWriteArrayList<(Any) -> Unit>>()

    fun <T : Any> subscribe(type: Class<T>, handler: (T) -> Unit) {
        val list = synchronized(handlers) {
            handlers.getOrPut(type) { CopyOnWriteArrayList() }
        }
        list.add { event -> handler(type.cast(event)) }
    }

    fun publish(event: Any) {
        val list = synchronized(handlers) {
            handlers[event.javaClass]
        } ?: return
        list.forEach { handler ->
            try {
                handler(event)
            } catch (e: Exception) {
                // 单个订阅者异常不应影响其他订阅者；调用方自行记录
                e.printStackTrace()
            }
        }
    }

    fun clear() {
        synchronized(handlers) {
            handlers.clear()
        }
    }
}
