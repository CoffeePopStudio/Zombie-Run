package cn.oneachina.zombieRun.command

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.Respawn
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

object TabCompleters {

    fun spawn(plugin: ZombieRun, args: Array<out String>): MutableList<String> {
        if (args.size < 2) return mutableListOf()
        return when (args[1].lowercase()) {
            "remove" -> {
                if (args.size == 3) {
                    plugin.respawnManager.getAllRespawns().map { it.name }
                        .filter { it.startsWith(args[2], ignoreCase = true) }
                        .toMutableList()
                } else mutableListOf()
            }
            "door-player", "door-zombie" -> {
                if (args.size == 3) {
                    plugin.doorManager.getAllDoors().map { it.doorNumber.toString() }
                        .filter { it.startsWith(args[2]) }
                        .toMutableList()
                } else mutableListOf()
            }
            "list" -> mutableListOf()
            else -> mutableListOf()
        }
    }

    fun doors(plugin: ZombieRun, args: Array<out String>, sender: CommandSender): MutableList<String> {
        if (args.size < 2) return mutableListOf()
        return when (args[1].lowercase()) {
            "add" -> {
                when {
                    args.size == 3 -> {
                        listOf("normal", "player", "zombie", "start")
                            .filter { it.startsWith(args[2].lowercase()) }
                            .toMutableList()
                    }
                    args.size == 4 -> {
                        val mode = args[2].lowercase()
                        if (mode in listOf("normal")) {
                            mutableListOf("-g")
                        } else mutableListOf()
                    }
                    args.size == 5 -> {
                        if (args[3] == "-g") {
                            plugin.doorManager.getDoorGroups().keys
                                .filter { it.startsWith(args[4], ignoreCase = true) }
                                .toMutableList()
                        } else mutableListOf()
                    }
                    args.size == 6 -> {
                        if (args[3] == "-g") {
                            mutableListOf("<duration:秒数 默认15>")
                        } else if (args[4].toIntOrNull() != null || args[3] != "-g") {
                            mutableListOf()
                        } else mutableListOf("<duration:秒数>")
                    }
                    else -> mutableListOf()
                }
            }
            "edit" -> {
                when (args.size) {
                    3 -> plugin.doorManager.getAllDoors().map { it.name }
                        .filter { it.startsWith(args[2], ignoreCase = true) }.toMutableList()
                    4 -> listOf("duration", "door-number", "group")
                        .filter { it.startsWith(args[3].lowercase()) }.toMutableList()
                    5 -> when (args[3].lowercase()) {
                        "group" -> plugin.doorManager.getDoorGroups().keys
                            .filter { it.startsWith(args[4], ignoreCase = true) }.toMutableList()
                        "duration" -> mutableListOf("<秒数>")
                        "door-number" -> mutableListOf("<新门号>")
                        else -> mutableListOf()
                    }
                    else -> mutableListOf()
                }
            }
            "remove", "reset", "info" -> {
                if (args.size == 3) {
                    plugin.doorManager.getAllDoors().map { it.name }
                        .filter { it.startsWith(args[2], ignoreCase = true) }
                        .toMutableList()
                } else mutableListOf()
            }
            "list" -> mutableListOf()
            else -> mutableListOf()
        }
    }

    fun doorBehavior(plugin: ZombieRun, args: List<String>): MutableList<String> {
        return when (args.size) {
            1 -> listOf("set", "remove", "info")
                .filter { it.startsWith(args[0].lowercase()) }.toMutableList()
            2 -> plugin.doorManager.getAllDoors()
                .filter { it.doorNumber > 0 }
                .map { it.doorNumber.toString() }
                .distinct()
                .filter { it.startsWith(args[1]) }.toMutableList()
            3 -> {
                if (args[0].lowercase() == "set") {
                    listOf("subway", "elevator", "airport")
                        .filter { it.startsWith(args[2].lowercase()) }.toMutableList()
                } else mutableListOf()
            }
            4 -> when (args[2].lowercase()) {
                "subway" -> mutableListOf("<线路名>")
                "elevator" -> mutableListOf("<human-y>")
                "airport" -> mutableListOf("<hx>")
                else -> mutableListOf()
            }
            else -> mutableListOf()
        }
    }

    fun buttons(plugin: ZombieRun, args: Array<out String>, sender: CommandSender): MutableList<String> {
        if (args.size < 2) return mutableListOf()
        return when (args[1].lowercase()) {
            "add" -> when (args.size) {
                3 -> mutableListOf(getTargetCoord(sender, 'x'))
                4 -> mutableListOf(getTargetCoord(sender, 'y'))
                5 -> mutableListOf(getTargetCoord(sender, 'z'))
                6 -> listOf("normal", "escape").filter { it.startsWith(args[5].lowercase()) }.toMutableList()
                7 -> when (args[5].lowercase()) {
                    "normal" -> mutableListOf("<门号>")
                    "escape" -> mutableListOf()
                    else -> mutableListOf()
                }
                else -> mutableListOf()
            }
            "remove" -> {
                if (args.size == 3) {
                    plugin.buttonManager.getAllButtons().map { it.name }
                        .filter { it.startsWith(args[2], ignoreCase = true) }.toMutableList()
                } else mutableListOf()
            }
            "list" -> mutableListOf()
            else -> mutableListOf()
        }
    }

    private fun getTargetCoord(sender: CommandSender, axis: Char): String {
        val player = sender as? Player ?: return "~"
        val target = player.getTargetBlockExact(60) ?: return "~"
        return when (axis) {
            'x' -> target.x.toString()
            'y' -> target.y.toString()
            'z' -> target.z.toString()
            else -> "~"
        }
    }
}
