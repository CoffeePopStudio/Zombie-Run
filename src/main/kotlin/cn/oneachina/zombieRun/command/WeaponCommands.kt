package cn.oneachina.zombieRun.command

import cn.oneachina.zombieRun.ZombieRun
import org.bukkit.command.CommandSender

object WeaponCommands {

    fun handle(plugin: ZombieRun, sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("zombie.run.admin")) {
            sender.sendMessage("§c你没有权限使用此命令！")
            return
        }
        if (args.isEmpty() || args[0].lowercase() != "create") {
            sender.sendMessage("§c用法: /zr weapon create <id> <material> <damage> <price>")
            return
        }
        if (args.size < 5) {
            sender.sendMessage("§c用法: /zr weapon create <id> <material> <damage> <price>")
            return
        }
        val id = args[1]
        val material = args[2].uppercase()
        val damage = args[3].toDoubleOrNull()
        val price = args[4].toIntOrNull()
        if (damage == null || price == null) {
            sender.sendMessage("§c伤害和价格必须是数字！")
            return
        }

        val config = plugin.configManager.getConfig()
        config.createSection("custom-weapons.$id")
        config.set("custom-weapons.$id.material", material)
        config.set("custom-weapons.$id.name", "&f$id")
        config.set("custom-weapons.$id.custom-model-data", 0)
        config.set("custom-weapons.$id.lore", emptyList<String>())
        config.set("custom-weapons.$id.damage", damage)
        config.set("custom-weapons.$id.ammo-type", "pistol_ammo")
        config.set("custom-weapons.$id.max-ammo", 30)
        config.set("custom-weapons.$id.price", price)
        config.set("custom-weapons.$id.cooldown-ticks", 10)
        config.set("custom-weapons.$id.range", 30)
        plugin.configManager.saveConfig()
        plugin.weaponManager.loadWeapons()
        sender.sendMessage("§a武器 '$id' 已创建到 config.yml，请编辑 config.yml 调整详细属性后 /zr reload")
    }
}
