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
import cn.oneachina.zombierun.v2.support.V2Logger
import java.util.logging.Logger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArenaYamlRepositoryTest {

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