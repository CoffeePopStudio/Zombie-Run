package cn.oneachina.zombieRun.task

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.GameManager
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player

class StartCountdownTask(
    private val plugin: ZombieRun,
    private val gameManager: GameManager
) {

    private var countdown = 15
    val getCountdown: Int
        get() = countdown
    private val alphaZombie: Player = gameManager.selectAlphaZombie()

    init {
        gameManager.alphaZombie = alphaZombie
        gameManager.isCountdownActive = true

        plugin.logger.info("准备阶段开始，母体: ${alphaZombie.name}，模式设为冒险")

        Bukkit.getOnlinePlayers().forEach { player ->
            if (player == alphaZombie) {
                player.teleportAsync(plugin.respawnManager.getZombieMainRespawn()?.getLocation(player.world)
                    ?: plugin.respawnManager.getDefaultRespawn().getLocation(player.world))
            } else {
                player.teleportAsync(plugin.respawnManager.getPlayerInitialRespawn()?.getLocation(player.world)
                    ?: plugin.respawnManager.getDefaultRespawn().getLocation(player.world))
            }
            player.gameMode = GameMode.ADVENTURE
            player.showTitle(Title.title(
                Component.text("准备阶段", NamedTextColor.AQUA),
                Component.text("大门将在 $countdown 秒后打开", NamedTextColor.WHITE)
            ))
        }
    }

    fun start(): ScheduledTask {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { task ->
            if (gameManager.getGameStatus() != GameManager.GameStatus.STARTING) {
                task.cancel()
                gameManager.isCountdownActive = false
                return@runAtFixedRate
            }

            countdown--

            if (countdown <= 0) {
                task.cancel()
                gameManager.isCountdownActive = false
                gameManager.beginGame()
                return@runAtFixedRate
            }

            Bukkit.getOnlinePlayers().forEach { player ->
                player.showTitle(Title.title(
                    Component.text("准备阶段", NamedTextColor.AQUA),
                    Component.text("大门将在 $countdown 秒后打开", NamedTextColor.WHITE)
                ))
            }
        }, 1L, 20L)
    }
}

