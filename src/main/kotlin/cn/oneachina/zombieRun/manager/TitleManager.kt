package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

    private val availableTitlesCache = ConcurrentHashMap<UUID, Set<String>>()

    fun getAvailableTitles(player: Player): List<Pair<String, String>> {
        val unlocks = getCachedUnlocks(player)
        val titles = mutableListOf<Pair<String, String>>()

        UNLOCK_TITLES.forEach { (key, name) ->
            if (key in unlocks) {
                titles.add(key to name)
            }
        }

        return titles
    }

    private fun getCachedUnlocks(player: Player): Set<String> {
        return availableTitlesCache.computeIfAbsent(player.uniqueId) {
            plugin.progressionManager.getUnlocks(player.uniqueId)
        }
    }

    fun invalidateUnlockCache(uuid: UUID) {
        availableTitlesCache.remove(uuid)
    }

    fun getPlayerTitle(player: Player): String {
        val equipped = plugin.progressionManager.getEquippedTitle(player.uniqueId)
        if (equipped != null) {
            val unlocks = getCachedUnlocks(player)
            val titleKey = UNLOCK_TITLES.entries.firstOrNull { it.value == equipped }?.key
            if (titleKey != null && titleKey in unlocks) return equipped
        }

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
