package cn.oneachina.zombieRun.listener

import cn.oneachina.zombieRun.ZombieRun
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent

class WeaponListener(private val plugin: ZombieRun) : Listener {

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val item = event.item ?: return
        val action = event.action

        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return

        val wm = plugin.weaponManager

        if (wm.isZombieRunWeapon(item)) {
            event.isCancelled = true

            if (player.isSneaking) {
                wm.reloadWeapon(player, item)
            } else {
                wm.handleShoot(player, item)
            }
        }
    }
}
