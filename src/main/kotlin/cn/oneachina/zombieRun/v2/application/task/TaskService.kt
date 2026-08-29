package cn.oneachina.zombierun.v2.application.task

import cn.oneachina.zombierun.v2.application.event.ApplicationEventBus
import cn.oneachina.zombierun.v2.application.event.GameEndedEvent
import cn.oneachina.zombierun.v2.application.event.GameStartedEvent
import cn.oneachina.zombierun.v2.application.event.InfectHumanEvent
import cn.oneachina.zombierun.v2.application.event.PlayerDamageDealtEvent
import cn.oneachina.zombierun.v2.application.event.PlayerPassedDoorEvent
import cn.oneachina.zombierun.v2.application.event.ZombieKilledEvent
import cn.oneachina.zombierun.v2.application.player.PlayerDataService
import cn.oneachina.zombierun.v2.domain.task.TaskDefinition
import cn.oneachina.zombierun.v2.domain.task.TaskPeriod
import cn.oneachina.zombierun.v2.domain.task.TaskProgress
import cn.oneachina.zombierun.v2.domain.task.TaskType
import cn.oneachina.zombierun.v2.infrastructure.config.TaskYamlRepository
import cn.oneachina.zombierun.v2.ports.PlayerMessagePort
import cn.oneachina.zombierun.v2.ports.PlayerTaskPort
import cn.oneachina.zombierun.v2.support.V2Logger
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 任务进度服务：事件总线驱动计数，完成后可领取硬币/经验奖励。
 *
 * 每日任务按自然日重置，每周任务按 ISO 周重置。
 * 支持 v1 对齐的 8 类任务，并提供固定任务 + 按玩家/周期确定性随机的随机池。
 */
