package cn.oneachina.zombierun.v2.application.task

import cn.oneachina.zombierun.v2.application.event.ApplicationEventBus
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

/**
 * 任务进度服务：事件总线驱动计数，完成后可领取硬币/经验奖励。
 * 每日任务按自然日重置，每周任务按 ISO 周重置。
 */
class TaskService(
    private val storage: PlayerTaskPort,
    private val taskRepository: TaskYamlRepository,
    private val playerData: PlayerDataService,
    private val messages: PlayerMessagePort,
    private val logger: V2Logger,
    private val eventBus: ApplicationEventBus,
) {
    private val definitions: List<TaskDefinition> = taskRepository.loadAll()
    private val cache = ConcurrentHashMap<UUID, ConcurrentHashMap<String, TaskProgress>>()

    init {
        eventBus.subscribe(PlayerPassedDoorEvent::class.java) { event ->
            increment(event.playerId, TaskType.DOOR_PASSES, event.doorNumbers.size.coerceAtLeast(1))
        }
        eventBus.subscribe(ZombieKilledEvent::class.java) { event ->
            increment(event.killerId, TaskType.ZOMBIE_KILLS, 1)
        }
    }

    fun allTasks(): List<TaskDefinition> = definitions

    /**
     * 返回所有任务的进度快照。过期任务（自然日/ISO 周已切换）在这里统一重置，
     * 保证 GUI/命令展示与领取判定都基于当前周期。
     */
    fun progressOf(playerId: UUID): List<Pair<TaskDefinition, TaskProgress>> {
        val progress = playerProgress(playerId)
        val today = LocalDate.now()
        var changed = false
        val snapshot = synchronized(progress) {
            definitions.map { task ->
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
        val task = definitions.firstOrNull { it.id == taskId }
            ?: return "任务不存在：$taskId"
        val progress = playerProgress(playerId)
        synchronized(progress) {
            val today = LocalDate.now()
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

    private fun increment(playerId: UUID, type: TaskType, amount: Int) {
        val progress = playerProgress(playerId)
        val today = LocalDate.now()
        var changed = false
        synchronized(progress) {
            definitions.filter { it.type == type }.forEach { task ->
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
}

private fun TaskProgress.completed(target: Int): Boolean = progress >= target