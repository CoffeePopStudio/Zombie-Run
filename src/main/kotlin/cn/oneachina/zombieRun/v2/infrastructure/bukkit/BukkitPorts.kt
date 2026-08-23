package cn.oneachina.zombierun.v2.infrastructure.bukkit

import cn.oneachina.zombierun.v2.ports.BlockOpsPort
import cn.oneachina.zombierun.v2.ports.PlayerMessagePort
import cn.oneachina.zombierun.v2.ports.PlayerRef
import cn.oneachina.zombierun.v2.ports.RegionLocation
import cn.oneachina.zombierun.v2.ports.SchedulerPort
import cn.oneachina.zombierun.v2.ports.TeleporterPort
import cn.oneachina.zombierun.v2.ports.WorldAccessPort
import cn.oneachina.zombierun.v2.domain.door.BlockRegion
import cn.oneachina.zombierun.v2.domain.door.Vec3
import cn.oneachina.zombierun.v2.infrastructure.bukkit.hook.MultiverseWorldResolver
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Player
import java.time.Duration
import java.util.UUID

class BukkitWorldAccessPort : WorldAccessPort {
    override fun playersIn(worldName: String): List<PlayerRef> =
        Bukkit.getOnlinePlayers()
            .filter { it.world.name == MultiverseWorldResolver.resolve(worldName) }
            .map { it.toRef() }

    override fun player(id: UUID): PlayerRef? = Bukkit.getPlayer(id)?.toRef()

    override fun worldLoaded(worldName: String): Boolean =
        Bukkit.getWorld(MultiverseWorldResolver.resolve(worldName)) != null
}

class BukkitBlockOpsPort(
    private val scheduler: SchedulerPort,
) : BlockOpsPort {

    override fun openRegion(worldName: String, region: BlockRegion) {
        execute(worldName, region) { world ->
            for (x in region.minX..region.maxX) {
                for (y in region.minY..region.maxY) {
                    for (z in region.minZ..region.maxZ) {
                        val block = world.getBlockAt(x, y, z)
                        if (!block.type.isAir) block.type = Material.AIR
                    }
                }
            }
        }
    }

    override fun closeRegion(
        worldName: String,
        region: BlockRegion,
        snapshot: Map<String, String>,
        fallbackMaterial: String,
    ) {
        execute(worldName, region) { world ->
            if (snapshot.isNotEmpty()) {
                snapshot.forEach { (key, materialName) ->
                    val parts = key.split(',')
                    if (parts.size != 3) return@forEach
                    val x = parts[0].toIntOrNull() ?: return@forEach
                    val y = parts[1].toIntOrNull() ?: return@forEach
                    val z = parts[2].toIntOrNull() ?: return@forEach
                    try {
                        world.getBlockAt(x, y, z).type = Material.valueOf(materialName)
                    } catch (_: IllegalArgumentException) {
                        world.getBlockAt(x, y, z).type = Material.valueOf(fallbackMaterial)
                    }
                }
            } else {
                val fallback = Material.matchMaterial(fallbackMaterial) ?: Material.STONE
                for (x in region.minX..region.maxX) {
                    for (y in region.minY..region.maxY) {
                        for (z in region.minZ..region.maxZ) {
                            val block = world.getBlockAt(x, y, z)
                            if (block.type.isAir) block.type = fallback
                        }
                    }
                }
            }
        }
    }

    override fun scanRegion(worldName: String, region: BlockRegion): Map<String, String> {
        val world = Bukkit.getWorld(MultiverseWorldResolver.resolve(worldName)) ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (x in region.minX..region.maxX) {
            for (y in region.minY..region.maxY) {
                for (z in region.minZ..region.maxZ) {
                    result["$x,$y,$z"] = world.getBlockAt(x, y, z).type.name
                }
            }
        }
        return result
    }

    private fun execute(worldName: String, region: BlockRegion, action: (World) -> Unit) {
        scheduler.regionExecute(
            RegionLocation(
                worldName = worldName,
                x = region.centerX,
                y = region.centerY,
                z = region.centerZ,
            )
        ) {
            val world = Bukkit.getWorld(MultiverseWorldResolver.resolve(worldName)) ?: return@regionExecute
            action(world)
        }
    }
}

class BukkitPlayerMessagePort : PlayerMessagePort {
    private fun player(id: UUID): Player? = Bukkit.getPlayer(id)

    override fun actionBar(playerId: UUID, message: String) {
        player(playerId)?.sendActionBar(Component.text(message, NamedTextColor.GREEN))
    }

    override fun chat(playerId: UUID, message: String) {
        player(playerId)?.sendMessage(Component.text(message, NamedTextColor.GREEN))
    }

    override fun title(playerId: UUID, title: String, subtitle: String) {
        val p = player(playerId) ?: return
        p.showTitle(Title.title(
            Component.text(title, NamedTextColor.GREEN),
            Component.text(subtitle, NamedTextColor.GRAY),
            Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(1200), Duration.ofMillis(300))
        ))
    }

    override fun soundBell(worldName: String) {
        val world = Bukkit.getWorld(MultiverseWorldResolver.resolve(worldName)) ?: return
        val loc = world.players.firstOrNull()?.location ?: world.spawnLocation
        world.playSound(loc, Sound.BLOCK_BELL_USE, 0.7f, 1.0f)
    }
}

class BukkitTeleporterPort : TeleporterPort {
    override fun teleport(
        playerId: UUID,
        worldName: String,
        x: Double,
        y: Double,
        z: Double,
        yaw: Float,
        pitch: Float,
    ) {
        val player = Bukkit.getPlayer(playerId) ?: return
        val world = Bukkit.getWorld(MultiverseWorldResolver.resolve(worldName)) ?: return
        player.teleportAsync(Location(world, x, y, z, yaw, pitch))
    }
}

fun Player.toRef(): PlayerRef = PlayerRef(
    id = uniqueId,
    name = name,
    worldName = world.name,
    position = Vec3(location.x, location.y, location.z),
)
