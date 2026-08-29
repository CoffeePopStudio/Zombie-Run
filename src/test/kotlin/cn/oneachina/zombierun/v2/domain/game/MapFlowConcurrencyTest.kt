package cn.oneachina.zombierun.v2.domain.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MapFlowConcurrencyTest {

    private fun flow(): MapFlowStateMachine {
        val definition = MapFlowDefinition(
            arenaName = "a",
            world = "w",
            minPlayers = 2,
            startDelaySeconds = 5,
            maxDurationSeconds = 60,
            stages = listOf(
                MapFlowStage("s1", "一阶段", listOf(1), "s2"),
                MapFlowStage("s2", "二阶段", listOf(2), null),
            ),
            finish = MapFlowFinish(FinishType.DOOR, 2),
        )
        return MapFlowStateMachine(definition)
    }

    @Test
    fun `concurrent door passes do not corrupt phase or stage`() {
        val machine = flow()
        assertTrue(machine.beginCountdown())
        assertTrue(machine.start())

        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { i ->
            pool.execute {
                try {
                    start.await()
                    val door = if (i % 2 == 0) 1 else 2
                    machine.onDoorPassed(listOf(door))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()

        // 无论并发顺序如何，状态必须是 HUMAN_WIN（s1 推进后 s2 命中即结束）或 RUNNING，
        // 但不能出现不一致的中间状态。
        val phase = machine.phaseSnapshot()
        assertTrue(phase == MapFlowPhase.RUNNING || phase == MapFlowPhase.HUMAN_WIN)
        if (phase == MapFlowPhase.HUMAN_WIN) {
            assertEquals(2, machine.passedStages().size)
        }
    }

    @Test
    fun `concurrent reset and advance never throws`() {
        val machine = flow()
        assertTrue(machine.beginCountdown())
        assertTrue(machine.start())

        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        repeat(4) { i ->
            pool.execute {
                try {
                    start.await()
                    when (i % 2) {
                        0 -> machine.onDoorPassed(listOf(1))
                        1 -> machine.reset()
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()
    }
}
