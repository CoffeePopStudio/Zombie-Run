package cn.oneachina.zombieRun.manager

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.Button
import org.bukkit.Location
import org.bukkit.Material
import java.util.concurrent.ConcurrentHashMap

class ButtonManager(private val plugin: ZombieRun) {

    private val buttons: ConcurrentHashMap<String, Button> = ConcurrentHashMap()
    private val originalButtonBlocks: MutableMap<Location, Material> = mutableMapOf()

    fun loadButtons() {
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
        val block = world.getBlockAt(button.x, button.y, button.z)
        if (block.type == Material.REDSTONE_LAMP || block.type == Material.LEVER) {
            originalButtonBlocks[block.location] = block.type
            block.type = Material.SEA_LANTERN
        }
    }

    fun resetAllButtons() {
        originalButtonBlocks.forEach { (loc, type) ->
            loc.block.type = type
        }
        originalButtonBlocks.clear()
    }

    fun clear() {
        // 先还原被点亮的按钮方块，避免服务端关闭/重置后按钮残留为海晶灯
        resetAllButtons()
        buttons.clear()
    }
}