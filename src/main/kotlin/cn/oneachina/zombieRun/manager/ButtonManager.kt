package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.Button
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import java.util.concurrent.ConcurrentHashMap

class ButtonManager(private val plugin: ZombieRun) {

    private val buttons: ConcurrentHashMap<String, Button> = ConcurrentHashMap()
    // 区域线程写入（点亮按钮）、全局调度线程读取（游戏重置），需线程安全
    private val originalButtonBlocks: ConcurrentHashMap<Location, Material> = ConcurrentHashMap()

    fun loadButtons() {
        // 先还原被点亮的按钮方块，避免 reload 后残留海晶灯
        resetAllButtons()
        buttons.clear()
        originalButtonBlocks.clear()
        val loadedButtons = plugin.configManager.loadButtons()
        loadedButtons.forEach { button ->
            buttons[button.name] = button
            plugin.logger.info("按钮 '${button.name}' 加载成功，模式: ${button.mode}")
        }
        plugin.logger.info("共加载 ${buttons.size} 个按钮")
    }

    fun getButton(worldName: String, x: Int, y: Int, z: Int): Button? {
        return buttons.values.find { it.world == worldName && it.matches(x, y, z) }
    }

    fun getAllButtons(): Collection<Button> {
        return buttons.values
    }

    fun getButtonByDoorNumber(worldName: String, doorNumber: Int): Button? {
        return buttons.values.find { it.world == worldName && it.isNormal() && it.doorNumber == doorNumber }
    }

    fun getButtonsInWorld(worldName: String): List<Button> {
        return buttons.values.filter { it.world == worldName }
    }

    fun addButton(button: Button) {
        buttons[button.name] = button
    }

    fun removeButton(name: String) {
        buttons.remove(name)
    }

    fun setButtonLit(button: Button) {
        val world = plugin.worldService.getWorldOrFirst(button.world)
        // Folia：方块读写必须在按钮所属区域线程执行（命令/全局调度线程均可能触发）
        val loc = Location(world, button.x.toDouble(), button.y.toDouble(), button.z.toDouble())
        Bukkit.getRegionScheduler().execute(plugin, loc) {
            val block = world.getBlockAt(button.x, button.y, button.z)
            if (block.type == Material.REDSTONE_LAMP || block.type == Material.LEVER) {
                originalButtonBlocks[block.location] = block.type
                block.type = Material.SEA_LANTERN
            }
        }
    }

    /** 仅还原指定世界被点亮的按钮（多世界隔离，避免误还原其他世界的按钮） */
    fun resetButtonsInWorld(world: String) {
        // 先快照再移除，避免与区域线程的点亮操作在弱一致迭代下互相干扰
        val toReset = originalButtonBlocks.entries.filter { it.key.world?.name == world }.toList()
        toReset.forEach { (loc, type) ->
            originalButtonBlocks.remove(loc)
            Bukkit.getRegionScheduler().execute(plugin, loc) {
                // 仅当该位置未被重新点亮时才还原，避免覆盖新一轮的点亮状态
                if (!originalButtonBlocks.containsKey(loc)) {
                    loc.block.type = type
                }
            }
        }
    }

    /** 还原所有被点亮的按钮（运行时路径：调度到各方块所属区域线程执行） */
    fun resetAllButtons() {
        val toReset = originalButtonBlocks.entries.toList()
        originalButtonBlocks.clear()
        toReset.forEach { (loc, type) ->
            Bukkit.getRegionScheduler().execute(plugin, loc) {
                if (!originalButtonBlocks.containsKey(loc)) {
                    loc.block.type = type
                }
            }
        }
    }

    fun clear() {
        // onDisable：调度器任务已不再执行，需同步还原方块，避免按钮残留海晶灯
        originalButtonBlocks.forEach { (loc, type) ->
            loc.block.type = type
        }
        originalButtonBlocks.clear()
        buttons.clear()
    }
}