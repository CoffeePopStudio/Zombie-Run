package cn.oneachina.zombierun.v2.domain.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapFlowStateMachineTest {

    private fun flow() = MapFlowDefinition(
        arenaName = "map1",
        world = "world",
        minPlayers = 2,
        startDelaySeconds = 5,
        maxDurationSeconds = 60,
        stages = listOf(
            MapFlowStage("s1", "大门", listOf(1, 2), "s2"),
            MapFlowStage("s2", "地铁", listOf(3), "s3"),
            MapFlowStage("s3", "终点", listOf(4)),
        ),
        finish = MapFlowFinish(FinishType.DOOR, 4),
    )

    @Test
    fun `complete game advances stages and ends with human win`() {
        val machine = MapFlowStateMachine(flow())
        assertEquals(MapFlowPhase.WAITING, machine.phaseSnapshot())
        assertTrue(machine.beginCountdown())
        assertTrue(machine.start())
        assertEquals(MapFlowPhase.RUNNING, machine.phaseSnapshot())
        assertEquals(listOf(1, 2), machine.currentDoorNumbers())

        assertEquals(MapFlowAdvanceResult.STAGE_ADVANCED, machine.onDoorPassed(listOf(1)))
        assertEquals(listOf(3), machine.currentDoorNumbers())
        assertTrue(machine.passedStages().contains("s1"))

        assertEquals(MapFlowAdvanceResult.WRONG_DOOR, machine.onDoorPassed(listOf(2)))
        assertEquals(listOf(3), machine.currentDoorNumbers())

        assertEquals(MapFlowAdvanceResult.STAGE_ADVANCED, machine.onDoorPassed(listOf(3)))
        assertEquals(listOf(4), machine.currentDoorNumbers())

        assertEquals(MapFlowAdvanceResult.FINISHED, machine.onDoorPassed(listOf(4)))
        assertEquals(MapFlowPhase.HUMAN_WIN, machine.phaseSnapshot())
        assertTrue(machine.isFinished())
    }

    @Test
    fun `wrong door in waiting or wrong current door does not advance`() {
        val machine = MapFlowStateMachine(flow())
        assertEquals(MapFlowAdvanceResult.WRONG_DOOR, machine.onDoorPassed(listOf(1)))
        assertTrue(machine.beginCountdown())
        assertTrue(machine.start())
        assertEquals(MapFlowAdvanceResult.WRONG_DOOR, machine.onDoorPassed(listOf(9)))
        assertEquals(listOf(1, 2), machine.currentDoorNumbers())
    }

    @Test
    fun `infection and time up end game`() {
        val machine = MapFlowStateMachine(flow())
        machine.beginCountdown()
        machine.start()
        assertTrue(machine.onAllHumansInfected())
        assertEquals(MapFlowPhase.ZOMBIE_WIN, machine.phaseSnapshot())

        val machine2 = MapFlowStateMachine(flow())
        machine2.beginCountdown()
        machine2.start()
        assertTrue(machine2.onTimeUp())
        assertEquals(MapFlowPhase.HUMAN_WIN, machine2.phaseSnapshot())
    }

    @Test
    fun `extraction ends game with human win`() {
        val machine = MapFlowStateMachine(flow())
        machine.beginCountdown()
        machine.start()
        assertTrue(machine.onExtraction())
        assertEquals(MapFlowPhase.HUMAN_WIN, machine.phaseSnapshot())
    }

    @Test
    fun `reset returns to waiting and first stage`() {
        val machine = MapFlowStateMachine(flow())
        machine.beginCountdown()
        machine.start()
        machine.onDoorPassed(listOf(1))
        machine.onAllHumansInfected()

        machine.reset()
        assertEquals(MapFlowPhase.WAITING, machine.phaseSnapshot())
        assertEquals(listOf(1, 2), machine.currentDoorNumbers())
        assertTrue(machine.passedStages().isEmpty())
    }
}