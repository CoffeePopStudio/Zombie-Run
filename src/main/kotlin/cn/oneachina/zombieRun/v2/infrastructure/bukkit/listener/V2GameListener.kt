package cn.oneachina.zombierun.v2.infrastructure.bukkit.listener

import cn.oneachina.zombierun.v2.application.game.GameFlowService
import cn.oneachina.zombierun.v2.domain.game.GamePhase
import cn.oneachina.zombierun.v2.domain.game.GameTeam
import cn.oneachina.zombierun.v2.infrastructure.bukkit.gui.GuiService
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 对局流程 Bukkit 监听：加入/退出/换世界/濒死感染/重生。
 */
class V2GameListener(
    private val gameFlow: GameFlowService,
    private val guiService: GuiService? = null,
    private val healthService: cn.oneachina.zombierun.v2.application.combat.CombatHealthService? = null,
    private val doorService: cn.oneachina.zombierun.v2.application.door.DoorApplicationService? = null,
    private val maxHealthProvider: (Player) -> Double = { player ->
        player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
    },
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
        doorService?.cancelPlayerTasks(id)
        gameFlow.onPlayerQuit(world, id)
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        val id = event.player.uniqueId
        val newWorld = event.player.world.name
        val oldWorld = playerWorlds[id]
        // 先退出旧世界对局（母体离开需补位、最后一名人类离开需结算），再加入新世界
        if (oldWorld != null && oldWorld != newWorld) {
            doorService?.cancelPlayerTasks(id)
            gameFlow.onPlayerLeaveWorld(oldWorld, id)
            // 跨世界后清掉旧世界自定义血量，避免旧世界状态泄漏到新世界
            healthService?.clear(id)
        }
        playerWorlds[id] = newWorld
        gameFlow.onPlayerJoin(newWorld, id)
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        val world = event.respawnLocation.world?.name ?: worldOf(event.player)
        gameFlow.onPlayerRespawn(world, event.player.uniqueId)
    }

    /**
     * 潜行 + F（副手切换）打开武器商店。
     * 仅对局中（RUNNING）或等待期的人类玩家可用，用于局内补弹/购买道具。
     */
    @EventHandler
    fun onSwapHandItems(event: PlayerSwapHandItemsEvent) {
        val gui = guiService ?: return
        val player = event.player
        val world = player.world.name
        val team = gameFlow.teamOf(world, player.uniqueId) ?: return
        val phase = gameFlow.phaseOf(world)
        val canOpen = (team == GameTeam.HUMAN && phase == GamePhase.RUNNING) ||
            (team == GameTeam.SPECTATOR && (phase == GamePhase.WAITING || phase == GamePhase.STARTING))
        if (player.isSneaking && canOpen) {
            event.isCancelled = true
            gui.openShop(player)
        }
    }
}
