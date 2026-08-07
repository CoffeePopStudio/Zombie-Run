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
import org.bukkit.entity.Player
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 多世界游戏状态机：每个世界独立维护一局游戏（GameInstance）。
 * 玩家级状态（队伍/房间）按玩家索引，玩家属于其所在世界的实例。
 */
class GameManager(private val plugin: ZombieRun) {

    enum class GameStatus { WAITING, STARTING, RUNNING, ENDED }
    enum class Team { HUMAN, ZOMBIE, ZOMBIE_MAIN, SPECTATOR }

    /** 一局游戏的全部状态，按世界隔离 */
    class GameInstance(val worldName: String) {
        var status = GameStatus.WAITING
        val playerTeams = ConcurrentHashMap<Player, Team>()
        val playerRooms = ConcurrentHashMap<Player, Int>()

        var gameStartTime: Long = 0
        val humans = CopyOnWriteArrayList<Player>()
        val zombies = CopyOnWriteArrayList<Player>()
        val zombieMains = CopyOnWriteArrayList<Player>()

        var waitStartCountdown: Int = 0
        var alphaZombie: Player? = null
        var isCountdownActive = false
        var countdownTask: ScheduledTask? = null
        var startCountdownTaskInstance: StartCountdownTask? = null
        var waitStartTask: ScheduledTask? = null
        var maxDurationTask: ScheduledTask? = null
    }

    private val games = ConcurrentHashMap<String, GameInstance>()

    init {
        startAutoCheckTask()
    }

    // ==================== 实例访问 ====================

    /** 获取指定世界的游戏实例（不存在则创建） */
    fun getGame(world: String): GameInstance {
        return games.computeIfAbsent(world) { GameInstance(it) }
    }

    fun gameOf(player: Player): GameInstance = getGame(player.world.name)

    fun getGameIfExists(world: String): GameInstance? = games[world]

    fun getAllGames(): Collection<GameInstance> = games.values

    /** 指定世界内在线玩家 */
    fun getWorldPlayers(world: String): List<Player> {
        return Bukkit.getOnlinePlayers().filter { it.world.name == world }
    }

    /** 是否配置为游戏世界（有门或重生点数据） */
    fun isGameWorld(world: String): Boolean {
        return plugin.doorManager.hasDoorsInWorld(world) ||
            plugin.respawnManager.getAllRespawns().any { it.world == world }
    }

    fun getGameStatus(world: String): GameStatus = getGame(world).status

    fun getGameStatus(player: Player): GameStatus = getGame(player.world.name).status

    // ==================== 玩家加入/离开 ====================

    fun addPlayer(player: Player) {
        val game = gameOf(player)
        game.playerTeams[player] = Team.HUMAN
        game.humans.add(player)
        game.playerRooms[player] = 0
    }

    fun removePlayer(player: Player) {
        val game = gameOf(player)
        game.playerTeams.remove(player)
        game.playerRooms.remove(player)
        game.humans.remove(player)
        game.zombies.remove(player)
        game.zombieMains.remove(player)

        if (game.status == GameStatus.WAITING || game.status == GameStatus.STARTING) {
            checkAutoStartCondition(game)
        }
        checkGameEnd(game)
    }

    fun setGameStatus(game: GameInstance, newStatus: GameStatus) {
        val oldStatus = game.status
        game.status = newStatus
        DebugLogger.game("[${game.worldName}] 游戏状态 $oldStatus → $newStatus")
    }

    fun forceStartGame(world: String) {
        val game = getGame(world)
        if (game.status != GameStatus.WAITING && game.status != GameStatus.ENDED) return
        if (getWorldPlayers(world).isEmpty() && !plugin.debugMode) {
            plugin.logger.warning("[${world}] 尝试开始游戏但没有玩家在线")
            return
        }
        cancelWaitStartTask(game)
        setGameStatus(game, GameStatus.STARTING)
        game.startCountdownTaskInstance = StartCountdownTask(plugin, this, game)
        game.countdownTask = game.startCountdownTaskInstance!!.start()
    }

