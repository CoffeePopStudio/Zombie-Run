package cn.oneachina.zombieRun.model

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.GameManager
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

sealed class SpecialDoorBehavior {

    /** 执行传送，返回可取消的 ScheduledTask（即时行为返回 null） */
    abstract fun execute(context: ExecuteContext): ScheduledTask?

    data class ExecuteContext(
        val plugin: ZombieRun,
        val door: Door,
        val players: List<Player>,
        val world: World,
        val doorTasks: CopyOnWriteArrayList<ScheduledTask>
    )

    /** 按队伍分流传送 */
    protected fun teleportByTeams(
        players: List<Player>,
        world: World,
        humanTarget: Location,
        zombieTarget: Location?,
        plugin: ZombieRun
    ) {
        players.forEach { p ->
            val target = if (plugin.gameManager.getPlayerTeam(p) == GameManager.Team.HUMAN) {
                humanTarget
            } else {
                zombieTarget ?: humanTarget
            }
            p.teleportAsync(target)
        }
    }

    // ---- Elevator ----

    data class Elevator(
        val humanTargetY: Int,
        val zombieTargetY: Int? = null,
        val countdown: Int = 5,
        val departureMsg: String = "<yellow>电梯即将到达……</yellow>",
        val arrivalMsg: String = "<green>电梯已到达，祝您旅途愉快</green>"
    ) : SpecialDoorBehavior() {
        override fun execute(context: ExecuteContext): ScheduledTask {
            val mm = MiniMessage.miniMessage()
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
                            mm.deserialize(departureMsg),
                            Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(500))
                        ))
                    }
                    remaining--
                } else {
                    context.players.forEach { p ->
                        val hY = humanTargetY.toDouble()
                        val zY = zombieTargetY?.toDouble() ?: hY
                        val targetY = if (context.plugin.gameManager.getPlayerTeam(p) == GameManager.Team.HUMAN) hY else zY
                        p.teleportAsync(Location(context.world, p.location.x, targetY, p.location.z))
                        p.showTitle(Title.title(mm.deserialize(arrivalMsg), Component.empty()))
                    }
                    schedTask.cancel()
                }
            }, 1L, 20L)
            return task
        }
    }

    // ---- Subway ----

    data class Subway(
        val humanTargetX: Int,
        val humanTargetY: Int,
        val humanTargetZ: Int,
        val zombieTargetX: Int? = null,
        val zombieTargetY: Int? = null,
        val zombieTargetZ: Int? = null,
        val lineName: String = "1号线",
        val departureMsg: String = "<aqua>{line}即将发车……</aqua>",
        val arrivalMsg: String = "<green>{line}已到站，请有序下车</green>"
    ) : SpecialDoorBehavior() {
        override fun execute(context: ExecuteContext): ScheduledTask? {
            val mm = MiniMessage.miniMessage()
            val depMsg = departureMsg.replace("{line}", lineName)
            val arrMsg = arrivalMsg.replace("{line}", lineName)
            context.players.forEach { p ->
                p.showTitle(Title.title(mm.deserialize(depMsg), mm.deserialize(arrMsg)))
                val isHuman = context.plugin.gameManager.getPlayerTeam(p) == GameManager.Team.HUMAN
                val tx = if (isHuman) humanTargetX else (zombieTargetX ?: humanTargetX)
                val ty = if (isHuman) humanTargetY else (zombieTargetY ?: humanTargetY)
                val tz = if (isHuman) humanTargetZ else (zombieTargetZ ?: humanTargetZ)
                p.teleportAsync(Location(context.world, tx + 0.5, ty.toDouble(), tz + 0.5))
            }
            return null
        }
    }

    // ---- Airport ----

    data class Airport(
        val humanTargetX: Int,
        val humanTargetY: Int,
        val humanTargetZ: Int,
        val zombieTargetX: Int? = null,
        val zombieTargetY: Int? = null,
        val zombieTargetZ: Int? = null,
        val delayTicks: Long = 60,
        val departureMsg: String = "<green>感谢乘坐机场专线</green>",
        val arrivalMsg: String = "<yellow>请拿好你的行李，有序下车</yellow>"
    ) : SpecialDoorBehavior() {
        override fun execute(context: ExecuteContext): ScheduledTask {
            val mm = MiniMessage.miniMessage()
            context.players.forEach { p ->
                p.showTitle(Title.title(mm.deserialize(departureMsg), mm.deserialize(arrivalMsg)))
            }
            val task = Bukkit.getGlobalRegionScheduler().runDelayed(context.plugin, { _ ->
                context.players.forEach { p ->
                    val isHuman = context.plugin.gameManager.getPlayerTeam(p) == GameManager.Team.HUMAN
                    val tx = if (isHuman) humanTargetX else (zombieTargetX ?: humanTargetX)
                    val ty = if (isHuman) humanTargetY else (zombieTargetY ?: humanTargetY)
                    val tz = if (isHuman) humanTargetZ else (zombieTargetZ ?: humanTargetZ)
                    p.teleportAsync(Location(context.world, tx + 0.5, ty.toDouble(), tz + 0.5))
                }
            }, delayTicks)
            return task
        }
    }
}
