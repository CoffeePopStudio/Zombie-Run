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
    private var countdown: Int
) {

    fun start(): ScheduledTask {
        gameManager.waitStartCountdown = countdown
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { task ->
            if (gameManager.getGameStatus() != GameManager.GameStatus.WAITING) {
                gameManager.cancelWaitStartTask()
                gameManager.waitStartCountdown = 0
                task.cancel()
                return@runAtFixedRate
            }

            val onlineCount = Bukkit.getOnlinePlayers().size
            val minPlayers = plugin.configManager.getMinPlayers()
            if (onlineCount < minPlayers) {
                gameManager.cancelWaitStartTask()
                gameManager.waitStartCountdown = 0
                task.cancel()
                return@runAtFixedRate
            }

            if (countdown <= 0) {
                gameManager.cancelWaitStartTask()
                gameManager.waitStartCountdown = 0
                gameManager.forceStartGame()
                task.cancel()
                return@runAtFixedRate
            }

            Bukkit.getOnlinePlayers().forEach { player ->
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
            gameManager.waitStartCountdown = countdown
        }, 1L, 20L)
    }
}

