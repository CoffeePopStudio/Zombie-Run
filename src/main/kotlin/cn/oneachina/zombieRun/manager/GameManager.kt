package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.Door
import cn.oneachina.zombieRun.task.StartCountdownTask
import cn.oneachina.zombieRun.task.WaitStartCountdownTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class GameManager(private val plugin: ZombieRun) {
    enum class GameStatus { WAITING, STARTING, RUNNING, ENDED }
    enum class Team { HUMAN, ZOMBIE, ZOMBIE_MAIN, SPECTATOR }

    private var status = GameStatus.WAITING
    private val playerTeams = ConcurrentHashMap<Player, Team>()
    private val playerRooms = ConcurrentHashMap<Player, Int>()

    private var gameStartTime: Long = 0
    private val humans = CopyOnWriteArrayList<Player>()
    private val zombies = CopyOnWriteArrayList<Player>()
    private val zombieMains = CopyOnWriteArrayList<Player>()

    private var waitStartCountdown: Int = 0

    var alphaZombie: Player? = null
    var isCountdownActive = false
    var countdownTask: StartCountdownTask? = null

    private var waitStartTask: WaitStartCountdownTask? = null
    private var autoCheckTaskId: Int = -1
    private var maxDurationTaskId: Int = -1

    init {
        startAutoCheckTask()
    }

    fun addPlayer(player: Player) {
        playerTeams[player] = Team.HUMAN
        humans.add(player)
        plugin.staminaManager.addPlayer(player)
        playerRooms[player] = 0
    }

    fun removePlayer(player: Player) {
        playerTeams.remove(player)
        playerRooms.remove(player)
        plugin.staminaManager.removePlayer(player)
        humans.remove(player)
        zombies.remove(player)
        zombieMains.remove(player)

        if (status == GameStatus.WAITING || status == GameStatus.STARTING) {
            checkAutoStartCondition()
        }
        checkGameEnd()
    }

    fun setGameStatus(newStatus: GameStatus) {
        this.status = newStatus
    }

    fun forceStartGame() {
        if (status != GameStatus.WAITING && status != GameStatus.ENDED) return
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            plugin.logger.warning("尝试开始游戏但没有玩家在线")
            return
        }
        cancelWaitStartTask()
        setGameStatus(GameStatus.STARTING)
        countdownTask = StartCountdownTask(plugin, this)
        countdownTask!!.runTaskTimer(plugin, 0L, 20L)
    }

    fun selectAlphaZombie(): Player {
        val online = Bukkit.getOnlinePlayers()
        return if (online.isEmpty()) throw IllegalStateException("No players online") else online.random()
    }

    fun beginGame() {
        if (status != GameStatus.STARTING) return
        setGameStatus(GameStatus.RUNNING)
        gameStartTime = System.currentTimeMillis()

        plugin.doorManager.reset()

        val alpha = alphaZombie ?: selectAlphaZombie()
        setPlayerTeam(alpha, Team.ZOMBIE_MAIN)
        alpha.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 500.0
        alpha.health = 500.0

        alpha.sendMessage(Component.text("你被选为母体！6秒后容器破裂，届时你可以行动。", NamedTextColor.LIGHT_PURPLE))

        Bukkit.getOnlinePlayers().forEach { player ->
            if (player != alpha) {
                setPlayerTeam(player, Team.HUMAN)
                player.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 20.0
                player.health = 20.0
                player.gameMode = GameMode.ADVENTURE
                plugin.miscManager.giveStarterKit(player)
            }
        }

        plugin.doorManager.getAllDoors()
            .filter { it.mode == Door.DoorMode.PLAYER }
            .forEach { plugin.doorManager.openDoorImmediatelyByName(it.name, broadcast = false) }

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            plugin.doorManager.getAllDoors()
                .filter { it.mode == Door.DoorMode.ZOMBIE }
                .forEach { plugin.doorManager.openDoorImmediatelyByName(it.name, broadcast = false) }
        }, 100L)

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            Bukkit.getOnlinePlayers().forEach { player ->
                player.showTitle(Title.title(Component.text("警告！", NamedTextColor.RED), Component.text("收容装置发生破裂！请尽全力逃出！", NamedTextColor.RED)))
            }
        }, 100L)

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (alpha.isOnline && getPlayerTeam(alpha) == Team.ZOMBIE_MAIN) {
                alpha.gameMode = GameMode.ADVENTURE
                alpha.showTitle(Title.title(
                    Component.text("容器破裂！", NamedTextColor.RED),
                    Component.text("现在你可以行动了！", NamedTextColor.GOLD),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(2), Duration.ofMillis(500))
                ))
                alpha.sendMessage(Component.text("容器破裂！现在你可以行动了！", NamedTextColor.RED))
                plugin.staminaManager.applyZombieEffects(alpha)
            }
        }, 120L)

        plugin.doorManager.getAllDoors()
            .filter { it.mode == Door.DoorMode.START }
            .forEach { plugin.doorManager.openDoorImmediately(it.doorNumber) }

        plugin.startEffectManager.executeStartEffects()

        startMaxDurationTimer()
    }

    private fun startMaxDurationTimer() {
        val maxDuration = plugin.configManager.getMaxDuration()
        maxDurationTaskId = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, Runnable {
            if (status == GameStatus.RUNNING) {
                plugin.logger.info("游戏时间已达上限，强制结束")
                Bukkit.broadcast(Component.text("§c游戏时间已达上限！"))
                endGame(Team.SPECTATOR)
            }
        }, (maxDuration * 20L).coerceAtLeast(1L))
    }

    fun setPlayerTeam(player: Player, team: Team) {
        val oldTeam = playerTeams[player]
        playerTeams[player] = team
        humans.remove(player)
        zombies.remove(player)
        zombieMains.remove(player)

        when (team) {
            Team.HUMAN -> humans.add(player)
            Team.ZOMBIE -> zombies.add(player)
            Team.ZOMBIE_MAIN -> zombieMains.add(player)
            else -> {}
        }

        if (status == GameStatus.RUNNING && oldTeam == Team.HUMAN && team != Team.HUMAN) {
            checkGameEnd()
        }
    }

    fun checkGameEnd() {
        if (status == GameStatus.RUNNING && humans.isEmpty()) {
            plugin.logger.info("人类数量为0，游戏结束")
            endGame(Team.ZOMBIE)
        }
    }

    fun endGame(winner: Team) {
        if (status != GameStatus.RUNNING) return
        status = GameStatus.ENDED

        plugin.doorManager.reset()

        val title = when (winner) {
            Team.HUMAN -> Component.text("人类胜利！", NamedTextColor.GREEN)
            Team.ZOMBIE, Team.ZOMBIE_MAIN -> Component.text("僵尸胜利！", NamedTextColor.RED)
            else -> Component.text("游戏结束", NamedTextColor.YELLOW)
        }

        val sound = if (winner == Team.HUMAN) Sound.ENTITY_ENDER_DRAGON_DEATH else Sound.ENTITY_WITHER_DEATH

        Bukkit.getOnlinePlayers().forEach {
            it.showTitle(Title.title(title, Component.text("")))
            it.playSound(it.location, sound, 1f, 1f)
            it.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 20.0
            it.health = 20.0
            it.gameMode = GameMode.SPECTATOR
            it.inventory.clear()
            it.clearActivePotionEffects()
        }

        sendGameEndResult()

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            Bukkit.getOnlinePlayers().forEach { player ->
                plugin.respawnManager.teleportToWaitRespawn(player)
                player.gameMode = GameMode.ADVENTURE
                setPlayerRoom(player, 0)
                setPlayerTeam(player, Team.HUMAN)
            }
            status = GameStatus.WAITING
        }, 80L)
    }

    private fun sendGameEndResult() {
        val onlinePlayers = Bukkit.getOnlinePlayers()
        if (onlinePlayers.isEmpty()) return

        val kills = plugin.miscManager.getAllKills()
        val infections = plugin.miscManager.getAllInfections()

        val killList = kills.entries.sortedByDescending { it.value }.take(3)
        val infectList = infections.entries.sortedByDescending { it.value }.take(3)

        fun formatEntry(entry: Map.Entry<Player, Int>?) = entry?.let { "${it.key.name} ${it.value}" } ?: "none"

        val killFirst = formatEntry(killList.getOrNull(0))
        val killSecond = formatEntry(killList.getOrNull(1))
        val killThird = formatEntry(killList.getOrNull(2))

        val infectFirst = formatEntry(infectList.getOrNull(0))
        val infectSecond = formatEntry(infectList.getOrNull(1))
        val infectThird = formatEntry(infectList.getOrNull(2))

        val line1 = Component.text("==========================================", NamedTextColor.GREEN)
        Bukkit.broadcast(line1)

        val line2 = Component.text()
            .append(Component.text("                           ", NamedTextColor.YELLOW))
            .append(Component.text("游戏结算", NamedTextColor.YELLOW, TextDecoration.BOLD))
            .build()
        Bukkit.broadcast(line2)

        Bukkit.broadcast(Component.empty())

        val line4 = Component.text()
            .append(Component.text("  ", NamedTextColor.GRAY))
            .append(Component.text("击杀 ", NamedTextColor.AQUA))
            .append(Component.text("第 1 名", NamedTextColor.YELLOW))
            .append(Component.text(" - ", NamedTextColor.GRAY))
            .append(Component.text("[", NamedTextColor.GRAY))
            .append(Component.text(killFirst, NamedTextColor.WHITE))
            .append(Component.text("] ", NamedTextColor.GRAY))
            .append(Component.text("+ 200 硬币!", NamedTextColor.GOLD))
            .build()
        Bukkit.broadcast(line4)

        val line5 = Component.text()
            .append(Component.text("  ", NamedTextColor.GRAY))
            .append(Component.text("击杀 ", NamedTextColor.AQUA))
            .append(Component.text("第 2 名", NamedTextColor.WHITE))
            .append(Component.text(" - ", NamedTextColor.GRAY))
            .append(Component.text("[", NamedTextColor.GRAY))
            .append(Component.text(killSecond, NamedTextColor.WHITE))
            .append(Component.text("] ", NamedTextColor.GRAY))
            .append(Component.text("+ 150 硬币!", NamedTextColor.GOLD))
            .build()
        Bukkit.broadcast(line5)

        val line6 = Component.text()
            .append(Component.text("  ", NamedTextColor.GRAY))
            .append(Component.text("击杀 ", NamedTextColor.AQUA))
            .append(Component.text("第 3 名", NamedTextColor.GOLD))
            .append(Component.text(" - ", NamedTextColor.GRAY))
            .append(Component.text("[", NamedTextColor.GRAY))
            .append(Component.text(killThird, NamedTextColor.WHITE))
            .append(Component.text("] ", NamedTextColor.GRAY))
            .append(Component.text("+ 100 硬币!", NamedTextColor.GOLD))
            .build()
        Bukkit.broadcast(line6)

        Bukkit.broadcast(Component.empty())

        val line8 = Component.text()
            .append(Component.text("  ", NamedTextColor.GRAY))
            .append(Component.text("感染 ", NamedTextColor.DARK_GREEN))
            .append(Component.text("第 1 名", NamedTextColor.YELLOW))
            .append(Component.text(" - ", NamedTextColor.GRAY))
            .append(Component.text("[", NamedTextColor.GRAY))
            .append(Component.text(infectFirst, NamedTextColor.WHITE))
            .append(Component.text("] ", NamedTextColor.GRAY))
            .append(Component.text("+ 200 硬币!", NamedTextColor.GOLD))
            .build()
        Bukkit.broadcast(line8)

        val line9 = Component.text()
            .append(Component.text("  ", NamedTextColor.GRAY))
            .append(Component.text("感染 ", NamedTextColor.DARK_GREEN))
            .append(Component.text("第 2 名", NamedTextColor.WHITE))
            .append(Component.text(" - ", NamedTextColor.GRAY))
            .append(Component.text("[", NamedTextColor.GRAY))
            .append(Component.text(infectSecond, NamedTextColor.WHITE))
            .append(Component.text("] ", NamedTextColor.GRAY))
            .append(Component.text("+ 150 硬币!", NamedTextColor.GOLD))
            .build()
        Bukkit.broadcast(line9)

        val line10 = Component.text()
            .append(Component.text("  ", NamedTextColor.GRAY))
            .append(Component.text("感染 ", NamedTextColor.DARK_GREEN))
            .append(Component.text("第 3 名", NamedTextColor.GOLD))
            .append(Component.text(" - ", NamedTextColor.GRAY))
            .append(Component.text("[", NamedTextColor.GRAY))
            .append(Component.text(infectThird, NamedTextColor.WHITE))
            .append(Component.text("] ", NamedTextColor.GRAY))
            .append(Component.text("+ 100 硬币!", NamedTextColor.GOLD))
            .build()
        Bukkit.broadcast(line10)

        Bukkit.broadcast(Component.empty())
        Bukkit.broadcast(line1)

        val killRewards = mapOf(0 to 200, 1 to 150, 2 to 100)
        killList.forEachIndexed { index, (player, _) ->
            val reward = killRewards[index] ?: 0
            plugin.coinManager.addCoins(player.uniqueId, reward)
            player.sendMessage("§6+ $reward 硬币! (击杀第 ${index+1} 名)")
        }

        val infectRewards = mapOf(0 to 200, 1 to 150, 2 to 100)
        infectList.forEachIndexed { index, (player, _) ->
            val reward = infectRewards[index] ?: 0
            plugin.coinManager.addCoins(player.uniqueId, reward)
            player.sendMessage("§6+ $reward 硬币! (感染第 ${index+1} 名)")
        }
    }

    fun getPlayerTeam(player: Player?) = playerTeams.getOrDefault(player, Team.SPECTATOR)
    fun getPlayerRoom(player: Player) = playerRooms.getOrDefault(player, 0)
    fun setPlayerRoom(player: Player, room: Int) { playerRooms[player] = room }
    fun getGameStartTime(): Long = gameStartTime
    fun getHumans(): List<Player> = humans
    fun getZombies(): List<Player> = zombies
    fun getZombieMains(): List<Player> = zombieMains
    fun getGameStatus() = status

    fun clear() {
        playerTeams.clear()
        playerRooms.clear()
        countdownTask?.cancel()
        cancelWaitStartTask()
        if (autoCheckTaskId != -1) {
            Bukkit.getScheduler().cancelTask(autoCheckTaskId)
            autoCheckTaskId = -1
        }
        if (maxDurationTaskId != -1) {
            Bukkit.getScheduler().cancelTask(maxDurationTaskId)
            maxDurationTaskId = -1
        }
    }

    private fun startAutoCheckTask() {
        autoCheckTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, {
            checkAutoStartCondition()
        }, 0L, 20L)
    }

    private fun checkAutoStartCondition() {
        if (status != GameStatus.WAITING && status != GameStatus.STARTING) return

        val onlineCount = Bukkit.getOnlinePlayers().size
        val minPlayers = plugin.configManager.getMinPlayers()

        when (status) {
            GameStatus.WAITING -> {
                if (onlineCount >= minPlayers) {
                    if (waitStartTask == null) {
                        waitStartCountdown = plugin.configManager.getStartDelay()
                        waitStartTask = WaitStartCountdownTask(plugin, this, waitStartCountdown)
                        waitStartTask!!.runTaskTimer(plugin, 0L, 20L)
                    }
                } else {
                    cancelWaitStartTask()
                }
            }
            GameStatus.STARTING -> {
                if (onlineCount < minPlayers) {
                    cancelCountdownTask()
                    setGameStatus(GameStatus.WAITING)
                    Bukkit.broadcast(Component.text("§c人数不足，游戏取消"))
                }
            }

            else -> {}
        }
    }

    fun cancelWaitStartTask() {
        waitStartTask?.cancel()
        waitStartTask = null
    }

    fun cancelCountdownTask() {
        countdownTask?.cancel()
        countdownTask = null
        isCountdownActive = false
    }
}
