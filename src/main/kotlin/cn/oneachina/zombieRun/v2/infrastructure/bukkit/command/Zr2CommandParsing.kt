package cn.oneachina.zombierun.v2.infrastructure.bukkit.command

import cn.oneachina.zombierun.v2.domain.arena.ArenaDefinition
import cn.oneachina.zombierun.v2.domain.door.BlockRegion
import cn.oneachina.zombierun.v2.domain.door.DoorDefinition
import cn.oneachina.zombierun.v2.domain.door.DoorMode
import cn.oneachina.zombierun.v2.domain.door.Portal
import cn.oneachina.zombierun.v2.domain.door.PortalAxis
import cn.oneachina.zombierun.v2.domain.door.PortalFront

/**
 * `/zr2` 命令的纯参数解析逻辑，与 Bukkit Sender/仓储副作用解耦，便于单元测试。
 */
data class ParsedArgs(
    val options: Map<String, String>,
    val positional: List<String>,
)

/**
 * 解析 `--key value` 选项与位置参数。
 * 最后一个 token 若是 `--xxx` 且没有后续值，会被当作位置参数（兼容 `--overwrite` 这类开关）。
 */
fun parseArgs(args: List<String>): ParsedArgs {
    val options = LinkedHashMap<String, String>()
    val positional = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        val token = args[i]
        if (token.startsWith("--") && i + 1 < args.size) {
            options[token.removePrefix("--")] = args[i + 1]
            i += 2
        } else {
            positional.add(token)
            i++
        }
    }
    return ParsedArgs(options, positional)
}

sealed interface DoorAddParseResult {
    data class Success(val door: DoorDefinition) : DoorAddParseResult
    data class Error(val message: String) : DoorAddParseResult
}

/**
 * 解析 `/zr2 door add --arena <名称> <x1> <y1> <z1> <x2> <y2> <z2> <axis:x|y|z> <front:positive|negative> [--number N] [--group G] [--open N] [--close N]`
 * 中的门定义。不扫描快照、不写仓储。
 */
fun parseDoorAdd(
    arena: ArenaDefinition,
    doorId: String,
    options: Map<String, String>,
    positional: List<String>,
): DoorAddParseResult {
    if (positional.size < 8) {
        return DoorAddParseResult.Error("需要 6 个坐标 + axis + front")
    }
    val coords = positional.take(6).map { it.toIntOrNull() }
    if (coords.any { it == null }) {
        return DoorAddParseResult.Error("坐标必须是整数")
    }
    val axis = PortalAxis.entries.firstOrNull { it.name.equals(positional[6], ignoreCase = true) }
    val front = PortalFront.entries.firstOrNull { it.name.equals(positional[7], ignoreCase = true) }
    if (axis == null || front == null) {
        return DoorAddParseResult.Error("axis 必须是 x|y|z，front 必须是 positive|negative")
    }

    val minX = minOf(coords[0]!!, coords[3]!!)
    val minY = minOf(coords[1]!!, coords[4]!!)
    val minZ = minOf(coords[2]!!, coords[5]!!)
    val maxX = maxOf(coords[0]!!, coords[3]!!)
    val maxY = maxOf(coords[1]!!, coords[4]!!)
    val maxZ = maxOf(coords[2]!!, coords[5]!!)
    val region = BlockRegion(minX, minY, minZ, maxX, maxY, maxZ)

    val number = options["number"]?.toIntOrNull()
        ?: (arena.doors.mapNotNull { it.number }.maxOrNull() ?: 0) + 1
    if (arena.doors.any { it.number == number }) {
        return DoorAddParseResult.Error("门号 $number 已存在")
    }

    val snapshotId = "${arena.name}_$doorId"
    val plane = when (axis) {
        PortalAxis.X -> (minX + maxX) / 2.0
        PortalAxis.Z -> (minZ + maxZ) / 2.0
        PortalAxis.Y -> (minY + maxY) / 2.0
    }
    val transverseMin = when (axis) {
        PortalAxis.X -> minZ.toDouble()
        PortalAxis.Z -> minX.toDouble()
        PortalAxis.Y -> minX.toDouble()
    }
    val transverseMax = when (axis) {
        PortalAxis.X -> maxZ.toDouble()
        PortalAxis.Z -> maxX.toDouble()
        PortalAxis.Y -> maxX.toDouble()
    }
    val verticalMin = when (axis) {
        PortalAxis.X, PortalAxis.Z -> minY.toDouble()
        PortalAxis.Y -> minZ.toDouble()
    }
    val verticalMax = when (axis) {
        PortalAxis.X, PortalAxis.Z -> maxY.toDouble()
        PortalAxis.Y -> maxZ.toDouble()
    }

    val door = DoorDefinition(
        id = doorId,
        world = arena.world,
        number = number,
        mode = DoorMode.NORMAL,
        group = options["group"],
        openSeconds = options["open"]?.toIntOrNull() ?: 15,
        closeSeconds = options["close"]?.toIntOrNull() ?: 15,
        portal = Portal(
            axis = axis,
            front = front,
            planeCoordinate = plane,
            transverseMin = transverseMin,
            transverseMax = transverseMax,
            yMin = verticalMin,
            yMax = verticalMax,
        ),
        region = region,
        snapshotId = snapshotId,
        fallbackMaterial = "STONE",
    )
    return DoorAddParseResult.Success(door)
}
