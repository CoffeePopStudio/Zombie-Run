package cn.oneachina.zombieRun.listener

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 追踪玩家相关的调度任务，退出/重生时统一取消，防止任务泄漏。
 * 对同一玩家的操作可能来自不同 region 线程，故对列表操作加锁。
 */
class PlayerTaskTracker {

    private val playerTasks = ConcurrentHashMap<UUID, MutableList<ScheduledTask>>()

    fun register(task: ScheduledTask, player: Player) {
        val tasks = playerTasks.computeIfAbsent(player.uniqueId) { mutableListOf() }
        synchronized(tasks) {
            tasks.add(task)
        }
    }

    fun unregister(task: ScheduledTask, playerId: UUID) {
        val tasks = playerTasks[playerId] ?: return
        synchronized(tasks) {
            tasks.remove(task)
            if (tasks.isEmpty()) {
                playerTasks.remove(playerId)
            }
        }
    }

    fun clearAll(playerId: UUID) {
        val tasks = playerTasks.remove(playerId) ?: return
        synchronized(tasks) {
            tasks.forEach { it.cancel() }
        }
    }
}
