package cn.oneachina.zombieRun.model

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.GameManager
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 特殊门行为抽象基类。
 * 每种行为类型封装自己的倒计时、Title 提示、传送逻辑。
 * 未来新增类型只需添加新的 data class 并实现 [execute]。
 */
sealed class SpecialDoorBehavior {

    /** 执行传送行为，返回可取消的 ScheduledTask */
    abstract fun execute(context: ExecuteContext): ScheduledTask

    data class ExecuteContext(
        val plugin: ZombieRun,
        val door: Door,
        val players: List<Player>,
        val world: World,
        val doorTasks: CopyOnWriteArrayList<ScheduledTask>
    )

    /** 电梯：垂直传送（保留玩家 X/Z，仅改变 Y） */
    data class Elevator(
        val targetY: Int,
        val countdown: Int = 5,
        val departureMsg: String = "&e电梯即将到达……",
        val arrivalMsg: String = "&a电梯已到达，祝您旅途愉快"
    ) : SpecialDoorBehavior() {
        override fun execute(context: ExecuteContext): ScheduledTask {
            var remaining = countdown
            val task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(context.plugin, { schedTask ->
                if (context.plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
                    schedTask.cancel()
                    return@runAtFixedRate
                }
                if (remaining > 0) {
                    context.players.forEach { p ->
                        p.showTitle(Title.title(
                            Component.text("$remaining", NamedTextColor.GREEN),
                            LegacyComponentSerializer.legacySection().deserialize(departureMsg),
                            Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(500))
                        ))
                    }
                    remaining--
                } else {
                    context.players.forEach { p ->
                        val target = Location(context.world, p.location.x, targetY.toDouble(), p.location.z)
                        p.teleportAsync(target)
                        p.showTitle(Title.title(
                            LegacyComponentSerializer.legacySection().deserialize(arrivalMsg),
                            Component.empty()
                        ))
                    }
                    schedTask.cancel()
                }
            }, 1L, 20L)
            return task
        }
    }

    /** 地铁：传送到固定目标坐标 */
    data class Subway(
        val targetX: Int,
        val targetY: Int,
        val targetZ: Int,
        val lineName: String = "1号线",
        val countdown: Int = 10,
        val departureMsg: String = "&b%s即将发车，请站稳扶好……".format(lineName),
        val arrivalMsg: String = "&a%s已到站，请有序下车".format(lineName)
    ) : SpecialDoorBehavior() {
        override fun execute(context: ExecuteContext): ScheduledTask {
            var remaining = countdown
            val task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(context.plugin, { schedTask ->
                if (context.plugin.gameManager.getGameStatus() != GameManager.GameStatus.RUNNING) {
                    schedTask.cancel()
                    return@runAtFixedRate
                }
                if (remaining > 0) {
                    context.players.forEach { p ->
                        p.showTitle(Title.title(
                            Component.text("$remaining", NamedTextColor.AQUA),
                            LegacyComponentSerializer.legacySection().deserialize(departureMsg),
                            Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(500))
                        ))
                    }
                    remaining--
                } else {
                    val target = Location(context.world, targetX + 0.5, targetY.toDouble(), targetZ + 0.5)
                    context.players.forEach { p ->
                        p.teleportAsync(target)
                        p.showTitle(Title.title(
                            LegacyComponentSerializer.legacySection().deserialize(arrivalMsg),
                            Component.empty()
                        ))
                    }
                    schedTask.cancel()
                }
            }, 1L, 20L)
            return task
        }
    }

    /** 机场专线：延迟一段时间后传送到固定坐标（不倒数、不等待） */
    data class Airport(
        val targetX: Int,
        val targetY: Int,
        val targetZ: Int,
        val delayTicks: Long = 60,
        val departureMsg: String = "&a感谢乘坐机场专线",
        val arrivalMsg: String = "&e请拿好你的行李，有序下车"
    ) : SpecialDoorBehavior() {
        override fun execute(context: ExecuteContext): ScheduledTask {
            context.players.forEach { p ->
                p.showTitle(Title.title(
                    LegacyComponentSerializer.legacySection().deserialize(departureMsg),
                    LegacyComponentSerializer.legacySection().deserialize(arrivalMsg)
                ))
            }
            val target = Location(context.world, targetX + 0.5, targetY.toDouble(), targetZ + 0.5)
            val task = Bukkit.getGlobalRegionScheduler().runDelayed(context.plugin, { _ ->
                context.players.forEach { p -> p.teleportAsync(target) }
            }, delayTicks)
            return task
        }
    }
}
