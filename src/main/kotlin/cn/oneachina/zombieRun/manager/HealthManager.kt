package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class HealthManager(private val plugin: ZombieRun) {

    private val healthMap = ConcurrentHashMap<UUID, Double>()
    private val maxHealthMap = ConcurrentHashMap<UUID, Double>()
    private val lastDamager = ConcurrentHashMap<UUID, UUID>()

    companion object {
        fun maxHealthForTeam(team: GameManager.Team): Double = when (team) {
            GameManager.Team.ZOMBIE_MAIN -> 500.0
            GameManager.Team.ZOMBIE -> 200.0
            else -> 20.0
        }
    }

    fun initPlayerHealth(player: Player, team: GameManager.Team) {
        val maxHp = maxHealthForTeam(team)
        maxHealthMap[player.uniqueId] = maxHp
        healthMap[player.uniqueId] = maxHp
        // 原版血量同步设为 20.0，防止被其他插件影响
        player.health = 20.0
    }

    fun damage(player: Player, amount: Double, damager: Player? = null) {
        if (damager != null) {
            lastDamager[player.uniqueId] = damager.uniqueId
        }

        val current = healthMap.getOrDefault(player.uniqueId, 0.0)
        val newHealth = (current - amount).coerceAtLeast(0.0)
        healthMap[player.uniqueId] = newHealth

        if (newHealth <= 0.0) {
            player.health = 0.0
        }
    }

    fun heal(player: Player, amount: Double) {
        val max = getMaxHealth(player)
        val current = getHealth(player)
        healthMap[player.uniqueId] = (current + amount).coerceAtMost(max)
    }

    fun getHealth(player: Player): Double = healthMap.getOrDefault(player.uniqueId, 0.0)

    fun getMaxHealth(player: Player): Double = maxHealthMap.getOrDefault(player.uniqueId, 20.0)

    fun getHealthPercent(player: Player): Double {
        val max = getMaxHealth(player)
        return if (max > 0) getHealth(player) / max else 0.0
    }

    fun getLastDamager(victimId: UUID): UUID? = lastDamager.remove(victimId)

    fun clear(player: Player) {
        healthMap.remove(player.uniqueId)
        maxHealthMap.remove(player.uniqueId)
        lastDamager.remove(player.uniqueId)
    }

    fun clearAll() {
        healthMap.clear()
        maxHealthMap.clear()
        lastDamager.clear()
    }
}
