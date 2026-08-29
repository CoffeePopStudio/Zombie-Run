package cn.oneachina.zombierun.v2.infrastructure.bukkit.listener

import cn.oneachina.zombierun.v2.application.combat.CombatHealthService
import cn.oneachina.zombierun.v2.application.event.ApplicationEventBus
import cn.oneachina.zombierun.v2.application.event.InfectHumanEvent
import cn.oneachina.zombierun.v2.application.event.PlayerDamageDealtEvent
import cn.oneachina.zombierun.v2.application.game.GameFlowService
import cn.oneachina.zombierun.v2.application.player.PlayerDataService
import cn.oneachina.zombierun.v2.domain.game.GamePhase
import cn.oneachina.zombierun.v2.domain.game.GameTeam
import cn.oneachina.zombierun.v2.infrastructure.bukkit.scheduler.BukkitSchedulerPort
import cn.oneachina.zombierun.v2.infrastructure.config.V2Settings
import cn.oneachina.zombierun.v2.support.V2Logger
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import me.zombie_striker.qg.api.QAWeaponDamageEntityEvent
import me.zombie_striker.qg.api.QAWeaponPrepareShootEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * 战斗监听：自定义血量体系下的全部伤害/死亡/感染链路。
 *
 * - QA 射击：仅 RUNNING 人类可射；伤害事件 cancel 原版、改走自定义血量 + 击退
 * - 近战：同队 cancel；人类→僵尸按 swordDamage；僵尸→人类按 zombieDamage
 * - 死亡：cancel 原版（无掉落/无死亡消息），人类被僵尸击杀 → 感染转僵尸
 */
