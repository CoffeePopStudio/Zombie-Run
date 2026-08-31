package cn.oneachina.zombieRun.gui

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.GameManager
import me.zombie_striker.qg.api.QualityArmory
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import kotlin.math.ceil

class ShopGUI(private val plugin: ZombieRun) : Listener {

    companion object {
        private const val GUI_TITLE = "枪械商店"
        private const val WEAPONS_PER_PAGE = 45
        private const val INVENTORY_SIZE = 54
        private const val BAR_INDEX = 45
    }

    private val shopKey = NamespacedKey("zombie-run", "shop_weapon")
    private val pageKey = NamespacedKey("zombie-run", "shop_page")

    fun open(player: Player, page: Int = 0) {
        val items = plugin.weaponManager.getAllItemIds()
        if (items.isEmpty()) {
            player.sendMessage(Component.text("当前没有可用枪械/道具。", NamedTextColor.RED))
            return
        }

        val pageCount = ceil(items.size / WEAPONS_PER_PAGE.toDouble()).toInt().coerceAtLeast(1)
        val currentPage = page.coerceIn(0, pageCount - 1)
        val start = currentPage * WEAPONS_PER_PAGE
        val end = minOf(start + WEAPONS_PER_PAGE, items.size)
        val pageItems = items.subList(start, end)

        val inv = Bukkit.createInventory(null, INVENTORY_SIZE, Component.text(GUI_TITLE).color(NamedTextColor.GRAY))

        pageItems.forEachIndexed { index, itemId ->
            val itemObj = plugin.weaponManager.getItem(itemId) ?: return@forEachIndexed
            val item = QualityArmory.getCustomItemAsItemStack(itemObj) ?: return@forEachIndexed
            val meta = item.itemMeta ?: return@forEachIndexed
            val lore = (meta.lore() ?: emptyList()).toMutableList()
            lore.add(Component.empty())
            lore.add(Component.text("价格: ", NamedTextColor.YELLOW)
                .append(Component.text("${plugin.weaponManager.getWeaponPrice(itemId).toInt()} 硬币", NamedTextColor.GOLD)))

            val gun = plugin.weaponManager.getGun(itemId)
            if (gun != null) {
                lore.add(Component.text("伤害: ${gun.damage.toInt()} | 弹匣: ${gun.maxBullets} | 弹药: ${gun.ammoType?.name ?: "无"}", NamedTextColor.GRAY))
            } else {
                lore.add(Component.text("道具", NamedTextColor.GRAY))
            }

            meta.lore(lore)
            meta.persistentDataContainer.set(shopKey, PersistentDataType.STRING, itemId)
            item.itemMeta = meta
            inv.setItem(index, item)
        }

        if (currentPage > 0) {
            val prev = ItemStack(Material.ARROW)
            val prevMeta = prev.itemMeta ?: return
            prevMeta.displayName(Component.text("上一页", NamedTextColor.YELLOW))
            prevMeta.persistentDataContainer.set(pageKey, PersistentDataType.INTEGER, currentPage - 1)
            prev.itemMeta = prevMeta
            inv.setItem(BAR_INDEX, prev)
        }

        val close = ItemStack(Material.BARRIER)
        val closeMeta = close.itemMeta ?: return
        closeMeta.displayName(Component.text("关闭商店", NamedTextColor.RED))
        close.itemMeta = closeMeta
        inv.setItem(BAR_INDEX + 4, close)

        if (currentPage < pageCount - 1) {
            val next = ItemStack(Material.ARROW)
            val nextMeta = next.itemMeta ?: return
            nextMeta.displayName(Component.text("下一页", NamedTextColor.YELLOW))
            nextMeta.persistentDataContainer.set(pageKey, PersistentDataType.INTEGER, currentPage + 1)
            next.itemMeta = nextMeta
            inv.setItem(BAR_INDEX + 8, next)
        }

        player.openInventory(inv)
    }

    fun onAutoOpen(player: Player) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
            if (player.isOnline &&
                plugin.gameManager.getGameStatus() == GameManager.GameStatus.WAITING) {
                open(player)
            }
        }, 20L)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val title = event.view.title()
        val plainTitle = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(title)

        if (plainTitle != GUI_TITLE) return

        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val clicked = event.currentItem ?: return

        val meta = clicked.itemMeta ?: return
        val pdc = meta.persistentDataContainer

        val page = pdc.get(pageKey, PersistentDataType.INTEGER)
        if (page != null) {
            open(player, page)
            return
        }

        val weaponId = pdc.get(shopKey, PersistentDataType.STRING)

        if (weaponId != null) {
            val weapons = plugin.miscManager.getSelectableWeapons()
            val weaponIndex = weapons.indexOf(weaponId) + 1
            if (weaponIndex <= 0) {
                player.sendMessage(Component.text("此武器暂不可选", NamedTextColor.RED))
                player.closeInventory()
                return
            }
            plugin.miscManager.setSelectedWeapon(player, weaponIndex)
            val price = plugin.weaponManager.getWeaponPrice(weaponId).toInt()
            player.sendMessage(Component.text(
                "已预购 $weaponId（$price 硬币），游戏开始自动发放。/zr unselect 可取消", NamedTextColor.GREEN
            ))
            player.closeInventory()
            return
        }

        if (clicked.type == Material.BARRIER) {
            player.closeInventory()
        }
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        val title = event.view.title()
        val plainTitle = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(title)

        if (plainTitle == GUI_TITLE) {
            event.isCancelled = true
        }
    }
}
