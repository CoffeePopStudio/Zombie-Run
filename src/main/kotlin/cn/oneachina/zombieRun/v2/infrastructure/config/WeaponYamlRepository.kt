package cn.oneachina.zombierun.v2.infrastructure.config

import cn.oneachina.zombierun.v2.domain.weapon.WeaponCategory
import cn.oneachina.zombierun.v2.domain.weapon.WeaponDefinition
import cn.oneachina.zombierun.v2.support.V2Logger
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class WeaponYamlRepository(
    private val dataFolder: File,
    private val logger: V2Logger,
) {
    private val file: File
        get() = File(dataFolder, "config/weapons.yml")

    private val cache = ConcurrentHashMap<String, WeaponDefinition>()

    fun loadAll(): List<WeaponDefinition> {
        cache.clear()
        val f = file
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            f.writeText(DEFAULT_WEAPONS)
        }
        val yaml = YamlConfiguration.loadConfiguration(f)
        val section = yaml.getConfigurationSection("weapons") ?: return emptyList()
        section.getKeys(false).sorted().forEach { id ->
            try {
                val w = section.getConfigurationSection(id)
                val category = WeaponCategory.entries.firstOrNull {
                    it.name.equals(w?.getString("category", "gun"), ignoreCase = true)
                } ?: WeaponCategory.GUN
                cache[id] = WeaponDefinition(
                    id = id,
                    displayName = w?.getString("display-name") ?: id,
                    type = w?.getString("type") ?: id,
                    category = category,
                    price = w?.getDouble("price", 0.0) ?: 0.0,
                    enabled = w?.getBoolean("enabled", true) ?: true,
                )
            } catch (e: Exception) {
                logger.severe("failed to load weapon '$id': ${e.message}")
            }
        }
        logger.info("loaded ${cache.size} weapon(s)")
        return all()
    }

    fun all(): List<WeaponDefinition> = cache.values.toList()

    fun byId(id: String): WeaponDefinition? = cache[id]

    fun save(weapon: WeaponDefinition) {
        file.parentFile?.mkdirs()
        val yaml = if (file.exists()) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
        yaml.set("schema", 2)
        yaml.set("weapons.${weapon.id}.display-name", weapon.displayName)
        yaml.set("weapons.${weapon.id}.type", weapon.type)
        yaml.set("weapons.${weapon.id}.category", weapon.category.name.lowercase())
        yaml.set("weapons.${weapon.id}.price", weapon.price)
        yaml.set("weapons.${weapon.id}.enabled", weapon.enabled)
        yaml.save(file)
        cache[weapon.id] = weapon
    }

    fun remove(id: String): Boolean {
        val removed = cache.remove(id) != null
        if (removed) {
            val yaml = if (file.exists()) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
            yaml.set("weapons.$id", null)
            yaml.save(file)
        }
        return removed
    }

    companion object {
        private val DEFAULT_WEAPONS = """
            schema: 2
            weapons:
              ak47:
                display-name: AK-47
                type: AK47
                category: gun
                price: 1000.0
                enabled: true
              m4:
                display-name: M4A1
                type: M4A1
                category: gun
                price: 1500.0
                enabled: true
              knife:
                display-name: 战术小刀
                type: Knife
                category: melee
                price: 200.0
                enabled: true
        """.trimIndent() + "\n"
    }
}