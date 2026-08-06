package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.Door
import cn.oneachina.zombieRun.task.StartCountdownTask
import cn.oneachina.zombieRun.task.WaitStartCountdownTask
import cn.oneachina.zombieRun.util.DebugLogger
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
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
    var countdownTask: ScheduledTask? = null
    var startCountdownTaskInstance: StartCountdownTask? = null

    private var waitStartTask: ScheduledTask? = null
    private var autoCheckTask: ScheduledTask? = null
    private var maxDurationTask: ScheduledTask? = null

    init {
        startAutoCheckTask()
    }

    fun addPlayer(player: Player) {
        playerTeams[player] = Team.HUMAN
        humans.add(player)
        playerRooms[player] = 0
    }

    fun removePlayer(player: Player) {
        playerTeams.remove(player)
        playerRooms.remove(player)
        humans.remove(player)
        zombies.remove(player)
        zombieMains.remove(player)

        if (status == GameStatus.WAITING || status == GameStatus.STARTING) {
            checkAutoStartCondition()
        }
        checkGameEnd()
    }

    fun setGameStatus(newStatus: GameStatus) {
        val oldStatus = this.status
        this.status = newStatus
        DebugLogger.game("游戏状态 $oldStatus → $newStatus")
    }

    fun forceStartGame() {
        if (status != GameStatus.WAITING && status != GameStatus.ENDED) return
        if (Bukkit.getOnlinePlayers().isEmpty() && !plugin.debugMode) {
            plugin.logger.warning("尝试开始游戏但没有玩家在线")
            return
        }
        cancelWaitStartTask()
        setGameStatus(GameStatus.STARTING)
        startCountdownTaskInstance = StartCountdownTask(plugin, this)
        countdownTask = startCountdownTaskInstance!!.start()
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
        plugin.healthManager.initPlayerHealth(alpha, Team.ZOMBIE_MAIN)

        alpha.sendMessage(Component.text("你被选为母体！6秒后容器破裂，届时你可以行动。", NamedTextColor.LIGHT_PURPLE))

        Bukkit.getOnlinePlayers().forEach { player ->
            if (player != alpha) {
                setPlayerTeam(player, Team.HUMAN)
                plugin.healthManager.initPlayerHealth(player, Team.HUMAN)
                player.gameMode = GameMode.ADVENTURE
                plugin.miscManager.giveStarterKit(player)
            }
        }

        plugin.doorManager.getAllDoors()
            .filter { it.mode == Door.DoorMode.PLAYER }
            .forEach { plugin.doorManager.openDoorImmediatelyByName(it.name, broadcast = false) }

        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
            plugin.doorManager.getAllDoors()
                .filter { it.mode == Door.DoorMode.ZOMBIE }
                .forEach { plugin.doorManager.openDoorImmediatelyByName(it.name, broadcast = false) }
        }, 100L)

        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
            Bukkit.getOnlinePlayers().forEach { player ->
                player.showTitle(Title.title(Component.text("警告！", NamedTextColor.RED), Component.text("收容装置发生破裂！请尽全力逃出！", NamedTextColor.RED)))
            }
        }, 100L)

        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
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

        Bukkit.getOnlinePlayers().forEach { player ->
            plugin.questManager.ensureQuests(player)
        }

        startMaxDurationTimer()
    }

    private fun startMaxDurationTimer() {
        val maxDuration = plugin.configManager.getMaxDuration()
        maxDurationTask = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
            if (status == GameStatus.RUNNING) {
                plugin.logger.info("游戏时间已达上限，强制结束")
                Bukkit.broadcast(Component.text("游戏时间已达上限！", NamedTextColor.RED))
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

        // 切换队伍时重置自定义生命值
        if (team == Team.ZOMBIE || team == Team.ZOMBIE_MAIN || team == Team.HUMAN) {
            plugin.healthManager.initPlayerHealth(player, team)
        }

        if (status == GameStatus.RUNNING && oldTeam == Team.HUMAN && team != Team.HUMAN) {
            checkGameEnd()
        }
    }

    fun checkGameEnd() {
        if (plugin.debugMode) return
        if (status == GameStatus.RUNNING && humans.isEmpty()) {
            plugin.logger.info("人类数量为0，游戏结束")
            endGame(Team.ZOMBIE)
        }
    }

    fun endGame(winner: Team) {
        if (status != GameStatus.RUNNING) return
        status = GameStatus.ENDED

        alphaZombie = null
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
            it.gameMode = GameMode.SPECTATOR
            it.inventory.clear()
            it.clearActivePotionEffects()
        }

        plugin.healthManager.clearAll()

        sendGameEndResult()

        plugin.progressionListener.onGameEnd()
        if (winner == Team.HUMAN) {
            plugin.progressionListener.onHumanWin()
        }

        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
            Bukkit.getOnlinePlayers().forEach { player ->
                plugin.nametagManager.clear(player)
                player.displayName(null)
                plugin.respawnManager.teleportToWaitRespawn(player)
                player.gameMode = GameMode.ADVENTURE
                player.clearActivePotionEffects()
                setPlayerRoom(player, 0)
                setPlayerTeam(player, Team.SPECTATOR)
            }
            plugin.healthManager.clearAll()
            status = GameStatus.WAITING
        }, 80L)
    }

    private fun sendGameEndResult() {
        val onlinePlayers = Bukkit.getOnlinePlayers()
        if (onlinePlayers.isEmpty()) return

        val kills = plugin.miscManager.getAllKills()
        val infections = plugin.miscManager.getAllInfections()

        val separator = Component.text("==========================================", NamedTextColor.GREEN)
        val header = Component.text()
            .append(Component.text("                           ", NamedTextColor.YELLOW))
            .append(Component.text("游戏结算", NamedTextColor.YELLOW, TextDecoration.BOLD))
            .build()

        Bukkit.broadcast(separator)
        Bukkit.broadcast(header)
        Bukkit.broadcast(Component.empty())

        val killRanking = formatRanking(kills, "击杀", NamedTextColor.AQUA)
        killRanking.forEach { Bukkit.broadcast(it) }
        Bukkit.broadcast(Component.empty())

        val infectRanking = formatRanking(infections, "感染", NamedTextColor.DARK_GREEN)
        infectRanking.forEach { Bukkit.broadcast(it) }
        Bukkit.broadcast(Component.empty())
        Bukkit.broadcast(separator)

        val rewards = rankRewards()
        awardRankingRewards(kills, rewards, "击杀")
        awardRankingRewards(infections, rewards, "感染")
    }

    private fun formatRanking(
        entries: Map<Player, Int>,
        label: String,
        labelColor: NamedTextColor
    ): List<Component> {
        val topThree = entries.entries.sortedByDescending { it.value }.take(3)
        val medals = listOf(
            Pair(NamedTextColor.YELLOW, "第 1 名"),
            Pair(NamedTextColor.WHITE, "第 2 名"),
            Pair(NamedTextColor.GOLD, "第 3 名")
        )
        return topThree.mapIndexed { index, (player, value) ->
            val (medalColor, medalText) = medals[index]
            Component.text()
                .append(Component.text("  ", NamedTextColor.GRAY))
                .append(Component.text(label, labelColor))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(Component.text(medalText, medalColor))
                .append(Component.text(" - ", NamedTextColor.GRAY))
                .append(Component.text("[${player.name} $value]", NamedTextColor.WHITE))
                .append(Component.text(" + ${rankRewards()[index]} 硬币!", NamedTextColor.GOLD))
                .build()
        }
    }

    private fun awardRankingRewards(entries: Map<Player, Int>, rewards: List<Int>, label: String) {
        val topThree = entries.entries.sortedByDescending { it.value }.take(3)
        topThree.forEachIndexed { index, (player, _) ->
            val reward = rewards.getOrNull(index) ?: 0
            plugin.coinManager.addCoins(player.uniqueId, reward)
            player.sendMessage(Component.text("+ $reward 硬币! ($label 第 ${index + 1} 名)", NamedTextColor.GOLD))
        }
    }

    private fun rankRewards(): List<Int> = plugin.economyConfig.rankRewardCoins

    fun getPlayerTeam(player: Player?) = playerTeams.getOrDefault(player, Team.SPECTATOR)
    fun getPlayerRoom(player: Player) = playerRooms.getOrDefault(player, 0)

    /** 人类推进进度 = 人类队伍中已通过的最高门/房间号；无人类时返回 0 */
    fun getHumanProgress(): Int = humans.asSequence()
        .mapNotNull { playerRooms[it] }
        .maxOrNull() ?: 0

    fun setPlayerRoom(player: Player, room: Int) {
        val oldRoom = playerRooms[player]
        playerRooms[player] = room
        if (oldRoom != room) {
            DebugLogger.room("${player.name} 区域 $oldRoom → $room")
        }
    }
    fun getGameStartTime(): Long = gameStartTime
    fun getHumans(): List<Player> = humans
    fun getZombies(): List<Player> = zombies
    fun getZombieMains(): List<Player> = zombieMains
    fun getGameStatus() = status

    fun clear() {
        playerTeams.clear()
        playerRooms.clear()
        countdownTask?.cancel()
        countdownTask = null
        startCountdownTaskInstance = null
        cancelWaitStartTask()
        autoCheckTask?.cancel()
        autoCheckTask = null
        maxDurationTask?.cancel()
        maxDurationTask = null
    }

    private fun startAutoCheckTask() {
        if (!plugin.isEnabled) return
        autoCheckTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { _ ->
            checkAutoStartCondition()
        }, 1L, 20L)
    }

    private fun checkAutoStartCondition() {
        if (status != GameStatus.WAITING && status != GameStatus.STARTING) return

        if (plugin.debugMode) return

        val onlineCount = Bukkit.getOnlinePlayers().size
        val minPlayers = plugin.configManager.getMinPlayers()

        when (status) {
            GameStatus.WAITING -> {
                if (onlineCount >= minPlayers) {
                    if (waitStartTask == null) {
                        waitStartCountdown = plugin.configManager.getStartDelay()
                        waitStartTask = WaitStartCountdownTask(plugin, this, waitStartCountdown).start()
                    }
                } else {
                    cancelWaitStartTask()
                }
            }
            GameStatus.STARTING -> {
                if (onlineCount < minPlayers) {
                    cancelCountdownTask()
                    setGameStatus(GameStatus.WAITING)
                    Bukkit.broadcast(Component.text("人数不足，游戏取消", NamedTextColor.RED))
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
        startCountdownTaskInstance = null
        isCountdownActive = false
    }
}

