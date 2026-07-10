package cn.oneachina.zombieRun.listener

import cn.oneachina.zombieRun.ZombieRun
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.NamespacedKey
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.persistence.PersistentDataType

class WeaponListener(private val plugin: ZombieRun) : Listener {

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val item = event.item ?: return
        val action = event.action
        val wm = plugin.weaponManager

        if (!wm.isZombieRunWeapon(item)) return

        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            event.isCancelled = true

            if (wm.isReloading(item)) return

            if (player.isSneaking) {
                val weaponId = wm.getWeaponId(item) ?: return
                val config = wm.getWeaponConfig(weaponId) ?: return
                if (wm.getMagazine(item) >= config.magazineSize) {
                    player.sendMessage(Component.text("弹匣已满", NamedTextColor.GREEN))
                    return
                }
                wm.handleReload(player, item)
            } else {
                val weaponId = wm.getWeaponId(item) ?: return
                val config = wm.getWeaponConfig(weaponId) ?: return
                if (config.automatic) {
                    wm.startAutoFire(player)
                } else {
                    wm.handleShoot(player, item)
                }
            }
        }
    }

    @EventHandler
    fun onItemHeldChange(event: PlayerItemHeldEvent) {
        val player = event.player
        val prevItem = player.inventory.getItem(event.previousSlot)
        if (prevItem != null && plugin.weaponManager.isZombieRunWeapon(prevItem)) {
            if (plugin.weaponManager.isReloading(prevItem)) {
                plugin.weaponManager.cancelReload(player, prevItem)
            }
            plugin.weaponManager.stopAutoFire(player)
        }
        val newItem = player.inventory.getItem(event.newSlot)
        if (newItem != null && plugin.weaponManager.isZombieRunWeapon(newItem)) {
            val pdc = newItem.itemMeta?.persistentDataContainer
            pdc?.set(NamespacedKey("zombie-run", "shot_count"), PersistentDataType.INTEGER, 0)
            newItem.itemMeta = newItem.itemMeta
        }
    }

    @EventHandler
    fun onSneakToggle(event: PlayerToggleSneakEvent) {
        val player = event.player
        val item = player.inventory.itemInMainHand
        val wm = plugin.weaponManager

        if (!wm.isZombieRunWeapon(item)) {
            if (wm.isAds(player)) {
                wm.setAds(player, false)
            }
            return
        }

        if (event.isSneaking) {
            wm.stopAutoFire(player)
            val weaponId = wm.getWeaponId(item) ?: return
            val config = wm.getWeaponConfig(weaponId) ?: return
            if (wm.isReloading(item) && wm.getMagazine(item) < config.magazineSize) {
                wm.handleReload(player, item)
            }
        } else {
            if (wm.isReloading(item)) {
                wm.cancelReload(player, item)
            }
        }
    }
}
