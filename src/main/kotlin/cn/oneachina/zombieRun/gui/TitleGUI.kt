package cn.oneachina.zombieRun.gui

import cn.oneachina.zombieRun.ZombieRun
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

        val inv = Bukkit.createInventory(null, size, Component.text(GUI_TITLE).color(NamedTextColor.DARK_GRAY))

        val currentTitle = plugin.titleManager.getPlayerTitle(player)

        val noneItem = ItemStack(Material.BARRIER)
        val noneMeta = noneItem.itemMeta
        if (currentTitle.isEmpty()) {
            noneMeta.displayName(Component.text("► 默认（已装备）", NamedTextColor.GREEN))
        } else {
            noneMeta.displayName(Component.text("默认", NamedTextColor.GRAY))
        }
        val defaultTitle = cn.oneachina.zombieRun.manager.TitleManager.getDefaultTitle(plugin.progressionManager.getLevel(player.uniqueId))
        noneMeta.lore(listOf(
            Component.text("使用等级默认称号", NamedTextColor.GRAY),
            Component.text("当前：${defaultTitle.ifEmpty { "无" }}", NamedTextColor.GRAY)
        ))
        noneMeta.persistentDataContainer.set(titleKey, PersistentDataType.STRING, "none")
        noneItem.itemMeta = noneMeta
        inv.setItem(0, noneItem)

        titles.forEachIndexed { index, (key, name) ->
            val material = if (name == currentTitle) Material.LIME_STAINED_GLASS_PANE else Material.WHITE_STAINED_GLASS_PANE
            val item = ItemStack(material)
            val meta = item.itemMeta
            val prefix = if (name == currentTitle) "► " else ""
            meta.displayName(Component.text("$prefix$name", NamedTextColor.WHITE))
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
        closeMeta.displayName(Component.text("关闭", NamedTextColor.RED))
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
                player.sendMessage(Component.text("已切换到默认称号", NamedTextColor.GREEN))
                player.closeInventory()
            }
            "select" -> {
                val titleName = meta.persistentDataContainer.get(equipKey, PersistentDataType.STRING)
                if (titleName != null && plugin.titleManager.equipTitle(player, titleName)) {
                    player.sendMessage(Component.text()
                        .append(Component.text("已装备称号：", NamedTextColor.GREEN))
                        .append(Component.text(titleName, NamedTextColor.YELLOW))
                        .build())
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
