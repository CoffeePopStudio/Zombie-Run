package cn.oneachina.zombierun.v2.infrastructure.bukkit.listener

import cn.oneachina.zombierun.v2.application.combat.StaminaService
import cn.oneachina.zombierun.v2.domain.combat.StaminaRules
import cn.oneachina.zombierun.v2.ports.SchedulerPort
import cn.oneachina.zombierun.v2.support.TaskRegistry
import cn.oneachina.zombierun.v2.support.V2Logger
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerToggleSprintEvent
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals

class V2CombatListenerTest {

    private val id = UUID.randomUUID()

    /** 1 tick 扣 1.0 点、不恢复、疲劳后长时间不解除：2 tick 后进入 EXHAUSTED。 */
    private fun exhaustedService(): StaminaService {
        val service = StaminaService(V2Logger(Logger.getLogger("test")))
        service.applyRules(
            StaminaRules(
                max = 1.0,
                sprintDrainPerTick = 1.0,
                regenPerTick = 0.0,
                exhaustRecoveryDelayTicks = 1000,
                recoverAt = 2.0,
            ),
        )
        repeat(2) { service.update(id, sprinting = true) }
        return service
    }

    private fun listener(stamina: StaminaService) =
        V2CombatListener(stamina, mock<SchedulerPort>(), TaskRegistry())

    @Test
    fun `sprint toggle is cancelled when stamina is exhausted`() {
        val stamina = exhaustedService()
        val l = listener(stamina)
        val player = mock<Player>()
        whenever(player.uniqueId).thenReturn(id)

        val event = mock<PlayerToggleSprintEvent>()
        whenever(event.isSprinting).thenReturn(true)
        whenever(event.player).thenReturn(player)

        l.onToggleSprint(event)

        Mockito.verify(event).setCancelled(true)
    }

    @Test
    fun `sprint toggle is not cancelled when stamina is normal`() {
        val stamina = StaminaService(V2Logger(Logger.getLogger("test")))
        val l = listener(stamina)
        val player = mock<Player>()
        whenever(player.uniqueId).thenReturn(id)

        val event = mock<PlayerToggleSprintEvent>()
        whenever(event.isSprinting).thenReturn(true)
        whenever(event.player).thenReturn(player)

        l.onToggleSprint(event)

        Mockito.verify(event, Mockito.never()).setCancelled(true)
    }

    @Test
    fun `join resets stamina to full`() {
        val stamina = exhaustedService()
        assertEquals(0.0, stamina.fraction(id))
        val l = listener(stamina)
        val player = mock<Player>()
        whenever(player.uniqueId).thenReturn(id)

        val event = mock<PlayerJoinEvent>()
        whenever(event.player).thenReturn(player)

        l.onJoin(event)

        assertEquals(1.0, stamina.fraction(id))
    }

    @Test
    fun `quit removes stamina state`() {
        val stamina = exhaustedService()
        val l = listener(stamina)
        val player = mock<Player>()
        whenever(player.uniqueId).thenReturn(id)

        val event = mock<PlayerQuitEvent>()
        whenever(event.player).thenReturn(player)

        l.onQuit(event)

        // 移除后重新获取是满体力；若未移除则仍为 0
        assertEquals(1.0, stamina.fraction(id))
    }
}
