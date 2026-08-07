package cn.oneachina.zombieRun.task

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.GameManager
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit

class WaitStartCountdownTask(
    private val plugin: ZombieRun,
    private val gameManager: GameManager,
    private val game: GameManager.GameInstance,
    private var countdown: Int
) {

    fun start(): ScheduledTask {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { task ->
            if (game.status != GameManager.GameStatus.WAITING) {
                gameManager.cancelWaitStartTask(game)
                task.cancel()
                return@runAtFixedRate
            }

            val onlineCount = gameManager.getWorldPlayers(game.worldName).size
            val minPlayers = plugin.configManager.getMinPlayers()
            if (onlineCount < minPlayers) {
                gameManager.cancelWaitStartTask(game)
                task.cancel()
                return@runAtFixedRate
            }

            if (countdown <= 0) {
                gameManager.cancelWaitStartTask(game)
                gameManager.forceStartGame(game.worldName)
                task.cancel()
                return@runAtFixedRate
            }

            gameManager.getWorldPlayers(game.worldName).forEach { player ->
                player.showTitle(Title.title(
                    Component.text("", NamedTextColor.GREEN),
                    Component.text()
                        .append(Component.text("游戏将在 ", NamedTextColor.GREEN))
                        .append(Component.text("$countdown", NamedTextColor.RED))
                        .append(Component.text(" 秒后开始", NamedTextColor.GREEN))
                        .build()
                ))
            }

            countdown--
        }, 1L, 20L)
    }
}
