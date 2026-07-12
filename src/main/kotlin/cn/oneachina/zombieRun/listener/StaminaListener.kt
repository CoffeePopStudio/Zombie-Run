package cn.oneachina.zombieRun.listener

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.GameManager
import com.destroystokyo.paper.event.player.PlayerJumpEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerToggleSprintEvent

class StaminaListener(private val plugin: ZombieRun) : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        if (event.from.blockX == event.to.blockX &&
            event.from.blockY == event.to.blockY &&
            event.from.blockZ == event.to.blockZ) return

        plugin.staminaManager.setMoving(player, true)
        plugin.staminaManager.setSprinting(player, player.isSprinting)

        if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return
        if (plugin.gameManager.getPlayerTeam(player) != GameManager.Team.HUMAN) return

        if (player.isSprinting && !plugin.staminaManager.canSprintOrJump(player)) {
            event.to = event.from
            player.isSprinting = false
            plugin.staminaManager.setSprinting(player, false)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerToggleSprint(event: PlayerToggleSprintEvent) {
        val player = event.player
        plugin.staminaManager.setSprinting(player, event.isSprinting)

        if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return
        if (plugin.gameManager.getPlayerTeam(player) != GameManager.Team.HUMAN) return

        if (event.isSprinting && !plugin.staminaManager.canSprintOrJump(player)) {
            event.isCancelled = true
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c体力耗尽！请等待体力恢复到100才能疾跑。"))
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerJump(event: PlayerJumpEvent) {
        val player = event.player
        if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return
        if (plugin.gameManager.getPlayerTeam(player) != GameManager.Team.HUMAN) return

        if (!plugin.staminaManager.canSprintOrJump(player)) {
            event.isCancelled = true
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c体力耗尽！请等待体力恢复到100才能跳跃。"))
            return
        }

        val health = player.health
        val cost = (3.0 - (health * 0.15)).coerceAtLeast(0.5)
        plugin.staminaManager.deductStamina(player, cost)
    }
}
