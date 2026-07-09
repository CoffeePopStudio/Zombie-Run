package cn.oneachina.zombieRun.test

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.manager.DoorZoneManager
import cn.oneachina.zombieRun.model.Door
import org.bukkit.Location
import org.bukkit.World
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class DoorPerformanceTest(private val plugin: ZombieRun) {

    private val doorZoneManager = DoorZoneManager()
    private val optimizedDoorZoneManager = OptimizedDoorZoneManager()
    private val testDoors = mutableListOf<Door>()
    private val testLocations = mutableListOf<Location>()
    private val random = Random()

    fun setup() {
        // 创建测试门数据
        for (i in 1..100) {
            val minX = i * 10
            val minY = 0
            val minZ = i * 10
            val maxX = minX + 5
            val maxY = 3
            val maxZ = minZ + 5
            
            val door = Door(
                name = "test_door_$i",
                minX = minX,
                minY = minY,
                minZ = minZ,
                maxX = maxX,
                maxY = maxY,
                maxZ = maxZ,
                doorNumber = i
            ).apply {
                isOpen = true
            }
            testDoors.add(door)
        }
        
        // 初始化DoorZoneManager
        doorZoneManager.initialize(testDoors)
        optimizedDoorZoneManager.initialize(testDoors)
        
        // 创建测试位置数据
        val world = plugin.server.worlds.firstOrNull() ?: return
        for (i in 1..1000) {
            val x = random.nextInt(1000).toDouble()
            val y = random.nextInt(10).toDouble()
            val z = random.nextInt(1000).toDouble()
            testLocations.add(Location(world, x, y, z))
        }
    }

    fun runOriginalTest(): PerformanceResult {
        val results = mutableListOf<Long>()
        
        // 运行测试多次以获得更准确的结果
        for (i in 1..10) {
            val startTime = System.nanoTime()
            
            // 模拟玩家移动时的过门检测逻辑
            for (location in testLocations) {
                val doors = doorZoneManager.getDoorsInZone(location).filter { it.doorNumber >= 1 && it.isOpen }
                var currentDoorNumber = -1
                for (door in doors) {
                    if (door.containsLocation(location)) {
                        currentDoorNumber = door.doorNumber
                        break
                    }
                }
            }
            
            val endTime = System.nanoTime()
            results.add(endTime - startTime)
        }
        
        // 计算平均时间和标准差
        val average = results.average()
        val variance = results.map { (it - average) * (it - average) }.average()
        val stdDev = sqrt(variance)
        
        return PerformanceResult(
            average = average / 1_000_000, // 转换为毫秒
            stdDev = stdDev / 1_000_000, // 转换为毫秒
            min = results.minOrNull()?.toDouble()?.div(1_000_000) ?: 0.0,
            max = results.maxOrNull()?.toDouble()?.div(1_000_000) ?: 0.0
        )
    }

    fun runOptimizedTest(): PerformanceResult {
        val results = mutableListOf<Long>()
        
        // 运行测试多次以获得更准确的结果
        for (i in 1..10) {
            val startTime = System.nanoTime()
            
            // 模拟玩家移动时的过门检测逻辑（使用优化后的实现）
            for (location in testLocations) {
                val doors = optimizedDoorZoneManager.getDoorsInZone(location).filter { it.doorNumber >= 1 && it.isOpen }
                var currentDoorNumber = -1
                for (door in doors) {
                    if (door.containsLocation(location)) {
                        currentDoorNumber = door.doorNumber
                        break
                    }
                }
            }
            
            val endTime = System.nanoTime()
            results.add(endTime - startTime)
        }
        
        // 计算平均时间和标准差
        val average = results.average()
        val variance = results.map { (it - average) * (it - average) }.average()
        val stdDev = Math.sqrt(variance)
        
        return PerformanceResult(
            average = average / 1_000_000, // 转换为毫秒
            stdDev = stdDev / 1_000_000, // 转换为毫秒
            min = results.minOrNull()?.toDouble()?.div(1_000_000) ?: 0.0,
            max = results.maxOrNull()?.toDouble()?.div(1_000_000) ?: 0.0
        )
    }

    fun runComparisonTest(): String {
        val originalResult = runOriginalTest()
        val optimizedResult = runOptimizedTest()
        
        val improvement = ((originalResult.average - optimizedResult.average) / originalResult.average) * 100
        
        return """性能测试结果：
原始实现：$originalResult
优化实现：$optimizedResult
性能提升：%.2f%%
""".format(improvement)
    }

    data class PerformanceResult(
        val average: Double,
        val stdDev: Double,
        val min: Double,
        val max: Double
    ) {
        override fun toString(): String {
            return "PerformanceResult(average=%.3fms, stdDev=%.3fms, min=%.3fms, max=%.3fms)".format(
                average, stdDev, min, max
            )
        }
    }
}
