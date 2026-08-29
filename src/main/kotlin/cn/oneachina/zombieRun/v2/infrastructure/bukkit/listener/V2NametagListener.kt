package cn.oneachina.zombierun.v2.infrastructure.bukkit.listener

import cn.oneachina.zombierun.v2.application.combat.CombatHealthService
import cn.oneachina.zombierun.v2.application.game.GameFlowService
import cn.oneachina.zombierun.v2.domain.game.GameTeam
import cn.oneachina.zombierun.v2.ports.SchedulerPort
import cn.oneachina.zombierun.v2.ports.TaskHandle
import cn.oneachina.zombierun.v2.support.TaskRegistry
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * 头顶名牌/血量显示（对齐 v1 NametagManager）。
 *
 * 只对 arena 世界玩家生效；离开 arena 世界/退出时恢复原始名字，避免污染其他世界。
 */
class V2NametagListener(
    private val gameFlow: GameFlowService,
    private val healthService: CombatHealthService,
    private val scheduler: SchedulerPort,
    private val taskRegistry: TaskRegistry,
) : Listener {

    private var tickTask: TaskHandle? = null

    fun start() {
        if (tickTask != null) return
        val handle = scheduler.globalTimer(REFRESH_TICKS, REFRESH_TICKS) { refresh() }
        tickTask = handle
        taskRegistry.register(handle)
    }

    fun stop() {
        tickTask?.cancel()
        tickTask = null
        // 停止时恢复所有在线玩家原始名字
        Bukkit.getOnlinePlayers().forEach { it.displayName(Component.text(it.name)) }
    }

    private fun refresh() {
        Bukkit.getOnlinePlayers().forEach { player ->
            val world = player.world.name
            if (!gameFlow.isArenaWorld(world)) {
                resetNameIfNeeded(player)
                return@forEach
            }
            val team = gameFlow.teamOf(world, player.uniqueId)
            val percent = healthService.getHealthPercent(player.uniqueId)
            player.displayName(buildNametag(player.name, team, percent))
        }
    }

    private fun buildNametag(name: String, team: GameTeam?, healthPercent: Double): Component {
        val (prefix, color) = when (team) {
            GameTeam.HUMAN -> "[人类] " to NamedTextColor.AQUA
            GameTeam.ZOMBIE -> "[僵尸] " to NamedTextColor.DARK_GREEN
            GameTeam.ZOMBIE_MAIN -> "[母体] " to NamedTextColor.LIGHT_PURPLE
            GameTeam.SPECTATOR -> "[观战] " to NamedTextColor.GRAY
            null -> "" to NamedTextColor.WHITE
        }
        val hearts = ((healthPercent.coerceIn(0.0, 1.0)) * 10).toInt().coerceIn(0, 10)
        return Component.text()
            .append(Component.text(prefix, color))
            .append(Component.text(name, NamedTextColor.WHITE))
            .append(Component.text(" $hearts/10", NamedTextColor.RED))
            .build()
    }

    private fun resetNameIfNeeded(player: Player) {
        val current = PlainTextComponentSerializer.plainText().serialize(player.displayName())
        if (current != player.name) {
            player.displayName(Component.text(player.name))
        }
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        if (!gameFlow.isArenaWorld(event.player.world.name)) {
            event.player.displayName(Component.text(event.player.name))
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        event.player.displayName(Component.text(event.player.name))
    }

    companion object {
        private const val REFRESH_TICKS = 10L
    }
}
