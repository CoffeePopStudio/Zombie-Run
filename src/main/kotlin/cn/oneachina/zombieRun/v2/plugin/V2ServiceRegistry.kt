package cn.oneachina.zombierun.v2.plugin

import kotlin.reflect.KClass

class V2ServiceRegistry {

    private val services = LinkedHashMap<KClass<*>, Any>()

    fun <T : Any> register(type: KClass<T>, service: T): T {
        require(!services.containsKey(type)) { "duplicate service registration: ${type.simpleName}" }
        services[type] = service
        return service
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(type: KClass<T>): T =
        services[type] as? T ?: error("service not registered: ${type.simpleName}")
}
