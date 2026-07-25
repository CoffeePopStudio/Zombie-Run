package cn.oneachina.zombieRun.listener

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.FireMode
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.persistence.PersistentDataType

class WeaponListener(private val plugin: ZombieRun) : Listener {

    // ---- 左键射击 (PlayerAnimationEvent = arm swing) ----

    @EventHandler
    fun onPlayerAnimation(event: PlayerAnimationEvent) {
        val player = event.player
        val item = player.inventory.itemInMainHand
        val wm = plugin.weaponManager

        if (!wm.isZombieRunWeapon(item)) return
        if (!wm.canOperate(player)) return

        val weaponId = wm.getWeaponId(item) ?: return
        val config = wm.getWeaponConfig(weaponId) ?: return

        when (config.fireMode) {
            FireMode.AUTO -> {
                if (wm.isAutoFiring(player)) {
                    wm.stopAutoFire(player)
                } else {
                    wm.startAutoFire(player)
                }
            }
            FireMode.SEMI -> {
                wm.handleShoot(player)
            }
            FireMode.BURST -> {
                // BURST: 左键按一次打 burstCount 发
                burstFire(player, wm, config)
            }
        }
    }

    private fun burstFire(player: org.bukkit.entity.Player, wm: cn.oneachina.zombieRun.manager.WeaponManager, config: cn.oneachina.zombieRun.model.WeaponConfig) {
        if (!wm.canOperate(player)) return
        var count = 0
        val task = org.bukkit.Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { t ->
            val curItem = player.inventory.itemInMainHand
            if (!wm.isZombieRunWeapon(curItem) || wm.getWeaponId(curItem) != config.id) {
                t.cancel(); return@runAtFixedRate
            }
            if (!wm.canOperate(player) || wm.getMagazine(curItem) <= 0) {
                t.cancel(); return@runAtFixedRate
            }
            if (count >= config.burstCount) { t.cancel(); return@runAtFixedRate }
            wm.handleShoot(player)
            count++
        }, 1L, config.cooldownTicks.toLong())
    }

    // ---- 右键 ADS (切换) ----

    @EventHandler
    fun onRightClick(event: PlayerInteractEvent) {
        val player = event.player
        val item = event.item ?: return
        val wm = plugin.weaponManager

        if (!wm.isZombieRunWeapon(item)) return

        if (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK) {
            event.isCancelled = true

            if (wm.isReloading(item)) {
                wm.cancelReload(player, item)
                player.sendMessage(Component.text("换弹已取消", NamedTextColor.YELLOW))
                return
            }

            // 切换 ADS
            if (wm.isAds(player)) {
                wm.setAds(player, false)
            } else {
                wm.stopAutoFire(player)
                wm.setAds(player, true)
            }
        }
    }

    // ---- F 键换弹 (PlayerSwapHandItemsEvent) ----

    @EventHandler
    fun onSwapHand(event: PlayerSwapHandItemsEvent) {
        val player = event.player
        val item = player.inventory.itemInMainHand
        val wm = plugin.weaponManager

        if (!wm.isZombieRunWeapon(item)) return

        event.isCancelled = true

        if (wm.isPlayerReloading(player)) {
            wm.cancelReload(player, item)
            player.sendMessage(Component.text("换弹已取消", NamedTextColor.YELLOW))
            return
        }

        if (wm.isBolting(player)) return

        wm.handleReload(player)
    }

    // ---- 切换武器 ----

    @EventHandler
    fun onItemHeldChange(event: PlayerItemHeldEvent) {
        val player = event.player
        val wm = plugin.weaponManager
        val prevItem = player.inventory.getItem(event.previousSlot)

        if (prevItem != null && wm.isZombieRunWeapon(prevItem)) {
            if (wm.isReloading(prevItem)) wm.cancelReload(player, prevItem)
            wm.stopAutoFire(player)
        }

        val newItem = player.inventory.getItem(event.newSlot)
        if (newItem != null && wm.isZombieRunWeapon(newItem)) {
            val meta = newItem.itemMeta ?: return
            meta.persistentDataContainer.set(NamespacedKey("zombie-run", "shot_count"), PersistentDataType.INTEGER, 0)
            newItem.itemMeta = meta
        }
    }
}
