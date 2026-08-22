package cn.oneachina.zombierun.v2.domain.game

import java.util.UUID

enum class GamePhase { WAITING, STARTING, RUNNING, ENDED }

enum class GameTeam { HUMAN, ZOMBIE, ZOMBIE_MAIN, SPECTATOR }

data class GameRules(
    val minPlayers: Int,
    val startDelaySeconds: Int,
    val maxDurationSeconds: Int,
    val manualStart: Boolean = false,
)

data class PlayerAssignment(
    val playerId: UUID,
    val team: GameTeam,
    val room: Int,
)

data class InfectResult(
    val infectedId: UUID,
    val remainingHumans: Int,
    val gameEnded: Boolean,
)

/**
 * 单世界对局聚合（纯 Kotlin，线程安全）。
 *
 * 只维护状态与规则，不直接操作 Bukkit；所有副作用由 GameFlowService 编排。
 */
class GameInstance(
    val worldName: String,
    val rules: GameRules,
) {
    private val lock = Any()

    @Volatile
    var phase: GamePhase = GamePhase.WAITING
        private set

    private val teams = LinkedHashMap<UUID, GameTeam>()
    private val rooms = LinkedHashMap<UUID, Int>()
    private var alphaId: UUID? = null

    fun phaseSnapshot(): GamePhase = phase

    fun teamOf(playerId: UUID): GameTeam? = synchronized(lock) {
        teams[playerId]
    }

    fun roomOf(playerId: UUID): Int = synchronized(lock) {
        rooms[playerId] ?: 0
    }

    fun playerIds(): Set<UUID> = synchronized(lock) {
        teams.keys.toSet()
    }

    fun humanIds(): Set<UUID> = synchronized(lock) {
        teams.filterValues { it == GameTeam.HUMAN }.keys.toSet()
    }

    fun zombieIds(): Set<UUID> = synchronized(lock) {
        teams.filterValues { it == GameTeam.ZOMBIE || it == GameTeam.ZOMBIE_MAIN }.keys.toSet()
    }

    fun alphaId(): UUID? = synchronized(lock) {
        alphaId
    }

    /** 母体离开后从僵尸/人类中补位，返回是否成功。 */
    fun promoteZombieToAlpha(playerId: UUID): Boolean = synchronized(lock) {
        if (teams[playerId] != GameTeam.ZOMBIE && teams[playerId] != GameTeam.HUMAN) return false
        teams[playerId] = GameTeam.ZOMBIE_MAIN
        alphaId = playerId
        true
    }

    fun addPlayer(playerId: UUID): GameTeam = synchronized(lock) {
        val team = when (phase) {
            GamePhase.RUNNING -> GameTeam.ZOMBIE
            else -> GameTeam.SPECTATOR
        }
        teams[playerId] = team
        rooms.putIfAbsent(playerId, 0)
        team
    }

    fun removePlayer(playerId: UUID): GameTeam? = synchronized(lock) {
        if (alphaId == playerId) alphaId = null
        rooms.remove(playerId)
        teams.remove(playerId)
    }

    fun beginCountdown() {
        synchronized(lock) {
            if (phase == GamePhase.WAITING) phase = GamePhase.STARTING
        }
    }

    fun cancelCountdown() {
        synchronized(lock) {
            if (phase == GamePhase.STARTING) phase = GamePhase.WAITING
        }
    }

    /**
     * 正式开局并分配队伍。
     * [playerIds] 是本次参与对局的玩家；[alphaIndex] 由调用方决定（可随机），便于测试。
     */
    fun start(playerIds: List<UUID>, alphaIndex: Int): List<PlayerAssignment> = synchronized(lock) {
        require(playerIds.isNotEmpty()) { "cannot start game without players" }
        require(alphaIndex in playerIds.indices) { "alphaIndex out of range" }

        teams.clear()
        rooms.clear()
        playerIds.forEachIndexed { index, id ->
            val team = if (index == alphaIndex) GameTeam.ZOMBIE_MAIN else GameTeam.HUMAN
            teams[id] = team
            rooms[id] = 0
        }
        alphaId = playerIds[alphaIndex]
        phase = GamePhase.RUNNING
        playerIds.map { PlayerAssignment(it, teams.getValue(it), rooms.getValue(it)) }
    }

    fun infect(humanId: UUID): InfectResult = synchronized(lock) {
        require(teams[humanId] == GameTeam.HUMAN) { "player is not human" }
        teams[humanId] = GameTeam.ZOMBIE
        val remaining = humanIds().size
        val ended = remaining == 0
        if (ended) {
            phase = GamePhase.ENDED
        }
        InfectResult(humanId, remaining, ended)
    }

    fun setRoom(playerId: UUID, room: Int): Boolean = synchronized(lock) {
        if (playerId !in teams) return false
        rooms[playerId] = maxOf(rooms[playerId] ?: 0, room)
        true
    }

    fun end(winner: GameTeam) {
        synchronized(lock) {
            phase = GamePhase.ENDED
            if (winner == GameTeam.HUMAN || winner == GameTeam.ZOMBIE || winner == GameTeam.ZOMBIE_MAIN) {
                // 记录胜者，未来接结算系统
                lastWinner = winner
            }
        }
    }

    fun lastWinner(): GameTeam? = synchronized(lock) {
        lastWinner
    }

    private var lastWinner: GameTeam? = null
}
