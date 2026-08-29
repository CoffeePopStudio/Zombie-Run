package cn.oneachina.zombierun.v2.domain.game

/**
 * 完整游戏流程（MapFlow）：一张地图从等待、开局、门推进到结算的状态机。
 * 纯 Kotlin/领域层，不依赖 Bukkit。
 *
 * 该类可能被门事件、战斗感染、全局定时任务从不同线程调用，
 * 因此所有可变状态读写都通过 [lock] 同步。
 */
enum class MapFlowPhase { WAITING, STARTING, RUNNING, HUMAN_WIN, ZOMBIE_WIN }

enum class FinishType { DOOR, EXTRACTION }

data class MapFlowStage(
    val id: String,
    val label: String,
    val doorNumbers: List<Int>,
    val nextStageId: String? = null,
) {
    init {
        require(id.isNotBlank()) { "stage id must not be blank" }
        require(doorNumbers.isNotEmpty()) { "stage $id must have doorNumbers" }
    }
}

data class MapFlowFinish(
    val type: FinishType,
    val doorNumber: Int? = null,
) {
    init {
        if (type == FinishType.DOOR) {
            require(doorNumber != null) { "DOOR finish requires doorNumber" }
        }
    }
}

data class MapFlowDefinition(
    val arenaName: String,
    val world: String,
    val minPlayers: Int,
    val startDelaySeconds: Int,
    val maxDurationSeconds: Int,
    val stages: List<MapFlowStage>,
    val finish: MapFlowFinish,
    val rewardCoinsHuman: Int = 0,
    val rewardXpHuman: Int = 0,
    val rewardCoinsZombie: Int = 0,
    val rewardXpZombie: Int = 0,
    val starterWeaponId: String? = null,
    val motherReleaseDelaySeconds: Int = 10,
) {
    init {
        require(stages.isNotEmpty()) { "map flow must have at least one stage" }
        require(minPlayers > 0) { "minPlayers must be positive" }
        require(maxDurationSeconds > 0) { "maxDurationSeconds must be positive" }
        val ids = stages.map { it.id }
        require(ids.distinct().size == ids.size) { "map flow stage ids must be unique" }
        require(stages.all { it.nextStageId == null || it.nextStageId in ids }) {
            "map flow next-stage-id must reference an existing stage"
        }
    }

    fun stageById(id: String): MapFlowStage? = stages.firstOrNull { it.id == id }
}

enum class MapFlowAdvanceResult {
    WRONG_DOOR,
    STAGE_ADVANCED,
    FINISHED,
}

class MapFlowStateMachine(
    val definition: MapFlowDefinition,
) {
    private val lock = Any()
    private var phase: MapFlowPhase = MapFlowPhase.WAITING
    private var currentStage: MapFlowStage = definition.stages.first()
    private val passedStageIds = LinkedHashSet<String>()

    fun phaseSnapshot(): MapFlowPhase = synchronized(lock) { phase }

    fun currentStage(): MapFlowStage = synchronized(lock) { currentStage }

    fun currentDoorNumbers(): List<Int> = synchronized(lock) { currentStage.doorNumbers }

    fun passedStages(): Set<String> = synchronized(lock) { passedStageIds.toSet() }

    fun isFinished(): Boolean = synchronized(lock) {
        phase == MapFlowPhase.HUMAN_WIN || phase == MapFlowPhase.ZOMBIE_WIN
    }

    fun beginCountdown(): Boolean = synchronized(lock) {
        if (phase != MapFlowPhase.WAITING) return false
        phase = MapFlowPhase.STARTING
        true
    }

    fun start(): Boolean = synchronized(lock) {
        if (phase != MapFlowPhase.STARTING) return false
        phase = MapFlowPhase.RUNNING
        true
    }

    /** 玩家穿过当前阶段门。命中当前阶段则推进或结算。 */
    fun onDoorPassed(doorNumbers: Collection<Int>): MapFlowAdvanceResult = synchronized(lock) {
        if (phase != MapFlowPhase.RUNNING) return MapFlowAdvanceResult.WRONG_DOOR
        if (currentStage.doorNumbers.none { it in doorNumbers }) return MapFlowAdvanceResult.WRONG_DOOR

        passedStageIds += currentStage.id
        val next = currentStage.nextStageId?.let { definition.stageById(it) }
        if (next == null) {
            phase = MapFlowPhase.HUMAN_WIN
            return MapFlowAdvanceResult.FINISHED
        }
        currentStage = next
        MapFlowAdvanceResult.STAGE_ADVANCED
    }

    fun onAllHumansInfected(): Boolean = synchronized(lock) {
        if (phase != MapFlowPhase.RUNNING) return false
        phase = MapFlowPhase.ZOMBIE_WIN
        true
    }

    fun onTimeUp(): Boolean = synchronized(lock) {
        if (phase != MapFlowPhase.RUNNING) return false
        phase = MapFlowPhase.HUMAN_WIN
        true
    }

    fun onExtraction(): Boolean = synchronized(lock) {
        if (phase != MapFlowPhase.RUNNING) return false
        phase = MapFlowPhase.HUMAN_WIN
        true
    }

    fun reset() {
        synchronized(lock) {
            phase = MapFlowPhase.WAITING
            currentStage = definition.stages.first()
            passedStageIds.clear()
        }
    }
}
