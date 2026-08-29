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
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 头顶名牌/血量显示（对齐 v1 NametagManager）。
 *
 * 只对 arena 世界玩家生效；只恢复本插件设置过的名牌，避免覆盖其他昵称/称号插件。
 */
class V2NametagListener(
    private val gameFlow: GameFlowService,
    private val healthService: CombatHealthService,
    private val scheduler: SchedulerPort,
    private val taskRegistry: TaskRegistry,
    private val plugin: JavaPlugin? = null,
) : Listener {

    private val managedNames = ConcurrentHashMap.newKeySet<UUID>()
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
        // 只恢复本插件设置过的名牌
        managedNames.forEach { id -> Bukkit.getPlayer(id)?.let { runOnPlayer(it) { resetName(it) } } }
        managedNames.clear()
    }

    private fun refresh() {
        Bukkit.getOnlinePlayers().forEach { player ->
            val world = player.world.name
            if (!gameFlow.isArenaWorld(world)) {
                if (player.uniqueId in managedNames) {
                    runOnPlayer(player) { resetName(player) }
                }
                return@forEach
            }
            val team = gameFlow.teamOf(world, player.uniqueId)
            val percent = healthService.getHealthPercent(player.uniqueId)
            runOnPlayer(player) {
                player.displayName(buildNametag(player.name, team, percent))
                managedNames.add(player.uniqueId)
            }
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

    private fun resetName(player: Player) {
        player.displayName(Component.text(player.name))
        managedNames.remove(player.uniqueId)
    }

    private fun runOnPlayer(player: Player, action: () -> Unit) {
        val p = plugin
        if (p != null) {
            player.scheduler.run(p, { _ -> action() }, null)
        } else {
            action()
        }
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        val id = event.player.uniqueId
        if (!gameFlow.isArenaWorld(event.player.world.name) && id in managedNames) {
            runOnPlayer(event.player) { resetName(event.player) }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        managedNames.remove(event.player.uniqueId)
    }

    companion object {
        private const val REFRESH_TICKS = 10L
    }
}
