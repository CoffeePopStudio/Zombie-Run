package cn.oneachina.zombierun.v2.infrastructure.bukkit.listener

import cn.oneachina.zombierun.v2.application.combat.CombatHealthService
import cn.oneachina.zombierun.v2.application.event.ApplicationEventBus
import cn.oneachina.zombierun.v2.application.event.GameStartedEvent
import cn.oneachina.zombierun.v2.application.game.GameFlowService
import cn.oneachina.zombierun.v2.domain.game.GamePhase
import cn.oneachina.zombierun.v2.domain.game.GameTeam
import cn.oneachina.zombierun.v2.support.V2Logger
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * 玩家状态接管：加入/换世界/开局/结算时统一 GameMode、背包、药水、血量载体。
 *
 * - 进入 arena：ADVENTURE + 清状态 + 初始化自定义血量
 * - 开局：状态准备由 GameFlowService 在发枪前同步完成；事件回调不再清背包
 * - 僵尸：ADVENTURE + 力量/速度/跳跃 buff
 * - 结束：全员回观战清理
 */
class V2PlayerStateListener(
    private val plugin: JavaPlugin,
    private val gameFlow: GameFlowService,
    private val healthService: CombatHealthService,
    private val logger: V2Logger,
) : Listener {

    companion object {
        /** GameFlowService 僵尸 buff 钩子的默认实现。 */
        fun zombieBuffs(playerId: UUID) {
            val p = Bukkit.getPlayer(playerId) ?: return
            p.addPotionEffect(PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 0, false, false))
            p.addPotionEffect(PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 0, false, false))
            p.addPotionEffect(PotionEffect(PotionEffectType.JUMP_BOOST, PotionEffect.INFINITE_DURATION, 0, false, false))
        }

        /** 母体释放：解除观战冻结，回到 ADVENTURE 并上僵尸增益。 */
        fun unfreezeAlpha(playerId: UUID) {
            val p = Bukkit.getPlayer(playerId) ?: return
            p.gameMode = GameMode.ADVENTURE
            zombieBuffs(playerId)
        }

        fun spectatorCleanup(playerId: UUID) {
            val p = Bukkit.getPlayer(playerId) ?: return
            p.gameMode = GameMode.SPECTATOR
            p.inventory.clear()
            p.activePotionEffects.forEach { p.removePotionEffect(it.type) }
        }

        /**
         * 准备玩家进入对局状态。默认清背包；开局前调用应清背包，事件回调请传 [clearInventory]=false
         * 以免异步清空刚发放的武器。
         */
        fun preparePlayer(
            player: Player,
            team: GameTeam,
            healthService: CombatHealthService,
            gameFlow: GameFlowService,
            clearInventory: Boolean = true,
        ) {
            player.gameMode = GameMode.ADVENTURE
            if (clearInventory) player.inventory.clear()
            player.clearActivePotionEffects()
            player.health = 20.0
            healthService.initPlayer(player.uniqueId, team)
            when (team) {
                GameTeam.ZOMBIE_MAIN -> {
                    // 母体容器：保护期内冻结（观察等待释放）
                    if (gameFlow.isProtected(player.uniqueId)) {
                        player.gameMode = GameMode.SPECTATOR
                    }
                }
                GameTeam.HUMAN -> player.gameMode = GameMode.ADVENTURE
                else -> Unit
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val world = player.world.name
        if (gameFlow.phaseOf(world) == null) return // 非 arena 世界不接管
        val team = gameFlow.teamOf(world, player.uniqueId) ?: GameTeam.SPECTATOR
        runOnPlayer(player) {
            preparePlayer(player, team, healthService, gameFlow, clearInventory = true)
        }
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        val player = event.player
        val world = player.world.name
        if (gameFlow.phaseOf(world) == null) return // 非 arena 世界不接管
        val team = gameFlow.teamOf(world, player.uniqueId) ?: GameTeam.SPECTATOR
        runOnPlayer(player) {
            preparePlayer(player, team, healthService, gameFlow, clearInventory = true)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        healthService.clear(event.player.uniqueId)
    }

    /** 订阅开局事件：在玩家所属线程补状态；不再清背包，避免清掉开局已发武器。 */
    fun onGameStarted(assignments: Map<UUID, String>, worldName: String) {
        assignments.forEach { (id, teamName) ->
            val player = Bukkit.getPlayer(id) ?: return@forEach
            val team = GameTeam.valueOf(teamName)
            runOnPlayer(player) {
                preparePlayer(player, team, healthService, gameFlow, clearInventory = false)
            }
        }
        logger.debug("state", "[$worldName] game start state applied to ${assignments.size} players")
    }

    fun onGameEnded(worldName: String) {
        Bukkit.getWorld(worldName)?.players?.forEach { p ->
            runOnPlayer(p) { spectatorCleanup(p.uniqueId) }
        }
    }

    private fun runOnPlayer(player: Player, action: () -> Unit) {
        val scheduler: io.papermc.paper.threadedregions.scheduler.EntityScheduler? = player.scheduler
        if (scheduler != null) {
            scheduler.run(plugin, { _ -> action() }, null)
        } else {
            action()
        }
    }
}

/** 事件桥：ApplicationEventBus 订阅入口（组合根调用）。 */
fun bindPlayerStateBridge(
    bus: ApplicationEventBus,
    listener: V2PlayerStateListener,
) {
    bus.subscribe(GameStartedEvent::class.java) { event ->
        listener.onGameStarted(event.playerAssignments, event.worldName)
    }
    bus.subscribe(cn.oneachina.zombierun.v2.application.event.GameEndedEvent::class.java) { event ->
        listener.onGameEnded(event.worldName)
    }
}
