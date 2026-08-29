package cn.oneachina.zombierun.v2.infrastructure.bukkit.listener

import cn.oneachina.zombierun.v2.application.game.GameFlowService
import org.bukkit.GameMode
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.plugin.java.JavaPlugin
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.Test

class V2ProtectionListenerTest {

    private fun playerInWorld(name: String, gameMode: GameMode): Player {
        val world = mock<World>()
        whenever(world.name).thenReturn(name)
        val player = mock<Player>()
        whenever(player.world).thenReturn(world)
        whenever(player.gameMode).thenReturn(gameMode)
        return player
    }

    @Test
    fun `non arena world block break is not cancelled`() {
        val gameFlow = mock<GameFlowService>()
        whenever(gameFlow.isArenaWorld("lobby")).thenReturn(false)
        val listener = V2ProtectionListener(mock<JavaPlugin>(), gameFlow)

        val player = playerInWorld("lobby", GameMode.SURVIVAL)
        val event = mock<BlockBreakEvent>()
        whenever(event.player).thenReturn(player)

        listener.onBlockBreak(event)

        Mockito.verify(event, Mockito.never()).setCancelled(true)
    }

    @Test
    fun `arena world survival block break is cancelled`() {
        val gameFlow = mock<GameFlowService>()
        whenever(gameFlow.isArenaWorld("arena")).thenReturn(true)
        val listener = V2ProtectionListener(mock<JavaPlugin>(), gameFlow)

        val player = playerInWorld("arena", GameMode.SURVIVAL)
        val event = mock<BlockBreakEvent>()
        whenever(event.player).thenReturn(player)

        listener.onBlockBreak(event)

        Mockito.verify(event).setCancelled(true)
    }

    @Test
    fun `arena world creative block break is not cancelled`() {
        val gameFlow = mock<GameFlowService>()
        whenever(gameFlow.isArenaWorld("arena")).thenReturn(true)
        val listener = V2ProtectionListener(mock<JavaPlugin>(), gameFlow)

        val player = playerInWorld("arena", GameMode.CREATIVE)
        val event = mock<BlockBreakEvent>()
        whenever(event.player).thenReturn(player)

        listener.onBlockBreak(event)

        Mockito.verify(event, Mockito.never()).setCancelled(true)
    }
}