    fun selectAlphaZombie(game: GameInstance): Player {
        val online = getWorldPlayers(game.worldName)
        return if (online.isEmpty()) throw IllegalStateException("No players online in world ${game.worldName}") else online.random()
    }

    fun beginGame(game: GameInstance) {
        if (game.status != GameStatus.STARTING) return
        setGameStatus(game, GameStatus.RUNNING)
        game.gameStartTime = System.currentTimeMillis()
        val worldName = game.worldName

        plugin.doorManager.reset(worldName)

        val alpha = game.alphaZombie ?: selectAlphaZombie(game)
        setPlayerTeam(game, alpha, Team.ZOMBIE_MAIN)
        plugin.healthManager.initPlayerHealth(alpha, Team.ZOMBIE_MAIN)

        alpha.sendMessage(Component.text("你被选为母体！6秒后容器破裂，届时你可以行动。", NamedTextColor.LIGHT_PURPLE))

        getWorldPlayers(worldName).forEach { player ->
            if (player != alpha) {
                setPlayerTeam(game, player, Team.HUMAN)
                plugin.healthManager.initPlayerHealth(player, Team.HUMAN)
                player.gameMode = GameMode.ADVENTURE
                plugin.miscManager.giveStarterKit(player)
            }
        }

        plugin.doorManager.getDoorsInWorld(worldName)
            .filter { it.mode == Door.DoorMode.PLAYER }
            .forEach { plugin.doorManager.openDoorImmediatelyByName(it.name, broadcast = false) }

        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
            plugin.doorManager.getDoorsInWorld(worldName)
                .filter { it.mode == Door.DoorMode.ZOMBIE }
                .forEach { plugin.doorManager.openDoorImmediatelyByName(it.name, broadcast = false) }
        }, 100L)

        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
            getWorldPlayers(worldName).forEach { player ->
                player.showTitle(Title.title(Component.text("警告！", NamedTextColor.RED), Component.text("收容装置发生破裂！请尽全力逃出！", NamedTextColor.RED)))
            }
        }, 100L)

        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
            if (alpha.isOnline && getPlayerTeam(game, alpha) == Team.ZOMBIE_MAIN) {
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

        plugin.doorManager.getDoorsInWorld(worldName)
            .filter { it.mode == Door.DoorMode.START }
            .forEach { plugin.doorManager.openDoorImmediately(it.doorNumber, worldName) }

        plugin.startEffectManager.executeStartEffects(worldName)

        getWorldPlayers(worldName).forEach { player ->
            plugin.questManager.ensureQuests(player)
        }

        startMaxDurationTimer(game)
    }

    private fun startMaxDurationTimer(game: GameInstance) {
        val maxDuration = plugin.configManager.getMaxDuration()
        game.maxDurationTask = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
            if (game.status == GameStatus.RUNNING) {
                plugin.logger.info("[${game.worldName}] 游戏时间已达上限，强制结束")
                getWorldPlayers(game.worldName).forEach { it.sendMessage(Component.text("游戏时间已达上限！", NamedTextColor.RED)) }
                endGame(game, Team.SPECTATOR)
            }
        }, (maxDuration * 20L).coerceAtLeast(1L))
    }

    fun setPlayerTeam(game: GameInstance, player: Player, team: Team) {
        val oldTeam = game.playerTeams[player]
        game.playerTeams[player] = team
        game.humans.remove(player)
        game.zombies.remove(player)
        game.zombieMains.remove(player)

        when (team) {
            Team.HUMAN -> game.humans.add(player)
            Team.ZOMBIE -> game.zombies.add(player)
            Team.ZOMBIE_MAIN -> game.zombieMains.add(player)
            else -> {}
        }

        // 切换队伍时重置自定义生命值
        if (team == Team.ZOMBIE || team == Team.ZOMBIE_MAIN || team == Team.HUMAN) {
            plugin.healthManager.initPlayerHealth(player, team)
        }

        if (game.status == GameStatus.RUNNING && oldTeam == Team.HUMAN && team != Team.HUMAN) {
            checkGameEnd(game)
        }
    }

    fun setPlayerTeam(player: Player, team: Team) {
        setPlayerTeam(gameOf(player), player, team)
    }

    fun checkGameEnd(game: GameInstance) {
        if (plugin.debugMode) return
        if (game.status == GameStatus.RUNNING && game.humans.isEmpty()) {
            plugin.logger.info("[${game.worldName}] 人类数量为0，游戏结束")
            endGame(game, Team.ZOMBIE)
        }
    }

    fun endGame(game: GameInstance, winner: Team) {
        if (game.status != GameStatus.RUNNING) return
        game.status = GameStatus.ENDED
        val worldName = game.worldName

        game.alphaZombie = null
        plugin.doorManager.reset(worldName)

        val title = when (winner) {
            Team.HUMAN -> Component.text("人类胜利！", NamedTextColor.GREEN)
            Team.ZOMBIE, Team.ZOMBIE_MAIN -> Component.text("僵尸胜利！", NamedTextColor.RED)
            else -> Component.text("游戏结束", NamedTextColor.YELLOW)
        }

        val sound = if (winner == Team.HUMAN) Sound.ENTITY_ENDER_DRAGON_DEATH else Sound.ENTITY_WITHER_DEATH

        getWorldPlayers(worldName).forEach {
            it.showTitle(Title.title(title, Component.text("")))
            it.playSound(it.location, sound, 1f, 1f)
            it.gameMode = GameMode.SPECTATOR
            it.inventory.clear()
            it.clearActivePotionEffects()
        }

        plugin.healthManager.clearAll()

        sendGameEndResult(game)

        plugin.progressionListener.onGameEnd(worldName)
        if (winner == Team.HUMAN) {
            plugin.progressionListener.onHumanWin(worldName)
        }

        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
            getWorldPlayers(worldName).forEach { player ->
                plugin.nametagManager.clear(player)
                player.displayName(null)
                plugin.respawnManager.teleportToWaitRespawn(player)
                player.gameMode = GameMode.ADVENTURE
                player.clearActivePotionEffects()
                setPlayerRoom(player, 0)
                setPlayerTeam(game, player, Team.SPECTATOR)
            }
            plugin.healthManager.clearAll()
            game.status = GameStatus.WAITING
        }, 80L)
    }

    private fun sendGameEndResult(game: GameInstance) {
        val onlinePlayers = getWorldPlayers(game.worldName)
        if (onlinePlayers.isEmpty()) return

        val kills = plugin.miscManager.getAllKills(game.worldName)
        val infections = plugin.miscManager.getAllInfections(game.worldName)

        val separator = Component.text("==========================================", NamedTextColor.GREEN)
        val header = Component.text()
            .append(Component.text("                           ", NamedTextColor.YELLOW))
            .append(Component.text("游戏结算", NamedTextColor.YELLOW, TextDecoration.BOLD))
            .build()

        onlinePlayers.forEach { p ->
            p.sendMessage(separator)
            p.sendMessage(header)
            p.sendMessage(Component.empty())
        }

        val killRanking = formatRanking(kills, "击杀", NamedTextColor.AQUA)
        killRanking.forEach { comp -> onlinePlayers.forEach { it.sendMessage(comp) } }
        onlinePlayers.forEach { it.sendMessage(Component.empty()) }

        val infectRanking = formatRanking(infections, "感染", NamedTextColor.DARK_GREEN)
        infectRanking.forEach { comp -> onlinePlayers.forEach { it.sendMessage(comp) } }
        onlinePlayers.forEach { it.sendMessage(Component.empty()) }
        onlinePlayers.forEach { it.sendMessage(separator) }

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

    // ==================== 玩家查询（按玩家自动路由到所在世界实例） ====================

    fun getPlayerTeam(player: Player?): Team {
        if (player == null) return Team.SPECTATOR
        return gameOf(player).playerTeams.getOrDefault(player, Team.SPECTATOR)
    }

    fun getPlayerTeam(game: GameInstance, player: Player): Team {
        return game.playerTeams.getOrDefault(player, Team.SPECTATOR)
    }

    fun getPlayerRoom(player: Player) = gameOf(player).playerRooms.getOrDefault(player, 0)

    /** 人类推进进度 = 该世界人类队伍中已通过的最高门/房间号；无人类时返回 0 */
    fun getHumanProgress(world: String): Int = getGame(world).humans.asSequence()
        .mapNotNull { getGame(world).playerRooms[it] }
        .maxOrNull() ?: 0

    fun getHumanProgress(player: Player): Int = getHumanProgress(player.world.name)

    fun setPlayerRoom(player: Player, room: Int) {
        val game = gameOf(player)
        val oldRoom = game.playerRooms[player]
        game.playerRooms[player] = room
        if (oldRoom != room) {
            DebugLogger.room("[${game.worldName}] ${player.name} 区域 $oldRoom → $room")
        }
    }

    fun getGameStartTime(world: String): Long = getGame(world).gameStartTime
    fun getHumans(world: String): List<Player> = getGame(world).humans
    fun getZombies(world: String): List<Player> = getGame(world).zombies
    fun getZombieMains(world: String): List<Player> = getGame(world).zombieMains
    fun getAlphaZombie(world: String): Player? = getGame(world).alphaZombie

    fun clear() {
        games.values.forEach { game ->
            game.countdownTask?.cancel()
            game.startCountdownTaskInstance = null
            game.waitStartTask?.cancel()
            game.maxDurationTask?.cancel()
            game.playerTeams.clear()
            game.playerRooms.clear()
        }
        games.clear()
        autoCheckTask?.cancel()
        autoCheckTask = null
    }

    // ==================== 自动开局 ====================

    private var autoCheckTask: ScheduledTask? = null

    private fun startAutoCheckTask() {
        if (!plugin.isEnabled) return
        autoCheckTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { _ ->
            checkAutoStartAllWorlds()
        }, 1L, 20L)
    }

    private fun checkAutoStartAllWorlds() {
        if (plugin.debugMode) return

        val candidateWorlds = mutableSetOf<String>()
        Bukkit.getOnlinePlayers().forEach { candidateWorlds.add(it.world.name) }
        plugin.respawnManager.getAllRespawns().forEach { candidateWorlds.add(it.world) }
        plugin.doorManager.getAllDoors().forEach { candidateWorlds.add(it.world) }

        candidateWorlds.filter { isGameWorld(it) }.forEach { world ->
            checkAutoStartCondition(getGame(world))
        }
    }

    private fun checkAutoStartCondition(game: GameInstance) {
        if (game.status != GameStatus.WAITING && game.status != GameStatus.STARTING) return

        if (plugin.debugMode) return

        val onlineCount = getWorldPlayers(game.worldName).size
        val minPlayers = plugin.configManager.getMinPlayers()

        when (game.status) {
            GameStatus.WAITING -> {
                if (onlineCount >= minPlayers) {
                    if (game.waitStartTask == null) {
                        game.waitStartCountdown = plugin.configManager.getStartDelay()
                        game.waitStartTask = WaitStartCountdownTask(plugin, this, game, game.waitStartCountdown).start()
                    }
                } else {
                    cancelWaitStartTask(game)
                }
            }
            GameStatus.STARTING -> {
                if (onlineCount < minPlayers) {
                    cancelCountdownTask(game)
                    setGameStatus(game, GameStatus.WAITING)
                    getWorldPlayers(game.worldName).forEach { it.sendMessage(Component.text("人数不足，游戏取消", NamedTextColor.RED)) }
                }
            }

            else -> {}
        }
    }

    fun cancelWaitStartTask(game: GameInstance) {
        game.waitStartTask?.cancel()
        game.waitStartTask = null
    }

    fun cancelCountdownTask(game: GameInstance) {
        game.countdownTask?.cancel()
        game.countdownTask = null
        game.startCountdownTaskInstance = null
        game.isCountdownActive = false
    }
}
