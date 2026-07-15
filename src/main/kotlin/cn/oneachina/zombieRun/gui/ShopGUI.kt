package cn.oneachina.zombieRun.gui

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.GameManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
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
    }

    private val shopKey = NamespacedKey("zombie-run", "shop_weapon")

    fun open(player: Player) {
        val weapons = plugin.weaponManager.getAllWeaponConfigs().toList()
        val rows = ceil(weapons.size / 9.0).toInt().coerceAtLeast(1)
        val totalRows = rows + 1
        val size = totalRows * 9

        val inv = Bukkit.createInventory(null, size, Component.text(GUI_TITLE).color(NamedTextColor.GRAY))

        weapons.forEachIndexed { index, config ->
            val material = Material.matchMaterial(config.material) ?: Material.WOODEN_HOE
            val item = ItemStack(material)
            val meta = item.itemMeta ?: return@forEachIndexed
            val customModelDataComponent = meta.customModelDataComponent
            val serializer = LegacyComponentSerializer.legacySection()
            meta.displayName(serializer.deserialize(config.name.replace("&", "§")))
            val lore : MutableList<Component> = config.lore
                .map { serializer.deserialize(it.replace("&", "§")) }
                .toMutableList()
            lore.add(Component.empty())
            lore.add(Component.text("价格: ", NamedTextColor.YELLOW)
                .append(Component.text("${config.price} 硬币", NamedTextColor.GOLD)))
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§7伤害: ${config.damage} | 弹匣: ${config.magazineSize} | 弹药: ${config.ammoCategory}"))

            meta.lore(lore)
            if (!config.customModelData.floats().isEmpty()) {
                customModelDataComponent.floats = config.customModelData.floats()
                meta.setCustomModelDataComponent(customModelDataComponent)
            }
            meta.persistentDataContainer.set(shopKey, PersistentDataType.STRING, config.id)
            item.itemMeta = meta
            inv.setItem(index, item)
        }

        val barIndex = rows * 9
        val close = ItemStack(Material.BARRIER)
        val closeMeta = close.itemMeta ?: return
        closeMeta.displayName(Component.text("关闭商店", NamedTextColor.RED))
        close.itemMeta = closeMeta
        inv.setItem(barIndex + 4, close)

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
            val config = plugin.weaponManager.getWeaponConfig(weaponId)
            val price = config?.price ?: 0
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(
                "§a已预购 ${weaponId}（${price} 硬币），游戏开始自动发放。§7/zr unselect 可取消"
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
