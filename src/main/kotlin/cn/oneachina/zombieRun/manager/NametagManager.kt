package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.concurrent.CopyOnWriteArrayList

class NametagManager(private val plugin: ZombieRun) {

    private val tasks = CopyOnWriteArrayList<ScheduledTask>()

    fun init() {
        val task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { _ ->
            if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return@runAtFixedRate

            for (player in Bukkit.getOnlinePlayers()) {
                val team = plugin.gameManager.getPlayerTeam(player)
                if (team == GameManager.Team.SPECTATOR) continue

                val percent = plugin.healthManager.getHealthPercent(player)
                player.displayName(buildNametag(player.name, team, percent))
            }
        }, 1L, 10L) // 每 10 tick (0.5秒) 刷新
        tasks.add(task)
        plugin.logger.info("头顶名牌系统初始化完成")
    }

    private fun buildNametag(name: String, team: GameManager.Team, percent: Double): Component {
        val teamLabel = when (team) {
            GameManager.Team.HUMAN -> Component.text("[人类]", NamedTextColor.AQUA, TextDecoration.BOLD)
            GameManager.Team.ZOMBIE -> Component.text("[僵尸]", NamedTextColor.DARK_GREEN, TextDecoration.BOLD)
            GameManager.Team.ZOMBIE_MAIN -> Component.text("[母体]", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
            else -> Component.empty()
        }

        val filledCount = (percent * 10).toInt().coerceIn(0, 10)
        val emptyCount = 10 - filledCount

        val heartColor = when {
            percent > 0.5 -> NamedTextColor.GREEN
            percent > 0.25 -> NamedTextColor.YELLOW
            else -> NamedTextColor.RED
        }

        val hearts = Component.text()
            .append(Component.text("♥".repeat(filledCount), heartColor))
            .append(Component.text("♡".repeat(emptyCount), NamedTextColor.GRAY))
            .build()

        val percentText = Component.text(" ${(percent * 100).toInt()}%", NamedTextColor.WHITE)

        return Component.text()
            .append(teamLabel)
            .append(Component.space())
            .append(Component.text(name, NamedTextColor.WHITE))
            .append(Component.text("  ", NamedTextColor.GRAY))
            .append(hearts)
            .append(percentText)
            .build()
    }

    fun clear(player: Player) {
        player.displayName(null) // 恢复默认
    }

    fun clearAll() {
        tasks.forEach { it.cancel() }
        tasks.clear()
        Bukkit.getOnlinePlayers().forEach { it.displayName(null) }
    }
}
