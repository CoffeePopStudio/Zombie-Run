package cn.oneachina.zombieRun.manager

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import kotlin.math.min

class WeaponHelper {

    private val available: Boolean
    private val weaponMechanicsClass: Class<*>?
    private val weaponMechanicsAPI: Class<*>?

    init {
        val wmPlugin = Bukkit.getPluginManager().getPlugin("WeaponMechanics")
        available = wmPlugin != null
        weaponMechanicsClass = if (available) runCatching {
            Class.forName("me.deecaad.weaponmechanics.WeaponMechanics")
        }.getOrNull() else null
        weaponMechanicsAPI = if (available) runCatching {
            Class.forName("me.deecaad.weaponmechanics.WeaponMechanicsAPI")
        }.getOrNull() else null
    }

    fun isAvailable(): Boolean = available

    fun loadWeaponTitles(): List<String> {
        if (!available) return emptyList()
        return runCatching {
            val getInstance = weaponMechanicsClass!!.getMethod("getInstance")
            val instance = getInstance.invoke(null)
            val weaponHandlerField = instance.javaClass.getMethod("getWeaponHandler").invoke(instance)
            val infoHandlerField = weaponHandlerField.javaClass.getMethod("getInfoHandler").invoke(weaponHandlerField)
            val sortedWeaponList = infoHandlerField.javaClass.getMethod("getSortedWeaponList").invoke(infoHandlerField) as List<*>
            sortedWeaponList.filterIsInstance<String>().filter { it.isNotBlank() }
        }.getOrElse { emptyList() }
    }

    fun giveOrDropWeapon(weaponTitle: String, player: Player): Boolean {
        if (!available) return false
        return runCatching {
            val getInstance = weaponMechanicsClass!!.getMethod("getInstance")
            val instance = getInstance.invoke(null)
            val weaponHandler = instance.javaClass.getMethod("getWeaponHandler").invoke(instance)
            val infoHandler = weaponHandler.javaClass.getMethod("getInfoHandler").invoke(weaponHandler)
            val method = infoHandler.javaClass.getMethod("giveOrDropWeapon", String::class.java, Player::class.java, Int::class.javaPrimitiveType)
            method.invoke(infoHandler, weaponTitle, player, 1)
            true
        }.getOrDefault(false)
    }

    fun getWeaponTitle(itemStack: ItemStack): String? {
        if (!available) return null
        return runCatching {
            val method = weaponMechanicsAPI!!.getMethod("getWeaponTitle", ItemStack::class.java)
            method.invoke(null, itemStack) as? String
        }.getOrNull()
    }

    fun getCurrentAmmo(itemStack: ItemStack): AmmoData? {
        if (!available) return null
        return runCatching {
            val method = weaponMechanicsAPI!!.getMethod("getCurrentAmmo", ItemStack::class.java)
            val ammo = method.invoke(null, itemStack) ?: return@runCatching null
            val ammoClass = ammo.javaClass
            val ammoTitleMethod = ammoClass.getMethod("getAmmoTitle")
            val ammoTitle = ammoTitleMethod.invoke(ammo) as? String ?: return@runCatching null
            AmmoData(ammoTitle, ammo)
        }.getOrNull()
    }

    fun generateAmmo(ammoTitle: String, isMagazine: Boolean): ItemStack? {
        if (!available) return null
        return runCatching {
            val method = weaponMechanicsAPI!!.getMethod("generateAmmo", String::class.java, Boolean::class.javaPrimitiveType)
            method.invoke(null, ammoTitle, isMagazine) as? ItemStack
        }.getOrNull()
    }

    data class AmmoData(val ammoTitle: String, val rawAmmo: Any)

    companion object {
        @Volatile
        private var instance: WeaponHelper? = null

        fun getInstance(): WeaponHelper {
            return instance ?: synchronized(this) {
                instance ?: WeaponHelper().also { instance = it }
            }
        }
    }
}
