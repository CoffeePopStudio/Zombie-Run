package cn.oneachina.zombierun.v2.infrastructure.config

import cn.oneachina.zombierun.v2.domain.arena.ArenaDefinition
import cn.oneachina.zombierun.v2.domain.arena.ButtonDefinition
import cn.oneachina.zombierun.v2.domain.arena.ButtonMode
import cn.oneachina.zombierun.v2.domain.arena.RespawnDefinition
import cn.oneachina.zombierun.v2.domain.arena.RespawnType
import cn.oneachina.zombierun.v2.domain.door.BlockRegion
import cn.oneachina.zombierun.v2.domain.door.DoorBehavior
import cn.oneachina.zombierun.v2.domain.door.DoorBehaviorType
import cn.oneachina.zombierun.v2.domain.door.DoorDefinition
import cn.oneachina.zombierun.v2.domain.door.DoorMode
import cn.oneachina.zombierun.v2.domain.door.Portal
import cn.oneachina.zombierun.v2.domain.door.PortalAxis
import cn.oneachina.zombierun.v2.domain.door.PortalFront
import cn.oneachina.zombierun.v2.domain.game.FinishType
import cn.oneachina.zombierun.v2.domain.game.MapFlowDefinition
import cn.oneachina.zombierun.v2.domain.game.MapFlowFinish
import cn.oneachina.zombierun.v2.domain.game.MapFlowStage
import cn.oneachina.zombierun.v2.support.V2Logger
import java.util.logging.Logger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArenaYamlRepositoryTest {

    @Test
    fun `map flow round trip`() {
        val dir = createTempDirectory("zr2-arena-test").toFile()
        val repo = ArenaYamlRepository(dir, V2Logger(Logger.getLogger("test")))
        val arena = ArenaDefinition(
            name = "flow",
            world = "w",
            mapFlow = MapFlowDefinition(
                arenaName = "flow",
                world = "w",
                minPlayers = 2,
                startDelaySeconds = 10,
                maxDurationSeconds = 300,
                motherReleaseDelaySeconds = 20,
                stages = listOf(
                    MapFlowStage("s1", "大门", listOf(1, 2), "s2"),
                    MapFlowStage("s2", "终点", listOf(3)),
                ),
                finish = MapFlowFinish(FinishType.DOOR, 3),
            ),
        )

        repo.save(arena)
        val loaded = repo.all().single().mapFlow
        val flow = assertNotNull(loaded)
        assertEquals(2, flow.stages.size)
        assertEquals(listOf(1, 2), flow.stages[0].doorNumbers)
        assertEquals("s2", flow.stages[0].nextStageId)
        assertEquals(FinishType.DOOR, flow.finish.type)
        assertEquals(3, flow.finish.doorNumber)
        assertEquals(20, flow.motherReleaseDelaySeconds)
    }

    @Test
    fun `y axis door round trip uses x z portal fields`() {
        val dir = createTempDirectory("zr2-arena-test").toFile()
        val repo = ArenaYamlRepository(dir, V2Logger(Logger.getLogger("test")))
        val arena = ArenaDefinition(
            name = "updoor",
            world = "w",
            doors = listOf(
                DoorDefinition(
                    id = "up1",
                    world = "w",
                    number = 1,
                    mode = DoorMode.NORMAL,
                    group = null,
                    openSeconds = 5,
                    closeSeconds = 10,
                    portal = Portal(
                        axis = PortalAxis.Y,
                        front = PortalFront.POSITIVE,
                        planeCoordinate = 64.0,
                        transverseMin = 10.0,
                        transverseMax = 20.0,
                        yMin = 30.0,
                        yMax = 40.0,
                    ),
                    region = BlockRegion(10, 64, 30, 20, 64, 40),
                    snapshotId = null,
                    fallbackMaterial = "STONE",
                ),
            ),
        )

        repo.save(arena)
        val loaded = repo.loadAll().single().doors.single()
        assertEquals(PortalAxis.Y, loaded.portal.axis)
        assertEquals(64.0, loaded.portal.planeCoordinate)
        assertEquals(10.0, loaded.portal.transverseMin)
        assertEquals(20.0, loaded.portal.transverseMax)
        assertEquals(30.0, loaded.portal.yMin)
        assertEquals(40.0, loaded.portal.yMax)
    }

    @Test
    fun `behavior and escape button round trip`() {
        val dir = createTempDirectory("zr2-arena-test").toFile()
        val repo = ArenaYamlRepository(dir, V2Logger(Logger.getLogger("test")))
        val arena = ArenaDefinition(
            name = "behavior",
            world = "w",
            doors = listOf(
                DoorDefinition(
                    id = "d1",
                    world = "w",
                    number = 1,
                    mode = DoorMode.NORMAL,
                    group = null,
                    openSeconds = 10,
                    closeSeconds = 12,
                    portal = Portal(PortalAxis.X, PortalFront.POSITIVE, 5.0, -1.0, 1.0, 63.0, 65.0),
                    region = BlockRegion(5, 63, -1, 5, 65, 1),
                    snapshotId = null,
                    fallbackMaterial = "STONE",
                    behavior = DoorBehavior(
                        type = DoorBehaviorType.ELEVATOR,
                        humanTargetY = 48.0,
                        zombieTargetY = 48.0,
                        countdown = 5,
                        departureMessage = "电梯即将到达",
                        arrivalMessage = "电梯已到达",
                    ),
                ),
            ),
            buttons = listOf(
                ButtonDefinition("esc", "w", 1, 64, 0, ButtonMode.ESCAPE, emptyList()),
            ),
            respawns = listOf(
                RespawnDefinition("r1", "w", RespawnType.WAIT, 0.0, 64.0, 0.0, 0f, 0f, null),
            ),
        )

        repo.save(arena)
        val loaded = repo.all().single()

        assertEquals(1, loaded.doors.size)
        val door = loaded.doors.single()
        val behavior = assertNotNull(door.behavior)
        assertEquals(DoorBehaviorType.ELEVATOR, behavior.type)
        assertEquals(48.0, behavior.humanTargetY)
        assertEquals("电梯即将到达", behavior.departureMessage)
        assertEquals(ButtonMode.ESCAPE, loaded.buttons.single().mode)
        assertTrue(loaded.buttons.single().doorNumbers.isEmpty())
    }
}