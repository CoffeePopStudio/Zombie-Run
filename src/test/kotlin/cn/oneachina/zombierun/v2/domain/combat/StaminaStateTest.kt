package cn.oneachina.zombierun.v2.domain.combat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaminaStateTest {

    private val rules = StaminaRules(max = 20.0, sprintDrainPerTick = 0.5, regenPerTick = 0.2, exhaustRecoveryDelayTicks = 3, recoverAt = 2.0)

    @Test
    fun `sprinting drains stamina`() {
        val s = StaminaState(rules)
        repeat(10) { s.tick(sprinting = true) }
        assertEquals(15.0, s.current)
        assertEquals(StaminaStatus.NORMAL, s.status)
    }

    @Test
    fun `drain to zero enters exhausted`() {
        val s = StaminaState(rules)
        var exhaustedTick = -1
        repeat(50) { tick ->
            if (s.tick(sprinting = true) && exhaustedTick == -1) exhaustedTick = tick
        }
        assertEquals(39, exhaustedTick) // 20 / 0.5 = 40 ticks，第 40 次 tick 报告疲劳
        assertEquals(0.0, s.current)
        assertEquals(StaminaStatus.EXHAUSTED, s.status)
    }

    @Test
    fun `exhausted cannot sprint and recovers after delay`() {
        val s = StaminaState(rules)
        repeat(41) { s.tick(sprinting = true) }
        assertEquals(StaminaStatus.EXHAUSTED, s.status)

        // 停止疾跑后前 2 个 tick 不恢复（第 3 个 tick 开始恢复）
        repeat(2) { s.tick(sprinting = false) }
        assertEquals(0.0, s.current)
        assertEquals(StaminaStatus.RECOVERING, s.status)

        // 再恢复直到过门槛（恢复到满）
        repeat(120) { s.tick(sprinting = false) }
        assertEquals(20.0, s.current)
        assertEquals(StaminaStatus.NORMAL, s.status)
    }

    @Test
    fun `resting regens to max but never above`() {
        val s = StaminaState(rules)
        s.tick(sprinting = true)
        repeat(100) { s.tick(sprinting = false) }
        assertEquals(20.0, s.current)
    }

    @Test
    fun `fraction is bounded`() {
        val s = StaminaState(rules)
        assertEquals(1.0, s.fraction())
        repeat(50) { s.tick(sprinting = true) }
        assertEquals(0.0, s.fraction())
    }

    @Test
    fun `newly exhausted is reported exactly once`() {
        val s = StaminaState(rules)
        val reports = mutableListOf<Int>()
        repeat(50) { tick ->
            if (s.tick(sprinting = true)) reports.add(tick)
        }
        assertEquals(listOf(39), reports)
    }
}
