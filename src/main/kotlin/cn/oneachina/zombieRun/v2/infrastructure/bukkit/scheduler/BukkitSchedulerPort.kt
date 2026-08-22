package cn.oneachina.zombierun.v2.infrastructure.bukkit.scheduler

import cn.oneachina.zombierun.v2.ports.RegionLocation
import cn.oneachina.zombierun.v2.ports.SchedulerPort
import cn.oneachina.zombierun.v2.ports.TaskHandle
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.plugin.java.JavaPlugin

class BukkitSchedulerPort(private val plugin: JavaPlugin) : SchedulerPort {

    override fun globalTimer(
        delayTicks: Long,
        periodTicks: Long,
        callback: (TaskHandle) -> Unit,
    ): TaskHandle {
        val handle = BukkitTaskHandle()
        val task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { _ ->
            callback(handle)
        }, delayTicks, periodTicks)
        handle.bind(task)
        return handle
    }

    override fun globalLater(delayTicks: Long, action: () -> Unit): TaskHandle {
        val handle = BukkitTaskHandle()
        val task = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
            action()
        }, delayTicks)
        handle.bind(task)
        return handle
    }

    override fun regionExecute(location: RegionLocation, action: () -> Unit) {
        val world = Bukkit.getWorld(location.worldName) ?: return
        Bukkit.getRegionScheduler().execute(
            plugin,
            Location(world, location.x, location.y, location.z)
        ) {
            action()
        }
    }
}

class BukkitTaskHandle : TaskHandle {
    @Volatile
    private var task: ScheduledTask? = null

    fun bind(task: ScheduledTask) {
        this.task = task
    }

    override fun cancel() {
        task?.cancel()
    }

    override val isCancelled: Boolean
        get() = task?.isCancelled ?: true
}
