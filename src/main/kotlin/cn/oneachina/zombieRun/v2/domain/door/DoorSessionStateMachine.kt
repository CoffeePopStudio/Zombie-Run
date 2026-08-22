package cn.oneachina.zombierun.v2.domain.door

import java.util.UUID

enum class DoorSessionPhase { OPENING, CLOSING, CLOSED }

enum class DoorPassDecision { PASSED, PASSED_FALLBACK, BEHIND }

data class DoorCloseOutcome(
    val playerId: UUID,
    val decision: DoorPassDecision,
    val reason: String,
)

data class DoorMoveOutcome(
    val newlyCrossed: Boolean,
    val crossedDoorId: String?,
)

/**
 * 一次开门的会话状态机（一组门共享一个会话）。
 *
 * 线程安全：Folia 下多个玩家移动事件可能来自不同 region 线程，
 * 所有可变状态都收敛在本类中，用单锁保护。
 */
class DoorSessionStateMachine(
    val sessionId: String,
    val doors: List<DoorDefinition>,
    initialPositions: Map<UUID, Vec3>,
) {
    private val lock = Any()
    private val initialSides = HashMap<UUID, List<Side>>()
    private val lastSides = HashMap<UUID, MutableList<Side>>()
    private val crossedPlayerIds = HashSet<UUID>()
    private val teleportedPlayerIds = HashSet<UUID>()

    @Volatile
    var phase: DoorSessionPhase = DoorSessionPhase.OPENING
        private set

    init {
        require(doors.isNotEmpty()) { "session requires at least one door" }
        initialPositions.forEach { (playerId, position) ->
            val sides = doors.map { it.portal.side(position) }
            initialSides[playerId] = sides
            lastSides[playerId] = sides.toMutableList()
        }
    }

    fun startClosing() {
        synchronized(lock) {
            phase = DoorSessionPhase.CLOSING
        }
    }

    fun markTeleported(playerId: UUID) {
        synchronized(lock) {
            teleportedPlayerIds.add(playerId)
        }
    }

    fun hasCrossed(playerId: UUID): Boolean = synchronized(lock) {
        playerId in crossedPlayerIds
    }

    /**
     * 玩家移动事件：维护每扇门的 BEHIND/FRONT 状态，并记录合法正向穿越。
     */
    fun onMove(playerId: UUID, from: Vec3, to: Vec3): DoorMoveOutcome {
        synchronized(lock) {
            if (phase != DoorSessionPhase.CLOSING) return DoorMoveOutcome(false, null)
            if (playerId in teleportedPlayerIds) return DoorMoveOutcome(false, null)

            val sides = lastSides[playerId] ?: doors.map { it.portal.side(from) }
            val newSides = ArrayList<Side>(doors.size)
            var newlyCrossed = false
            var crossedDoorId: String? = null

            doors.forEachIndexed { index, door ->
                val fromSide = door.portal.side(from)
                val toSide = door.portal.side(to)
                val previousSide = sides[index]

                if (playerId !in crossedPlayerIds &&
                    previousSide == Side.BEHIND &&
                    fromSide == Side.BEHIND &&
                    toSide == Side.FRONT &&
                    PortalCrossingDetector.crossing(from, to, door.portal) != null
                ) {
                    crossedPlayerIds.add(playerId)
                    newlyCrossed = true
                    crossedDoorId = door.id
                }
                newSides.add(toSide)
            }

            lastSides[playerId] = newSides
            return DoorMoveOutcome(newlyCrossed, crossedDoorId)
        }
    }

    /**
     * 关门判定：
     * 1. 记录过正向穿越 → PASSED
     * 2. 初始在门后侧、现在位于门前侧投影内、且本会话没有传送 → PASSED_FALLBACK
     * 3. 其余 → BEHIND
     */
    fun close(currentPositions: Map<UUID, Vec3>): List<DoorCloseOutcome> {
        synchronized(lock) {
            if (phase != DoorSessionPhase.CLOSED) phase = DoorSessionPhase.CLOSED

            val allPlayerIds = LinkedHashSet<UUID>()
            allPlayerIds.addAll(initialSides.keys)
            allPlayerIds.addAll(lastSides.keys)
            allPlayerIds.addAll(currentPositions.keys)
            allPlayerIds.addAll(crossedPlayerIds)

            return allPlayerIds.map { playerId ->
                when {
                    playerId in crossedPlayerIds ->
                        DoorCloseOutcome(playerId, DoorPassDecision.PASSED, "crossed")

                    playerId in teleportedPlayerIds ->
                        DoorCloseOutcome(playerId, DoorPassDecision.BEHIND, "teleported")

                    else -> {
                        val position = currentPositions[playerId]
                        val initial = initialSides[playerId]
                        val fallbackDoor = if (position != null && initial != null) {
                            doors.firstOrNull { door ->
                                initial[doors.indexOf(door)] == Side.BEHIND &&
                                    PortalCrossingDetector.isInFrontProjection(position, door.portal)
                            }
                        } else {
                            null
                        }
                        if (fallbackDoor != null) {
                            DoorCloseOutcome(playerId, DoorPassDecision.PASSED_FALLBACK, "fallback:${fallbackDoor.id}")
                        } else {
                            DoorCloseOutcome(playerId, DoorPassDecision.BEHIND, "behind")
                        }
                    }
                }
            }
        }
    }

    fun initialSideOf(playerId: UUID): List<Side> = synchronized(lock) {
        initialSides[playerId] ?: emptyList()
    }
}
