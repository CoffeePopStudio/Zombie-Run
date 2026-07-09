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
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { task ->
            if (gameManager.getGameStatus() != GameManager.GameStatus.WAITING) {
                gameManager.cancelWaitStartTask()
                task.cancel()
                return@runAtFixedRate
            }

            val onlineCount = Bukkit.getOnlinePlayers().size
            val minPlayers = plugin.configManager.getMinPlayers()
            if (onlineCount < minPlayers) {
                gameManager.cancelWaitStartTask()
                task.cancel()
                return@runAtFixedRate
            }

            if (countdown <= 0) {
                gameManager.cancelWaitStartTask()
                gameManager.forceStartGame()
                task.cancel()
                return@runAtFixedRate
            }

            Bukkit.getOnlinePlayers().forEach { player ->
                player.showTitle(Title.title(
                    Component.text("", NamedTextColor.GREEN),
                    Component.text("§a游戏将在 §c$countdown §a秒后开始")
                ))
            }

            countdown--
        }, 1L, 20L)
    }
}

