package cn.oneachina.zombierun.v2.domain.door

data class Vec3(val x: Double, val y: Double, val z: Double) {

    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)

    operator fun times(scale: Double): Vec3 = Vec3(x * scale, y * scale, z * scale)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}
