package cn.oneachina.zombierun.v2.domain.game

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameInstanceTest {

    private fun rules() = GameRules(minPlayers = 2, startDelaySeconds = 5, maxDurationSeconds = 60)

    private fun uuid(seed: Int): UUID = UUID(0L, seed.toLong())

    @Test
    fun `new game is waiting`() {
        assertEquals(GamePhase.WAITING, GameInstance("world", rules()).phaseSnapshot())
    }

    @Test
    fun `countdown transitions waiting to starting and can cancel`() {
        val game = GameInstance("world", rules())
        game.beginCountdown()
        assertEquals(GamePhase.STARTING, game.phaseSnapshot())
        game.cancelCountdown()
        assertEquals(GamePhase.WAITING, game.phaseSnapshot())
    }

    @Test
    fun `start assigns one alpha and the rest human`() {
        val game = GameInstance("world", rules())
        val ids = listOf(uuid(1), uuid(2), uuid(3), uuid(4))
        val assignments = game.start(ids, alphaIndex = 1)

        assertEquals(GamePhase.RUNNING, game.phaseSnapshot())
        assertEquals(GameTeam.ZOMBIE_MAIN, game.teamOf(ids[1]))
        assertEquals(GameTeam.HUMAN, game.teamOf(ids[0]))
        assertEquals(GameTeam.HUMAN, game.teamOf(ids[2]))
        assertEquals(3, game.humanIds().size)
        assertEquals(ids[1], game.alphaId())
        assertEquals(4, assignments.size)
    }

    @Test
    fun `midgame join becomes zombie`() {
        val game = GameInstance("world", rules())
        val ids = listOf(uuid(1), uuid(2))
        game.start(ids, alphaIndex = 0)
        val joiner = uuid(3)
        assertEquals(GameTeam.ZOMBIE, game.addPlayer(joiner))
        assertTrue(game.playerIds().contains(joiner))
    }

    @Test
    fun `infecting a human leaves them as ordinary zombie and game continues`() {
        val game = GameInstance("world", rules())
        val ids = listOf(uuid(1), uuid(2), uuid(3))
        game.start(ids, alphaIndex = 0)

        val result = game.infect(ids[1])
        assertEquals(1, result.remainingHumans)
        assertFalse(result.gameEnded)
        assertEquals(GameTeam.ZOMBIE, game.teamOf(ids[1]))
        assertEquals(GamePhase.RUNNING, game.phaseSnapshot())
    }

    @Test
    fun `ordinary zombie can be promoted when alpha leaves`() {
        val game = GameInstance("world", rules())
        val ids = listOf(uuid(1), uuid(2), uuid(3))
        game.start(ids, alphaIndex = 0)
        game.infect(ids[1])
        game.removePlayer(ids[0])

        assertNull(game.alphaId())
        assertTrue(game.promoteZombieToAlpha(ids[1]))
        assertEquals(GameTeam.ZOMBIE_MAIN, game.teamOf(ids[1]))
        assertEquals(ids[1], game.alphaId())
    }

    @Test
    fun `infecting last human ends game`() {
        val game = GameInstance("world", rules())
        val ids = listOf(uuid(1), uuid(2))
        game.start(ids, alphaIndex = 0)

        val result = game.infect(ids[1])
        assertEquals(0, result.remainingHumans)
        assertTrue(result.gameEnded)
        assertEquals(GamePhase.ENDED, game.phaseSnapshot())
        assertEquals(GameTeam.ZOMBIE, game.teamOf(ids[1]))
    }

    @Test
    fun `removing last human should be visible to caller but does not self-end domain state`() {
        val game = GameInstance("world", rules())
        val ids = listOf(uuid(1), uuid(2))
        game.start(ids, alphaIndex = 0)

        assertEquals(GameTeam.HUMAN, game.removePlayer(ids[1]))
        assertFalse(game.playerIds().contains(ids[1]))
    }

    @Test
    fun `spectate converts human to spectator and removes from humans`() {
        val game = GameInstance("world", rules())
        val ids = listOf(uuid(1), uuid(2))
        game.start(ids, alphaIndex = 0)

        assertTrue(game.spectate(ids[1]))
        assertEquals(GameTeam.SPECTATOR, game.teamOf(ids[1]))
        assertFalse(game.humanIds().contains(ids[1]))
    }

    @Test
    fun `room never decreases`() {
        val game = GameInstance("world", rules())
        val ids = listOf(uuid(1), uuid(2))
        game.start(ids, alphaIndex = 0)

        game.setRoom(ids[1], 3)
        game.setRoom(ids[1], 2)
        assertEquals(3, game.roomOf(ids[1]))
    }

    @Test
    fun `tryEnd is idempotent`() {
        val game = GameInstance("world", rules())
        val ids = listOf(uuid(1), uuid(2))
        game.start(ids, alphaIndex = 0)

        assertTrue(game.tryEnd(GameTeam.HUMAN))
        assertFalse(game.tryEnd(GameTeam.HUMAN))
        assertFalse(game.tryEnd(GameTeam.ZOMBIE_MAIN))
        assertEquals(GameTeam.HUMAN, game.lastWinner())
        assertEquals(GamePhase.ENDED, game.phaseSnapshot())
    }

    @Test
    fun `tryEnd allows settlement after infect already ended phase`() {
        val game = GameInstance("world", rules())
        val ids = listOf(uuid(1), uuid(2))
        game.start(ids, alphaIndex = 0)
        game.infect(ids[1])

        // infect 已把 phase 置为 ENDED，但首次结算仍应成功
        assertTrue(game.tryEnd(GameTeam.ZOMBIE_MAIN))
        assertFalse(game.tryEnd(GameTeam.ZOMBIE_MAIN))
    }

    @Test
    fun `end records winner`() {
        val game = GameInstance("world", rules())
        game.end(GameTeam.HUMAN)
        assertEquals(GamePhase.ENDED, game.phaseSnapshot())
        assertEquals(GameTeam.HUMAN, game.lastWinner())
    }

    @Test
    fun `unknown team is null`() {
        val game = GameInstance("world", rules())
        assertNull(game.teamOf(uuid(99)))
    }
}