class V2BattleListener(
    private val plugin: JavaPlugin,
    private val gameFlow: GameFlowService,
    private val healthService: CombatHealthService,
    private val playerData: PlayerDataService?,
    private val settings: V2Settings,
    private val logger: V2Logger,
    private val eventBus: ApplicationEventBus? = null,
) : Listener {

    // ==================== QA 枪械 ====================

    @EventHandler(ignoreCancelled = true)
    fun onQAPrepareShoot(event: QAWeaponPrepareShootEvent) {
        if (settings.debug) return
        val player = event.player
        val world = player.world.name
        if (!gameFlow.isArenaWorld(world)) return
        val ok = gameFlow.phaseOf(world) == GamePhase.RUNNING &&
            gameFlow.teamOf(world, player.uniqueId) == GameTeam.HUMAN
        if (!ok) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onQADamage(event: QAWeaponDamageEntityEvent) {
        val victim = event.damaged as? Player ?: return
        val shooter = event.player
        val world = victim.world.name
        if (!gameFlow.isArenaWorld(world)) return

        if (!settings.debug) {
            if (gameFlow.phaseOf(world) != GamePhase.RUNNING) return
            val shooterTeam = gameFlow.teamOf(world, shooter.uniqueId)
            val victimTeam = gameFlow.teamOf(world, victim.uniqueId)
            if (shooterTeam != GameTeam.HUMAN || victimTeam !in setOf(GameTeam.ZOMBIE, GameTeam.ZOMBIE_MAIN)) return
        }

        // 取消 QA 原版伤害，改走自定义血量
        event.isCancelled = true
        val knockback = event.gun.knockbackPower
        val damage = event.damage

        Bukkit.getRegionScheduler().execute(plugin, victim.location) {
            val dead = healthService.damage(victim.uniqueId, damage, shooter.uniqueId)
            eventBus?.publish(PlayerDamageDealtEvent(shooter.uniqueId, victim.uniqueId, damage))
            // 沿 shooter→victim 方向击退
            val dir = victim.location.toVector().subtract(shooter.location.toVector())
            dir.y = 0.0
            if (dir.lengthSquared() > 0.001) {
                victim.velocity = dir.normalize().multiply(knockback).setY(0.2)
            }
            if (event.isHeadshot) {
                victim.world.spawnParticle(
                    org.bukkit.Particle.CRIT,
                    victim.location.clone().add(0.0, victim.eyeHeight - 0.2, 0.0),
                    5, 0.2, 0.2, 0.2, 0.1,
                )
                playerData?.addXp(shooter.uniqueId, economy.headshotXp)
            }
            if (dead) {
                victim.health = 0.0
            } else {
                flashRed(victim)
            }
        }
    }

    // ==================== 近战 / 僵尸爪 ====================

    @EventHandler(ignoreCancelled = true)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val world = victim.world.name
        if (!gameFlow.isArenaWorld(world)) return
        val attacker = when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        } ?: return

        val victimTeam = gameFlow.teamOf(world, victim.uniqueId)
        val attackerTeam = gameFlow.teamOf(world, attacker.uniqueId)
        if (victimTeam == null || attackerTeam == null) return

        // 母体未释放不能攻击；观战者不可被攻击
        if (attackerTeam == GameTeam.ZOMBIE_MAIN && !gameFlow.isMotherReleased(world)) {
            event.isCancelled = true
            return
        }
        if (victimTeam == GameTeam.SPECTATOR || attackerTeam == GameTeam.SPECTATOR) {
            event.isCancelled = true
            return
        }
        // QA 子弹伤害已由 onQADamage 接管，此处防双算（QA 链路之外的 isGun 兜底）
        if (me.zombie_striker.qg.api.QualityArmory.isGun(attacker.inventory.itemInMainHand)) {
            event.isCancelled = true
            return
        }

        // 同队不误伤
        if (attackerTeam == victimTeam && !settings.debug) {
            event.isCancelled = true
            return
        }

        when {
            attackerTeam == GameTeam.HUMAN && victimTeam in setOf(GameTeam.ZOMBIE, GameTeam.ZOMBIE_MAIN) -> {
                event.isCancelled = true
                val damage = healthService.rules.swordDamage
                val dead = healthService.damage(victim.uniqueId, damage, attacker.uniqueId)
                eventBus?.publish(PlayerDamageDealtEvent(attacker.uniqueId, victim.uniqueId, damage))
                if (dead) victim.health = 0.0 else flashRed(victim)
            }
            attackerTeam in setOf(GameTeam.ZOMBIE, GameTeam.ZOMBIE_MAIN) && victimTeam == GameTeam.HUMAN -> {
                event.isCancelled = true
                val dmg = if (attackerTeam == GameTeam.ZOMBIE_MAIN) {
                    healthService.rules.zombieMainDamage
                } else {
                    healthService.rules.zombieDamage
                }
                val dead = healthService.damage(victim.uniqueId, dmg, attacker.uniqueId)
                if (dead) victim.health = 0.0 else flashRed(victim)
            }
            else -> Unit
        }
    }

    // ==================== 环境伤害 / 坠落 ====================

    @EventHandler(ignoreCancelled = true)
    fun onEntityDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val world = player.world.name
        if (!gameFlow.isArenaWorld(world)) return
        if (event.cause == EntityDamageEvent.DamageCause.FALL) {
            event.isCancelled = true
            return
        }
        // 爆炸伤害按配置减免（仅对人类）
        if (event.cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION ||
            event.cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
        ) {
            if (gameFlow.teamOf(world, player.uniqueId) == GameTeam.HUMAN) {
                event.damage *= healthService.rules.explosionDamageReduction
            }
        }
    }

    // ==================== 死亡 → 感染/击杀 ====================

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val victim = event.entity
        val world = victim.world.name
        // 非 arena 世界不接管死亡；不在当前对局名册中的玩家也不做任何转化
        if (!gameFlow.isArenaWorld(world)) return
        val victimTeam = gameFlow.teamOf(world, victim.uniqueId) ?: return

        event.isCancelled = true
        event.drops.clear()
        event.deathMessage(null)

        val killer = victim.killer
            ?: healthService.pollLastDamager(victim.uniqueId)?.let { Bukkit.getPlayer(it) }

        when (victimTeam) {
            GameTeam.HUMAN -> {
                val killerTeam = killer?.let { gameFlow.teamOf(world, it.uniqueId) }
                if (killer != null && killerTeam in setOf(GameTeam.ZOMBIE, GameTeam.ZOMBIE_MAIN)) {
                    infectHuman(killer!!, victim, world)
                } else {
                    // 环境死亡：直接转僵尸
                    convertToZombie(victim, world, "你已死亡并变为僵尸！")
                }
            }
            GameTeam.ZOMBIE, GameTeam.ZOMBIE_MAIN -> {
                val killerTeam = killer?.let { gameFlow.teamOf(world, it.uniqueId) }
                if (killer != null && killerTeam == GameTeam.HUMAN) {
                    val isMain = victimTeam == GameTeam.ZOMBIE_MAIN
                    gameFlow.onZombieKilled(world, killer.uniqueId, victim.uniqueId)
                    val coins = if (isMain) economy.killZombieMainCoins else economy.killZombieCoins
                    val xp = if (isMain) economy.killZombieMainXp else economy.killZombieXp
                    playerData?.addCoins(killer.uniqueId, coins)
                    playerData?.addXp(killer.uniqueId, xp)
                    killer.sendMessage(Component.text("+ $coins 硬币!", NamedTextColor.GOLD))
                }
                respawnZombie(victim, world)
            }
            // 其他队伍（SPECTATOR 等）不接管，允许服务器正常处理
            else -> Unit
        }
    }

    private fun infectHuman(attacker: Player, victim: Player, world: String) {
        gameFlow.onCombatInfection(world, attacker.uniqueId, victim.uniqueId)
        playerData?.addCoins(attacker.uniqueId, economy.infectHumanCoins)
        playerData?.addXp(attacker.uniqueId, economy.infectHumanXp)
        attacker.sendMessage(Component.text("+ ${economy.infectHumanCoins} 硬币！感染了一名人类", NamedTextColor.GOLD))
    }

    private fun convertToZombie(victim: Player, world: String, message: String) {
        victim.inventory.clear()
        victim.gameMode = GameMode.SPECTATOR
        victim.activePotionEffects.forEach { victim.removePotionEffect(it.type) }
        victim.health = 20.0
        healthService.resetForTeam(victim.uniqueId, GameTeam.ZOMBIE)
        gameFlow.onHumanDiedByEnvironment(world, victim.uniqueId, message)
    }

    private fun respawnZombie(victim: Player, world: String) {
        victim.inventory.clear()
        victim.gameMode = GameMode.SPECTATOR
        victim.activePotionEffects.forEach { victim.removePotionEffect(it.type) }
        victim.health = 20.0
        healthService.resetForTeam(victim.uniqueId, GameTeam.ZOMBIE)
        gameFlow.onZombieDiedRespawn(world, victim.uniqueId)
    }

    private fun flashRed(player: Player) {
        if (player.health > 0.5) {
            val before = player.health
            player.damage(0.01)
            player.health = before
        }
    }

    private val economy get() = settings.economy
}