class TaskService(
    private val storage: PlayerTaskPort,
    private val taskRepository: TaskYamlRepository,
    private val playerData: PlayerDataService,
    private val messages: PlayerMessagePort,
    private val logger: V2Logger,
    private val eventBus: ApplicationEventBus,
) {
    private var configuredDefinitions: List<TaskDefinition> = taskRepository.loadAll()
    private val cache = ConcurrentHashMap<UUID, ConcurrentHashMap<String, TaskProgress>>()

    init {
        eventBus.subscribe(PlayerPassedDoorEvent::class.java) { event ->
            increment(event.playerId, TaskType.DOOR_PASSES, event.doorNumbers.size.coerceAtLeast(1))
        }
        eventBus.subscribe(ZombieKilledEvent::class.java) { event ->
            increment(event.killerId, TaskType.ZOMBIE_KILLS, 1)
            if (event.victimTeam == "ZOMBIE_MAIN") {
                increment(event.killerId, TaskType.KILL_ALPHA, 1)
            }
        }
        eventBus.subscribe(InfectHumanEvent::class.java) { event ->
            increment(event.attackerId, TaskType.INFECT_HUMAN, 1)
        }
        eventBus.subscribe(GameStartedEvent::class.java) { event ->
            event.playerAssignments.keys.forEach { playerId ->
                increment(playerId, TaskType.PLAY_GAME, 1)
            }
        }
        eventBus.subscribe(GameEndedEvent::class.java) { event ->
            if (event.winner == "HUMAN") {
                event.winnerPlayerIds.forEach { playerId ->
                    increment(playerId, TaskType.HUMAN_WIN, 1)
                    increment(playerId, TaskType.SURVIVE_TIME, 1)
                }
            }
        }
        eventBus.subscribe(PlayerDamageDealtEvent::class.java) { event ->
            increment(event.attackerId, TaskType.DEAL_DAMAGE, event.damage.toInt().coerceAtLeast(1))
        }
    }

    /** 所有可选任务（配置 + 内置池，用于 Tab 补全等展示）。 */
    fun allTasks(): List<TaskDefinition> =
        (configuredDefinitions + FIXED_DAILY + RANDOM_DAILY + FIXED_WEEKLY + RANDOM_WEEKLY)
            .distinctBy { it.id }

    /** 热重载 tasks.yml。已缓存的玩家进度保留，但定义列表会更新。 */
    fun reload() {
        configuredDefinitions = taskRepository.loadAll()
        logger.info("task definitions reloaded: ${configuredDefinitions.size}")
    }

    /**
     * 返回所有任务的进度快照。过期任务（自然日/ISO 周已切换）在这里统一重置，
     * 保证 GUI/命令展示与领取判定都基于当前周期。
     */
    fun progressOf(playerId: UUID): List<Pair<TaskDefinition, TaskProgress>> {
        val progress = playerProgress(playerId)
        val today = LocalDate.now()
        var changed = false
        val snapshot = synchronized(progress) {
            definitionsFor(playerId, today).map { task ->
                val p = progress.computeIfAbsent(task.id) {
                    TaskProgress(task.id).also { it.lastReset = task.periodKey(today) }
                }
                if (p.isExpired(task, today)) {
                    p.reset(task, today)
                    changed = true
                }
                task to p
            }
        }
        if (changed) save(playerId)
        return snapshot
    }

    fun onJoin(playerId: UUID) {
        playerProgress(playerId)
    }

    fun onQuit(playerId: UUID) {
        cache.remove(playerId)?.let { storage.save(playerId, it) }
    }

    fun claim(playerId: UUID, taskId: String): String {
        val today = LocalDate.now()
        val task = definitionsFor(playerId, today).firstOrNull { it.id == taskId }
            ?: return "任务不存在：$taskId"
        val progress = playerProgress(playerId)
        synchronized(progress) {
            val p = progress.computeIfAbsent(taskId) {
                TaskProgress(taskId).also { it.lastReset = task.periodKey(today) }
            }
            if (p.isExpired(task, today)) p.reset(task, today)
            if (!p.completed(task.target)) return "任务尚未完成"
            if (p.claimed) return "该任务奖励已领取"

            p.claimed = true
            if (task.rewardCoins > 0) playerData.addCoins(playerId, task.rewardCoins)
            if (task.rewardXp > 0) playerData.addXp(playerId, task.rewardXp)
            save(playerId)
            messages.chat(playerId, "任务完成：${task.description}，奖励 ${task.rewardCoins} 硬币 / ${task.rewardXp} 经验")
            logger.info("player $playerId claimed task $taskId")
            return "已领取任务奖励：${task.description}"
        }
    }

    fun close() {
        cache.forEach { (playerId, progress) -> storage.save(playerId, progress) }
        cache.clear()
        storage.close()
    }

    private fun playerProgress(playerId: UUID): ConcurrentHashMap<String, TaskProgress> =
        cache.computeIfAbsent(playerId) {
            ConcurrentHashMap(storage.load(it))
        }

    private fun definitionsFor(playerId: UUID, today: LocalDate): List<TaskDefinition> {
        val dailyRandom = Random(playerId.hashCode() * 31 + today.dayOfYear)
        val weeklyRandom = Random(playerId.hashCode() * 37 + today.get(WeekFields.of(Locale.ROOT).weekOfWeekBasedYear()))
        val daily = FIXED_DAILY + RANDOM_DAILY.shuffled(dailyRandom).take(2)
        val weekly = FIXED_WEEKLY + RANDOM_WEEKLY.shuffled(weeklyRandom).take(2)
        return (configuredDefinitions + daily + weekly).distinctBy { it.id }
    }

    private fun increment(playerId: UUID, type: TaskType, amount: Int) {
        val progress = playerProgress(playerId)
        val today = LocalDate.now()
        var changed = false
        synchronized(progress) {
            definitionsFor(playerId, today).filter { it.type == type }.forEach { task ->
                val p = progress.computeIfAbsent(task.id) {
                    TaskProgress(task.id).also { it.lastReset = task.periodKey(today) }
                }
                if (p.isExpired(task, today)) {
                    p.reset(task, today)
                    changed = true
                }
                if (p.claimed) return@forEach
                val before = p.progress
                p.progress = minOf(task.target, p.progress + amount)
                if (p.progress != before) {
                    changed = true
                    if (p.completed(task.target)) {
                        messages.chat(playerId, "任务完成：${task.description}（${task.rewardCoins} 硬币 / ${task.rewardXp} 经验）")
                    }
                }
            }
        }
        if (changed) save(playerId)
    }

    private fun save(playerId: UUID) {
        cache[playerId]?.let { storage.save(playerId, it) }
    }

    private fun TaskProgress.isExpired(task: TaskDefinition, today: LocalDate): Boolean =
        lastReset != null && lastReset != task.periodKey(today)

    private fun TaskProgress.reset(task: TaskDefinition, today: LocalDate) {
        progress = 0
        claimed = false
        lastReset = task.periodKey(today)
    }

    private fun TaskDefinition.periodKey(today: LocalDate): String =
        if (period == TaskPeriod.DAILY) {
            today.toString()
        } else {
            val week = today.get(WeekFields.of(Locale.ROOT).weekBasedYear()) * 100 +
                today.get(WeekFields.of(Locale.ROOT).weekOfWeekBasedYear())
            week.toString()
        }

    companion object {
        private val FIXED_DAILY = listOf(
            TaskDefinition("daily_doors", "通过 5 扇门", TaskType.DOOR_PASSES, 5, 50, 10, TaskPeriod.DAILY),
            TaskDefinition("daily_zombie", "击杀 3 只僵尸", TaskType.ZOMBIE_KILLS, 3, 80, 20, TaskPeriod.DAILY),
        )

        private val RANDOM_DAILY = listOf(
            TaskDefinition("daily_alpha", "击杀 1 次母体", TaskType.KILL_ALPHA, 1, 120, 30, TaskPeriod.DAILY),
            TaskDefinition("daily_infect", "感染 2 名人类", TaskType.INFECT_HUMAN, 2, 80, 20, TaskPeriod.DAILY),
            TaskDefinition("daily_damage", "造成 200 点伤害", TaskType.DEAL_DAMAGE, 200, 100, 30, TaskPeriod.DAILY),
            TaskDefinition("daily_survive", "作为人类存活 1 次胜利", TaskType.SURVIVE_TIME, 1, 120, 30, TaskPeriod.DAILY),
        )

        private val FIXED_WEEKLY = listOf(
            TaskDefinition("weekly_play", "参与 5 场对局", TaskType.PLAY_GAME, 5, 200, 50, TaskPeriod.WEEKLY),
            TaskDefinition("weekly_win", "赢得 3 场人类胜利", TaskType.HUMAN_WIN, 3, 250, 60, TaskPeriod.WEEKLY),
        )

        private val RANDOM_WEEKLY = listOf(
            TaskDefinition("weekly_alpha", "击杀 2 次母体", TaskType.KILL_ALPHA, 2, 300, 80, TaskPeriod.WEEKLY),
            TaskDefinition("weekly_infect", "感染 5 名人类", TaskType.INFECT_HUMAN, 5, 250, 60, TaskPeriod.WEEKLY),
            TaskDefinition("weekly_damage", "造成 1000 点伤害", TaskType.DEAL_DAMAGE, 1000, 300, 80, TaskPeriod.WEEKLY),
            TaskDefinition("weekly_survive", "作为人类存活 3 次胜利", TaskType.SURVIVE_TIME, 3, 250, 60, TaskPeriod.WEEKLY),
        )
    }
}

private fun TaskProgress.completed(target: Int): Boolean = progress >= target
