package cn.oneachina.zombierun.v2.application.game

import cn.oneachina.zombierun.v2.application.event.ApplicationEventBus
import cn.oneachina.zombierun.v2.application.event.GameEndedEvent
import cn.oneachina.zombierun.v2.application.event.InfectHumanEvent
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
import cn.oneachina.zombierun.v2.infrastructure.bukkit.hook.MultiverseWorldResolver
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
    @Volatile private var settings: V2Settings,
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
    private val worldLocks = ConcurrentHashMap<String, Any>()
    private val gameStartTimes = ConcurrentHashMap<String, Long>()
    private val mapFlows = ConcurrentHashMap<String, MapFlowStateMachine>()
    private val countdowns = ConcurrentHashMap<String, Countdown>()
    private val escapeCountdowns = ConcurrentHashMap<String, EscapeCountdown>()
    private val maxDurationTasks = ConcurrentHashMap<String, TaskHandle>()
    private val zombieDoorTasks = ConcurrentHashMap<String, TaskHandle>()
    private val autoResetTasks = ConcurrentHashMap<String, TaskHandle>()
    private val zombieKills = ConcurrentHashMap<UUID, Int>()
    private val infectCount = ConcurrentHashMap<UUID, Int>()
    private val protectedUntil = ConcurrentHashMap<UUID, Long>()
    private val motherReleasedWorlds = ConcurrentHashMap<String, Boolean>()
    private val motherReleaseTasks = ConcurrentHashMap<String, TaskHandle>()
    private var autoTickTask: TaskHandle? = null

    init {
        eventBus.subscribe(PlayerPassedDoorEvent::class.java) { event ->
            onDoorPassed(event)
        }
    }

    fun updateSettings(newSettings: V2Settings) {
        settings = newSettings
        logger.info("game settings updated")
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
        zombieDoorTasks.values.forEach { it.cancel() }
        zombieDoorTasks.clear()
        autoResetTasks.values.forEach { it.cancel() }
        autoResetTasks.clear()
        motherReleaseTasks.values.forEach { it.cancel() }
        motherReleaseTasks.clear()
        motherReleasedWorlds.clear()
        zombieKills.clear()
        infectCount.clear()
        protectedUntil.clear()
        games.clear()
        mapFlows.clear()
    }

    fun gameWorlds(): Set<String> = arenaRepository.all().map { it.world }.toSet()

    /** 是否为已配置 arena 的世界；所有全局副作用 listener 都应先用它过滤。 */
    private fun canonicalWorld(worldName: String): String = try {
        MultiverseWorldResolver.resolve(worldName)
    } catch (_: Exception) {
        // 单元测试/无 Bukkit 环境下保持原世界名
        worldName
    }

    fun isArenaWorld(worldName: String): Boolean =
        worldName in gameWorlds() || canonicalWorld(worldName) in gameWorlds()

    private fun worldLock(worldName: String): Any = worldLocks.computeIfAbsent(worldName) { Any() }

    fun instance(worldName: String): GameInstance? = games[worldName]

    fun phaseOf(worldName: String): GamePhase? = games[worldName]?.phaseSnapshot()

    private fun mapFlowDef(worldName: String): MapFlowDefinition? =
        arenaRepository.byWorld(canonicalWorld(worldName)).firstNotNullOfOrNull { it.mapFlow }

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
        val countdownSeconds = settings.helicopterCountdownSec
        val timer = EscapeCountdown(countdownSeconds)
        escapeCountdowns[worldName] = timer
        worldAccess.playersIn(worldName).forEach {
            messages.chat(it.id, "${operator ?: "玩家"} 启动了直升机撤离！$countdownSeconds 秒后人类获胜")
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
                // 撤离成功：同步 MapFlow 状态（HUMAN_WIN），再统一结算
                mapFlows[worldName]?.onExtraction()
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
        const val RESPAWN_DELAY_TICKS = 100L
        const val ZOMBIE_DOOR_DELAY_TICKS = 100L
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
        // 必须与 autoTick 使用同一规则源：map-flow 的 min-players 优先，否则
        // map-flow 地图人数阈值低于全局 settings.min-players 时会反复开始/取消倒计时。
        if (players.size < rules(worldName).minPlayers) {
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
        synchronized(worldLock(worldName)) {
        // 防止倒计时到点与管理员强开并发导致重复开局；ENDED 允许强开新一局（等价先 reset 再开）
        if (game.phaseSnapshot() == GamePhase.RUNNING) return
        cancelCountdownSilently(worldName)
        maxDurationTasks.remove(worldName)?.cancel()

        mapFlowDef(worldName)?.let { flow ->
            val machine = mapFlows.computeIfAbsent(worldName) { MapFlowStateMachine(flow) }
            // 上一局结算后 5 秒自动复位前被强制开局的场景：先复位流程再启动，
            // 否则 machine.start() 会失败，新对局带着 HUMAN_WIN/ZOMBIE_WIN 状态运行。
            if (machine.phaseSnapshot() != MapFlowPhase.STARTING) {
                machine.reset()
                machine.beginCountdown()
            }
            machine.start()
        }

        val startToken = System.currentTimeMillis()
        gameStartTimes[worldName] = startToken
        val assignments = game.start(playerIds, alphaIndex = Random.nextInt(playerIds.size))
        val alphaId = game.alphaId()

        assignments.forEach { assignment ->
            // 必须先清状态/背包，再发武器，避免 onGameStarted 异步清理清掉刚发的枪
            playerStatePreparer?.invoke(assignment.playerId, assignment.team)
            val spawn = spawnForTeam(worldName, assignment.team, initial = true)
            if (spawn != null) {
                teleportTo(worldName, assignment.playerId, spawn)
            }
            if (assignment.team == GameTeam.HUMAN) {
                val ws = weaponService
                val starter = mapFlowDef(worldName)?.starterWeaponId
                val selected = ws?.selectedWeaponId(assignment.playerId)
                if (ws != null && selected != null) {
                    val weapon = ws.byId(selected)
                    if (weapon != null && weapon.enabled) {
                        val price = weapon.price.toInt()
                        val afterSpend = playerData?.spendCoins(assignment.playerId, price)
                        if (afterSpend != null) {
                            val ok = ws.giveWeaponWithAmmo(assignment.playerId, selected)
                            if (ok) {
                                messages.chat(assignment.playerId, "已购买预选武器：${weapon.displayName}（-$price 硬币）")
                            } else {
                                playerData?.addCoins(assignment.playerId, price)
                                messages.chat(assignment.playerId, "预选武器发放失败，已退款；改发默认武器")
                                starter?.let { ws.giveStarter(assignment.playerId, it) }
                            }
                        } else {
                            messages.chat(assignment.playerId, "预选武器余额不足，改发默认武器")
                            starter?.let { ws.giveStarter(assignment.playerId, it) }
                        }
                    } else {
                        starter?.let { ws.giveStarter(assignment.playerId, it) }
                    }
                    ws.clearSelected(assignment.playerId)
                } else {
                    // 开局默认武器（不扣款）：优先配置的 starter-weapon，否则随机枪并补满弹药
                    if (starter != null) {
                        ws?.giveStarter(assignment.playerId, starter)
                    } else {
                        val random = ws?.giveRandom(assignment.playerId, null)
                        if (random != null) {
                            ws?.refillAmmo(assignment.playerId, random.id, cn.oneachina.zombierun.v2.application.weapon.WeaponService.STARTER_MAGAZINES)
                        }
                    }
                }
            } else if (assignment.team == GameTeam.ZOMBIE_MAIN) {
                val releaseDelay = mapFlowDef(worldName)?.motherReleaseDelaySeconds ?: 0
                protect(assignment.playerId, releaseDelay)
                scheduleMotherRelease(worldName, releaseDelay)
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
        if (settings.startEffects.isNotEmpty()) {
            startEffectExecutor?.invoke(worldName)
        }
        logger.info("[$worldName] game started: ${playerIds.size} players, alpha=$alphaName")

        eventBus.publish(
            GameStartedEvent(
                worldName = worldName,
                playerAssignments = assignments.associate { it.playerId to it.team.name },
            ),
        )

        // 自动门：START/PLAYER 立即开启，ZOMBIE 门 5 秒后开启（对齐 v1 行为）
        autoOpenDoors(worldName, "START")
        autoOpenDoors(worldName, "PLAYER")
        zombieDoorTasks.remove(worldName)?.cancel()
        val zombieDoorHandle = scheduler.globalLater(ZOMBIE_DOOR_DELAY_TICKS) {
            if (game.phaseSnapshot() == GamePhase.RUNNING && gameStartTimes[worldName] == startToken) {
                autoOpenDoors(worldName, "ZOMBIE")
            }
        }
        zombieDoorTasks[worldName] = zombieDoorHandle
        taskRegistry.register(zombieDoorHandle)

        val durationTicks = rules(worldName).maxDurationSeconds * 20L
        val handle = scheduler.globalLater(durationTicks) {
            if (game.phaseSnapshot() == GamePhase.RUNNING) {
                mapFlows[worldName]?.onTimeUp()
                endGame(worldName, game, GameTeam.HUMAN, "时间耗尽，人类获胜")
            }
        }
        maxDurationTasks[worldName] = handle
        taskRegistry.register(handle)
        }
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

    /** 复活后短暂无敌/母体未释放期间不可被伤害。 */
    fun isProtected(playerId: UUID): Boolean {
        val deadline = protectedUntil[playerId] ?: return false
        if (System.currentTimeMillis() < deadline) return true
        protectedUntil.remove(playerId)
        return false
    }

    fun isMotherReleased(worldName: String): Boolean =
        motherReleasedWorlds[worldName] != false

    private fun protect(playerId: UUID, seconds: Int) {
        if (seconds <= 0) return
        protectedUntil[playerId] = System.currentTimeMillis() + seconds * 1000L
    }

    private fun scheduleMotherRelease(worldName: String, delaySeconds: Int) {
        motherReleaseTasks.remove(worldName)?.cancel()
        motherReleasedWorlds[worldName] = false
        val alphaId = games[worldName]?.alphaId() ?: return
        if (delaySeconds <= 0) {
            motherReleasedWorlds[worldName] = true
            return
        }
        var remaining = delaySeconds
        val handle = scheduler.globalTimer(1L, 20L) { taskHandle ->
            if (games[worldName]?.phaseSnapshot() != GamePhase.RUNNING) {
                motherReleasedWorlds.remove(worldName)
                taskHandle.cancel()
                return@globalTimer
            }
            if (remaining > 0) {
                worldAccess.player(alphaId)?.let { messages.title(it.id, "$remaining", "母体即将释放") }
                remaining--
            } else {
                motherReleasedWorlds[worldName] = true
                worldAccess.player(alphaId)?.let {
                    messages.chat(it.id, "母体已释放，狩猎开始！")
                    motherReleaseStateSync?.invoke(alphaId)
                }
                worldAccess.playersIn(worldName).forEach { p ->
                    messages.chat(p.id, "母体已释放！")
                }
                motherReleaseTasks.remove(worldName)
                taskHandle.cancel()
            }
        }
        motherReleaseTasks[worldName] = handle
        taskRegistry.register(handle)
    }

    private fun cancelMotherRelease(worldName: String) {
        motherReleaseTasks.remove(worldName)?.cancel()
        motherReleasedWorlds.remove(worldName)
    }

    override fun setRoom(worldName: String, playerId: UUID, room: Int) {
        games[worldName]?.setRoom(playerId, room)
    }

    override fun currentStageDoorNumbers(worldName: String): List<Int>? =
        mapFlows[worldName]?.currentDoorNumbers()

    fun mapFlowPhase(worldName: String): MapFlowPhase? =
        mapFlows[worldName]?.phaseSnapshot()

    fun currentStageLabel(worldName: String): String? =
        mapFlows[worldName]?.currentStage()?.label

    // ---------- PAPI / 展示辅助 ----------

    fun humanCount(worldName: String): Int = instance(worldName)?.humanIds()?.size ?: 0

    fun zombieCount(worldName: String): Int = instance(worldName)?.zombieIds()?.size ?: 0

    fun alphaName(worldName: String): String =
        instance(worldName)?.alphaId()?.let { worldAccess.player(it)?.name } ?: ""

    fun alphaId(worldName: String): UUID? = instance(worldName)?.alphaId()

    fun gameStateFormatted(worldName: String): String =
        phaseOf(worldName)?.name?.lowercase() ?: "none"

    fun timeLeftSeconds(worldName: String): Int {
        val game = instance(worldName) ?: return 0
        if (game.phaseSnapshot() != GamePhase.RUNNING) return 0
        val start = gameStartTimes[worldName] ?: return 0
        val maxSeconds = rules(worldName).maxDurationSeconds
        return ((start + maxSeconds * 1000L - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
    }

    fun progressPercent(worldName: String): Double {
        val machine = mapFlows[worldName] ?: return 0.0
        val total = machine.definition.stages.size
        if (total == 0) return 0.0
        return machine.passedStages().size.toDouble() / total
    }

    fun minPlayers(worldName: String): Int = rules(worldName).minPlayers

    fun maxPlayers(worldName: String): Int = settings.maxPlayers

    fun onlinePlayers(worldName: String): Int = worldAccess.playersIn(worldName).size

    fun roomOf(worldName: String, playerId: UUID): Int =
        instance(worldName)?.roomOf(playerId) ?: 0

    override fun isDoorUnlocked(worldName: String, doorNumber: Int): Boolean {
        val machine = mapFlows[worldName] ?: return true
        if (machine.phaseSnapshot() != MapFlowPhase.RUNNING) return false
        return doorNumber in machine.currentDoorNumbers()
    }

    private fun onDoorPassed(event: PlayerPassedDoorEvent) {
        val player = worldAccess.player(event.playerId) ?: return
        // 过门即时 XP（economy.pass-door-xp，默认 5）
        val xp = settings.economy.passDoorXp
        if (xp > 0) playerData?.addXp(event.playerId, xp)
        val machine = mapFlows[event.worldName] ?: return
        when (machine.onDoorPassed(event.doorNumbers)) {
            MapFlowAdvanceResult.STAGE_ADVANCED -> {
                val stage = machine.currentStage()
                messages.chat(event.playerId, "进度更新：你已抵达 ${event.doorNumbers.maxOrNull()} 号门")
                logger.info("[${event.worldName}] ${player.name} reached door ${event.doorNumbers.joinToString("/")}")
                worldAccess.playersIn(event.worldName).forEach {
                    messages.chat(it.id, "已通过当前阶段，下一目标：${stage.label}（门号 ${stage.doorNumbers.joinToString("/")}）")
                }
            }
            MapFlowAdvanceResult.FINISHED -> {
                logger.info("[${event.worldName}] ${player.name} reached final door ${event.doorNumbers.joinToString("/")}")
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
        val canonical = canonicalWorld(worldName)
        if (canonical !in gameWorlds()) return
        val game = games.computeIfAbsent(canonical) { GameInstance(canonical, rules(canonical)) }
        val team = game.addPlayer(playerId)
        when (game.phaseSnapshot()) {
            GamePhase.RUNNING -> {
                messages.chat(playerId, "对局进行中，你以僵尸身份加入")
                playerStatePreparer?.invoke(playerId, GameTeam.ZOMBIE)
                zombieBuffApplier?.invoke(playerId)
                val spawn = spawnForTeam(canonical, GameTeam.ZOMBIE)
                if (spawn != null) teleportTo(canonical, playerId, spawn)
            }
            GamePhase.WAITING, GamePhase.STARTING -> {
                messages.chat(playerId, "你以观战身份等待对局")
                playerStatePreparer?.invoke(playerId, GameTeam.SPECTATOR)
                val waitSpawn = arenaRepository.byWorld(canonical)
                    .flatMap { it.respawns }
                    .firstOrNull { it.type == RespawnType.WAIT }
                if (waitSpawn != null) teleportTo(canonical, playerId, waitSpawn)
            }
            GamePhase.ENDED -> {
                messages.chat(playerId, "本局已结束")
                playerStatePreparer?.invoke(playerId, GameTeam.SPECTATOR)
                val waitSpawn = arenaRepository.byWorld(canonical)
                    .flatMap { it.respawns }
                    .firstOrNull { it.type == RespawnType.WAIT }
                if (waitSpawn != null) teleportTo(canonical, playerId, waitSpawn)
            }
        }
        if (team == GameTeam.ZOMBIE) logger.info("[$canonical] ${playerId} joined as zombie")
    }

    fun onPlayerQuit(worldName: String, playerId: UUID) {
        handlePlayerGone(worldName, playerId)
    }

    /** 玩家传送到其他世界：与退出等价——从旧世界对局名册移除，母体离开则补位。 */
    fun onPlayerLeaveWorld(worldName: String, playerId: UUID) {
        handlePlayerGone(worldName, playerId)
    }

    private fun handlePlayerGone(worldName: String, playerId: UUID) {
        val canonical = canonicalWorld(worldName)
        val game = games[canonical] ?: return
        val removedTeam = game.removePlayer(playerId) ?: return
        // 离开对局世界/退出时清理保护状态，避免跨世界残留无敌
        protectedUntil.remove(playerId)
        if (game.phaseSnapshot() != GamePhase.RUNNING) return

        if (removedTeam == GameTeam.ZOMBIE_MAIN) {
            replaceAlpha(canonical, game)
        }
        if (game.humanIds().isEmpty()) {
            endGame(canonical, game, GameTeam.ZOMBIE_MAIN, "人类被感染殆尽，僵尸获胜")
        }
    }

    private fun replaceAlpha(worldName: String, game: GameInstance) {
        val candidates = game.zombieIds().shuffled()
        val replacement = candidates.firstOrNull() ?: game.humanIds().firstOrNull()
        if (replacement != null && game.promoteZombieToAlpha(replacement)) {
            // 取消原母体的未释放倒计时，新母体立即释放
            cancelMotherRelease(worldName)
            motherReleasedWorlds[worldName] = true
            // 完整状态同步：解冻结、初始化母体血量、上僵尸增益
            playerStatePreparer?.invoke(replacement, GameTeam.ZOMBIE_MAIN)
            motherReleaseStateSync?.invoke(replacement)
            zombieBuffApplier?.invoke(replacement)
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
        // 母体未释放前不能感染/伤害人类
        if (attackerTeam == GameTeam.ZOMBIE_MAIN && !isMotherReleased(worldName)) return false
        if (!lethal) return false

        val result = game.infect(victimId)
        val name = worldAccess.player(victimId)?.name ?: victimId.toString()
        worldAccess.playersIn(worldName).forEach {
            messages.chat(it.id, "$name 被感染了！")
        }
        logger.info("[$worldName] $name infected; remaining humans=${result.remainingHumans}")

        val spawn = spawnForTeam(worldName, GameTeam.ZOMBIE)
        if (spawn != null) {
            protect(victimId, settings.infectCountdownSec)
            val handle = scheduler.globalLater(20L) { teleportTo(worldName, victimId, spawn) }
            taskRegistry.register(handle)
        }

        if (result.gameEnded) {
            mapFlows[worldName]?.onAllHumansInfected()
            endGame(worldName, game, GameTeam.ZOMBIE_MAIN, "人类被感染殆尽，僵尸获胜")
        }
        return true
    }

    /** 监听器已确认感染成立（attacker→victim）：执行感染转化 + 延迟复活。 */
    fun onCombatInfection(worldName: String, attackerId: UUID, victimId: UUID): Boolean {
        val game = games[worldName] ?: return false
        if (game.phaseSnapshot() != GamePhase.RUNNING) return false
        if (game.teamOf(victimId) != GameTeam.HUMAN) return false
        val attackerTeam = game.teamOf(attackerId) ?: return false
        if (attackerTeam != GameTeam.ZOMBIE && attackerTeam != GameTeam.ZOMBIE_MAIN) return false

        val result = game.infect(victimId)
        infectCount.merge(attackerId, 1, Int::plus)
        val victimName = worldAccess.player(victimId)?.name ?: victimId.toString()
        val attackerName = worldAccess.player(attackerId)?.name ?: attackerId.toString()
        worldAccess.playersIn(worldName).forEach {
            messages.chat(it.id, "$attackerName 感染了 $victimName！")
        }
        eventBus.publish(InfectHumanEvent(attackerId, victimId))
        logger.info("[$worldName] $victimName infected by $attackerName; remaining humans=${result.remainingHumans}")

        scheduleZombieRespawn(worldName, victimId, "你现在是僵尸！阻止人类前进！")
        if (result.gameEnded) {
            mapFlows[worldName]?.onAllHumansInfected()
            endGame(worldName, game, GameTeam.ZOMBIE_MAIN, "人类被感染殆尽，僵尸获胜")
        }
        return true
    }

    /** 人类非感染死亡（摔死/环境伤害等）：直接感染转僵尸。 */
    fun onHumanDiedByEnvironment(worldName: String, playerId: UUID, message: String) {
        val game = games[worldName] ?: return
        if (game.phaseSnapshot() != GamePhase.RUNNING) return
        if (game.teamOf(playerId) != GameTeam.HUMAN) return
        val result = game.infect(playerId)
        worldAccess.player(playerId)?.let { messages.chat(it.id, message) }
        logger.info("[$worldName] ${worldAccess.player(playerId)?.name ?: playerId} died to environment")
        scheduleZombieRespawn(worldName, playerId, "你现在是僵尸！阻止人类前进！")
        if (result.gameEnded) {
            mapFlows[worldName]?.onAllHumansInfected()
            endGame(worldName, game, GameTeam.ZOMBIE_MAIN, "人类全部阵亡，僵尸获胜")
        }
    }

    /** 保留旧入口：转观战并按原逻辑结算。 */
    fun onHumanDied(worldName: String, playerId: UUID) {
        val game = games[worldName] ?: return
        if (game.phaseSnapshot() != GamePhase.RUNNING) return
        if (game.teamOf(playerId) != GameTeam.HUMAN) return
        game.spectate(playerId)
        worldAccess.playersIn(worldName).forEach {
            messages.chat(it.id, "${worldAccess.player(playerId)?.name ?: playerId} 阵亡了，进入观战")
        }
        if (game.humanIds().isEmpty()) {
            mapFlows[worldName]?.onAllHumansInfected()
            endGame(worldName, game, GameTeam.ZOMBIE_MAIN, "人类全部阵亡，僵尸获胜")
        }
    }

    /** 僵尸死亡后延迟复活：应用僵尸效果、按进度布防传送。 */
    fun onZombieDiedRespawn(worldName: String, playerId: UUID) {
        val game = games[worldName] ?: return
        if (game.phaseSnapshot() != GamePhase.RUNNING) return
        val team = game.teamOf(playerId) ?: return
        if (team != GameTeam.ZOMBIE && team != GameTeam.ZOMBIE_MAIN) return
        scheduleZombieRespawn(worldName, playerId, "你已复活为僵尸！")
    }

    /** 延迟复活僵尸/母体：传送布防 + 僵尸增益 + 消息。 */
    private fun scheduleZombieRespawn(worldName: String, playerId: UUID, message: String) {
        val handle = scheduler.globalLater(settings.respawnDelayTicks) {
            val game = games[worldName] ?: return@globalLater
            if (game.phaseSnapshot() != GamePhase.RUNNING) return@globalLater
            val team = game.teamOf(playerId) ?: return@globalLater
            if (team != GameTeam.ZOMBIE && team != GameTeam.ZOMBIE_MAIN) return@globalLater
            val spawnTeam = if (team == GameTeam.ZOMBIE_MAIN) GameTeam.ZOMBIE_MAIN else GameTeam.ZOMBIE
            val spawn = spawnForTeam(worldName, spawnTeam)
            if (spawn != null) teleportTo(worldName, playerId, spawn)
            zombieBuffApplier?.invoke(playerId)
            worldAccess.player(playerId)?.let { messages.chat(it.id, message) }
        }
        taskRegistry.register(handle)
    }

    /** 僵尸药水增益钩子：由装配点注入（Infrastructure 层 Bukkit 效果）。 */
    var zombieBuffApplier: ((UUID) -> Unit)? = null

    /** 母体释放状态同步钩子：SPECTATOR 冻结 → ADVENTURE（装配点注入）。 */
    var motherReleaseStateSync: ((UUID) -> Unit)? = null

    /** 自动开门钩子：由装配点注入（DoorApplicationService.triggerAutoDoors）。 */
    var doorAutoOpener: ((worldName: String, mode: String) -> Unit)? = null

    /** 开局效果钩子：由装配点注入（执行 settings.start-effects 控制台命令）。 */
    var startEffectExecutor: ((worldName: String) -> Unit)? = null

    /** 开局前玩家状态准备钩子：清背包/药水、设 GameMode、初始化自定义血量。 */
    var playerStatePreparer: ((playerId: UUID, team: GameTeam) -> Unit)? = null

    private fun autoOpenDoors(worldName: String, mode: String) {
        doorAutoOpener?.invoke(worldName, mode)
    }

    fun killCount(playerId: UUID): Int = zombieKills[playerId] ?: 0

    fun infectCount(playerId: UUID): Int = infectCount[playerId] ?: 0

    /** 结算榜单：击杀 Top-N（UUID） */
    fun topKillers(worldName: String, n: Int): List<Pair<UUID, Int>> =
        games[worldName]?.playerIds()?.mapNotNull { id ->
            killCount(id).takeIf { it > 0 }?.let { id to it }
        }?.sortedByDescending { it.second }?.take(n) ?: emptyList()

    /** 结算榜单：感染 Top-N（UUID） */
    fun topInfectors(worldName: String, n: Int): List<Pair<UUID, Int>> =
        games[worldName]?.playerIds()?.mapNotNull { id ->
            infectCount(id).takeIf { it > 0 }?.let { id to it }
        }?.sortedByDescending { it.second }?.take(n) ?: emptyList()

    /** 僵尸被击杀：记录击杀统计（僵尸死亡由监听器取消、按重生点传送）。返回是否成功计数。 */
    fun onZombieKilled(worldName: String, killerId: UUID, victimId: UUID): Boolean {
        val game = games[worldName] ?: return false
        if (game.phaseSnapshot() != GamePhase.RUNNING) return false
        if (game.teamOf(victimId) != GameTeam.ZOMBIE && game.teamOf(victimId) != GameTeam.ZOMBIE_MAIN) return false
        zombieKills.merge(killerId, 1, Int::plus)
        eventBus.publish(ZombieKilledEvent(worldName, killerId, victimId, game.teamOf(victimId)?.name))
        val killerName = worldAccess.player(killerId)?.name ?: killerId.toString()
        val victimName = worldAccess.player(victimId)?.name ?: victimId.toString()
        worldAccess.playersIn(worldName).forEach {
            messages.chat(it.id, "$killerName 击杀了僵尸 $victimName")
        }
        logger.info("[$worldName] $killerName killed zombie $victimName")
        return true
    }

    fun onPlayerRespawn(worldName: String, playerId: UUID) {
        val game = games[worldName] ?: return
        val team = game.teamOf(playerId) ?: return
        if (team == GameTeam.SPECTATOR) return
        val spawn = spawnForTeam(worldName, team)
        if (spawn != null) {
            protect(playerId, settings.infectCountdownSec)
            val handle = scheduler.globalLater(2L) { teleportTo(worldName, playerId, spawn) }
            taskRegistry.register(handle)
        }
    }

    private fun spawnForTeam(worldName: String, team: GameTeam, initial: Boolean = false): RespawnDefinition? {
        val respawns = arenaRepository.byWorld(canonicalWorld(worldName)).flatMap { it.respawns }
        fun randomOf(type: RespawnType): RespawnDefinition? {
            val matches = respawns.filter { it.type == type }
            return if (matches.isEmpty()) null else matches.random()
        }
        fun doorCheckpoint(type: RespawnType): RespawnDefinition? {
            val currentDoorNumbers = mapFlows[worldName]?.currentDoorNumbers() ?: return null
            val matches = respawns.filter { it.type == type && it.doorNumber in currentDoorNumbers }
            return if (matches.isEmpty()) null else matches.random()
        }

        return when (team) {
            GameTeam.HUMAN -> randomOf(RespawnType.PLAYER) ?: randomOf(RespawnType.WAIT)
            GameTeam.ZOMBIE_MAIN ->
                if (initial) {
                    randomOf(RespawnType.ZOMBIE_MAIN) ?: randomOf(RespawnType.ZOMBIE) ?: randomOf(RespawnType.WAIT)
                } else {
                    doorCheckpoint(RespawnType.DOOR_ZOMBIE)
                        ?: randomOf(RespawnType.ZOMBIE_MAIN)
                        ?: randomOf(RespawnType.ZOMBIE)
                        ?: randomOf(RespawnType.WAIT)
                }
            GameTeam.ZOMBIE ->
                doorCheckpoint(RespawnType.DOOR_ZOMBIE)
                    ?: randomOf(RespawnType.ZOMBIE)
                    ?: randomOf(RespawnType.WAIT)
            GameTeam.SPECTATOR -> null
        }
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
        synchronized(worldLock(worldName)) {
        // 幂等：只有 RUNNING -> ENDED 成功才继续结算
        if (!game.tryEnd(winner)) return
        cancelCountdownSilently(worldName)
        cancelEscape(worldName)
        cancelMotherRelease(worldName)
        maxDurationTasks.remove(worldName)?.cancel()
        zombieDoorTasks.remove(worldName)?.cancel()
        worldAccess.playersIn(worldName).forEach { messages.chat(it.id, message) }
        awardParticipationXp(worldName, game)
        awardHumanWinXp(worldName, game, winner)
        awardSurviveReward(worldName, game, winner)
        awardRankRewards(worldName, game)
        awardMapFlowRewards(worldName, game, winner)
        logger.info("[$worldName] game ended: winner=$winner - $message")
        val winnerPlayerIds = when (winner) {
            GameTeam.HUMAN -> game.humanIds()
            GameTeam.ZOMBIE, GameTeam.ZOMBIE_MAIN -> game.zombieIds()
            else -> emptySet()
        }
        val survivalSecondsByPlayer = if (winner == GameTeam.HUMAN) {
            val start = gameStartTimes[worldName] ?: System.currentTimeMillis()
            game.humanIds().associateWith {
                ((System.currentTimeMillis() - start) / 1000L).toInt().coerceAtLeast(0)
            }
        } else {
            emptyMap()
        }
        eventBus.publish(GameEndedEvent(worldName, winner.name, winnerPlayerIds, survivalSecondsByPlayer))

        // 完整流程：5 秒后自动回到等待阶段，准备下一局
        val resetHandle = scheduler.globalLater(100L) {
            if (games[worldName]?.phaseSnapshot() == GamePhase.ENDED) {
                reset(worldName)
            }
        }
        autoResetTasks[worldName] = resetHandle
        taskRegistry.register(resetHandle)
        }
    }

    fun reset(worldName: String): Boolean {
        val game = games[worldName] ?: return false
        cancelCountdownSilently(worldName)
        cancelEscape(worldName)
        cancelMotherRelease(worldName)
        maxDurationTasks.remove(worldName)?.cancel()
        zombieDoorTasks.remove(worldName)?.cancel()
        autoResetTasks.remove(worldName)?.cancel()
        motherReleasedWorlds.remove(worldName)
        games.remove(worldName)
        mapFlows.remove(worldName)
        game.playerIds().forEach { zombieKills.remove(it); infectCount.remove(it) }
        val fresh = GameInstance(worldName, rules(worldName))
        games[worldName] = fresh
        // 对局结束会把玩家设为 SPECTATOR；重置后恢复等待状态
        worldAccess.playersIn(worldName).forEach {
            playerStatePreparer?.invoke(it.id, GameTeam.SPECTATOR)
        }
        logger.info("[$worldName] game reset to WAITING")
        return true
    }

    /** 参与对局 XP：对所有参与者发放。 */
    private fun awardParticipationXp(worldName: String, game: GameInstance) {
        val xp = settings.economy.participateXp
        if (xp <= 0) return
        val pd = playerData ?: return
        game.playerIds().forEach { pd.addXp(it, xp) }
    }

    /** 人类胜利 XP：对人类阵营发放。 */
    private fun awardHumanWinXp(worldName: String, game: GameInstance, winner: GameTeam) {
        if (winner != GameTeam.HUMAN) return
        val xp = settings.economy.humanWinXp
        if (xp <= 0) return
        val pd = playerData ?: return
        game.humanIds().forEach { pd.addXp(it, xp) }
    }

    /** 直升机逃脱/撑到结束的人类存活奖励。 */
    private fun awardSurviveReward(worldName: String, game: GameInstance, winner: GameTeam) {
        if (winner != GameTeam.HUMAN) return
        val coins = settings.economy.surviveHumanCoins
        if (coins <= 0) return
        val pd = playerData ?: return
        game.humanIds().forEach { id ->
            pd.addCoins(id, coins)
            messages.chat(id, "作为人类活到最后！+$coins 硬币")
        }
    }

    /** 结算榜单奖励：击杀/感染 Top3 发 rank-reward-coins。 */
    private fun awardRankRewards(worldName: String, game: GameInstance) {
        val pd = playerData ?: return
        val rewards = settings.economy.rankRewardCoins
        if (rewards.isEmpty()) return
        listOf("击杀" to topKillers(worldName, rewards.size), "感染" to topInfectors(worldName, rewards.size))
            .forEach { (label, top) ->
                top.forEachIndexed { index, (id, count) ->
                    val reward = rewards.getOrNull(index) ?: return@forEachIndexed
                    if (reward > 0) {
                        pd.addCoins(id, reward)
                        messages.chat(id, "+ $reward 硬币! ($label 第 ${index + 1} 名，共 $count)")
                    }
                }
            }
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
