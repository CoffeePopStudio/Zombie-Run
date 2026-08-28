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
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * 玩家状态接管：加入/开局/结算时统一 GameMode、背包、药水、血量载体。
 *
 * - 加入 WAITING/ENDED 世界：ADVENTURE + 清理 + 观战名册
 * - 开局：人类 ADVENTURE 清状态初始化自定义血量；母体加 buff 延迟释放
 * - 僵尸：ADVENTURE + 力量/速度/跳跃 buff
 * - 结束：全员回观战清理
 */
class V2PlayerStateListener(
    private val plugin: JavaPlugin,
    private val gameFlow: GameFlowService,
    private val healthService: CombatHealthService,
    private val logger: V2Logger,
) : Listener {

    init {
        // 开局状态接管：人类清状态，母体保持容器保护
        gameFlowEventBridge = this
    }

    companion object {
        private var gameFlowEventBridge: V2PlayerStateListener? = null

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
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val world = player.world.name
        if (gameFlow.phaseOf(world) == null) return // 非 arena 世界不接管

        player.gameMode = GameMode.ADVENTURE
        player.clearActivePotionEffects()
        player.health = 20.0
        healthService.initPlayer(player.uniqueId, gameFlow.teamOf(world, player.uniqueId) ?: GameTeam.SPECTATOR)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        healthService.clear(event.player.uniqueId)
    }

    /** 订阅开局事件：为每名玩家做队伍状态接管。 */
    fun onGameStarted(assignments: Map<UUID, String>, worldName: String) {
        Bukkit.getGlobalRegionScheduler().run(plugin) { _ ->
            assignments.forEach { (id, teamName) ->
                val player = Bukkit.getPlayer(id) ?: return@forEach
                val team = GameTeam.valueOf(teamName)
                player.gameMode = GameMode.ADVENTURE
                player.inventory.clear()
                player.clearActivePotionEffects()
                player.health = 20.0
                healthService.initPlayer(id, team)
                when (team) {
                    GameTeam.ZOMBIE_MAIN -> {
                        // 母体容器：保护期内冻结（观察等待释放）
                        if (gameFlow.isProtected(id)) {
                            player.gameMode = GameMode.SPECTATOR
                        }
                    }
                    GameTeam.HUMAN -> {
                        player.gameMode = GameMode.ADVENTURE
                    }
                    else -> Unit
                }
            }
            logger.debug("state", "[$worldName] game start state applied to ${assignments.size} players")
        }
    }

    fun onGameEnded(worldName: String) {
        Bukkit.getGlobalRegionScheduler().run(plugin) { _ ->
            Bukkit.getWorld(worldName)?.players?.forEach { p ->
                spectatorCleanup(p.uniqueId)
            }
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
