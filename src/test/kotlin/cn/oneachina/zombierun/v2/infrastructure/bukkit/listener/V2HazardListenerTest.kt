package cn.oneachina.zombierun.v2.infrastructure.bukkit.listener

import cn.oneachina.zombierun.v2.application.combat.CombatHealthService
import cn.oneachina.zombierun.v2.application.game.GameFlowService
import cn.oneachina.zombierun.v2.domain.game.GamePhase
import cn.oneachina.zombierun.v2.domain.game.GameTeam
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerMoveEvent
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID
import kotlin.test.Test

class V2HazardListenerTest {

    private fun mockBlock(material: Material): Block {
        val block = mock<Block>()
        whenever(block.type).thenReturn(material)
        return block
    }

    private fun moveEvent(material: Material): Pair<PlayerMoveEvent, Player> {
        val world = mock<World>()
        whenever(world.name).thenReturn("arena")
        val block = mockBlock(material)
        val location = mock<Location>()
        whenever(location.block).thenReturn(block)
        whenever(location.clone()).thenReturn(location)
        whenever(location.subtract(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(location)
        val player = mock<Player>()
        whenever(player.uniqueId).thenReturn(UUID.randomUUID())
        whenever(player.world).thenReturn(world)
        whenever(player.location).thenReturn(location)
        val event = mock<PlayerMoveEvent>()
        whenever(event.player).thenReturn(player)
        val from = mock<Location>()
        val to = mock<Location>()
        whenever(from.blockX).thenReturn(0); whenever(from.blockY).thenReturn(0); whenever(from.blockZ).thenReturn(0)
        whenever(to.blockX).thenReturn(1); whenever(to.blockY).thenReturn(0); whenever(to.blockZ).thenReturn(0)
        whenever(event.from).thenReturn(from)
        whenever(event.to).thenReturn(to)
        return event to player
    }

    @Test
    fun `black wool damages human in running arena`() {
        val gameFlow = mock<GameFlowService>()
        whenever(gameFlow.phaseOf("arena")).thenReturn(GamePhase.RUNNING)
        whenever(gameFlow.teamOf(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(GameTeam.HUMAN)
        val health = mock<CombatHealthService>()
        whenever(health.damage(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.isNull()))
            .thenReturn(true)
        val listener = V2HazardListener(gameFlow, health)
        val (event, _) = moveEvent(Material.BLACK_WOOL)

        listener.onPlayerMove(event)

        Mockito.verify(health).damage(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.isNull())
    }

    @Test
    fun `non running world ignores black wool`() {
        val gameFlow = mock<GameFlowService>()
        whenever(gameFlow.phaseOf("arena")).thenReturn(GamePhase.WAITING)
        val health = mock<CombatHealthService>()
        val listener = V2HazardListener(gameFlow, health)
        val (event, _) = moveEvent(Material.BLACK_WOOL)

        listener.onPlayerMove(event)

        Mockito.verify(health, Mockito.never()).damage(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.isNull())
    }
}
