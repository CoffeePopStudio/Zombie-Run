package cn.oneachina.zombierun.v2.support

import cn.oneachina.zombierun.v2.ports.TaskHandle
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 所有长生命周期定时任务统一登记，插件禁用/游戏结束时统一取消。
 */
class TaskRegistry {

    private val handles = CopyOnWriteArrayList<TaskHandle>()

    fun register(handle: TaskHandle): TaskHandle {
        handles.add(handle)
        return handle
    }

    fun cancelAll() {
        handles.forEach { it.cancel() }
        handles.clear()
    }

    fun size(): Int = handles.size
}
