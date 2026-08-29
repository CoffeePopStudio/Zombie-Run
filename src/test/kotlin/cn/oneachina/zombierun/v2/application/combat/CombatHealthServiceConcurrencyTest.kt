package cn.oneachina.zombierun.v2.application.combat

import cn.oneachina.zombierun.v2.domain.game.GameTeam
import cn.oneachina.zombierun.v2.support.V2Logger
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CombatHealthServiceConcurrencyTest {

    @Test
    fun `concurrent damage never drops below zero and is lossless at zero`() {
        val service = CombatHealthService(V2Logger(Logger.getLogger("test")))
        val id = UUID.randomUUID()
        service.initPlayer(id, GameTeam.ZOMBIE_MAIN)

        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.execute {
                try {
                    start.await()
                    repeat(25) {
                        service.damage(id, 1.0, null)
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

        assertEquals(0.0, service.getHealth(id))
    }

    @Test
    fun `heal never exceeds max`() {
        val service = CombatHealthService(V2Logger(Logger.getLogger("test")))
        val id = UUID.randomUUID()
        service.initPlayer(id, GameTeam.HUMAN)
        repeat(100) {
            service.damage(id, 0.1, null)
            service.heal(id, 10.0)
        }
        assertEquals(service.getMaxHealth(id), service.getHealth(id))
    }
}
