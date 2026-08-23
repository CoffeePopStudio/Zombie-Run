package cn.oneachina.zombierun.v2.domain.weapon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WeaponDefinitionTest {

    @Test
    fun `random weapon picks from enabled only`() {
        val weapons = listOf(
            WeaponDefinition("a", "A", "A", WeaponCategory.GUN, 1.0, enabled = false),
            WeaponDefinition("b", "B", "B", WeaponCategory.MELEE, 2.0, enabled = true),
        )
        assertEquals("b", weapons.randomWeapon()?.id)
    }

    @Test
    fun `random weapon returns null when all disabled`() {
        val weapons = listOf(
            WeaponDefinition("a", "A", "A", WeaponCategory.GUN, 1.0, enabled = false),
        )
        assertNull(weapons.randomWeapon())
    }

    @Test
    fun `random gun filters by category`() {
        val weapons = listOf(
            WeaponDefinition("gun", "G", "G", WeaponCategory.GUN, 1.0),
            WeaponDefinition("melee", "M", "M", WeaponCategory.MELEE, 1.0),
        )
        assertEquals("gun", weapons.randomGun()?.id)
    }
}