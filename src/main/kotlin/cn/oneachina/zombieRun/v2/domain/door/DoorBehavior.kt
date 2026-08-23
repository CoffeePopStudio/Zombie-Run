package cn.oneachina.zombierun.v2.domain.door

enum class DoorBehaviorType { ELEVATOR, SUBWAY, AIRPORT }

/**
 * 特殊门行为配置：通过门后触发传送/撤离动作。
 * 所有坐标均为可选；ELEVATOR 至少需要 human-target-y。
 */
data class DoorBehavior(
    val type: DoorBehaviorType,
    val humanTargetX: Double? = null,
    val humanTargetY: Double? = null,
    val humanTargetZ: Double? = null,
    val zombieTargetX: Double? = null,
    val zombieTargetY: Double? = null,
    val zombieTargetZ: Double? = null,
    val lineName: String? = null,
    val countdown: Int = 5,
    val delayTicks: Long = 0,
    val departureMessage: String? = null,
    val arrivalMessage: String? = null,
)