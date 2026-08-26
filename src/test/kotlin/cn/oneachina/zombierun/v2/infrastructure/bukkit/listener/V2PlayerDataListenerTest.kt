package cn.oneachina.zombierun.v2.infrastructure.bukkit.listener

import cn.oneachina.zombierun.v2.application.player.PlayerDataService
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID
import kotlin.test.Test

class V2PlayerDataListenerTest {

    private val service = mock<PlayerDataService>()
    private val listener = V2PlayerDataListener(service)
    private val id = UUID.randomUUID()

    @Test
    fun `join loads player data`() {
        val player = mock<Player>()
        whenever(player.uniqueId).thenReturn(id)
        val event = mock<PlayerJoinEvent>()
        whenever(event.player).thenReturn(player)

        listener.onJoin(event)

        verify(service).onJoin(id)
    }

    @Test
    fun `quit saves player data`() {
        val player = mock<Player>()
        whenever(player.uniqueId).thenReturn(id)
        val event = mock<PlayerQuitEvent>()
        whenever(event.player).thenReturn(player)

        listener.onQuit(event)

        verify(service).onQuit(id)
    }
}
