package cn.oneachina.zombieRun.listener

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PlayerTaskTracker {

    private val playerTasks = ConcurrentHashMap<UUID, MutableList<Int>>()

    fun register(taskId: Int, player: Player) {
        playerTasks.computeIfAbsent(player.uniqueId) { mutableListOf() }.add(taskId)
    }

    fun unregister(taskId: Int, playerId: UUID) {
        val tasks = playerTasks[playerId] ?: return
        tasks.remove(taskId)
        if (tasks.isEmpty()) {
            playerTasks.remove(playerId)
        }
    }

    fun clearAll(playerId: UUID) {
        playerTasks[playerId]?.forEach { Bukkit.getScheduler().cancelTask(it) }
        playerTasks.remove(playerId)
    }
}
