package cn.oneachina.zombierun.v2.application.game

import cn.oneachina.zombierun.v2.application.event.ApplicationEventBus
import cn.oneachina.zombierun.v2.application.event.GameEndedEvent
import cn.oneachina.zombierun.v2.application.event.GameStartedEvent
import cn.oneachina.zombierun.v2.application.event.PlayerPassedDoorEvent
import cn.oneachina.zombierun.v2.application.event.ZombieKilledEvent
import cn.oneachina.zombierun.v2.application.player.PlayerDataService
import cn.oneachina.zombierun.v2.application.weapon.WeaponService
import cn.oneachina.zombierun.v2.domain.arena.RespawnDefinition
import cn.oneachina.zombierun.v2.domain.arena.RespawnType
import cn.oneachina.zombierun.v2.domain.game.GameInstance
import cn.oneachina.zombierun.v2.domain.game.GamePhase
import cn.oneachina.zombierun.v2.domain.game.GameRules
import cn.oneachina.zombierun.v2.domain.game.GameTeam
import cn.oneachina.zombierun.v2.domain.game.MapFlowAdvanceResult
import cn.oneachina.zombierun.v2.domain.game.MapFlowDefinition
import cn.oneachina.zombierun.v2.domain.game.MapFlowPhase
import cn.oneachina.zombierun.v2.domain.game.MapFlowStateMachine
import cn.oneachina.zombierun.v2.infrastructure.config.ArenaYamlRepository
import cn.oneachina.zombierun.v2.infrastructure.config.V2Settings
import cn.oneachina.zombierun.v2.ports.GameContextPort
import cn.oneachina.zombierun.v2.ports.PlayerMessagePort
import cn.oneachina.zombierun.v2.ports.SchedulerPort
import cn.oneachina.zombierun.v2.ports.TaskHandle
import cn.oneachina.zombierun.v2.ports.TeleporterPort
import cn.oneachina.zombierun.v2.ports.WorldAccessPort
import cn.oneachina.zombierun.v2.support.TaskRegistry
import cn.oneachina.zombierun.v2.support.V2Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 对局流程编排：自动开局倒计时、队伍分配、母体、感染切换、结束条件。
 * 通过 [GameContextPort] 供门系统查询队伍与推进房间。
 */
