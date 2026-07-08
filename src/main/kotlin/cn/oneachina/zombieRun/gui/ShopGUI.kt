package cn.oneachina.zombieRun.gui

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.GameManager
import net.kyori.adventure.text.Component
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

        val inv = Bukkit.createInventory(null, size, Component.text("§8$GUI_TITLE"))

        weapons.forEachIndexed { index, config ->
            val material = Material.matchMaterial(config.material) ?: Material.WOODEN_HOE
            val item = ItemStack(material)
            val meta = item.itemMeta ?: return@forEachIndexed
            meta.displayName(Component.text(config.name.replace("&", "§")))
            val lore = mutableListOf<String>()
            lore.addAll(config.lore.map { it.replace("&", "§") })
            lore.add("")
            lore.add("§e价格: §6${config.price} 硬币")
            lore.add("§7伤害: ${config.damage} | 弹容: ${config.maxAmmo}")
            meta.lore = lore
            if (config.customModelData > 0) {
                meta.setCustomModelData(config.customModelData)
            }
            meta.persistentDataContainer.set(shopKey, PersistentDataType.STRING, config.id)
            item.itemMeta = meta
            inv.setItem(index, item)
        }

        val barIndex = rows * 9
        val close = ItemStack(Material.BARRIER)
        val closeMeta = close.itemMeta ?: return
        closeMeta.displayName(Component.text("§c关闭商店"))
        close.itemMeta = closeMeta
        inv.setItem(barIndex + 4, close)

        player.openInventory(inv)
    }

    fun onAutoOpen(player: Player) {
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline &&
                plugin.gameManager.getGameStatus() == GameManager.GameStatus.WAITING) {
                open(player)
            }
        }, 20L)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val title = event.view.title()
        val plainTitle = if (title is Component) {
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(title)
        } else title.toString()

        if (plainTitle != "§8$GUI_TITLE") return

        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val clicked = event.currentItem ?: return

        val meta = clicked.itemMeta ?: return
        val pdc = meta.persistentDataContainer
        val weaponId = pdc.get(shopKey, PersistentDataType.STRING)

        if (weaponId != null) {
            val config = plugin.weaponManager.getWeaponConfig(weaponId) ?: return
            val coins = plugin.coinManager.getCoins(player.uniqueId)
            if (coins < config.price) {
                player.sendMessage("§c硬币不足！需要: ${config.price}，当前: $coins")
                player.closeInventory()
                return
            }

            if (player.inventory.firstEmpty() == -1) {
                player.sendMessage("§c背包已满，请清理后重试")
                player.closeInventory()
                return
            }

            plugin.coinManager.takeCoins(player.uniqueId, config.price)
            plugin.weaponManager.giveWeapon(player, weaponId)
            player.sendMessage("§a购买成功！${config.name.replace("&", "§")} §a花费: ${config.price} 硬币")
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
        val plainTitle = if (title is Component) {
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(title)
        } else title.toString()

        if (plainTitle == "§8$GUI_TITLE") {
            event.isCancelled = true
        }
    }
}
