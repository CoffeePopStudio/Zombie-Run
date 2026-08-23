package cn.oneachina.zombierun.v2.infrastructure.bukkit.listener

import cn.oneachina.zombierun.v2.application.task.TaskService
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class V2TaskListener(
    private val taskService: TaskService,
) : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        taskService.onJoin(event.player.uniqueId)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        taskService.onQuit(event.player.uniqueId)
    }
}