package cn.oneachina.zombieRun.listener

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.GameManager
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import java.time.Duration

class CombatListener(
    private val plugin: ZombieRun,
    private val taskTracker: PlayerTaskTracker
) : Listener {

    @EventHandler
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val attacker = event.damager as? Player ?: return
        val victim = event.entity as? Player ?: return

        if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return

        val attackerTeam = plugin.gameManager.getPlayerTeam(attacker)
        val victimTeam = plugin.gameManager.getPlayerTeam(victim)

        if (attackerTeam == GameManager.Team.HUMAN &&
            (victimTeam == GameManager.Team.ZOMBIE || victimTeam == GameManager.Team.ZOMBIE_MAIN)) {
            event.isCancelled = true
            val swordDamage = 5.0
            plugin.healthManager.damage(victim, swordDamage, attacker)
            victim.velocity = victim.velocity.add(attacker.location.direction.setY(-1.0).normalize().multiply(0.3))
            attacker.sendActionBar(Component.text("造成伤害: ${String.format("%.1f", swordDamage)}").color(NamedTextColor.RED))
            return
        }

        if ((attackerTeam == GameManager.Team.ZOMBIE || attackerTeam == GameManager.Team.ZOMBIE_MAIN) &&
            victimTeam == GameManager.Team.HUMAN) {
            event.isCancelled = true
            val zombieDamage = if (attackerTeam == GameManager.Team.ZOMBIE_MAIN) 10.0 else 6.0
            plugin.healthManager.damage(victim, zombieDamage, attacker)
            return
        }

        if (attackerTeam == victimTeam) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        event.isCancelled = true
        val victim = event.entity
        val killer = victim.killer
            ?: plugin.healthManager.getLastDamager(victim.uniqueId)?.let { Bukkit.getPlayer(it) }

        event.drops.clear()
        event.deathMessage(null)

        when (val victimTeam = plugin.gameManager.getPlayerTeam(victim)) {
            GameManager.Team.HUMAN -> {
                if (killer != null && plugin.gameManager.getPlayerTeam(killer) in setOf(GameManager.Team.ZOMBIE, GameManager.Team.ZOMBIE_MAIN)) {
                    infectPlayer(killer, victim)
                } else {
                    plugin.gameManager.setPlayerTeam(victim, GameManager.Team.ZOMBIE)
                    victim.gameMode = GameMode.SPECTATOR
                    scheduleZombieRespawn(victim, Component.text("你已死亡并变为僵尸！", NamedTextColor.DARK_GREEN))
                }
            }
            GameManager.Team.ZOMBIE, GameManager.Team.ZOMBIE_MAIN -> {
                if (killer != null && plugin.gameManager.getPlayerTeam(killer) == GameManager.Team.HUMAN) {
                    plugin.miscManager.addKill(killer)
                    plugin.progressionListener.onKillZombie(killer, victim, victimTeam == GameManager.Team.ZOMBIE_MAIN)
                    val reward = if (victimTeam == GameManager.Team.ZOMBIE_MAIN) 150 else 50
                    plugin.coinManager.addCoins(killer.uniqueId, reward)
                    killer.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§6+ $reward 硬币!"))
                    val teamColorCode = if (victimTeam == GameManager.Team.ZOMBIE_MAIN) "5" else "2"
                    Bukkit.broadcast(LegacyComponentSerializer.legacySection().deserialize("§b${killer.name} §f击杀了 §${teamColorCode}${victim.name}"))
                } else {
                    Bukkit.broadcast(LegacyComponentSerializer.legacySection().deserialize("§2${victim.name} §f死亡了"))
                }
                victim.gameMode = GameMode.SPECTATOR
                scheduleZombieRespawn(victim, Component.text("你已复活为僵尸！", NamedTextColor.DARK_GREEN))
            }
            else -> {
                victim.inventory.clear()
                plugin.gameManager.setPlayerTeam(victim, GameManager.Team.ZOMBIE)
                victim.gameMode = GameMode.SPECTATOR
                scheduleZombieRespawn(victim, Component.text("你已死亡并变为僵尸！", NamedTextColor.DARK_GREEN))
            }
        }
    }

    @EventHandler
    fun onEntityDamage(event: EntityDamageEvent) {
        val entity = event.entity
        if (entity !is Player) return

        if (event.cause == EntityDamageEvent.DamageCause.FALL) {
            event.isCancelled = true
            return
        }
    }

    @EventHandler
    fun onPlayerPickupItem(event: EntityPickupItemEvent) {
        val player = if (event.entity is Player) event.entity as? Player else return
        val team = plugin.gameManager.getPlayerTeam(player)
        if (team == GameManager.Team.ZOMBIE || team == GameManager.Team.ZOMBIE_MAIN) {
            event.isCancelled = true
        }
    }

    private fun infectPlayer(attacker: Player, victim: Player) {
        plugin.miscManager.addInfection(attacker)
        plugin.progressionListener.onInfectHuman(attacker, victim)
        plugin.coinManager.addCoins(attacker.uniqueId, 50)

        val attackerColor = if (plugin.gameManager.getPlayerTeam(attacker) == GameManager.Team.ZOMBIE_MAIN) "5" else "2"
        Bukkit.broadcast(LegacyComponentSerializer.legacySection().deserialize("§${attackerColor}${attacker.name} §c感染了 §b${victim.name}"))

        victim.inventory.clear()
        plugin.gameManager.setPlayerTeam(victim, GameManager.Team.ZOMBIE)
        victim.gameMode = GameMode.SPECTATOR

        var countdown = 5
        var scheduledTask: ScheduledTask? = null
        scheduledTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { task ->
            if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
                taskTracker.unregister(task, victim.uniqueId)
                task.cancel()
                return@runAtFixedRate
            }
            if (countdown > 0) {
                val title = Title.title(
                    LegacyComponentSerializer.legacySection().deserialize("§c$countdown"),
                    LegacyComponentSerializer.legacySection().deserialize("§2你已死亡，等待部署"),
                    Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO)
                )
                victim.showTitle(title)
                countdown--
            } else {
                victim.gameMode = GameMode.ADVENTURE
                plugin.staminaManager.applyZombieEffects(victim)
                plugin.respawnManager.teleportToZombieRespawn(victim)
                victim.sendMessage(Component.text("你现在是僵尸！阻止人类前进！", NamedTextColor.DARK_GREEN))
                taskTracker.unregister(task, victim.uniqueId)
                task.cancel()
            }
        }, 1L, 20L)
        taskTracker.register(scheduledTask, victim)
    }

    private fun scheduleZombieRespawn(victim: Player, message: Component) {
        val scheduledTask = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { task ->
            taskTracker.unregister(task, victim.uniqueId)
            if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
                return@runDelayed
            }
            victim.gameMode = GameMode.ADVENTURE
            plugin.staminaManager.applyZombieEffects(victim)
            plugin.respawnManager.teleportToZombieRespawn(victim)
            victim.sendMessage(message)
        }, 100L)
        taskTracker.register(scheduledTask, victim)
    }
}

