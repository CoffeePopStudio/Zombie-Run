package cn.oneachina.zombierun.v2.application.event

import java.util.UUID

data class PlayerPassedDoorEvent(
    val worldName: String,
    val playerId: UUID,
    val doorNumbers: List<Int>,
)

data class GameStartedEvent(
    val worldName: String,
    val playerAssignments: Map<UUID, String>,
)

data class GameEndedEvent(
    val worldName: String,
    val winner: String,
    val winnerPlayerIds: Set<UUID> = emptySet(),
)

data class ZombieKilledEvent(
    val worldName: String,
    val killerId: UUID,
    val victimId: UUID,
    val victimTeam: String? = null,
)

data class InfectHumanEvent(
    val attackerId: UUID,
    val victimId: UUID,
)

/** 玩家对僵尸/母体造成伤害（用于 DEAL_DAMAGE 等任务计数）。 */
data class PlayerDamageDealtEvent(
    val attackerId: UUID,
    val victimId: UUID,
    val damage: Double,
)
