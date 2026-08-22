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
)
