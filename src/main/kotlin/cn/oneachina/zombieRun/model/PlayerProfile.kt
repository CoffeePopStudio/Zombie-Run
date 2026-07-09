package cn.oneachina.zombieRun.model

import java.util.UUID

data class PlayerProfile(
    val uuid: UUID,
    var level: Int = 1,
    var xp: Int = 0,
    var totalKills: Int = 0,
    var totalInfections: Int = 0,
    var gamesPlayed: Int = 0,
    var humanWins: Int = 0,
    var equippedTitle: String? = null
)
