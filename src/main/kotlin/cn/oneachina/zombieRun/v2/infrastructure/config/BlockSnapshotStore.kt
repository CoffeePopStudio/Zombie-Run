package cn.oneachina.zombierun.v2.infrastructure.config

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * v2 方块快照存储：每扇门一个 yaml，key 为 "x,y,z"，value 为 MATERIAL 名。
 */
class BlockSnapshotStore(private val dataFolder: File) {

    private val snapshotDir: File
        get() = File(dataFolder, "config/doors")

    fun load(snapshotId: String): Map<String, String> {
        val file = fileFor(snapshotId)
        if (!file.exists()) return emptyMap()
        val yaml = YamlConfiguration.loadConfiguration(file)
        return yaml.getKeys(false).mapNotNull { key ->
            val value = yaml.getString(key) ?: return@mapNotNull null
            key to value
        }.toMap()
    }

    fun save(snapshotId: String, blocks: Map<String, String>) {
        snapshotDir.mkdirs()
        val yaml = YamlConfiguration()
        blocks.forEach { (pos, material) -> yaml.set(pos, material) }
        yaml.save(fileFor(snapshotId))
    }

    fun delete(snapshotId: String) {
        fileFor(snapshotId).delete()
    }

    private fun fileFor(snapshotId: String): File {
        val safe = snapshotId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(snapshotDir, "$safe.scandata.yml")
    }
}
