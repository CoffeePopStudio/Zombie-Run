package cn.oneachina.zombieRun.command

import cn.oneachina.zombieRun.ZombieRun
import cn.oneachina.zombieRun.model.Respawn
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

object TabCompleters {

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

    fun spawn(plugin: ZombieRun, args: Array<out String>): MutableList<String> {
        if (args.size < 2) return mutableListOf()
        return when (args[1].lowercase()) {
            "add" -> {
                when (args.size) {
                    3 -> mutableListOf()
                    4 -> {
                        Respawn.RespawnType.entries.map { it.name.lowercase() }
                            .filter { it.startsWith(args[3].lowercase()) }
                            .toMutableList()
                    }
                    5 -> mutableListOf("~")
                    6 -> mutableListOf("~")
                    else -> mutableListOf()
                }
            }
            "remove" -> {
                if (args.size == 3) {
                    plugin.respawnManager.getAllRespawns().map { it.name }
                        .filter { it.startsWith(args[2], ignoreCase = true) }
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
                when (args.size) {
                    3 -> mutableListOf(getTargetCoord(sender, 'x'))
                    4 -> mutableListOf(getTargetCoord(sender, 'y'))
                    5 -> mutableListOf(getTargetCoord(sender, 'z'))
                    6 -> mutableListOf(getTargetCoord(sender, 'x'))
                    7 -> mutableListOf(getTargetCoord(sender, 'y'))
                    8 -> mutableListOf(getTargetCoord(sender, 'z'))
                    9 -> {
                        listOf("normal", "player", "zombie", "start")
                            .filter { it.startsWith(args[7].lowercase()) }
                            .toMutableList()
                    }
                    10 -> {
                        (0..9).map { it.toString() }
                            .filter { it.startsWith(args[8]) }
                            .toMutableList()
                    }
                    11 -> {
                        listOf("30", "60", "90", "120")
                            .filter { it.startsWith(args[9]) }
                            .toMutableList()
                    }
                    else -> mutableListOf()
                }
            }
            "remove" -> {
                if (args.size == 3) {
                    plugin.doorManager.getAllDoors().map { it.name }
                        .filter { it.startsWith(args[2], ignoreCase = true) }
                        .toMutableList()
                } else mutableListOf()
            }
            "list" -> mutableListOf()
            "reset" -> {
                if (args.size == 3) {
                    plugin.doorManager.getAllDoors().map { it.name }
                        .filter { it.startsWith(args[2], ignoreCase = true) }
                        .toMutableList()
                } else mutableListOf()
            }
            else -> mutableListOf()
        }
    }

    fun buttons(plugin: ZombieRun, args: Array<out String>): MutableList<String> {
        if (args.size < 2) return mutableListOf()
        return when (args[1].lowercase()) {
            "add" -> {
                when (args.size) {
                    3, 4 -> mutableListOf("~")
                    5 -> {
                        listOf("normal", "tp", "escape")
                            .filter { it.startsWith(args[4].lowercase()) }
                            .toMutableList()
                    }

                    6 -> {
                        val mode = args[4].lowercase()
                        when (mode) {
                            "normal" -> listOf("<门号>").filter { it.startsWith(args[5]) }
                            "tp" -> mutableListOf("~")
                            "escape" -> mutableListOf()
                            else -> mutableListOf()
                        }
                    }

                    7 -> {
                        val mode = args[4].lowercase()
                        when (mode) {
                            "tp" -> mutableListOf("~")
                            else -> mutableListOf()
                        }
                    }

                    8 -> {
                        val mode = args[4].lowercase()
                        when (mode) {
                            "tp" -> mutableListOf("~")
                            else -> mutableListOf()
                        }
                    }

                    9 -> {
                        val mode = args[4].lowercase()
                        when (mode) {
                            "tp" -> mutableListOf("~")
                            else -> mutableListOf()
                        }
                    }

                    10 -> {
                        val mode = args[4].lowercase()
                        when (mode) {
                            "tp" -> mutableListOf("~")
                            else -> mutableListOf()
                        }
                    }

                    11 -> {
                        val mode = args[4].lowercase()
                        when (mode) {
                            "tp" -> mutableListOf("~")
                            else -> mutableListOf()
                        }
                    }

                    else -> mutableListOf()
                }
            }

            "remove" -> {
                if (args.size == 3) {
                    plugin.buttonManager.getAllButtons().map { it.name }
                        .filter { it.startsWith(args[2], ignoreCase = true) }
                        .toMutableList()
                } else mutableListOf()
            }

            "list" -> mutableListOf()
             else -> mutableListOf()
        } as MutableList<String>
    }
}
