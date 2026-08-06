package cn.oneachina.zombieRun.listener

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.GameManager
import cn.oneachina.zombieRun.util.DebugLogger
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import me.zombie_striker.qg.api.QAWeaponDamageEntityEvent
import me.zombie_striker.qg.api.QAWeaponPrepareShootEvent
import me.zombie_striker.qg.api.QualityArmory
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Particle
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class CombatListener(
    private val plugin: ZombieRun,
    private val taskTracker: PlayerTaskTracker
) : Listener {

    /** 爆头 XP 防刷冷却 (ticks) */
    private val headshotXpCooldownTicks = 20
    private val headshotCooldowns = ConcurrentHashMap<String, Int>()

    // ==================== QualityArmory 枪械 ====================

    /** 游戏规则拦截：仅人类在 RUNNING 状态下允许射击 */
    @EventHandler
    fun onQAWeaponPrepareShoot(event: QAWeaponPrepareShootEvent) {
        if (plugin.debugMode) return
        val player = event.player
        val allowed = plugin.gameManager.getGameStatus() == GameManager.GameStatus.RUNNING &&
            plugin.gameManager.getPlayerTeam(player) == GameManager.Team.HUMAN
        if (!allowed) event.isCancelled = true
    }

    /** 接管 QA 枪械伤害：应用阵营规则 + 自定义生命值 + 统计 */
    @EventHandler
    fun onQAWeaponDamage(event: QAWeaponDamageEntityEvent) {
        val shooter = event.player
        val victim = event.damaged as? Player ?: return

        if (!plugin.debugMode) {
            if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
                event.isCancelled = true
                return
            }
            val shooterTeam = plugin.gameManager.getPlayerTeam(shooter)
            val victimTeam = plugin.gameManager.getPlayerTeam(victim)
            if (shooterTeam != GameManager.Team.HUMAN ||
                (victimTeam != GameManager.Team.ZOMBIE && victimTeam != GameManager.Team.ZOMBIE_MAIN)
            ) {
                // 阻止误伤与非僵尸目标（例如大厅内开枪）
                event.isCancelled = true
                return
            }
        }

        // 接管伤害：取消 QA 原版伤害，走 ZombieRun 自定义生命值
        event.isCancelled = true
        val damage = event.damage
        plugin.healthManager.damage(victim, damage, shooter)
        plugin.progressionListener.onDealDamage(shooter, damage)

        // 击退（沿射击者→目标方向）
        val knockback = event.gun.knockbackPower
        if (knockback > 0) {
            val dir = victim.location.toVector().subtract(shooter.location.toVector())
            if (dir.lengthSquared() > 0.0001) {
                victim.velocity = victim.velocity.add(dir.normalize().multiply(knockback))
            }
        }

        // 爆头：粒子 + XP（带防刷冷却）
        if (event.isHeadshot) {
            val now = plugin.server.currentTick
            val key = "${shooter.uniqueId}:${victim.uniqueId}"
            val last = headshotCooldowns.getOrDefault(key, 0)
            if (now - last >= headshotXpCooldownTicks) {
                headshotCooldowns[key] = now
                plugin.progressionManager.addXp(shooter, plugin.economyConfig.headshotXp, "爆头")
                victim.world.spawnParticle(
                    Particle.CRIT,
                    victim.location.clone().add(0.0, victim.eyeHeight - 0.2, 0.0),
                    5, 0.3, 0.3, 0.3, 0.0
                )
            }
        }

        spawnDamageDisplay(victim, damage, event.isHeadshot)
        DebugLogger.damage("${shooter.name}(人类) → ${victim.name} QA枪械 ${String.format("%.1f", damage)}伤害 [HP:${String.format("%.1f", plugin.healthManager.getHealth(victim))}]")
    }

    private fun spawnDamageDisplay(target: Player, dmg: Double, isHeadshot: Boolean) {
        val world = target.world
        val loc = target.location.clone().add(0.0, target.eyeHeight + 0.5, 0.0)

        val dmgInt = dmg.toInt()
        val color = if (isHeadshot) NamedTextColor.GOLD else NamedTextColor.RED
        val text = Component.text("$dmgInt", color)

        val display = world.spawn(loc, TextDisplay::class.java) { td ->
            td.text(text)
            td.isSeeThrough = false
            td.billboard = Display.Billboard.CENTER
            td.isShadowed = true
        }

        var ticks = 0
        val task = display.scheduler.runAtFixedRate(plugin, { t ->
            if (ticks >= 12 || display.isDead) {
                display.remove()
                t.cancel()
                return@runAtFixedRate
            }
            display.teleport(display.location.add(0.0, 0.08, 0.0))
            display.textOpacity = ((12 - ticks) / 12f * 0xFF).toInt().toByte()
            ticks++
        }, null, 1L, 1L)
    }

    @EventHandler
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val attacker = event.damager as? Player ?: return
        val victim = event.entity as? Player ?: return

        if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return

        val attackerTeam = plugin.gameManager.getPlayerTeam(attacker)
        val victimTeam = plugin.gameManager.getPlayerTeam(victim)

        if (attackerTeam == victimTeam) {
            event.isCancelled = true
            return
        }

        if (attackerTeam == GameManager.Team.HUMAN &&
            (victimTeam == GameManager.Team.ZOMBIE || victimTeam == GameManager.Team.ZOMBIE_MAIN)) {
            if (QualityArmory.isGun(attacker.inventory.itemInMainHand)) {
                event.isCancelled = true
                plugin.healthManager.damage(victim, event.damage, attacker)
                return
            }
            event.isCancelled = true
            val swordDamage = plugin.combatConfig.swordDamage
            plugin.healthManager.damage(victim, swordDamage, attacker)
            victim.velocity = victim.velocity.add(attacker.location.direction.setY(-1.0).normalize().multiply(0.3))
            attacker.sendActionBar(Component.text("造成伤害: ${String.format("%.1f", swordDamage)}").color(NamedTextColor.RED))
            DebugLogger.damage("${attacker.name}(人类) → ${victim.name}(${victimTeam}) ${String.format("%.1f", swordDamage)}伤害 [HP:${String.format("%.1f", plugin.healthManager.getHealth(victim))}]")
            return
        }

        if ((attackerTeam == GameManager.Team.ZOMBIE || attackerTeam == GameManager.Team.ZOMBIE_MAIN) &&
            victimTeam == GameManager.Team.HUMAN) {
            val zombieDamage = if (attackerTeam == GameManager.Team.ZOMBIE_MAIN)
                plugin.combatConfig.zombieMainDamage else plugin.combatConfig.zombieDamage
            // 取消原版伤害，避免与自定义伤害叠加造成双倍伤害
            event.isCancelled = true
            plugin.healthManager.damage(victim, zombieDamage, attacker)
            DebugLogger.damage("${attacker.name}(${attackerTeam}) → ${victim.name}(人类) ${String.format("%.1f", zombieDamage)}伤害 [HP:${String.format("%.1f", plugin.healthManager.getHealth(victim))}]")
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
                    val reward = if (victimTeam == GameManager.Team.ZOMBIE_MAIN)
                        plugin.economyConfig.killZombieMainCoins else plugin.economyConfig.killZombieCoins
                    plugin.coinManager.addCoins(killer.uniqueId, reward)
                    killer.sendMessage(Component.text("+ $reward 硬币!", NamedTextColor.GOLD))
                    val teamColor = if (victimTeam == GameManager.Team.ZOMBIE_MAIN) NamedTextColor.DARK_PURPLE else NamedTextColor.DARK_GREEN
                    Bukkit.broadcast(Component.text()
                        .append(Component.text(killer.name, NamedTextColor.AQUA))
                        .append(Component.text(" 击杀了 ", NamedTextColor.WHITE))
                        .append(Component.text(victim.name, teamColor))
                        .build())
                } else {
                    Bukkit.broadcast(Component.text()
                        .append(Component.text(victim.name, NamedTextColor.DARK_GREEN))
                        .append(Component.text(" 死亡了", NamedTextColor.WHITE))
                        .build())
                }

                DebugLogger.damage("${victim.name} 被击杀 (${victimTeam})")
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
        plugin.coinManager.addCoins(attacker.uniqueId, plugin.economyConfig.infectHumanCoins)

        val attackerColor = if (plugin.gameManager.getPlayerTeam(attacker) == GameManager.Team.ZOMBIE_MAIN) NamedTextColor.DARK_PURPLE else NamedTextColor.DARK_GREEN
        Bukkit.broadcast(Component.text()
            .append(Component.text(attacker.name, attackerColor))
            .append(Component.text(" 感染了 ", NamedTextColor.RED))
            .append(Component.text(victim.name, NamedTextColor.AQUA))
            .build())

        DebugLogger.damage("${victim.name} 被 ${attacker.name} 感染")

        victim.inventory.clear()
        plugin.gameManager.setPlayerTeam(victim, GameManager.Team.ZOMBIE)
        victim.gameMode = GameMode.SPECTATOR

        var countdown = plugin.balanceConfig.infectCountdownSec
        var scheduledTask: ScheduledTask? = null
        scheduledTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { task ->
            if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
                taskTracker.unregister(task, victim.uniqueId)
                task.cancel()
                return@runAtFixedRate
            }
            if (countdown > 0) {
                val title = Title.title(
                    Component.text("$countdown", NamedTextColor.RED),
                    Component.text("你已死亡，等待部署", NamedTextColor.DARK_GREEN),
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
            // 僵尸死亡：布防复活到人类前方更远的僵尸点
            plugin.respawnManager.teleportZombieByProgress(
                victim, plugin.gameManager.getHumanProgress(), ahead = true
            )
            victim.sendMessage(message)
        }, plugin.balanceConfig.respawnDelayTicks)
        taskTracker.register(scheduledTask, victim)
    }
}

