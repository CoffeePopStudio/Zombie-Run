package cn.oneachina.zombierun.v2.infrastructure.bukkit.listener

import cn.oneachina.zombierun.v2.application.player.PlayerDataService
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class V2PlayerDataListener(
    private val playerData: PlayerDataService,
) : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        playerData.onJoin(event.player.uniqueId)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        playerData.onQuit(event.player.uniqueId)
    }
}