class GameFlowService(
    private val settings: V2Settings,
    private val arenaRepository: ArenaYamlRepository,
    private val worldAccess: WorldAccessPort,
    private val scheduler: SchedulerPort,
    private val taskRegistry: TaskRegistry,
    private val messages: PlayerMessagePort,
    private val teleporter: TeleporterPort,
    private val logger: V2Logger,
    private val eventBus: ApplicationEventBus,
    private val playerData: PlayerDataService? = null,
    private val weaponService: WeaponService? = null,
) : GameContextPort {

    private class Countdown(
        @Volatile var remainingSeconds: Int,
        @Volatile var task: TaskHandle? = null,
    )

    private class EscapeCountdown(
        @Volatile var remainingSeconds: Int,
        @Volatile var task: TaskHandle? = null,
    )

    private val games = ConcurrentHashMap<String, GameInstance>()
    private val mapFlows = ConcurrentHashMap<String, MapFlowStateMachine>()
    private val countdowns = ConcurrentHashMap<String, Countdown>()
    private val escapeCountdowns = ConcurrentHashMap<String, EscapeCountdown>()
    private val maxDurationTasks = ConcurrentHashMap<String, TaskHandle>()
    private val autoResetTasks = ConcurrentHashMap<String, TaskHandle>()
    private val zombieKills = ConcurrentHashMap<UUID, Int>()
    private var autoTickTask: TaskHandle? = null

    init {
        eventBus.subscribe(PlayerPassedDoorEvent::class.java) { event ->
            onDoorPassed(event)
        }
    }

    fun start() {
        stopAutoTick()
        val handle = scheduler.globalTimer(20L, 20L) { autoTick() }
        autoTickTask = handle
        taskRegistry.register(handle)
    }

    fun stop() {
        stopAutoTick()
        countdowns.values.forEach { it.task?.cancel() }
        countdowns.clear()
        cancelEscapeCountdowns()
        maxDurationTasks.values.forEach { it.cancel() }
        maxDurationTasks.clear()
        autoResetTasks.values.forEach { it.cancel() }
        autoResetTasks.clear()
        games.clear()
        mapFlows.clear()
    }

    fun gameWorlds(): Set<String> = arenaRepository.all().map { it.world }.toSet()

    fun instance(worldName: String): GameInstance? = games[worldName]

    fun phaseOf(worldName: String): GamePhase? = games[worldName]?.phaseSnapshot()

    private fun mapFlowDef(worldName: String): MapFlowDefinition? =
        arenaRepository.byWorld(worldName).firstNotNullOfOrNull { it.mapFlow }

    private fun rules(worldName: String): GameRules {
        val flow = mapFlowDef(worldName)
        return GameRules(
            minPlayers = flow?.minPlayers ?: settings.minPlayers,
            startDelaySeconds = flow?.startDelaySeconds ?: settings.startDelaySeconds,
            maxDurationSeconds = flow?.maxDurationSeconds ?: settings.maxDurationSeconds,
        )
    }

    private fun autoTick() {
        gameWorlds().forEach { worldName ->
            val game = games.computeIfAbsent(worldName) { GameInstance(worldName, rules(worldName)) }
            val playerCount = worldAccess.playersIn(worldName).size
            when (game.phaseSnapshot()) {
                GamePhase.WAITING -> {
                    if (playerCount >= rules(worldName).minPlayers) beginCountdown(worldName, game)
                }
                GamePhase.STARTING -> {
                    if (playerCount < rules(worldName).minPlayers) {
                        cancelCountdown(worldName, game, "人数不足，取消开局")
                    }
                }
                else -> Unit
            }
        }
    }

    /** 手动强制开局（跳过人数与倒计时，仍要求至少 1 名玩家）。 */
    fun forceStart(worldName: String): Boolean {
        if (worldName !in gameWorlds()) return false
        val players = worldAccess.playersIn(worldName).map { it.id }
        if (players.isEmpty()) return false
        val game = games.computeIfAbsent(worldName) { GameInstance(worldName, rules(worldName)) }
        cancelCountdownSilently(worldName)
        startGame(worldName, game, players)
        return true
    }

    fun requestCountdown(worldName: String): Boolean {
        if (worldName !in gameWorlds()) return false
        val game = games.computeIfAbsent(worldName) { GameInstance(worldName, rules(worldName)) }
        if (game.phaseSnapshot() == GamePhase.WAITING) beginCountdown(worldName, game)
        return true
    }

    /** 直升机撤离：按钮触发 30 秒倒计时，结束后人类获胜。 */
    fun triggerEscape(worldName: String, operator: String? = null): Boolean {
        if (worldName !in gameWorlds()) return false
        val game = games[worldName] ?: return false
        if (game.phaseSnapshot() != GamePhase.RUNNING) return false
        if (game.humanIds().isEmpty()) return false

        cancelEscape(worldName)
        val timer = EscapeCountdown(ESCAPE_SECONDS)
        escapeCountdowns[worldName] = timer
        worldAccess.playersIn(worldName).forEach {
            messages.chat(it.id, "${operator ?: "玩家"} 启动了直升机撤离！${ESCAPE_SECONDS} 秒后人类获胜")
        }

        val handle = scheduler.globalTimer(1L, 20L) { taskHandle ->
            val current = escapeCountdowns[worldName]
            if (current !== timer) {
                taskHandle.cancel()
                return@globalTimer
            }
            if (timer.remainingSeconds > 0) {
                if (timer.remainingSeconds <= 10 || timer.remainingSeconds % 5 == 0) {
                    worldAccess.playersIn(worldName).forEach { p ->
                        messages.title(p.id, "${timer.remainingSeconds}", "直升机即将抵达")
                    }
                }
                timer.remainingSeconds--
            } else {
                escapeCountdowns.remove(worldName, timer)
                taskHandle.cancel()
                endGame(worldName, game, GameTeam.HUMAN, "直升机撤离成功，人类获胜")
            }
        }
        timer.task = handle
        taskRegistry.register(handle)
        logger.info("[$worldName] escape sequence started by ${operator ?: "?"}")
        return true
    }

    private fun cancelEscape(worldName: String) {
        escapeCountdowns.remove(worldName)?.task?.cancel()
    }

    private fun cancelEscapeCountdowns() {
        escapeCountdowns.values.forEach { it.task?.cancel() }
        escapeCountdowns.clear()
    }

    companion object {
        const val ESCAPE_SECONDS = 30
    }

    private fun beginCountdown(worldName: String, game: GameInstance) {
        game.beginCountdown()
        mapFlowDef(worldName)?.let { flow ->
            mapFlows.computeIfAbsent(worldName) { MapFlowStateMachine(flow) }.beginCountdown()
        }
        val delay = rules(worldName).startDelaySeconds
        val countdown = Countdown(delay)
        countdowns[worldName] = countdown
        worldAccess.playersIn(worldName).forEach {
            messages.chat(it.id, "人数已满足，$delay 秒后开始")
        }

        val handle = scheduler.globalTimer(20L, 20L) { taskHandle ->
            tickCountdown(worldName, game, countdown, taskHandle)
        }
        countdown.task = handle
        taskRegistry.register(handle)
    }

    private fun tickCountdown(worldName: String, game: GameInstance, countdown: Countdown, taskHandle: TaskHandle) {
        if (countdowns[worldName] !== countdown) {
            taskHandle.cancel()
            return
        }
        if (game.phaseSnapshot() != GamePhase.STARTING) {
            taskHandle.cancel()
            return
        }
        val players = worldAccess.playersIn(worldName)
        if (players.size < settings.minPlayers) {
            cancelCountdown(worldName, game, "人数不足，取消开局")
            taskHandle.cancel()
            return
        }

        val remaining = countdown.remainingSeconds
        if (remaining > 0) {
            if (remaining <= 10 || remaining % 5 == 0) {
                players.forEach { messages.title(it.id, "$remaining", "即将开始") }
            }
            countdown.remainingSeconds = remaining - 1
        } else {
            countdowns.remove(worldName, countdown)
            taskHandle.cancel()
            startGame(worldName, game, players.map { it.id })
        }
    }

    private fun startGame(worldName: String, game: GameInstance, playerIds: List<UUID>) {
        cancelCountdownSilently(worldName)
        maxDurationTasks.remove(worldName)?.cancel()

        mapFlowDef(worldName)?.let { flow ->
            val machine = mapFlows.computeIfAbsent(worldName) { MapFlowStateMachine(flow) }
            if (machine.phaseSnapshot() == MapFlowPhase.WAITING) machine.beginCountdown()
            machine.start()
        }

        val assignments = game.start(playerIds, alphaIndex = Random.nextInt(playerIds.size))
        val alphaId = game.alphaId()

        assignments.forEach { assignment ->
            val spawn = spawnForTeam(worldName, assignment.team)
            if (spawn != null) {
                teleportTo(worldName, assignment.playerId, spawn)
            }
            if (assignment.team == GameTeam.HUMAN) {
                mapFlowDef(worldName)?.starterWeaponId?.let { weaponId ->
                    weaponService?.giveWeapon(assignment.playerId, weaponId)
                }
            }
        }

        val alphaName = alphaId?.let { worldAccess.player(it)?.name } ?: "?"
        worldAccess.playersIn(worldName).forEach { p ->
            val teamName = when (game.teamOf(p.id)) {
                GameTeam.ZOMBIE_MAIN -> "母体僵尸"
                GameTeam.HUMAN -> "人类"
                else -> "未知"
            }
            messages.chat(p.id, "对局开始！你是 $teamName，母体：$alphaName")
        }
        messages.soundBell(worldName)
        logger.info("[$worldName] game started: ${playerIds.size} players, alpha=$alphaName")

        eventBus.publish(
            GameStartedEvent(
                worldName = worldName,
                playerAssignments = assignments.associate { it.playerId to it.team.name },
            ),
        )

        val durationTicks = rules(worldName).maxDurationSeconds * 20L
        val handle = scheduler.globalLater(durationTicks) {
            if (game.phaseSnapshot() == GamePhase.RUNNING) {
                endGame(worldName, game, GameTeam.HUMAN, "时间耗尽，人类获胜")
            }
        }
        maxDurationTasks[worldName] = handle
        taskRegistry.register(handle)
    }

    private fun cancelCountdownSilently(worldName: String) {
        countdowns.remove(worldName)?.task?.cancel()
    }

    private fun cancelCountdown(worldName: String, game: GameInstance, reason: String) {
        cancelCountdownSilently(worldName)
        game.cancelCountdown()
        worldAccess.playersIn(worldName).forEach { messages.chat(it.id, reason) }
    }

    override fun teamOf(worldName: String, playerId: UUID): GameTeam? =
        games[worldName]?.teamOf(playerId)

    override fun setRoom(worldName: String, playerId: UUID, room: Int) {
        games[worldName]?.setRoom(playerId, room)
    }

    override fun currentStageDoorNumbers(worldName: String): List<Int>? =
        mapFlows[worldName]?.currentDoorNumbers()

    fun mapFlowPhase(worldName: String): MapFlowPhase? =
        mapFlows[worldName]?.phaseSnapshot()

    fun currentStageLabel(worldName: String): String? =
        mapFlows[worldName]?.currentStage()?.label

    override fun isDoorUnlocked(worldName: String, doorNumber: Int): Boolean {
        val machine = mapFlows[worldName] ?: return true
        if (machine.phaseSnapshot() != MapFlowPhase.RUNNING) return false
        return doorNumber in machine.currentDoorNumbers()
    }

    private fun onDoorPassed(event: PlayerPassedDoorEvent) {
        val player = worldAccess.player(event.playerId) ?: return
        messages.chat(event.playerId, "进度更新：你已抵达 ${event.doorNumbers.maxOrNull()} 号门")
        logger.info("[${event.worldName}] ${player.name} reached door ${event.doorNumbers.joinToString("/")}")

        val machine = mapFlows[event.worldName] ?: return
        when (machine.onDoorPassed(event.doorNumbers)) {
            MapFlowAdvanceResult.STAGE_ADVANCED -> {
                val stage = machine.currentStage()
                worldAccess.playersIn(event.worldName).forEach {
                    messages.chat(it.id, "已通过当前阶段，下一目标：${stage.label}（门号 ${stage.doorNumbers.joinToString("/")}）")
                }
            }
            MapFlowAdvanceResult.FINISHED -> {
                val game = games[event.worldName] ?: return
                if (game.phaseSnapshot() == GamePhase.RUNNING) {
                    endGame(event.worldName, game, GameTeam.HUMAN, "到达终点，人类获胜")
                }
            }
            MapFlowAdvanceResult.WRONG_DOOR -> Unit
        }
    }

    /** 玩家进入配置了 arena 的世界。 */
    fun onPlayerJoin(worldName: String, playerId: UUID) {
        if (worldName !in gameWorlds()) return
        val game = games.computeIfAbsent(worldName) { GameInstance(worldName, rules(worldName)) }
        val team = game.addPlayer(playerId)
        when (game.phaseSnapshot()) {
            GamePhase.RUNNING -> {
                messages.chat(playerId, "对局进行中，你以僵尸身份加入")
                val spawn = spawnForTeam(worldName, GameTeam.ZOMBIE)
                if (spawn != null) teleportTo(worldName, playerId, spawn)
            }
            GamePhase.WAITING, GamePhase.STARTING -> {
                messages.chat(playerId, "你以观战身份等待对局")
            }
            GamePhase.ENDED -> messages.chat(playerId, "本局已结束")
        }
        if (team == GameTeam.ZOMBIE) logger.info("[$worldName] ${playerId} joined as zombie")
    }

    fun onPlayerQuit(worldName: String, playerId: UUID) {
        val game = games[worldName] ?: return
        val removedTeam = game.removePlayer(playerId) ?: return
        if (game.phaseSnapshot() != GamePhase.RUNNING) return

        if (removedTeam == GameTeam.ZOMBIE_MAIN) {
            replaceAlpha(worldName, game)
        }
        if (game.humanIds().isEmpty()) {
            endGame(worldName, game, GameTeam.ZOMBIE_MAIN, "人类被感染殆尽，僵尸获胜")
        }
    }

    private fun replaceAlpha(worldName: String, game: GameInstance) {
        val candidates = game.zombieIds().shuffled()
        val replacement = candidates.firstOrNull() ?: game.humanIds().firstOrNull()
        if (replacement != null && game.promoteZombieToAlpha(replacement)) {
            worldAccess.player(replacement)?.let {
                messages.chat(it.id, "原母体已离开，你成为新的母体僵尸")
                logger.info("[$worldName] ${it.name} promoted to alpha")
            }
        }
    }

    /** 战斗结果处理：僵尸攻击造成人类濒死时直接感染。返回 true 表示本次攻击转为感染。 */
    fun onCombat(victimId: UUID, attackerId: UUID?, worldName: String, lethal: Boolean): Boolean {
        val game = games[worldName] ?: return false
        if (game.phaseSnapshot() != GamePhase.RUNNING) return false
        if (game.teamOf(victimId) != GameTeam.HUMAN) return false
        val attackerTeam = attackerId?.let { game.teamOf(it) } ?: return false
        if (attackerTeam != GameTeam.ZOMBIE && attackerTeam != GameTeam.ZOMBIE_MAIN) return false
        if (!lethal) return false

        val result = game.infect(victimId)
        val name = worldAccess.player(victimId)?.name ?: victimId.toString()
        worldAccess.playersIn(worldName).forEach {
            messages.chat(it.id, "$name 被感染了！")
        }
        logger.info("[$worldName] $name infected; remaining humans=${result.remainingHumans}")

        val spawn = spawnForTeam(worldName, GameTeam.ZOMBIE)
        if (spawn != null) {
            val handle = scheduler.globalLater(20L) { teleportTo(worldName, victimId, spawn) }
            taskRegistry.register(handle)
        }

        if (result.gameEnded) {
            mapFlows[worldName]?.onAllHumansInfected()
            endGame(worldName, game, GameTeam.ZOMBIE_MAIN, "人类被感染殆尽，僵尸获胜")
        }
        return true
    }

    fun killCount(playerId: UUID): Int = zombieKills[playerId] ?: 0

    /** 僵尸被击杀：记录击杀统计（僵尸死亡由监听器取消、按重生点传送）。 */
    fun onZombieKilled(worldName: String, killerId: UUID, victimId: UUID) {
        val game = games[worldName] ?: return
        if (game.phaseSnapshot() != GamePhase.RUNNING) return
        if (game.teamOf(victimId) != GameTeam.ZOMBIE && game.teamOf(victimId) != GameTeam.ZOMBIE_MAIN) return
        zombieKills.merge(killerId, 1, Int::plus)
        eventBus.publish(ZombieKilledEvent(worldName, killerId, victimId))
        val killerName = worldAccess.player(killerId)?.name ?: killerId.toString()
        val victimName = worldAccess.player(victimId)?.name ?: victimId.toString()
        worldAccess.playersIn(worldName).forEach {
            messages.chat(it.id, "$killerName 击杀了僵尸 $victimName")
        }
        logger.info("[$worldName] $killerName killed zombie $victimName")
    }

    fun onPlayerRespawn(worldName: String, playerId: UUID) {
        val game = games[worldName] ?: return
        val team = game.teamOf(playerId) ?: return
        val spawn = spawnForTeam(worldName, team)
        if (spawn != null) {
            val handle = scheduler.globalLater(2L) { teleportTo(worldName, playerId, spawn) }
            taskRegistry.register(handle)
        }
    }

    private fun spawnForTeam(worldName: String, team: GameTeam): RespawnDefinition? {
        val respawns = arenaRepository.byWorld(worldName).flatMap { it.respawns }
        val preferred = when (team) {
            GameTeam.HUMAN -> listOf(RespawnType.PLAYER, RespawnType.WAIT)
            GameTeam.ZOMBIE_MAIN -> listOf(RespawnType.ZOMBIE_MAIN, RespawnType.ZOMBIE, RespawnType.WAIT)
            GameTeam.ZOMBIE -> listOf(RespawnType.ZOMBIE, RespawnType.WAIT)
            GameTeam.SPECTATOR -> emptyList()
        }
        return preferred.firstNotNullOfOrNull { type -> respawns.firstOrNull { it.type == type } }
    }

    private fun teleportTo(worldName: String, playerId: UUID, spawn: RespawnDefinition) {
        teleporter.teleport(
            playerId = playerId,
            worldName = spawn.world,
            x = spawn.x,
            y = spawn.y,
            z = spawn.z,
            yaw = spawn.yaw,
            pitch = spawn.pitch,
        )
    }

    fun endGame(worldName: String, winner: GameTeam): Boolean {
        val game = games[worldName] ?: return false
        endGame(worldName, game, winner, "对局结束")
        return true
    }

    private fun endGame(worldName: String, game: GameInstance, winner: GameTeam, message: String) {
        cancelCountdownSilently(worldName)
        cancelEscape(worldName)
        maxDurationTasks.remove(worldName)?.cancel()
        game.end(winner)
        worldAccess.playersIn(worldName).forEach { messages.chat(it.id, message) }
        awardMapFlowRewards(worldName, game, winner)
        logger.info("[$worldName] game ended: winner=$winner - $message")
        eventBus.publish(GameEndedEvent(worldName, winner.name))

        // 完整流程：5 秒后自动回到等待阶段，准备下一局
        val resetHandle = scheduler.globalLater(100L) {
            if (games[worldName]?.phaseSnapshot() == GamePhase.ENDED) {
                reset(worldName)
            }
        }
        autoResetTasks[worldName] = resetHandle
        taskRegistry.register(resetHandle)
    }

    fun reset(worldName: String): Boolean {
        val game = games[worldName] ?: return false
        cancelCountdownSilently(worldName)
        cancelEscape(worldName)
        maxDurationTasks.remove(worldName)?.cancel()
        autoResetTasks.remove(worldName)?.cancel()
        games.remove(worldName)
        mapFlows.remove(worldName)
        val fresh = GameInstance(worldName, rules(worldName))
        games[worldName] = fresh
        logger.info("[$worldName] game reset to WAITING")
        return true
    }

    private fun awardMapFlowRewards(worldName: String, game: GameInstance, winner: GameTeam) {
        val flow = mapFlowDef(worldName) ?: return
        val pd = playerData ?: return
        val (coins, xp) = if (winner == GameTeam.HUMAN) {
            flow.rewardCoinsHuman to flow.rewardXpHuman
        } else {
            flow.rewardCoinsZombie to flow.rewardXpZombie
        }
        if (coins == 0 && xp == 0) return

        val rewardedIds = when (winner) {
            GameTeam.HUMAN -> game.humanIds()
            GameTeam.ZOMBIE_MAIN -> game.zombieIds()
            else -> emptySet()
        }
        rewardedIds.forEach { id ->
            if (coins > 0) pd.addCoins(id, coins)
            if (xp > 0) pd.addXp(id, xp)
            messages.chat(id, "对局结束奖励：${coins} 硬币 / ${xp} 经验")
        }
    }

    private fun stopAutoTick() {
        autoTickTask?.cancel()
        autoTickTask = null
    }
}
