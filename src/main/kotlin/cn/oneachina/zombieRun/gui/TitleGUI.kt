package cn.oneachina.zombieRun.gui

import cn.oneachina.zombieRun.ZombieRun
import net.kyori.adventure.text.Component
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

class TitleGUI(private val plugin: ZombieRun) : Listener {

    companion object {
        private const val GUI_TITLE = "称号选择"
    }

    private val titleKey = NamespacedKey("zombie-run", "title_gui")
    private val equipKey = NamespacedKey("zombie-run", "title_equip")

    fun open(player: Player) {
        val titles = plugin.titleManager.getAvailableTitles(player)
        val rows = maxOf(1, (titles.size + 1) / 9 + 1)
        val size = rows * 9

        val inv = Bukkit.createInventory(null, size, LegacyComponentSerializer.legacySection().deserialize("§8$GUI_TITLE"))

        val currentTitle = plugin.titleManager.getPlayerTitle(player)

        val noneItem = ItemStack(Material.BARRIER)
        val noneMeta = noneItem.itemMeta
        if (currentTitle.isEmpty()) {
            noneMeta.displayName(LegacyComponentSerializer.legacySection().deserialize("§a► 默认（已装备）"))
        } else {
            noneMeta.displayName(LegacyComponentSerializer.legacySection().deserialize("§7默认"))
        }
        noneMeta.lore(listOf(
            LegacyComponentSerializer.legacySection().deserialize("§7使用等级默认称号"),
            LegacyComponentSerializer.legacySection().deserialize("§7当前：${cn.oneachina.zombieRun.manager.TitleManager.getDefaultTitle(plugin.progressionManager.getLevel(player.uniqueId)).ifEmpty { "无" }}")
        ))
        noneMeta.persistentDataContainer.set(titleKey, PersistentDataType.STRING, "none")
        noneItem.itemMeta = noneMeta
        inv.setItem(0, noneItem)

        titles.forEachIndexed { index, (key, name) ->
            val material = if (name == currentTitle) Material.LIME_STAINED_GLASS_PANE else Material.WHITE_STAINED_GLASS_PANE
            val item = ItemStack(material)
            val meta = item.itemMeta
            val prefix = if (name == currentTitle) "§a► " else ""
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize("${prefix}§f$name"))
            meta.persistentDataContainer.set(titleKey, PersistentDataType.STRING, "select")
            meta.persistentDataContainer.set(equipKey, PersistentDataType.STRING, name)
            if (name == currentTitle) {
                meta.setEnchantmentGlintOverride(true)
            }
            item.itemMeta = meta
            inv.setItem(1 + index, item)
        }

        val close = ItemStack(Material.BARRIER)
        val closeMeta = close.itemMeta
        closeMeta.displayName(LegacyComponentSerializer.legacySection().deserialize("§c关闭"))
        closeMeta.persistentDataContainer.set(titleKey, PersistentDataType.STRING, "close")
        close.itemMeta = closeMeta
        inv.setItem(size - 5, close)

        player.openInventory(inv)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val rawTitle = event.view.title()
        val titleStr = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(rawTitle)
        if (titleStr != GUI_TITLE) return

        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val clicked = event.currentItem ?: return
        val meta = clicked.itemMeta ?: return

        val action = meta.persistentDataContainer.get(titleKey, PersistentDataType.STRING)
        when (action) {
            "none" -> {
                plugin.titleManager.equipTitle(player, null)
                player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a已切换到默认称号"))
                player.closeInventory()
            }
            "select" -> {
                val titleName = meta.persistentDataContainer.get(equipKey, PersistentDataType.STRING)
                if (titleName != null && plugin.titleManager.equipTitle(player, titleName)) {
                    player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a已装备称号：§e$titleName"))
                    player.closeInventory()
                }
            }
            "close" -> player.closeInventory()
        }
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        val rawTitle = event.view.title()
        val titleStr = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(rawTitle)
        if (titleStr == GUI_TITLE) event.isCancelled = true
    }
}
