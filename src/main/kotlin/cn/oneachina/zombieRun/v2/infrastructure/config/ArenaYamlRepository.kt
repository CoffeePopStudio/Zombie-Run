package cn.oneachina.zombierun.v2.infrastructure.config

import cn.oneachina.zombierun.v2.domain.arena.ArenaDefinition
import cn.oneachina.zombierun.v2.domain.arena.ButtonDefinition
import cn.oneachina.zombierun.v2.domain.arena.ButtonMode
import cn.oneachina.zombierun.v2.domain.arena.RespawnDefinition
import cn.oneachina.zombierun.v2.domain.arena.RespawnType
import cn.oneachina.zombierun.v2.domain.door.BlockRegion
import cn.oneachina.zombierun.v2.domain.door.DoorDefinition
import cn.oneachina.zombierun.v2.domain.door.DoorMode
import cn.oneachina.zombierun.v2.domain.door.Portal
import cn.oneachina.zombierun.v2.domain.door.PortalAxis
import cn.oneachina.zombierun.v2.domain.door.PortalFront
import cn.oneachina.zombierun.v2.support.V2Logger
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ArenaValidationException(message: String) : RuntimeException(message)

class ArenaYamlRepository(
    private val dataFolder: File,
    private val logger: V2Logger,
) {
    private val arenaDir: File
        get() = File(dataFolder, "config/arenas")

    private val cache = ConcurrentHashMap<String, ArenaDefinition>()

    fun loadAll(): List<ArenaDefinition> {
        cache.clear()
        if (!arenaDir.exists()) arenaDir.mkdirs()

        val arenas = arenaDir.listFiles { f -> f.isFile && f.extension.equals("yml", ignoreCase = true) }
            ?.mapNotNull { file -> parseFile(file) }
            ?: emptyList()

        arenas.forEach { arena ->
            if (cache.putIfAbsent(arena.name, arena) != null) {
                logger.warn("duplicate arena name '${arena.name}': later file ignored")
            }
        }
        validateWorlds()
        logger.info("loaded ${cache.size} arena(s)")
        return all()
    }

    private fun parseFile(file: File): ArenaDefinition? {
        return try {
            parseArena(YamlConfiguration.loadConfiguration(file))
        } catch (e: Exception) {
            logger.severe("failed to load arena file ${file.name}: ${e.message}")
            null
        }
    }

    fun parseArena(root: ConfigurationSection): ArenaDefinition {
        val name = root.getString("name") ?: throw ArenaValidationException("missing 'name'")
        val world = root.getString("world") ?: throw ArenaValidationException("arena $name: missing 'world'")
        val doors = root.getConfigurationSection("doors")
            ?.getKeys(false)
            ?.sorted()
            ?.map { id -> parseDoor(root.getConfigurationSection("doors.$id")!!, id, world) }
            ?: emptyList()

        val buttons = root.getConfigurationSection("buttons")
            ?.getKeys(false)
            ?.sorted()
            ?.map { id -> parseButton(root.getConfigurationSection("buttons.$id")!!, id, world) }
            ?: emptyList()

        val respawns = root.getConfigurationSection("respawns")
            ?.getKeys(false)
            ?.sorted()
            ?.map { id -> parseRespawn(root.getConfigurationSection("respawns.$id")!!, id, world) }
            ?: emptyList()

        return ArenaDefinition(name, world, doors, buttons, respawns)
    }

    private fun parseDoor(section: ConfigurationSection, id: String, world: String): DoorDefinition {
        val region = section.intList("region", 6, "door $id.region")
        val minX = minOf(region[0], region[3])
        val minY = minOf(region[1], region[4])
        val minZ = minOf(region[2], region[5])
        val maxX = maxOf(region[0], region[3])
        val maxY = maxOf(region[1], region[4])
        val maxZ = maxOf(region[2], region[5])

        val mode = DoorMode.entries.firstOrNull {
            it.name.equals(section.getString("mode", "normal"), ignoreCase = true)
        } ?: throw ArenaValidationException("door $id: invalid mode")

        val number = if (mode == DoorMode.NORMAL) {
            val n = section.getInt("number", -1)
            if (n <= 0) throw ArenaValidationException("door $id: normal door requires positive 'number'")
            n
        } else {
            null
        }

        val portalSection = section.getConfigurationSection("portal")
            ?: throw ArenaValidationException("door $id: missing 'portal'")
        val axis = PortalAxis.entries.firstOrNull {
            it.name.equals(portalSection.getString("axis", "x"), ignoreCase = true)
        } ?: throw ArenaValidationException("door $id: portal.axis must be x or z")
        val front = PortalFront.entries.firstOrNull {
            it.name.equals(portalSection.getString("front", "positive"), ignoreCase = true)
        } ?: throw ArenaValidationException("door $id: portal.front must be positive or negative")

        val plane = portalSection.getDouble(
            "plane",
            if (axis == PortalAxis.X) (minX + maxX) / 2.0 else (minZ + maxZ) / 2.0
        )

        val yRange = portalSection.doubleList("y", 2)
        val yMin = yRange?.get(0) ?: minY.toDouble()
        val yMax = yRange?.get(1) ?: maxY.toDouble()

        val transverseRange = portalSection.doubleList("transverse", 2)
        val transverseDefault = if (axis == PortalAxis.X) minZ.toDouble() to maxZ.toDouble()
        else minX.toDouble() to maxX.toDouble()
        val transverseMin = transverseRange?.get(0) ?: transverseDefault.first
        val transverseMax = transverseRange?.get(1) ?: transverseDefault.second

        val schedule = section.getConfigurationSection("schedule")
        val openSeconds = schedule?.getInt("open", 15) ?: 15
        val closeSeconds = schedule?.getInt("close", 15) ?: 15

        return DoorDefinition(
            id = id,
            world = world,
            number = number,
            mode = mode,
            group = section.getString("group"),
            openSeconds = openSeconds,
            closeSeconds = closeSeconds,
            portal = Portal(axis, front, plane, transverseMin, transverseMax, yMin, yMax),
            region = BlockRegion(minX, minY, minZ, maxX, maxY, maxZ),
            snapshotId = section.getString("snapshot"),
            fallbackMaterial = section.getString("fallback-material", "STONE") ?: "STONE",
        )
    }

    private fun parseButton(section: ConfigurationSection, id: String, world: String): ButtonDefinition {
        val at = section.intList("at", 3, "button $id.at")
        val mode = ButtonMode.entries.firstOrNull {
            it.name.equals(section.getString("mode", "normal"), ignoreCase = true)
        } ?: throw ArenaValidationException("button $id: invalid mode")
        val doorNumbers = section.getIntegerList("door-numbers")
        if (mode == ButtonMode.NORMAL && doorNumbers.isEmpty()) {
            throw ArenaValidationException("button $id: normal button requires door-numbers")
        }
        return ButtonDefinition(id, world, at[0], at[1], at[2], mode, doorNumbers)
    }

    private fun parseRespawn(section: ConfigurationSection, id: String, world: String): RespawnDefinition {
        val at = section.getDoubleList("at")
        if (at.size != 3) throw ArenaValidationException("respawn $id: 'at' must have 3 numbers")
        val type = RespawnType.entries.firstOrNull {
            it.name.equals(section.getString("type"), ignoreCase = true)
        } ?: throw ArenaValidationException("respawn $id: invalid type")
        val doorNumber = if (type == RespawnType.DOOR_PLAYER || type == RespawnType.DOOR_ZOMBIE) {
            val n = section.getInt("door-number", -1)
            if (n <= 0) throw ArenaValidationException("respawn $id: door respawn requires door-number")
            n
        } else {
            null
        }
        return RespawnDefinition(
            id = id,
            world = world,
            type = type,
            x = at[0],
            y = at[1],
            z = at[2],
            yaw = section.getDouble("yaw", 0.0).toFloat(),
            pitch = section.getDouble("pitch", 0.0).toFloat(),
            doorNumber = doorNumber,
        )
    }

    private fun validateWorlds() {
        cache.values.groupBy { it.world }.forEach { (world, arenas) ->
            val numbers = arenas.flatMap { arena ->
                arena.doors.mapNotNull { it.number }
            }
            val duplicate = numbers.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (duplicate.isNotEmpty()) {
                throw ArenaValidationException("world '$world' has duplicate door numbers: ${duplicate.sorted()}")
            }
        }
    }

    fun all(): List<ArenaDefinition> = cache.values.toList()
    fun byName(name: String): ArenaDefinition? = cache[name]
    fun byWorld(world: String): List<ArenaDefinition> = cache.values.filter { it.world == world }

    fun save(arena: ArenaDefinition) {
        arenaDir.mkdirs()
        val yaml = YamlConfiguration()
        yaml.set("schema", 2)
        yaml.set("name", arena.name)
        yaml.set("world", arena.world)

        val doors = LinkedHashMap<String, Map<String, Any?>>()
        arena.doors.forEach { door ->
            doors[door.id] = door.toMap()
        }
        yaml.set("doors", doors)

        val buttons = LinkedHashMap<String, Map<String, Any?>>()
        arena.buttons.forEach { button ->
            buttons[button.id] = button.toMap()
        }
        yaml.set("buttons", buttons)

        val respawns = LinkedHashMap<String, Map<String, Any?>>()
        arena.respawns.forEach { respawn ->
            respawns[respawn.id] = respawn.toMap()
        }
        yaml.set("respawns", respawns)

        yaml.save(File(arenaDir, "${sanitize(arena.name)}.yml"))
        cache[arena.name] = arena
    }

    fun remove(name: String) {
        cache.remove(name)
        File(arenaDir, "${sanitize(name)}.yml").delete()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun DoorDefinition.toMap(): Map<String, Any> = linkedMapOf<String, Any?>(
        "number" to number,
        "mode" to mode.name.lowercase(),
        "group" to group,
        "region" to listOf(region.minX, region.minY, region.minZ, region.maxX, region.maxY, region.maxZ),
        "portal" to linkedMapOf(
            "axis" to portal.axis.name.lowercase(),
            "front" to portal.front.name.lowercase(),
            "plane" to portal.planeCoordinate,
            "y" to listOf(portal.yMin, portal.yMax),
            "transverse" to listOf(portal.transverseMin, portal.transverseMax),
        ),
        "schedule" to linkedMapOf("open" to openSeconds, "close" to closeSeconds),
        "snapshot" to snapshotId,
        "fallback-material" to fallbackMaterial,
    ).filterValues { it != null }.mapValues { it.value!! }

    private fun ButtonDefinition.toMap(): Map<String, Any> = linkedMapOf(
        "at" to listOf(x, y, z),
        "mode" to mode.name.lowercase(),
        "door-numbers" to doorNumbers,
    )

    private fun RespawnDefinition.toMap(): Map<String, Any> = linkedMapOf<String, Any?>(
        "type" to type.name.lowercase(),
        "at" to listOf(x, y, z),
        "yaw" to yaw,
        "pitch" to pitch,
        "door-number" to doorNumber,
    ).filterValues { it != null }.mapValues { it.value!! }
}

private fun ConfigurationSection.intList(path: String, size: Int, label: String): List<Int> {
    val values = getIntegerList(path)
    if (values.size != size) throw ArenaValidationException("$label must have $size integers")
    return values
}

private fun ConfigurationSection.doubleList(path: String, size: Int): List<Double>? {
    if (!isList(path)) return null
    val values = getDoubleList(path)
    if (values.size != size) throw ArenaValidationException("$path must have $size numbers")
    return values
}
