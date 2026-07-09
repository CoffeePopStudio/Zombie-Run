package cn.oneachina.zombieRun.gui

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.ProgressionManager
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

class ProfileGUI(private val plugin: ZombieRun) : Listener {

    companion object {
        private const val GUI_TITLE = "玩家档案"
    }

    private val profileKey = NamespacedKey("zombie-run", "profile_gui")

    fun open(player: Player, targetPlayer: Player? = null) {
        val target = targetPlayer ?: player
        val profile = plugin.progressionManager.getProfile(target.uniqueId)
        val title = plugin.titleManager.getPlayerTitle(target)

        val inv = Bukkit.createInventory(null, 27, LegacyComponentSerializer.legacySection().deserialize("§8$GUI_TITLE"))

        val head = ItemStack(Material.PLAYER_HEAD)
        val headMeta = head.itemMeta
        headMeta.displayName(LegacyComponentSerializer.legacySection().deserialize("§e${target.name}"))
        headMeta.lore(listOf(
            LegacyComponentSerializer.legacySection().deserialize("§7称号：${title.ifEmpty { "无" }}"),
            LegacyComponentSerializer.legacySection().deserialize("§b等级：${profile.level}"),
            LegacyComponentSerializer.legacySection().deserialize("§aXP：${profile.xp} / ${ProgressionManager.xpForLevel(profile.level)}"),
            Component.empty(),
            LegacyComponentSerializer.legacySection().deserialize("§7总击杀：${profile.totalKills}"),
            LegacyComponentSerializer.legacySection().deserialize("§7总感染：${profile.totalInfections}"),
            LegacyComponentSerializer.legacySection().deserialize("§7参与局数：${profile.gamesPlayed}"),
            LegacyComponentSerializer.legacySection().deserialize("§7人类获胜：${profile.humanWins}")
        ))
        headMeta.setEnchantmentGlintOverride(false)
        head.itemMeta = headMeta
        inv.setItem(4, head)

        val unlocks = plugin.progressionManager.getUnlocks(target.uniqueId)
        val unlockItems = listOf(
            Pair("title_survivor", listOf("称号：幸存者", "Lv.5")),
            Pair("title_escape_expert", listOf("称号：逃生专家", "Lv.10")),
            Pair("golden_pistol", listOf("金色手枪皮肤", "Lv.15")),
            Pair("title_elite_agent", listOf("称号：精英特工", "Lv.20")),
            Pair("title_legend", listOf("称号：传奇", "Lv.30")),
            Pair("golden_rifle", listOf("金色步枪皮肤", "Lv.50"))
        )
        unlockItems.forEachIndexed { index, (id, desc) ->
            val unlocked = id in unlocks
            val material = if (unlocked) Material.LIME_STAINED_GLASS_PANE else Material.GRAY_STAINED_GLASS_PANE
            val item = ItemStack(material)
            val meta = item.itemMeta
            meta.displayName(
                LegacyComponentSerializer.legacySection().deserialize(
                    if (unlocked) "§a${desc[0]}" else "§7🔒 ${desc[0]}"
                )
            )
            meta.lore(listOf(
                LegacyComponentSerializer.legacySection().deserialize(
                    if (unlocked) "§a已解锁" else "§7需要 ${desc[1]}"
                )
            ))
            meta.persistentDataContainer.set(profileKey, PersistentDataType.STRING, "unlock")
            item.itemMeta = meta
            inv.setItem(10 + index, item)
        }

        val close = ItemStack(Material.BARRIER)
        val closeMeta = close.itemMeta
        closeMeta.displayName(LegacyComponentSerializer.legacySection().deserialize("§c关闭"))
        closeMeta.persistentDataContainer.set(profileKey, PersistentDataType.STRING, "close")
        close.itemMeta = closeMeta
        inv.setItem(22, close)

        player.openInventory(inv)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val rawTitle = event.view.title()
        val titleStr = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(rawTitle)
        if (titleStr != GUI_TITLE) return

        event.isCancelled = true
        val clicked = event.currentItem ?: return
        val meta = clicked.itemMeta ?: return
        val action = meta.persistentDataContainer.get(profileKey, PersistentDataType.STRING)
        if (action == "close") {
            (event.whoClicked as? Player)?.closeInventory()
        }
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        val rawTitle = event.view.title()
        val titleStr = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(rawTitle)
        if (titleStr == GUI_TITLE) event.isCancelled = true
    }
}
