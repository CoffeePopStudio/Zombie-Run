package cn.oneachina.zombieRun.gui

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.PlayerQuestProgress
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

class QuestGUI(private val plugin: ZombieRun) : Listener {

    companion object {
        private const val GUI_TITLE = "任务面板"
    }

    private val questKey = NamespacedKey("zombie-run", "quest_gui")

    fun open(player: Player) {
        plugin.questManager.ensureQuests(player)
        val daily = plugin.questManager.getDailyQuests(player.uniqueId)
        val weekly = plugin.questManager.getWeeklyQuests(player.uniqueId)
        val totalSize = daily.size + weekly.size + 9
        val rows = ((totalSize / 9) + 1).coerceAtMost(6)
        val size = rows * 9

        val inv = Bukkit.createInventory(null, size, LegacyComponentSerializer.legacySection().deserialize("§8$GUI_TITLE"))

        val dailyLabel = ItemStack(Material.CLOCK)
        val dMeta = dailyLabel.itemMeta
        dMeta.displayName(Component.text("每日任务", NamedTextColor.YELLOW))
        dMeta.persistentDataContainer.set(questKey, PersistentDataType.STRING, "label")
        dailyLabel.itemMeta = dMeta
        inv.setItem(0, dailyLabel)

        daily.forEachIndexed { index, quest ->
            inv.setItem(1 + index, buildQuestItem(quest))
        }

        val weeklyStart = 1 + daily.size + 1
        val weeklyLabel = ItemStack(Material.WRITABLE_BOOK)
        val wMeta = weeklyLabel.itemMeta
        wMeta.displayName(Component.text("每周挑战", NamedTextColor.AQUA))
        wMeta.persistentDataContainer.set(questKey, PersistentDataType.STRING, "label")
        weeklyLabel.itemMeta = wMeta
        inv.setItem(weeklyStart - 1, weeklyLabel)

        weekly.forEachIndexed { index, quest ->
            inv.setItem(weeklyStart + index, buildQuestItem(quest))
        }

        val close = ItemStack(Material.BARRIER)
        val closeMeta = close.itemMeta
        closeMeta.displayName(LegacyComponentSerializer.legacySection().deserialize("§c关闭"))
        closeMeta.persistentDataContainer.set(questKey, PersistentDataType.STRING, "close")
        close.itemMeta = closeMeta
        inv.setItem(size - 5, close)

        player.openInventory(inv)
    }

    private fun buildQuestItem(quest: PlayerQuestProgress): ItemStack {
        val def = quest.questDef
        val material = when (def.type) {
            cn.oneachina.zombieRun.model.QuestType.KILL_ZOMBIE,
            cn.oneachina.zombieRun.model.QuestType.KILL_ALPHA -> Material.IRON_SWORD
            cn.oneachina.zombieRun.model.QuestType.INFECT_HUMAN -> Material.ROTTEN_FLESH
            cn.oneachina.zombieRun.model.QuestType.PASS_DOOR -> Material.OAK_DOOR
            cn.oneachina.zombieRun.model.QuestType.PLAY_GAME -> Material.PAPER
            cn.oneachina.zombieRun.model.QuestType.HUMAN_WIN -> Material.GOLDEN_APPLE
            cn.oneachina.zombieRun.model.QuestType.SURVIVE_TIME -> Material.CLOCK
            cn.oneachina.zombieRun.model.QuestType.DEAL_DAMAGE -> Material.BOW
        }
        val item = ItemStack(material)
        val meta = item.itemMeta

        val doneMark = if (quest.progress >= def.target) "§a✅" else ""
        meta.displayName(LegacyComponentSerializer.legacySection().deserialize("$doneMark §f${def.desc}"))

        val lore = mutableListOf<Component>()
        lore.add(LegacyComponentSerializer.legacySection().deserialize(
            "§7进度：§a${quest.progress}§7/§7${def.target}"
        ))
        val rewards = mutableListOf<String>()
        if (def.rewardXp > 0) rewards.add("§e${def.rewardXp} XP")
        if (def.rewardCoins > 0) rewards.add("§6${def.rewardCoins} 硬币")
        if (rewards.isNotEmpty()) {
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§7奖励：${rewards.joinToString(" ")}"))
        }
        if (quest.progress >= def.target) {
            meta.setEnchantmentGlintOverride(true)
        }
        meta.lore(lore)
        meta.persistentDataContainer.set(questKey, PersistentDataType.STRING, "quest")
        item.itemMeta = meta
        return item
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val rawTitle = event.view.title()
        val titleStr = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(rawTitle)
        if (titleStr != GUI_TITLE) return

        event.isCancelled = true
        val clicked = event.currentItem ?: return
        val meta = clicked.itemMeta ?: return
        val action = meta.persistentDataContainer.get(questKey, PersistentDataType.STRING)
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
