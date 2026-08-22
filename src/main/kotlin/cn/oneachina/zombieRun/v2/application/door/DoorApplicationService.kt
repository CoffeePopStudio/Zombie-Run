package cn.oneachina.zombierun.v2.application.door

import cn.oneachina.zombierun.v2.domain.arena.ArenaDefinition
import cn.oneachina.zombierun.v2.domain.arena.RespawnDefinition
import cn.oneachina.zombierun.v2.domain.arena.RespawnType
import cn.oneachina.zombierun.v2.domain.door.DoorDefinition
import cn.oneachina.zombierun.v2.domain.door.DoorPassDecision
import cn.oneachina.zombierun.v2.domain.door.DoorSessionPhase
import cn.oneachina.zombierun.v2.domain.door.DoorSessionStateMachine
import cn.oneachina.zombierun.v2.domain.door.Vec3
import cn.oneachina.zombierun.v2.infrastructure.config.ArenaYamlRepository
import cn.oneachina.zombierun.v2.infrastructure.config.BlockSnapshotStore
import cn.oneachina.zombierun.v2.ports.BlockOpsPort
import cn.oneachina.zombierun.v2.ports.PlayerMessagePort
import cn.oneachina.zombierun.v2.ports.SchedulerPort
import cn.oneachina.zombierun.v2.ports.TaskHandle
import cn.oneachina.zombierun.v2.ports.TeleporterPort
import cn.oneachina.zombierun.v2.ports.WorldAccessPort
import cn.oneachina.zombierun.v2.support.TaskRegistry
import cn.oneachina.zombierun.v2.support.V2Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DoorApplicationService(
    private val arenaRepository: ArenaYamlRepository,
    private val snapshotStore: BlockSnapshotStore,
    private val blockOps: BlockOpsPort,
    private val worldAccess: WorldAccessPort,
    private val scheduler: SchedulerPort,
    private val taskRegistry: TaskRegistry,
    private val messages: PlayerMessagePort,
    private val teleporter: TeleporterPort,
    private val logger: V2Logger,
) {
    private class RuntimeSession(
        val worldName: String,
        val doors: List<DoorDefinition>,
        val state: DoorSessionStateMachine,
        @Volatile var remainingSeconds: Int,
        @Volatile var phase: DoorSessionPhase,
        @Volatile var task: TaskHandle? = null,
    )

    private val activeSessions = ConcurrentHashMap<String, RuntimeSession>()
    private val transferTasks = ConcurrentHashMap<UUID, TaskHandle>()

    fun reload() {
        cancelAllSessions()
        arenaRepository.loadAll()
    }

    fun arenas(): List<ArenaDefinition> = arenaRepository.all()

    fun doorsInWorld(worldName: String): List<DoorDefinition> =
        arenaRepository.byWorld(worldName).flatMap { it.doors }

    fun doorById(id: String): DoorDefinition? =
        arenaRepository.all().asSequence().flatMap { it.doors.asSequence() }.firstOrNull { it.id == id }

    fun doorByNumber(worldName: String, number: Int): DoorDefinition? =
        doorsInWorld(worldName).firstOrNull { it.number == number }

    /** 触发开门；group 非空时同组所有门联动。 */
    fun triggerDoor(worldName: String, doorNumber: Int, operator: String? = null): TriggerResult {
        if (activeSessions.containsKey(worldName)) {
            return TriggerResult(false, "该世界已有门会话进行中")
        }
        val door = doorByNumber(worldName, doorNumber)
            ?: return TriggerResult(false, "世界 $worldName 不存在 $doorNumber 号门")

        val doors = if (door.group.isNullOrBlank()) {
            listOf(door)
        } else {
            doorsInWorld(worldName).filter { it.group == door.group }
        }
        if (doors.isEmpty()) return TriggerResult(false, "门组为空")

        val players = worldAccess.playersIn(worldName)
        val initialPositions = players.associate { it.id to it.position }
        val state = DoorSessionStateMachine(
            sessionId = "door-$worldName-$doorNumber-${System.currentTimeMillis()}",
            doors = doors,
            initialPositions = initialPositions,
        )

        val session = RuntimeSession(
            worldName = worldName,
            doors = doors,
            state = state,
            remainingSeconds = door.openSeconds,
            phase = DoorSessionPhase.OPENING,
        )
        activeSessions[worldName] = session

        val label = doorLabel(doors)
        players.forEach { p ->
            messages.chat(p.id, "$label 即将开启……")
        }

        val handle = scheduler.globalTimer(1L, 20L) { taskHandle ->
            tick(session, taskHandle)
        }
        session.task = handle
        taskRegistry.register(handle)

        logger.info("[$worldName] door session started: $label by ${operator ?: "?"}")
        return TriggerResult(true, "$label 已触发")
    }

    private fun tick(session: RuntimeSession, taskHandle: TaskHandle) {
        when (session.phase) {
            DoorSessionPhase.OPENING -> {
                val remaining = session.remainingSeconds
                if (remaining > 0) {
                    broadcastCountdown(session, remaining, opening = true)
                    session.remainingSeconds = remaining - 1
                } else {
                    openDoors(session)
                }
            }
            DoorSessionPhase.CLOSING -> {
                val remaining = session.remainingSeconds
                if (remaining > 0) {
                    broadcastCountdown(session, remaining, opening = false)
                    session.remainingSeconds = remaining - 1
                } else {
                    closeDoors(session)
                    taskHandle.cancel()
                }
            }
            DoorSessionPhase.CLOSED -> taskHandle.cancel()
        }
    }

    private fun openDoors(session: RuntimeSession) {
        session.doors.forEach { door ->
            blockOps.openRegion(door.world, door.region)
        }
        session.state.startClosing()
        session.phase = DoorSessionPhase.CLOSING
        session.remainingSeconds = session.doors.first().closeSeconds

        val label = doorLabel(session.doors)
        worldAccess.playersIn(session.worldName).forEach { p ->
            messages.title(p.id, "$label 已开启", "请穿过门洞")
        }
        messages.soundBell(session.worldName)
        logger.info("[${session.worldName}] $label opened")
    }

    private fun closeDoors(session: RuntimeSession) {
        session.doors.forEach { door ->
            val snapshot = door.snapshotId?.let { snapshotStore.load(it) } ?: emptyMap()
            blockOps.closeRegion(door.world, door.region, snapshot, door.fallbackMaterial)
        }

        val positions = worldAccess.playersIn(session.worldName)
            .associate { it.id to it.position }
        val outcomes = session.state.close(positions)

        outcomes.forEach { outcome ->
            val player = worldAccess.player(outcome.playerId) ?: return@forEach
            when (outcome.decision) {
                DoorPassDecision.PASSED -> {
                    messages.chat(outcome.playerId, "你已通过 ${doorLabel(session.doors)}（实时检测）")
                    logger.info("[${session.worldName}] ${player.name} passed door via ${outcome.reason}")
                }
                DoorPassDecision.PASSED_FALLBACK -> {
                    messages.chat(outcome.playerId, "你已通过 ${doorLabel(session.doors)}（兜底检测）")
                    logger.warn("[${session.worldName}] ${player.name} passed via fallback: ${outcome.reason}")
                }
                DoorPassDecision.BEHIND -> {
                    messages.chat(outcome.playerId, "${doorLabel(session.doors)}已关闭，你未能通过")
                    startTransferCountdown(session, outcome.playerId)
                }
            }
        }

        session.phase = DoorSessionPhase.CLOSED
        activeSessions.remove(session.worldName, session)
        logger.info("[${session.worldName}] ${doorLabel(session.doors)} closed; outcomes=${outcomes.map { it.decision }}")
    }

    private fun startTransferCountdown(session: RuntimeSession, playerId: UUID) {
        transferTasks.remove(playerId)?.cancel()
        var remaining = 10
        val doorNumber = session.doors.firstNotNullOfOrNull { it.number }

        val handle = scheduler.globalTimer(1L, 20L) { taskHandle ->
            if (remaining > 0) {
                messages.title(playerId, "$remaining", "门已关闭，等待传送")
                remaining--
            } else {
                val target = resolveBehindRespawn(session.worldName, doorNumber)
                if (target != null) {
                    teleporter.teleport(
                        playerId = playerId,
                        worldName = target.world,
                        x = target.x,
                        y = target.y,
                        z = target.z,
                        yaw = target.yaw,
                        pitch = target.pitch,
                    )
                    messages.chat(playerId, "你被送回门后区域")
                } else {
                    messages.chat(playerId, "未配置门后重生点，无法传送")
                }
                transferTasks.remove(playerId)
                taskHandle.cancel()
            }
        }
        transferTasks[playerId] = handle
    }

    private fun resolveBehindRespawn(worldName: String, doorNumber: Int?): RespawnDefinition? {
        val respawns = arenaRepository.byWorld(worldName).flatMap { it.respawns }
        return respawns.firstOrNull { it.type == RespawnType.DOOR_PLAYER && it.doorNumber == doorNumber }
            ?: respawns.firstOrNull { it.type == RespawnType.WAIT }
    }

    private fun broadcastCountdown(session: RuntimeSession, remaining: Int, opening: Boolean) {
        val subtitle = if (opening) "${doorLabel(session.doors)}即将开启" else "${doorLabel(session.doors)}即将关闭"
        worldAccess.playersIn(session.worldName).forEach { p ->
            messages.title(p.id, "$remaining", subtitle)
        }
    }

    /** 返回本次移动是否记录了新的穿越。 */
    fun onPlayerMove(worldName: String, playerId: UUID, from: Vec3, to: Vec3): Boolean {
        val session = activeSessions[worldName] ?: return false
        if (session.phase != DoorSessionPhase.CLOSING) return false
        val outcome = session.state.onMove(playerId, from, to)
        if (outcome.newlyCrossed) {
            val playerName = worldAccess.player(playerId)?.name ?: playerId.toString()
            messages.actionBar(playerId, "你已通过 ${doorLabel(session.doors)}")
            logger.debug("door", "[$worldName] $playerName crossed ${outcome.crossedDoorId}")
        }
        return outcome.newlyCrossed
    }

    fun onPlayerTeleport(worldName: String, playerId: UUID) {
        activeSessions[worldName]?.state?.markTeleported(playerId)
    }

    fun activeSessionInfo(worldName: String): String? {
        val session = activeSessions[worldName] ?: return null
        return "${doorLabel(session.doors)} phase=${session.phase} remaining=${session.remainingSeconds}s"
    }

    fun cancelAllSessions() {
        activeSessions.values.forEach { session ->
            session.state.close(emptyMap())
            session.task?.cancel()
        }
        activeSessions.clear()
        transferTasks.values.forEach { it.cancel() }
        transferTasks.clear()
    }

    private fun doorLabel(doors: List<DoorDefinition>): String =
        doors.mapNotNull { it.number }.distinct().joinToString("/") { "$it 号门" }
}

data class TriggerResult(val success: Boolean, val message: String)
