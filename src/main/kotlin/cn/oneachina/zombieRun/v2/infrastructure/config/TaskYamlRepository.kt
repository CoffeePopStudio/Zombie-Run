package cn.oneachina.zombierun.v2.infrastructure.config

import cn.oneachina.zombierun.v2.domain.task.TaskDefinition
import cn.oneachina.zombierun.v2.domain.task.TaskPeriod
import cn.oneachina.zombierun.v2.domain.task.TaskType
import cn.oneachina.zombierun.v2.support.V2Logger
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * v2 任务定义（默认生成空配置，管理员可手工添加）。
 * 配置示例：
 * tasks:
 *   daily_doors:
 *     description: 通过 5 扇门
 *     type: DOOR_PASSES
 *     target: 5
 *     reward-coins: 100
 *     reward-xp: 50
 *     period: DAILY
 */
class TaskYamlRepository(
    private val dataFolder: File,
    private val logger: V2Logger,
) {
    private val file = File(dataFolder, "config/tasks.yml")
    private var cache: List<TaskDefinition> = emptyList()

    fun loadAll(): List<TaskDefinition> {
        if (!file.exists()) {
            file.parentFile.mkdirs()
            file.writeText(defaultConfig)
            cache = emptyList()
            return cache
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        cache = yaml.getConfigurationSection("tasks")?.getKeys(false)?.mapNotNull { rawId ->
            val id: String = rawId ?: return@mapNotNull null
            val s = yaml.getConfigurationSection("tasks.$id") ?: return@mapNotNull null
            val type = TaskType.entries.firstOrNull { it.name.equals(s.getString("type"), ignoreCase = true) }
            val period = TaskPeriod.entries.firstOrNull { it.name.equals(s.getString("period"), ignoreCase = true) }
            if (type == null || period == null) {
                logger.warn("task $id skipped: invalid type or period")
                return@mapNotNull null
            }
            TaskDefinition(
                id = id,
                description = s.getString("description") ?: id,
                type = type,
                target = s.getInt("target", 1),
                rewardCoins = s.getInt("reward-coins", 0),
                rewardXp = s.getInt("reward-xp", 0),
                period = period,
            )
        } ?: emptyList()
        return cache
    }

    fun all(): List<TaskDefinition> = cache

    private val defaultConfig = """
        # v2 任务定义。空列表 = 未启用任务。
        # 管理员可自行添加每日/每周任务。
        tasks: {}
    """.trimIndent()
}