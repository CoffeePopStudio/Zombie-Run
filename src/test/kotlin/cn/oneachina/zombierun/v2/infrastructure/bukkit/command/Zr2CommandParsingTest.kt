package cn.oneachina.zombierun.v2.infrastructure.bukkit.command

import cn.oneachina.zombierun.v2.domain.arena.ArenaDefinition
import cn.oneachina.zombierun.v2.domain.door.PortalAxis
import cn.oneachina.zombierun.v2.domain.door.PortalFront
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Zr2CommandParsingTest {

    private fun arena(vararg doorNumbers: Int) =
        ArenaDefinition(
            name = "a",
            world = "w",
            doors = doorNumbers.map { number ->
                parseDoorAdd(
                    arena = ArenaDefinition("a", "w"),
                    doorId = "door_$number",
                    options = mapOf("number" to number.toString()),
                    positional = listOf("10", "64", "10", "10", "66", "20", "x", "positive"),
                ).let {
                    (it as DoorAddParseResult.Success).door
                }
            },
        )

    @Test
    fun `parseArgs splits options and positional`() {
        val parsed = parseArgs(listOf("--arena", "a", "10", "--open", "5", "64", "--group", "g"))

        assertEquals(mapOf("arena" to "a", "open" to "5", "group" to "g"), parsed.options)
        assertEquals(listOf("10", "64"), parsed.positional)
    }

    @Test
    fun `parseArgs treats trailing flag without value as positional`() {
        val parsed = parseArgs(listOf("migrate-data", "--overwrite"))

        assertEquals(listOf("migrate-data", "--overwrite"), parsed.positional)
        assertTrue(parsed.options.isEmpty())
    }

    @Test
    fun `door add parses x axis door with options`() {
        val result = parseDoorAdd(
            arena = ArenaDefinition("a", "w"),
            doorId = "door_1",
            options = mapOf("number" to "1", "group" to "g", "open" to "5", "close" to "7"),
            positional = listOf("10", "64", "10", "10", "66", "20", "x", "positive"),
        )

        val door = (result as DoorAddParseResult.Success).door
        assertEquals(1, door.number)
        assertEquals("g", door.group)
        assertEquals(5, door.openSeconds)
        assertEquals(7, door.closeSeconds)
        assertEquals(PortalAxis.X, door.portal.axis)
        assertEquals(PortalFront.POSITIVE, door.portal.front)
        assertEquals(10.0, door.portal.planeCoordinate)
        assertEquals(10.0, door.portal.transverseMin)
        assertEquals(20.0, door.portal.transverseMax)
        assertEquals(64.0, door.portal.yMin)
        assertEquals(66.0, door.portal.yMax)
        assertEquals("a_door_1", door.snapshotId)
    }

    @Test
    fun `door add parses y axis horizontal door`() {
        val result = parseDoorAdd(
            arena = ArenaDefinition("a", "w"),
            doorId = "door_up",
            options = emptyMap(),
            positional = listOf("10", "64", "30", "20", "64", "40", "y", "positive"),
        )

        val door = (result as DoorAddParseResult.Success).door
        assertEquals(PortalAxis.Y, door.portal.axis)
        assertEquals(64.0, door.portal.planeCoordinate)
        // Y 门：横向 = x，垂直 = z
        assertEquals(10.0, door.portal.transverseMin)
        assertEquals(20.0, door.portal.transverseMax)
        assertEquals(30.0, door.portal.yMin)
        assertEquals(40.0, door.portal.yMax)
    }

    @Test
    fun `door add defaults number after existing max and accepts z axis`() {
        val arenaWithDoor5 = arena(5)
        val result = parseDoorAdd(
            arena = arenaWithDoor5,
            doorId = "door_6",
            options = emptyMap(),
            positional = listOf("30", "64", "30", "30", "66", "40", "z", "negative"),
        )

        val door = (result as DoorAddParseResult.Success).door
        assertEquals(6, door.number)
        assertEquals(PortalAxis.Z, door.portal.axis)
        assertEquals(PortalFront.NEGATIVE, door.portal.front)
    }

    @Test
    fun `door add rejects duplicate number`() {
        val result = parseDoorAdd(
            arena = arena(5),
            doorId = "door_6",
            options = mapOf("number" to "5"),
            positional = listOf("10", "64", "10", "10", "66", "20", "x", "positive"),
        )

        assertEquals("门号 5 已存在", (result as DoorAddParseResult.Error).message)
    }

    @Test
    fun `door add rejects invalid axis or front`() {
        val result = parseDoorAdd(
            arena = ArenaDefinition("a", "w"),
            doorId = "door_1",
            options = emptyMap(),
            positional = listOf("10", "64", "10", "10", "66", "20", "diagonal", "positive"),
        )

        assertEquals("axis 必须是 x|y|z，front 必须是 positive|negative", (result as DoorAddParseResult.Error).message)
    }

    @Test
    fun `door add rejects non integer coordinates`() {
        val result = parseDoorAdd(
            arena = ArenaDefinition("a", "w"),
            doorId = "door_1",
            options = emptyMap(),
            positional = listOf("10.5", "64", "10", "10", "66", "20", "x", "positive"),
        )

        assertEquals("坐标必须是整数", (result as DoorAddParseResult.Error).message)
    }

    @Test
    fun `door add rejects too few positional args`() {
        val result = parseDoorAdd(
            arena = ArenaDefinition("a", "w"),
            doorId = "door_1",
            options = emptyMap(),
            positional = listOf("10", "64", "10"),
        )

        assertEquals("需要 6 个坐标 + axis + front", (result as DoorAddParseResult.Error).message)
    }
}
