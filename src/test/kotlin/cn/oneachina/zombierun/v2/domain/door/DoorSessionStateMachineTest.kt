package cn.oneachina.zombierun.v2.domain.door

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DoorSessionStateMachineTest {

    private fun door(
        id: String = "door_1",
        front: PortalFront = PortalFront.POSITIVE,
        plane: Double = 100.0,
    ) = DoorDefinition(
        id = id,
        world = "test",
        number = 1,
        mode = DoorMode.NORMAL,
        group = null,
        openSeconds = 5,
        closeSeconds = 10,
        portal = Portal(
            axis = PortalAxis.X,
            front = front,
            planeCoordinate = plane,
            transverseMin = 10.0,
            transverseMax = 20.0,
            yMin = 64.0,
            yMax = 66.0,
        ),
        region = BlockRegion(100, 64, 10, 100, 66, 20),
        snapshotId = null,
        fallbackMaterial = "STONE",
    )

    private val alice = UUID.randomUUID()

    @Test
    fun `normal crossing is recorded and stays passed after returning`() {
        val machine = DoorSessionStateMachine(
            sessionId = "s1",
            doors = listOf(door()),
            initialPositions = mapOf(alice to Vec3(95.0, 65.0, 15.0)),
        )
        machine.startClosing()

        val move = machine.onMove(alice, Vec3(99.0, 65.0, 15.0), Vec3(101.0, 65.0, 15.0))
        assertTrue(move.newlyCrossed)

        // 折返回门后侧：必须仍然算通过
        machine.onMove(alice, Vec3(101.0, 65.0, 15.0), Vec3(99.0, 65.0, 15.0))

        val outcomes = machine.close(mapOf(alice to Vec3(99.0, 65.0, 15.0)))
        assertEquals(DoorPassDecision.PASSED, outcomes.single().decision)
    }

    @Test
    fun `single large step crossing is recorded`() {
        val machine = DoorSessionStateMachine(
            sessionId = "s1",
            doors = listOf(door()),
            initialPositions = mapOf(alice to Vec3(95.0, 65.0, 15.0)),
        )
        machine.startClosing()
        val move = machine.onMove(alice, Vec3(96.0, 65.0, 15.0), Vec3(108.0, 65.0, 15.0))
        assertTrue(move.newlyCrossed)
    }

    @Test
    fun `teleported player cannot pass`() {
        val machine = DoorSessionStateMachine(
            sessionId = "s1",
            doors = listOf(door()),
            initialPositions = mapOf(alice to Vec3(95.0, 65.0, 15.0)),
        )
        machine.startClosing()
        machine.markTeleported(alice)

        val move = machine.onMove(alice, Vec3(95.0, 65.0, 15.0), Vec3(105.0, 65.0, 15.0))
        assertFalse(move.newlyCrossed)

        val outcomes = machine.close(mapOf(alice to Vec3(105.0, 65.0, 15.0)))
        assertEquals(DoorPassDecision.BEHIND, outcomes.single().decision)
    }

    @Test
    fun `player standing beside the aperture is not counted by fallback`() {
        val machine = DoorSessionStateMachine(
            sessionId = "s1",
            doors = listOf(door()),
            initialPositions = mapOf(alice to Vec3(95.0, 65.0, 15.0)),
        )
        machine.startClosing()

        // 穿过平面的路径不在门洞横向范围内：不记录穿越
        machine.onMove(alice, Vec3(99.0, 65.0, 25.0), Vec3(101.0, 65.0, 25.0))

        val outcomes = machine.close(mapOf(alice to Vec3(101.0, 65.0, 25.0)))
        assertEquals(DoorPassDecision.BEHIND, outcomes.single().decision)
    }

    @Test
    fun `tight fallback rescues a missed crossing`() {
        val machine = DoorSessionStateMachine(
            sessionId = "s1",
            doors = listOf(door()),
            initialPositions = mapOf(alice to Vec3(95.0, 65.0, 15.0)),
        )
        machine.startClosing()

        // 初始在门后，关门时位于门前投影内但从未记录到移动
        val outcomes = machine.close(mapOf(alice to Vec3(101.0, 65.0, 15.0)))
        assertEquals(DoorPassDecision.PASSED_FALLBACK, outcomes.single().decision)
    }

    @Test
    fun `player initially front cannot use fallback`() {
        val machine = DoorSessionStateMachine(
            sessionId = "s1",
            doors = listOf(door()),
            initialPositions = mapOf(alice to Vec3(105.0, 65.0, 15.0)),
        )
        machine.startClosing()
        val outcomes = machine.close(mapOf(alice to Vec3(106.0, 65.0, 15.0)))
        assertEquals(DoorPassDecision.BEHIND, outcomes.single().decision)
    }

    @Test
    fun `negative front door works`() {
        val machine = DoorSessionStateMachine(
            sessionId = "s1",
            doors = listOf(door(front = PortalFront.NEGATIVE)),
            initialPositions = mapOf(alice to Vec3(105.0, 65.0, 15.0)),
        )
        machine.startClosing()
        val move = machine.onMove(alice, Vec3(101.0, 65.0, 15.0), Vec3(99.0, 65.0, 15.0))
        assertTrue(move.newlyCrossed)
    }
}
