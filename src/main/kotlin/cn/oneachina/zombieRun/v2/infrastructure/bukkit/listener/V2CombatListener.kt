package cn.oneachina.zombierun.v2.infrastructure.bukkit.listener

import cn.oneachina.zombierun.v2.application.combat.StaminaService
import cn.oneachina.zombierun.v2.application.game.GameFlowService
import cn.oneachina.zombierun.v2.domain.combat.StaminaStatus
import cn.oneachina.zombierun.v2.domain.game.GamePhase
import cn.oneachina.zombierun.v2.domain.game.GameTeam
import cn.oneachina.zombierun.v2.ports.SchedulerPort
import cn.oneachina.zombierun.v2.ports.TaskHandle
import cn.oneachina.zombierun.v2.support.TaskRegistry
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerToggleSprintEvent

/**
 * 体力系统 Bukkit 监听：每 5 tick 更新疾跑消耗/恢复，疲劳时强制停止疾跑。
 */
class V2CombatListener(
    private val stamina: StaminaService,
    private val scheduler: SchedulerPort,
    private val taskRegistry: TaskRegistry,
    private val arenaFilter: (String?) -> Boolean = { true },
    private val plugin: org.bukkit.plugin.java.JavaPlugin? = null,
    private val gameFlow: GameFlowService? = null,
) : Listener {

    private var tickTask: TaskHandle? = null

    fun start() {
        if (tickTask != null) return
        val handle = scheduler.globalTimer(5L, 5L) { tick() }
        tickTask = handle
        taskRegistry.register(handle)
    }

    fun stop() {
        tickTask?.cancel()
        tickTask = null
    }

    private fun tick() {
        Bukkit.getOnlinePlayers().forEach { player ->
            if (!arenaFilter(player.world.name)) return@forEach
            val gf = gameFlow
            if (gf != null) {
                val world = player.world.name
                if (gf.phaseOf(world) != GamePhase.RUNNING || gf.teamOf(world, player.uniqueId) != GameTeam.HUMAN) {
                    return@forEach
                }
            }
            val beforeStatus = stamina.stateOf(player.uniqueId).status
            val newlyExhausted = stamina.update(player.uniqueId, player.isSprinting)
            val afterStatus = stamina.stateOf(player.uniqueId).status
            val action = {
                if (newlyExhausted && player.isSprinting) {
                    player.isSprinting = false
                    player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 60, 1, false, false, false))
                    player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.WEAKNESS, 60, 0, false, false, false))
                    player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.GLOWING, 60, 0, false, false, false))
                    player.sendActionBar(net.kyori.adventure.text.Component.text("体力耗尽！"))
                } else if (beforeStatus != StaminaStatus.NORMAL && afterStatus == StaminaStatus.NORMAL) {
                    player.sendActionBar(net.kyori.adventure.text.Component.text("体力已完全恢复，可以跑跳了！"))
                } else {
                    val pct = (stamina.fraction(player.uniqueId) * 100).toInt().coerceIn(0, 100)
                    val filled = (pct / 10).coerceIn(0, 10)
                    val bar = "█".repeat(filled) + "░".repeat(10 - filled)
                    player.sendActionBar(net.kyori.adventure.text.Component.text("体力 $bar $pct%"))
                }
            }
            val p = plugin
            if (p != null) {
                player.scheduler.run(p, { _ -> action() }, null)
            } else {
                action()
            }
        }
    }

    @EventHandler
    fun onToggleSprint(event: PlayerToggleSprintEvent) {
        if (!event.isSprinting) return
        if (!arenaFilter(event.player.world?.name)) return
        val gf = gameFlow
        if (gf != null) {
            val world = event.player.world.name
            if (gf.phaseOf(world) != GamePhase.RUNNING || gf.teamOf(world, event.player.uniqueId) != GameTeam.HUMAN) return
        }
        val state = stamina.stateOf(event.player.uniqueId)
        if (state.status == StaminaStatus.EXHAUSTED) {
            event.isCancelled = true
            event.player.sendActionBar(net.kyori.adventure.text.Component.text("体力耗尽，无法疾跑"))
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (!arenaFilter(event.player.world?.name)) return
        stamina.reset(event.player.uniqueId)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        stamina.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        val id = event.player.uniqueId
        val newWorld = event.player.world.name
        if (arenaFilter(newWorld)) stamina.reset(id) else stamina.remove(id)
    }
}
