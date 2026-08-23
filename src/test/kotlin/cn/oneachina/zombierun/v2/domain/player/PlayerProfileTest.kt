package cn.oneachina.zombierun.v2.domain.player

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerProfileTest {

    private val id = UUID.randomUUID()

    @Test
    fun `coins add and spend`() {
        val p = PlayerProfile(id).addCoins(100)
        assertEquals(100, p.coins)
        assertEquals(80, p.spendCoins(20)?.coins)
        assertNull(p.spendCoins(101))
    }

    @Test
    fun `xp levels up`() {
        val p = PlayerProfile(id, xp = 0, level = 1)
        val leveled = p.addXp(250)
        assertEquals(2, leveled.level)
        assertEquals(150, leveled.xp)
    }

    @Test
    fun `title set and clear`() {
        val p = PlayerProfile(id).setTitle("老兵")
        assertEquals("老兵", p.title)
        assertNull(p.setTitle(null).title)
    }

    @Test
    fun `kill and door counters increment`() {
        val p = PlayerProfile(id).addKill().addDoorPass().addKill()
        assertEquals(2, p.zombieKills)
        assertEquals(1, p.doorPasses)
    }
}