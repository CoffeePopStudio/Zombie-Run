package cn.oneachina.zombierun.v2.infrastructure.bukkit.listener

import cn.oneachina.zombierun.v2.application.combat.CombatHealthService
import cn.oneachina.zombierun.v2.application.game.GameFlowService
import cn.oneachina.zombierun.v2.domain.game.GamePhase
import cn.oneachina.zombierun.v2.domain.game.GameTeam
import cn.oneachina.zombierun.v2.support.V2Logger
import org.bukkit.GameMode
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test

class V2PlayerStateListenerTest {

    private fun playerInWorld(name: String): Pair<PlayerJoinEvent, Player> {
        val world = mock<World>()
        whenever(world.name).thenReturn(name)
        val player = mock<Player>()
        whenever(player.uniqueId).thenReturn(UUID.randomUUID())
        whenever(player.world).thenReturn(world)
        val event = mock<PlayerJoinEvent>()
        whenever(event.player).thenReturn(player)
        return event to player
    }

    private fun listener(gameFlow: GameFlowService, health: CombatHealthService) =
        V2PlayerStateListener(
            plugin = mock<JavaPlugin>(),
            gameFlow = gameFlow,
            healthService = health,
            logger = V2Logger(Logger.getLogger("test")),
        )

    @Test
    fun `non arena join does not take over state`() {
        val gameFlow = mock<GameFlowService>()
        whenever(gameFlow.phaseOf("lobby")).thenReturn(null)
        val health = mock<CombatHealthService>()
        val (event, _) = playerInWorld("lobby")

        listener(gameFlow, health).onJoin(event)

        Mockito.verify(health, Mockito.never()).initPlayer(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `arena join sets adventure and initializes health`() {
        val gameFlow = mock<GameFlowService>()
        whenever(gameFlow.phaseOf("arena")).thenReturn(GamePhase.WAITING)
        whenever(gameFlow.teamOf(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(GameTeam.SPECTATOR)
        val health = mock<CombatHealthService>()
        val (event, player) = playerInWorld("arena")

        listener(gameFlow, health).onJoin(event)

        Mockito.verify(player).setGameMode(GameMode.ADVENTURE)
        Mockito.verify(health).initPlayer(player.uniqueId, GameTeam.SPECTATOR)
    }
}
