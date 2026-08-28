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
        gameFlow.onPlayerQuit(world, id)
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        val id = event.player.uniqueId
        val newWorld = event.player.world.name
        val oldWorld = playerWorlds[id]
        // 先退出旧世界对局（母体离开需补位、最后一名人类离开需结算），再加入新世界
        if (oldWorld != null && oldWorld != newWorld) {
            gameFlow.onPlayerLeaveWorld(oldWorld, id)
        }
        playerWorlds[id] = newWorld
        gameFlow.onPlayerJoin(newWorld, id)
    }

    @EventHandler
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val world = worldOf(victim)

        // 复活保护/母体未释放保护：期间免疫伤害
        if (gameFlow.isProtected(victim.uniqueId)) {
            event.isCancelled = true
            return
        }

        val attacker = when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        } ?: return

        val lethal = victim.health - event.finalDamage <= 0.0

        // 人类击杀僵尸：记录统计，僵尸死亡后由重生逻辑送回僵尸出生点
        val victimTeam = gameFlow.teamOf(world, victim.uniqueId)
        val attackerTeam = gameFlow.teamOf(world, attacker.uniqueId)

        // 母体未释放前无法攻击人类
        if (attackerTeam == GameTeam.ZOMBIE_MAIN && !gameFlow.isMotherReleased(world)) {
            event.isCancelled = true
            return
        }

        if (lethal && attackerTeam == GameTeam.HUMAN &&
            (victimTeam == GameTeam.ZOMBIE || victimTeam == GameTeam.ZOMBIE_MAIN)
        ) {
            gameFlow.onZombieKilled(world, attacker.uniqueId, victim.uniqueId)
        }

        val infected = gameFlow.onCombat(victim.uniqueId, attacker.uniqueId, world, lethal)
        if (infected) {
            event.isCancelled = true
            victim.health = maxHealthProvider(victim)
        }
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        val victim = event.entity
        val world = worldOf(victim)
        if (gameFlow.teamOf(world, victim.uniqueId) == GameTeam.HUMAN) {
            gameFlow.onHumanDied(world, victim.uniqueId)
        }
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
        val canOpen = team == GameTeam.HUMAN &&
            (phase == GamePhase.RUNNING || phase == GamePhase.WAITING)
        if (player.isSneaking && canOpen) {
            event.isCancelled = true
            gui.openShop(player)
        }
    }
}
