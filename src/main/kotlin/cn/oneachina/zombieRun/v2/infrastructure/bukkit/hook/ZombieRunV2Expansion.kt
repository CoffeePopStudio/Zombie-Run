package cn.oneachina.zombierun.v2.infrastructure.bukkit.hook

import cn.oneachina.zombierun.v2.application.combat.CombatHealthService
import cn.oneachina.zombierun.v2.application.combat.StaminaService
import cn.oneachina.zombierun.v2.application.game.GameFlowService
import cn.oneachina.zombierun.v2.application.player.PlayerDataService
import cn.oneachina.zombierun.v2.application.weapon.WeaponService
import cn.oneachina.zombierun.v2.domain.game.GameTeam
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.entity.Player

/**
 * PlaceholderAPI 扩展，前缀 `zombierun`，对齐 v1 主要占位符名称。
 * 示例：%zombierun_profile_level%, %zombierun_human_count%, %zombierun_game_phase%
 */
class ZombieRunV2Expansion(
    private val playerData: PlayerDataService,
    private val gameFlow: GameFlowService,
    private val staminaService: StaminaService? = null,
    private val weaponService: WeaponService? = null,
    private val healthService: CombatHealthService? = null,
) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "zombierun"

    override fun getAuthor(): String = "oneachina"

    override fun getVersion(): String = "2.0.0"

    override fun onPlaceholderRequest(player: Player?, params: String): String? {
        if (player == null) return null
        val world = player.world.name
        val profile = playerData.profileOf(player.uniqueId)
        return when (params.lowercase()) {
            // ---- 玩家档案 ----
            "profile_level", "zr_level", "level" -> profile.level.toString()
            "profile_xp", "zr_exp", "xp" -> profile.xp.toString()
            "xp_max" -> profile.nextLevelXp().toString()
            "xp_percent" -> if (profile.nextLevelXp() > 0) {
                (profile.xp.toDouble() / profile.nextLevelXp()).toString()
            } else "0"
            "profile_coins", "zr_money", "money", "coins" -> profile.coins.toString()
            "profile_title", "zr_title", "title" -> profile.title ?: ""
            "profile_kills", "zr_kills", "kills" -> profile.zombieKills.toString()
            "profile_doors", "zr_doors", "doors" -> profile.doorPasses.toString()
            "total_kills" -> profile.zombieKills.toString()
            "total_infections" -> profile.totalInfections.toString()
            "games_played" -> profile.gamesPlayed.toString()
            "human_wins" -> profile.humanWins.toString()
            "selected_weapon" -> weaponService?.selectedWeaponId(player.uniqueId) ?: ""

            // ---- 对局世界 ----
            "game_phase", "zr_phase", "phase" -> gameFlow.gameStateFormatted(world)
            "game_state" -> gameFlow.gameStateFormatted(world)
            "game_state_formatted" -> gameFlow.gameStateFormatted(world)
            "human_count" -> gameFlow.humanCount(world).toString()
            "zombie_count" -> gameFlow.zombieCount(world).toString()
            "online_players" -> gameFlow.onlinePlayers(world).toString()
            "min_players" -> gameFlow.minPlayers(world).toString()
            "max_players" -> gameFlow.maxPlayers(world).toString().ifEmpty { "0" }
            "time_left" -> gameFlow.timeLeftSeconds(world).toString()
            "time_left_formatted" -> formatTime(gameFlow.timeLeftSeconds(world))
            "progress" -> gameFlow.progressPercent(world).toString()
            "bossbar" -> gameFlow.progressPercent(world).toString()

            // ---- 母体 ----
            "alpha_zombie_name" -> gameFlow.alphaName(world)
            "alpha_zombie_health" -> gameFlow.alphaId(world)?.let { healthService?.getHealth(it)?.toInt()?.toString() } ?: "0"
            "alpha_zombie_max_health" -> gameFlow.alphaId(world)?.let { healthService?.getMaxHealth(it)?.toInt()?.toString() } ?: "0"
            "alpha_zombie_health_percent" -> gameFlow.alphaId(world)?.let { healthService?.getHealthPercent(it)?.toString() } ?: "0"

            // ---- 玩家对局状态 ----
            "team" -> gameFlow.teamOf(world, player.uniqueId)?.name?.lowercase() ?: "none"
            "team_formatted" -> when (gameFlow.teamOf(world, player.uniqueId)) {
                GameTeam.HUMAN -> "人类"
                GameTeam.ZOMBIE -> "僵尸"
                GameTeam.ZOMBIE_MAIN -> "母体"
                GameTeam.SPECTATOR -> "观战"
                null -> "等待"
            }
            "room" -> gameFlow.roomOf(world, player.uniqueId).toString()

            // ---- 体力 ----
            "stamina", "stamina_percent" -> staminaService?.fraction(player.uniqueId)?.toString() ?: "0"
            "stamina_bar" -> staminaService?.let {
                val pct = it.fraction(player.uniqueId).coerceIn(0.0, 1.0)
                val filled = (pct * 10).toInt().coerceIn(0, 10)
                "█".repeat(filled) + "░".repeat(10 - filled)
            } ?: ""
            "max_stamina" -> staminaService?.rules?.max?.toString() ?: "0"
            "stamina_state" -> staminaService?.stateOf(player.uniqueId)?.status?.name?.lowercase() ?: "normal"

            // ---- v1 兼容别名 ----
            "infections" -> profile.totalInfections.toString()
            else -> null
        }
    }

    private fun formatTime(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%02d:%02d".format(m, s)
    }
}
