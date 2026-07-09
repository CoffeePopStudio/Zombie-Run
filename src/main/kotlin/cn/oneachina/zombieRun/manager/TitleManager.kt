package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import org.bukkit.entity.Player

class TitleManager(private val plugin: ZombieRun) {

    companion object {
        val UNLOCK_TITLES = mapOf(
            "title_survivor" to "幸存者",
            "title_escape_expert" to "逃生专家",
            "title_elite_agent" to "精英特工",
            "title_legend" to "传奇",
            "title_light_of_hope" to "希望之光",
            "title_butcher" to "屠夫",
            "title_undying" to "不死之身"
        )

        fun getDefaultTitle(level: Int): String {
            return when {
                level >= 50 -> "传奇"
                level >= 20 -> "精英特工"
                level >= 10 -> "逃生专家"
                level >= 5 -> "幸存者"
                else -> ""
            }
        }
    }

    fun getAvailableTitles(player: Player): List<Pair<String, String>> {
        val unlocks = plugin.progressionManager.getUnlocks(player.uniqueId)
        val titles = mutableListOf<Pair<String, String>>()

        UNLOCK_TITLES.forEach { (key, name) ->
            if (key in unlocks) {
                titles.add(key to name)
            }
        }

        return titles
    }

    fun getPlayerTitle(player: Player): String {
        val equipped = plugin.progressionManager.getEquippedTitle(player.uniqueId)
        if (equipped != null && equipped in UNLOCK_TITLES.values) return equipped

        val level = plugin.progressionManager.getLevel(player.uniqueId)
        return getDefaultTitle(level)
    }

    fun equipTitle(player: Player, titleName: String?): Boolean {
        if (titleName == null || titleName.isEmpty()) {
            plugin.progressionManager.setEquippedTitle(player.uniqueId, null)
            return true
        }
        val available = getAvailableTitles(player)
        if (available.none { it.second == titleName }) return false
        plugin.progressionManager.setEquippedTitle(player.uniqueId, titleName)
        return true
    }
}
