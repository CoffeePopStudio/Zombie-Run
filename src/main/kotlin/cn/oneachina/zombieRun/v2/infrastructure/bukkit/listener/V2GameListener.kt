package cn.oneachina.zombierun.v2.infrastructure.bukkit.listener

import cn.oneachina.zombierun.v2.application.game.GameFlowService
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 对局流程 Bukkit 监听：加入/退出/换世界/濒死感染/重生。
 */
class V2GameListener(
    private val gameFlow: GameFlowService,
) : Listener {

    private val playerWorlds = ConcurrentHashMap<UUID, String>()

    private fun worldOf(player: Player): String {
        val world = player.world
        playerWorlds[player.uniqueId] = world.name
        return world.name
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val world = worldOf(event.player)
        gameFlow.onPlayerJoin(world, event.player.uniqueId)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        val world = playerWorlds.remove(id) ?: "world"
        gameFlow.onPlayerQuit(world, id)
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        val world = worldOf(event.player)
        gameFlow.onPlayerJoin(world, event.player.uniqueId)
    }

    @EventHandler
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val world = worldOf(victim)

        val attacker = when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        } ?: return

        val lethal = victim.health - event.finalDamage <= 0.0
        val infected = gameFlow.onCombat(victim.uniqueId, attacker.uniqueId, world, lethal)
        if (infected) {
            event.isCancelled = true
            victim.health = victim.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
        }
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        val world = event.respawnLocation.world?.name ?: worldOf(event.player)
        gameFlow.onPlayerRespawn(world, event.player.uniqueId)
    }
}
