package cn.oneachina.zombieRun.papi

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.GameManager
import cn.oneachina.zombieRun.manager.ProgressionManager
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer

class ZombieRunExpansion(private val plugin: ZombieRun) : PlaceholderExpansion() {
    override fun getIdentifier(): String = "zombierun"
    override fun getAuthor(): String = "oneachina"
    override fun getVersion(): String = plugin.pluginMeta.version

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        return when (params.lowercase()) {
            "human_count" -> plugin.gameManager.getHumans().size.toString()
            "zombie_count" -> (plugin.gameManager.getZombies().size + plugin.gameManager.getZombieMains().size).toString()
            "alpha_zombie_name" -> plugin.gameManager.alphaZombie?.name ?: ""
            "alpha_zombie_health" -> plugin.gameManager.alphaZombie?.health?.toInt()?.toString() ?: "0"
            "alpha_zombie_max_health" -> plugin.gameManager.alphaZombie?.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.baseValue?.toInt()?.toString() ?: "0"
            "alpha_zombie_health_percent" -> {
                val alpha = plugin.gameManager.alphaZombie ?: return "0.0"
                val max = alpha.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.baseValue ?: 500.0
                (alpha.health / max).toString()
            }
            "game_state" -> plugin.gameManager.getGameStatus().name
            "game_state_formatted" -> formatGameState(plugin.gameManager.getGameStatus())
            "time_left" -> {
                if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return "0"
                val elapsed = (System.currentTimeMillis() - plugin.gameManager.getGameStartTime()) / 1000
                val max = plugin.configManager.getMaxDuration()
                (max - elapsed).toInt().coerceAtLeast(0).toString()
            }
            "time_left_formatted" -> {
                if (plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) return "00:00"
                val elapsed = (System.currentTimeMillis() - plugin.gameManager.getGameStartTime()) / 1000
                val max = plugin.configManager.getMaxDuration()
                val left = (max - elapsed).toInt().coerceAtLeast(0)
                formatTime(left)
            }
            "min_players" -> plugin.configManager.getMinPlayers().toString()
            "max_players" -> plugin.configManager.getMaxPlayers().toString()
            "online_players" -> Bukkit.getOnlinePlayers().size.toString()
            "progress" -> {
                when (plugin.gameManager.getGameStatus()) {
                    GameManager.GameStatus.WAITING -> {
                        val online = Bukkit.getOnlinePlayers().size
                        val min = plugin.configManager.getMinPlayers()
                        (online.toDouble() / min).toString()
                    }
                    GameManager.GameStatus.RUNNING -> {
                        val elapsed = (System.currentTimeMillis() - plugin.gameManager.getGameStartTime()) / 1000.0
                        val max = plugin.configManager.getMaxDuration()
                        (elapsed / max).toString()
                    }
                    else -> "0.0"
                }
            }
            "bossbar" -> {
                when (plugin.gameManager.getGameStatus()) {
                    GameManager.GameStatus.WAITING -> {
                        val online = Bukkit.getOnlinePlayers().size
                        val min = plugin.configManager.getMinPlayers()
                        "<white>等待玩家... (<yellow>$online<white>/<yellow>$min<white>)"
                    }
                    GameManager.GameStatus.STARTING -> {
                        val remaining = plugin.gameManager.startCountdownTaskInstance?.getCountdown ?: 0
                        "<green>准备阶段 <gray>- <red>${remaining}s"
                    }
                    GameManager.GameStatus.RUNNING -> {
                        val humans = plugin.gameManager.getHumans().size
                        val zombies = plugin.gameManager.getZombies().size + plugin.gameManager.getZombieMains().size
                        "<aqua>人类: $humans  <red>僵尸: $zombies"
                    }
                    else -> "<gray>游戏结束"
                }
            }

            else -> {
                if (player == null || player.player == null) return ""
                val p = player.player!!
                when (params.lowercase()) {
                    "level" -> plugin.progressionManager.getLevel(p.uniqueId).toString()
                    "xp" -> plugin.progressionManager.getXp(p.uniqueId).toString()
                    "xp_max" -> {
                        val lvl = plugin.progressionManager.getLevel(p.uniqueId)
                        if (lvl >= ProgressionManager.MAX_LEVEL) "0"
                        else ProgressionManager.xpForLevel(lvl).toString()
                    }
                    "xp_percent" -> {
                        val lvl = plugin.progressionManager.getLevel(p.uniqueId)
                        if (lvl >= ProgressionManager.MAX_LEVEL) "1.0"
                        else (plugin.progressionManager.getXp(p.uniqueId).toDouble() / ProgressionManager.xpForLevel(lvl)).toString()
                    }
                    "title" -> plugin.titleManager.getPlayerTitle(p)
                    "total_kills" -> plugin.progressionManager.getTotalKills(p.uniqueId).toString()
                    "total_infections" -> plugin.progressionManager.getTotalInfections(p.uniqueId).toString()
                    "games_played" -> plugin.progressionManager.getGamesPlayed(p.uniqueId).toString()
                    "human_wins" -> plugin.progressionManager.getHumanWins(p.uniqueId).toString()
                    "coins" -> plugin.coinManager.getCoins(p.uniqueId).toString()
                    "kills" -> plugin.miscManager.getKills(p).toString()
                    "infections" -> plugin.miscManager.getInfections(p).toString()
                    "selected_weapon" -> plugin.miscManager.getSelectedWeapon(p)?.toString() ?: "0"
                    "room" -> plugin.gameManager.getPlayerRoom(p).toString()
                    "team" -> plugin.gameManager.getPlayerTeam(p).name
                    "team_formatted" -> formatTeam(plugin.gameManager.getPlayerTeam(p))
                    "stamina" -> plugin.staminaManager.getStamina(p).toInt().toString()
                    "max_stamina" -> plugin.staminaManager.getMaxStamina(p).toInt().toString()
                    "stamina_percent" -> (plugin.staminaManager.getStamina(p) / plugin.staminaManager.getMaxStamina(p)).toString()
                    "stamina_bar" -> buildStaminaBar(plugin.staminaManager.getStamina(p), plugin.staminaManager.getMaxStamina(p))
                    "stamina_state" -> plugin.staminaManager.getStaminaState(p).toString()
                    else -> null
                }
            }
        }
    }

    private fun formatGameState(state: GameManager.GameStatus): String {
        return when (state) {
            GameManager.GameStatus.WAITING -> "§7等待中"
            GameManager.GameStatus.STARTING -> "§e准备中"
            GameManager.GameStatus.RUNNING -> "§a进行中"
            GameManager.GameStatus.ENDED -> "§c已结束"
        }
    }

    private fun formatTeam(team: GameManager.Team): String {
        return when (team) {
            GameManager.Team.HUMAN -> "§b人类"
            GameManager.Team.ZOMBIE -> "§2僵尸"
            GameManager.Team.ZOMBIE_MAIN -> "§5母体"
            GameManager.Team.SPECTATOR -> "§7旁观"
        }
    }

    private fun buildStaminaBar(current: Double, max: Double): String {
        val percentage = current / max
        val totalBars = 20
        val filledBars = (percentage * totalBars).toInt().coerceIn(0, totalBars)
        val emptyBars = totalBars - filledBars
        val filled = "§a" + "█".repeat(filledBars)
        val empty = "§7" + "█".repeat(emptyBars)
        return filled + empty
    }

    private fun formatTime(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", mins, secs)
    }
}