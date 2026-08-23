package cn.oneachina.zombierun.v2.domain.game

/**
 * 完整游戏流程（MapFlow）：一张地图从等待、开局、门推进到结算的状态机。
 * 纯 Kotlin/领域层，不依赖 Bukkit。
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
    private var phase: MapFlowPhase = MapFlowPhase.WAITING
    private var currentStage: MapFlowStage = definition.stages.first()
    private val passedStageIds = LinkedHashSet<String>()

    fun phaseSnapshot(): MapFlowPhase = phase

    fun currentStage(): MapFlowStage = currentStage

    fun currentDoorNumbers(): List<Int> = currentStage.doorNumbers

    fun passedStages(): Set<String> = passedStageIds.toSet()

    fun isFinished(): Boolean =
        phase == MapFlowPhase.HUMAN_WIN || phase == MapFlowPhase.ZOMBIE_WIN

    fun beginCountdown(): Boolean {
        if (phase != MapFlowPhase.WAITING) return false
        phase = MapFlowPhase.STARTING
        return true
    }

    fun start(): Boolean {
        if (phase != MapFlowPhase.STARTING) return false
        phase = MapFlowPhase.RUNNING
        return true
    }

    /** 玩家穿过当前阶段门。命中当前阶段则推进或结算。 */
    fun onDoorPassed(doorNumbers: Collection<Int>): MapFlowAdvanceResult {
        if (phase != MapFlowPhase.RUNNING) return MapFlowAdvanceResult.WRONG_DOOR
        if (currentStage.doorNumbers.none { it in doorNumbers }) return MapFlowAdvanceResult.WRONG_DOOR

        passedStageIds += currentStage.id
        val next = currentStage.nextStageId?.let { definition.stageById(it) }
        if (next == null) {
            phase = MapFlowPhase.HUMAN_WIN
            return MapFlowAdvanceResult.FINISHED
        }
        currentStage = next
        return MapFlowAdvanceResult.STAGE_ADVANCED
    }

    fun onAllHumansInfected(): Boolean {
        if (phase != MapFlowPhase.RUNNING) return false
        phase = MapFlowPhase.ZOMBIE_WIN
        return true
    }

    fun onTimeUp(): Boolean {
        if (phase != MapFlowPhase.RUNNING) return false
        phase = MapFlowPhase.HUMAN_WIN
        return true
    }

    fun onExtraction(): Boolean {
        if (phase != MapFlowPhase.RUNNING) return false
        phase = MapFlowPhase.HUMAN_WIN
        return true
    }

    fun reset() {
        phase = MapFlowPhase.WAITING
        currentStage = definition.stages.first()
        passedStageIds.clear()
    }
}