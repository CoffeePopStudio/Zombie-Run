package cn.oneachina.zombierun.v2.infrastructure.bukkit.hook

import cn.oneachina.zombierun.v2.application.game.GameFlowService
import cn.oneachina.zombierun.v2.application.player.PlayerDataService
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.entity.Player

/**
 * PlaceholderAPI 扩展，前缀 `zombierun`，兼容 v1 主要占位符名称。
 * 示例：%zombierun_profile_level%, %zombierun_profile_coins%, %zombierun_game_phase%
 */
class ZombieRunV2Expansion(
    private val playerData: PlayerDataService,
    private val gameFlow: GameFlowService,
) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "zombierun"

    override fun getAuthor(): String = "oneachina"

    override fun getVersion(): String = "2.0.0"

    override fun onPlaceholderRequest(player: Player?, params: String): String? {
        if (player == null) return null
        return when (params.lowercase()) {
            "profile_level", "zr_level", "level" -> playerData.profileOf(player.uniqueId).level.toString()
            "profile_xp", "zr_exp", "xp" -> playerData.profileOf(player.uniqueId).xp.toString()
            "profile_coins", "zr_money", "money", "coins" -> playerData.profileOf(player.uniqueId).coins.toString()
            "profile_title", "zr_title", "title" -> playerData.profileOf(player.uniqueId).title ?: ""
            "profile_kills", "zr_kills", "kills" -> playerData.profileOf(player.uniqueId).zombieKills.toString()
            "profile_doors", "zr_doors", "doors" -> playerData.profileOf(player.uniqueId).doorPasses.toString()
            "game_phase", "zr_phase", "phase" -> gameFlow.phaseOf(player.world.name)?.name?.lowercase() ?: "none"
            else -> null
        }
    }
}