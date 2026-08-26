package cn.oneachina.zombierun.v2.infrastructure.bukkit.gui

import cn.oneachina.zombierun.v2.application.event.ApplicationEventBus
import cn.oneachina.zombierun.v2.application.player.PlayerDataService
import cn.oneachina.zombierun.v2.application.task.TaskService
import cn.oneachina.zombierun.v2.application.weapon.WeaponService
import cn.oneachina.zombierun.v2.domain.player.PlayerProfile
import cn.oneachina.zombierun.v2.domain.weapon.WeaponCategory
import cn.oneachina.zombierun.v2.domain.weapon.WeaponDefinition
import cn.oneachina.zombierun.v2.infrastructure.config.WeaponYamlRepository
import cn.oneachina.zombierun.v2.ports.PlayerDataPort
import cn.oneachina.zombierun.v2.ports.PlayerMessagePort
import cn.oneachina.zombierun.v2.ports.WeaponIntegrationPort
import cn.oneachina.zombierun.v2.support.V2Logger
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * GuiService 测试：通过注入 inventoryFactory 隔离 Bukkit.createInventory，
 * 覆盖商店购买、发枪失败退款、点击安全与关闭清理。
 */
class GuiServiceTest {

    private class FakeStorage : PlayerDataPort {
        val store = ConcurrentHashMap<UUID, PlayerProfile>()
        override fun load(playerId: UUID): PlayerProfile? = store[playerId]
        override fun save(profile: PlayerProfile) {
            store[profile.playerId] = profile
        }

        override fun close() = Unit
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

    private class FakeIntegration(private val giveOk: Boolean) : WeaponIntegrationPort {
        val given = mutableListOf<Pair<UUID, String>>()
        override fun giveWeapon(playerId: UUID, weaponType: String): Boolean {
            if (giveOk) given.add(playerId to weaponType)
            return giveOk
        }

        override fun removeWeapon(playerId: UUID, weaponType: String): Boolean = true
        override fun isAvailable(weaponType: String): Boolean = true
    }

    private class Fixture(weaponGiveOk: Boolean) {
        val temp = createTempDirectory("zr2-gui").toFile()
        val logger = V2Logger(Logger.getLogger("test"))
        val bus = ApplicationEventBus()
        val messages = FakeMessages()
        val storage = FakeStorage()
        val playerData = PlayerDataService(storage, messages, logger, bus)
        val integration = FakeIntegration(weaponGiveOk)
        val repo = WeaponYamlRepository(temp, logger)
        val weapons = WeaponService(repo, integration, FakeMessages(), logger)
        val holders = mutableListOf<InventoryHolder>()
        val inv: Inventory = mock()
        val gui = GuiService(
            playerData = playerData,
            weapons = weapons,
            tasks = mock<TaskService>(),
            logger = logger,
            inventoryFactory = { holder, _, _ ->
                holders.add(holder)
                inv
            },
            iconFactory = { _, _, _ -> mock<ItemStack>() },
        )

        init {
            weapons.add(WeaponDefinition("ak", "AK-47", "AK47", WeaponCategory.GUN, 100.0))
        }

        fun openShopFor(id: UUID): Player {
            val player = mock<Player>()
            whenever(player.uniqueId).thenReturn(id)
            gui.openShop(player)
            whenever(inv.holder).thenReturn(holders.last())
            return player
        }

        fun click(player: Player, slot: Int = 0): InventoryClickEvent {
            val click = mock<InventoryClickEvent>()
            whenever(click.whoClicked).thenReturn(player)
            whenever(click.inventory).thenReturn(inv)
            whenever(click.slot).thenReturn(slot)
            return click
        }
    }

    @Test
    fun `shop purchase deducts coins and hands out weapon`() {
        val f = Fixture(weaponGiveOk = true)
        val id = UUID.randomUUID()
        f.playerData.addCoins(id, 300)
        val player = f.openShopFor(id)

        f.gui.onClick(f.click(player))

        assertEquals(200, f.playerData.profileOf(id).coins)
        assertEquals(listOf("AK47"), f.integration.given.map { it.second })
    }

    @Test
    fun `shop purchase refunds coins when weapon handout fails`() {
        val f = Fixture(weaponGiveOk = false)
        val id = UUID.randomUUID()
        f.playerData.addCoins(id, 300)
        val player = f.openShopFor(id)

        f.gui.onClick(f.click(player))

        // 发枪失败必须回滚扣款
        assertEquals(300, f.playerData.profileOf(id).coins)
        assertEquals(emptyList(), f.integration.given)
    }

    @Test
    fun `shop purchase fails with insufficient coins and never hands out weapon`() {
        val f = Fixture(weaponGiveOk = true)
        val id = UUID.randomUUID()
        // 初始 0 硬币，不充值
        val player = f.openShopFor(id)

        f.gui.onClick(f.click(player))

        assertEquals(0, f.playerData.profileOf(id).coins)
        assertEquals(emptyList(), f.integration.given)
    }

    @Test
    fun `closing the menu removes click actions`() {
        val f = Fixture(weaponGiveOk = true)
        val id = UUID.randomUUID()
        f.playerData.addCoins(id, 300)
        val player = f.openShopFor(id)

        val close = mock<InventoryCloseEvent>()
        whenever(close.inventory).thenReturn(f.inv)
        whenever(close.player).thenReturn(player)
        f.gui.onClose(close)

        // 关闭后再点击不应再扣款
        f.gui.onClick(f.click(player))
        assertEquals(300, f.playerData.profileOf(id).coins)
    }

    @Test
    fun `clicks on foreign inventories are ignored`() {
        val f = Fixture(weaponGiveOk = true)
        val id = UUID.randomUUID()
        f.playerData.addCoins(id, 300)
        val player = f.openShopFor(id)

        val foreignInv = mock<Inventory>()
        whenever(foreignInv.holder).thenReturn(null)
        val click = mock<InventoryClickEvent>()
        whenever(click.whoClicked).thenReturn(player)
        whenever(click.inventory).thenReturn(foreignInv)
        whenever(click.slot).thenReturn(0)

        f.gui.onClick(click)
        assertEquals(300, f.playerData.profileOf(id).coins)
    }
}
