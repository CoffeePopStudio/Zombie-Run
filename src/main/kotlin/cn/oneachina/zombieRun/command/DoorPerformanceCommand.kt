package cn.oneachina.zombieRun.command

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.test.DoorPerformanceTest
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class DoorPerformanceCommand(private val plugin: ZombieRun) : CommandExecutor {

    init {
        plugin.getCommand("doorperf")?.setExecutor(this)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("zombie.run.admin")) {
            sender.sendMessage("§c你没有权限执行此命令！")
            return true
        }

        sender.sendMessage("正在进行过门检测性能测试...")

        // 创建性能测试实例
        val performanceTest = DoorPerformanceTest(plugin)
        
        // 初始化测试数据
        performanceTest.setup()
        
        // 运行比较测试
        val result = performanceTest.runComparisonTest()
        
        // 发送测试结果
        sender.sendMessage(result)
        
        // 同时在控制台打印结果
        plugin.logger.info(result)
        
        return true
    }
}
