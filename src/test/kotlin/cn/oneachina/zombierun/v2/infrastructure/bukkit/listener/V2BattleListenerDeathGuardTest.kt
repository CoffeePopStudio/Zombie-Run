package cn.oneachina.zombierun.v2.infrastructure.bukkit.listener

import cn.oneachina.zombierun.v2.application.combat.CombatHealthService
import cn.oneachina.zombierun.v2.application.game.GameFlowService
import cn.oneachina.zombierun.v2.infrastructure.config.V2Settings
import cn.oneachina.zombierun.v2.support.V2Logger
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.plugin.java.JavaPlugin
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test

class V2BattleListenerDeathGuardTest {

    private fun settings() = V2Settings(
        schema = 2,
        debug = false,
        defaultWorld = "world",
        startDelaySeconds = 5,
        minPlayers = 2,
        maxDurationSeconds = 60,
        staminaMax = 20.0,
        staminaSprintDrain = 0.25,
        staminaRegen = 0.08,
        staminaExhaustDelayTicks = 40,
        economy = cn.oneachina.zombierun.v2.domain.combat.EconomyRules(),
    )

    private fun listener(gameFlow: GameFlowService) = V2BattleListener(
        plugin = mock<JavaPlugin>(),
        gameFlow = gameFlow,
        healthService = CombatHealthService(V2Logger(Logger.getLogger("test"))),
        playerData = null,
        settings = settings(),
        logger = V2Logger(Logger.getLogger("test")),
    )

    private fun deathEventInWorld(name: String): Pair<PlayerDeathEvent, Player> {
        val world = mock<World>()
        whenever(world.name).thenReturn(name)
        val victim = mock<Player>()
        whenever(victim.uniqueId).thenReturn(UUID.randomUUID())
        whenever(victim.world).thenReturn(world)
        val event = mock<PlayerDeathEvent>()
        whenever(event.entity).thenReturn(victim)
        return event to victim
    }

    @Test
    fun `non arena death is left untouched`() {
        val gameFlow = mock<GameFlowService>()
        whenever(gameFlow.isArenaWorld("lobby")).thenReturn(false)
        val (event, _) = deathEventInWorld("lobby")

        listener(gameFlow).onPlayerDeath(event)

        Mockito.verify(event, Mockito.never()).setCancelled(true)
    }

    @Test
    fun `arena death without team is left untouched`() {
        val gameFlow = mock<GameFlowService>()
        whenever(gameFlow.isArenaWorld("arena")).thenReturn(true)
        val (event, victim) = deathEventInWorld("arena")
        whenever(gameFlow.teamOf("arena", victim.uniqueId)).thenReturn(null)

        listener(gameFlow).onPlayerDeath(event)

        Mockito.verify(event, Mockito.never()).setCancelled(true)
    }
}
