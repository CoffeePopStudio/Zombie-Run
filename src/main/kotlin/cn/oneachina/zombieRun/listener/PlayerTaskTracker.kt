package cn.oneachina.zombieRun.listener

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PlayerTaskTracker {

    private val playerTasks = ConcurrentHashMap<UUID, MutableList<ScheduledTask>>()

    fun register(task: ScheduledTask, player: Player) {
        playerTasks.computeIfAbsent(player.uniqueId) { mutableListOf() }.add(task)
    }

    fun unregister(task: ScheduledTask, playerId: UUID) {
        val tasks = playerTasks[playerId] ?: return
        tasks.remove(task)
        if (tasks.isEmpty()) {
            playerTasks.remove(playerId)
        }
    }

    fun clearAll(playerId: UUID) {
        playerTasks[playerId]?.forEach { it.cancel() }
        playerTasks.remove(playerId)
    }
}
