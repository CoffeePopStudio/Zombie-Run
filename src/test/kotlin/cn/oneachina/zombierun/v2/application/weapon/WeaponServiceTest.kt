package cn.oneachina.zombierun.v2.application.weapon

import cn.oneachina.zombierun.v2.domain.weapon.WeaponCategory
import cn.oneachina.zombierun.v2.domain.weapon.WeaponDefinition
import cn.oneachina.zombierun.v2.infrastructure.config.WeaponYamlRepository
import cn.oneachina.zombierun.v2.ports.PlayerMessagePort
import cn.oneachina.zombierun.v2.ports.WeaponIntegrationPort
import cn.oneachina.zombierun.v2.support.V2Logger
import java.util.UUID
import java.util.logging.Logger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeaponServiceTest {

    private val logger = V2Logger(Logger.getLogger("test"))

    private class FakeIntegration : WeaponIntegrationPort {
        val given = mutableListOf<Pair<UUID, String>>()
        val refills = mutableListOf<Pair<UUID, Int>>()
        override fun giveWeapon(playerId: UUID, weaponType: String): Boolean {
            given.add(playerId to weaponType)
            return true
        }
        override fun removeWeapon(playerId: UUID, weaponType: String): Boolean = true
        override fun isAvailable(weaponType: String): Boolean = true
        override fun refillAmmo(playerId: UUID, weaponType: String, magazines: Int): Boolean {
            refills.add(playerId to magazines)
            return true
        }
        override fun holdsWeapon(playerId: UUID, weaponType: String): Boolean = true
    }

    private class FakeMessages : PlayerMessagePort {
        val chats = mutableListOf<String>()
        override fun actionBar(playerId: UUID, message: String) = Unit
        override fun chat(playerId: UUID, message: String) {
            chats.add(message)
        }
        override fun title(playerId: UUID, title: String, subtitle: String) = Unit
        override fun soundBell(worldName: String) = Unit
    }

    @Test
    fun `add and give weapon works`() {
        val temp = createTempDirectory("zr2-weapon").toFile()
        val repo = WeaponYamlRepository(temp, logger)
        val integration = FakeIntegration()
        val messages = FakeMessages()
        val service = WeaponService(repo, integration, messages, logger)

        val id = UUID.randomUUID()
        service.add(WeaponDefinition("ak", "AK", "AK47", WeaponCategory.GUN, 100.0))
        assertTrue(service.giveWeapon(id, "ak"))
        assertEquals("AK47", integration.given.single().second)
    }

    @Test
    fun `random gives only enabled weapon`() {
        val temp = createTempDirectory("zr2-weapon2").toFile()
        val repo = WeaponYamlRepository(temp, logger)
        val integration = FakeIntegration()
        val messages = FakeMessages()
        val service = WeaponService(repo, integration, messages, logger)

        service.add(WeaponDefinition("a", "A", "A", WeaponCategory.GUN, 1.0, enabled = false))
        service.add(WeaponDefinition("b", "B", "B", WeaponCategory.MELEE, 2.0))
        val weapon = service.giveRandom(UUID.randomUUID(), null)
        assertEquals("b", weapon?.id)
    }

    @Test
    fun `remove missing weapon returns false`() {
        val temp = createTempDirectory("zr2-weapon3").toFile()
        val repo = WeaponYamlRepository(temp, logger)
        val service = WeaponService(repo, FakeIntegration(), FakeMessages(), logger)
        assertFalse(service.remove("nothing"))
    }

    @Test
    fun `giveStarter hands out weapon and refills ammo without charge`() {
        val temp = createTempDirectory("zr2-weapon4").toFile()
        val repo = WeaponYamlRepository(temp, logger)
        val integration = FakeIntegration()
        val messages = FakeMessages()
        val service = WeaponService(repo, integration, messages, logger)

        val id = UUID.randomUUID()
        service.add(WeaponDefinition("ak", "AK", "AK47", WeaponCategory.GUN, 100.0))
        assertTrue(service.giveStarter(id, "ak"))
        assertEquals("AK47", integration.given.single().second)
        assertEquals(1, integration.refills.size)
        assertEquals(WeaponService.STARTER_MAGAZINES, integration.refills.single().second)
        // 开局发放不发购买消息
        assertTrue(messages.chats.isEmpty())
    }

    @Test
    fun `giveStarter fails for unknown weapon`() {
        val temp = createTempDirectory("zr2-weapon5").toFile()
        val repo = WeaponYamlRepository(temp, logger)
        val integration = FakeIntegration()
        val service = WeaponService(repo, integration, FakeMessages(), logger)
        assertFalse(service.giveStarter(UUID.randomUUID(), "missing"))
        assertEquals(emptyList(), integration.given)
    }

    @Test
    fun `refillAmmo delegates to integration with weapon type`() {
        val temp = createTempDirectory("zr2-weapon6").toFile()
        val repo = WeaponYamlRepository(temp, logger)
        val integration = FakeIntegration()
        val service = WeaponService(repo, integration, FakeMessages(), logger)
        service.add(WeaponDefinition("ak", "AK", "AK47", WeaponCategory.GUN, 100.0))
        assertTrue(service.refillAmmo(UUID.randomUUID(), "ak", 2))
        assertEquals(listOf<UUID>(integration.refills.single().first), integration.refills.map { it.first })
        assertEquals(2, integration.refills.single().second)
    }
}