package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.PlayerQuestProgress
import cn.oneachina.zombieRun.model.QuestDef
import cn.oneachina.zombieRun.model.QuestType
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.sql.Connection
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class QuestManager(private val plugin: ZombieRun) {

    companion object {
        private val DAILY_FIXED = listOf(
            QuestDef("daily_play", QuestType.PLAY_GAME, 2, 50, 0, "参与2场对局"),
            QuestDef("daily_door", QuestType.PASS_DOOR, 3, 80, 0, "通过3扇门")
        )
        private val DAILY_POOL = listOf(
            QuestDef("daily_kill", QuestType.KILL_ZOMBIE, 5, 120, 0, "击杀5只僵尸"),
            QuestDef("daily_infect", QuestType.INFECT_HUMAN, 3, 100, 0, "感染3名人类"),
            QuestDef("daily_alpha", QuestType.KILL_ALPHA, 1, 150, 0, "击杀1只母体"),
            QuestDef("daily_survive", QuestType.SURVIVE_TIME, 5, 0, 80, "人类存活5分钟"),
            QuestDef("daily_damage", QuestType.DEAL_DAMAGE, 200, 100, 0, "累计200点伤害")
        )
        private val WEEKLY_QUESTS = listOf(
            QuestDef("weekly_win", QuestType.HUMAN_WIN, 1, 500, 0, "人类阵营获胜1次"),
            QuestDef("weekly_alpha", QuestType.KILL_ALPHA, 3, 400, 300, "击杀母体3次"),
            QuestDef("weekly_door", QuestType.PASS_DOOR, 20, 300, 200, "累计通过20扇门")
        )
        private val SHANGHAI = ZoneId.of("Asia/Shanghai")
        private val DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE
    }

    private val dailyCache = ConcurrentHashMap<UUID, List<PlayerQuestProgress>>()
    private val weeklyCache = ConcurrentHashMap<UUID, List<PlayerQuestProgress>>()
    private var refreshTask: io.papermc.paper.threadedregions.scheduler.ScheduledTask? = null

    private fun getConnection(): Connection = plugin.progressionManager.getConnection()

    fun init() {
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        refreshTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { _ ->
            val now = LocalDate.now(SHANGHAI)
            checkDailyRefresh(now)
            checkWeeklyRefresh(now)
        }, 1L, 1200L)
    }

    private fun checkDailyRefresh(now: LocalDate) {
        dailyCache.clear()
    }

    private fun checkWeeklyRefresh(now: LocalDate) {
        if (now.dayOfWeek == DayOfWeek.MONDAY) {
            weeklyCache.clear()
        }
    }

    fun ensureQuests(player: Player) {
        val today = LocalDate.now(SHANGHAI).format(DATE_FMT)
        val monday = LocalDate.now(SHANGHAI).with(DayOfWeek.MONDAY).format(DATE_FMT)
        generateDailyIfNeeded(player.uniqueId, today)
        generateWeeklyIfNeeded(player.uniqueId, monday)
    }

    private fun generateDailyIfNeeded(uuid: UUID, dateKey: String) {
        if (dailyCache.containsKey(uuid)) return
        val poolPick = DAILY_POOL.shuffled().take(2)
        val allDefs = DAILY_FIXED + poolPick
        val quests = allDefs.map { PlayerQuestProgress("${it.id}_$dateKey", it, 0, false) }
        dailyCache[uuid] = quests
        loadQuestProgress(uuid, dateKey, quests)
    }

    private fun generateWeeklyIfNeeded(uuid: UUID, dateKey: String) {
        if (weeklyCache.containsKey(uuid)) return
        val quests = WEEKLY_QUESTS.map { PlayerQuestProgress("${it.id}_$dateKey", it, 0, false) }
        weeklyCache[uuid] = quests
        loadQuestProgress(uuid, dateKey, quests)
    }

    private fun loadQuestProgress(uuid: UUID, dateKey: String, quests: List<PlayerQuestProgress>) {
        CompletableFuture.runAsync {
            getConnection().use { conn ->
                conn.prepareStatement(
                    "SELECT quest_id, progress, completed FROM player_quests WHERE uuid = ? AND date = ?"
                ).use { stmt ->
                    stmt.setString(1, uuid.toString())
                    stmt.setString(2, dateKey)
                    val rs = stmt.executeQuery()
                    while (rs.next()) {
                        val qid = rs.getString("quest_id")
                        val progress = rs.getInt("progress")
                        val completed = rs.getInt("completed") == 1
                        quests.find { it.questId == qid }?.let { q ->
                            q.progress = progress
                            if (completed) q.completed = true
                        }
                    }
                }
            }
        }.get()
    }

    fun getDailyQuests(uuid: UUID): List<PlayerQuestProgress> = dailyCache[uuid] ?: emptyList()
    fun getWeeklyQuests(uuid: UUID): List<PlayerQuestProgress> = weeklyCache[uuid] ?: emptyList()

    fun incrementProgress(player: Player, type: QuestType, amount: Int) {
        val today = LocalDate.now(SHANGHAI).format(DATE_FMT)
        val uid = player.uniqueId

        val daily = dailyCache[uid] ?: return
        for (quest in daily) {
            if (quest.questDef.type == type && !quest.completed) {
                quest.progress = (quest.progress + amount).coerceAtMost(quest.questDef.target)
                if (quest.progress >= quest.questDef.target) {
                    completeQuest(player, quest, today)
                }
                saveQuestProgress(uid, quest, today)
            }
        }

        val weekly = weeklyCache[uid] ?: return
        val monday = LocalDate.now(SHANGHAI).with(DayOfWeek.MONDAY).format(DATE_FMT)
        for (quest in weekly) {
            if (quest.questDef.type == type && !quest.completed) {
                quest.progress = (quest.progress + amount).coerceAtMost(quest.questDef.target)
                if (quest.progress >= quest.questDef.target) {
                    completeQuest(player, quest, monday)
                }
                saveQuestProgress(uid, quest, monday)
            }
        }
    }

    private fun completeQuest(player: Player, quest: PlayerQuestProgress, dateKey: String) {
        val def = quest.questDef

        if (def.rewardXp > 0) {
            plugin.progressionManager.addXp(player, def.rewardXp, "任务：${def.desc}")
        }
        if (def.rewardCoins > 0) {
            plugin.coinManager.addCoins(player.uniqueId, def.rewardCoins)
        }

        val rewards = mutableListOf<String>()
        if (def.rewardXp > 0) rewards.add("${def.rewardXp} XP")
        if (def.rewardCoins > 0) rewards.add("${def.rewardCoins} 硬币")

        player.sendMessage(
            LegacyComponentSerializer.legacySection().deserialize(
                "§a✅ 任务完成：${def.desc} §7(+${rewards.joinToString(", ")})"
            )
        )

        CompletableFuture.runAsync {
            getConnection().use { conn ->
                conn.prepareStatement(
                    "INSERT OR REPLACE INTO player_quests (uuid, quest_id, progress, completed, date) VALUES (?, ?, ?, 1, ?)"
                ).use { stmt ->
                    stmt.setString(1, player.uniqueId.toString())
                    stmt.setString(2, quest.questId)
                    stmt.setInt(3, quest.progress)
                    stmt.setString(4, dateKey)
                    stmt.executeUpdate()
                }
            }
        }
    }

    private fun saveQuestProgress(uuid: UUID, quest: PlayerQuestProgress, dateKey: String) {
        CompletableFuture.runAsync {
            getConnection().use { conn ->
                conn.prepareStatement(
                    "INSERT OR REPLACE INTO player_quests (uuid, quest_id, progress, completed, date) VALUES (?, ?, ?, ?, ?)"
                ).use { stmt ->
                    stmt.setString(1, uuid.toString())
                    stmt.setString(2, quest.questId)
                    stmt.setInt(3, quest.progress)
                    stmt.setInt(4, if (quest.completed) 1 else 0)
                    stmt.setString(5, dateKey)
                    stmt.executeUpdate()
                }
            }
        }
    }

    fun invalidateCache(uuid: UUID) {
        dailyCache.remove(uuid)
        weeklyCache.remove(uuid)
    }
}
