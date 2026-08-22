package cn.oneachina.zombierun.v2.ports

/**
 * v2 调度抽象：domain/application 不直接依赖 Bukkit 调度器，
 * Paper/Folia 的差异全部收敛到 infrastructure 实现。
 */
interface SchedulerPort {

    fun globalTimer(delayTicks: Long, periodTicks: Long, callback: (TaskHandle) -> Unit): TaskHandle

    fun globalLater(delayTicks: Long, action: () -> Unit): TaskHandle

    /**
     * 在指定区域线程执行方块/世界操作。
     * 调用方必须保证 [location] 的世界已加载。
     */
    fun regionExecute(location: RegionLocation, action: () -> Unit)
}

interface TaskHandle {
    fun cancel()
    val isCancelled: Boolean
}

data class RegionLocation(
    val worldName: String,
    val x: Double,
    val y: Double,
    val z: Double,
)
