package cn.oneachina.zombieRun.model

class Button(
    val name: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val mode: String,               // normal, escape
    val doorNumber: Int? = null     // for normal mode
) {
    fun matches(x: Int, y: Int, z: Int): Boolean {
        return this.x == x && this.y == y && this.z == z
    }

    fun isNormal(): Boolean = mode.equals("normal", ignoreCase = true)
    fun isEscape(): Boolean = mode.equals("escape", ignoreCase = true)

    override fun toString(): String {
        return "Button(name='$name', mode=$mode, location=($x, $y, $z), doorNumber=$doorNumber)"
    }
}
