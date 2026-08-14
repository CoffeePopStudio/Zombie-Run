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
    private val gameManager: GameManager,
    private val game: GameManager.GameInstance,
    countdownSeconds: Int = 15
) {

    // PAPI 占位符（bossbar）跨线程读取，需 volatile
    @Volatile private var countdown = countdownSeconds
    val getCountdown: Int
        get() = countdown
    private val alphaZombie: Player = gameManager.selectAlphaZombie(game)

    init {
        game.alphaZombie = alphaZombie
        game.isCountdownActive = true

        plugin.logger.info("[${game.worldName}] 准备阶段开始，母体: ${alphaZombie.name}，模式设为冒险")

        gameManager.getWorldPlayers(game.worldName).forEach { player ->
            val respawn = if (player == alphaZombie) {
                plugin.respawnManager.getZombieMainRespawn(game.worldName)
            } else {
                plugin.respawnManager.getPlayerInitialRespawn(game.worldName)
            }
            // 使用重生点配置的世界（多世界支持），未配置时回退游戏世界出生点
            val target = if (respawn != null) {
                respawn.getLocation(plugin.respawnManager.getRespawnWorld(respawn))
            } else {
                plugin.worldService.getWorldOrFirst(game.worldName).spawnLocation
            }
            player.teleportAsync(target)
            player.gameMode = GameMode.ADVENTURE
            player.showTitle(Title.title(
                Component.text("准备阶段", NamedTextColor.AQUA),
                Component.text("大门将在 $countdown 秒后打开", NamedTextColor.WHITE)
            ))
        }
    }

    fun start(): ScheduledTask {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { task ->
            if (game.status != GameManager.GameStatus.STARTING) {
                task.cancel()
                game.isCountdownActive = false
                return@runAtFixedRate
            }

            countdown--

            if (countdown <= 0) {
                task.cancel()
                game.isCountdownActive = false
                gameManager.beginGame(game)
                return@runAtFixedRate
            }

            gameManager.getWorldPlayers(game.worldName).forEach { player ->
                player.showTitle(Title.title(
                    Component.text("准备阶段", NamedTextColor.AQUA),
                    Component.text("大门将在 $countdown 秒后打开", NamedTextColor.WHITE)
                ))
            }
        }, 1L, 20L)
    }
}
