package cn.oneachina.zombierun.v2.domain.task

enum class TaskType {
    DOOR_PASSES,
    ZOMBIE_KILLS,
    KILL_ALPHA,
    INFECT_HUMAN,
    PLAY_GAME,
    HUMAN_WIN,
    SURVIVE_TIME,
    DEAL_DAMAGE,
}

enum class TaskPeriod { DAILY, WEEKLY }

data class TaskDefinition(
    val id: String,
    val description: String,
    val type: TaskType,
    val target: Int,
    val rewardCoins: Int,
    val rewardXp: Int,
    val period: TaskPeriod,
) {
    init {
        require(id.isNotBlank()) { "task id must not be blank" }
        require(target > 0) { "task $id target must be positive" }
    }
}

data class TaskProgress(
    val taskId: String,
    var progress: Int = 0,
    var claimed: Boolean = false,
    var lastReset: String? = null,
)