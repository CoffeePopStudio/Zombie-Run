package cn.oneachina.zombierun.v2.infrastructure.bukkit.listener

import cn.oneachina.zombierun.v2.application.door.DoorApplicationService
import cn.oneachina.zombierun.v2.domain.arena.ButtonMode
import cn.oneachina.zombierun.v2.domain.door.Vec3
import cn.oneachina.zombierun.v2.infrastructure.config.ArenaYamlRepository
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent

/**
 * Bukkit 事件 → v2 应用服务的适配层。
 * 这里只做翻译，不包含门判定/开关门逻辑。
 */
class V2DoorListener(
    private val doorService: DoorApplicationService,
    private val arenaRepository: ArenaYamlRepository,
) : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        val from = event.from
        val to = event.to
        doorService.onPlayerMove(
            worldName = player.world.name,
            playerId = player.uniqueId,
            from = Vec3(from.x, from.y, from.z),
            to = Vec3(to.x, to.y, to.z),
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        val player = event.player
        val originWorld = event.from.world.name
        doorService.onPlayerTeleport(originWorld, player.uniqueId)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.REDSTONE_LAMP && block.type != Material.LEVER) return

        val player = event.player
        val button = arenaRepository.byWorld(player.world.name)
            .flatMap { it.buttons }
            .firstOrNull { it.x == block.x && it.y == block.y && it.z == block.z }

        if (button == null) return
        event.isCancelled = true

        when (button.mode) {
            ButtonMode.NORMAL -> {
                val doorNumber = button.doorNumbers.firstOrNull()
                if (doorNumber == null) {
                    player.sendMessage(Component.text("按钮配置错误：未指定门号", NamedTextColor.RED))
                    return
                }
                val result = doorService.triggerDoor(player.world.name, doorNumber, player.name)
                player.sendMessage(Component.text(result.message, if (result.success) NamedTextColor.GREEN else NamedTextColor.RED))
            }
            ButtonMode.ESCAPE -> {
                player.sendMessage(Component.text("撤离按钮将在游戏流程里程碑中启用", NamedTextColor.YELLOW))
            }
        }
    }
}
