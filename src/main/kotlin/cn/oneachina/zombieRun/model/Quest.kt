package cn.oneachina.zombieRun.model

enum class QuestType {
    KILL_ZOMBIE,
    KILL_ALPHA,
    INFECT_HUMAN,
    PASS_DOOR,
    PLAY_GAME,
    HUMAN_WIN,
    SURVIVE_TIME,
    DEAL_DAMAGE
}

data class QuestDef(
    val id: String,
    val type: QuestType,
    val target: Int,
    val rewardXp: Int,
    val rewardCoins: Int,
    val desc: String
)

data class PlayerQuestProgress(
    val questId: String,
    val questDef: QuestDef,
    var progress: Int,
    var completed: Boolean = false
)
