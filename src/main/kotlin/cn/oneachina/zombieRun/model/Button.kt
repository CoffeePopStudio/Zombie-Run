package cn.oneachina.zombieRun.model

class Button(
    val name: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val mode: String,                      // normal, escape
    val doorNumber: Int? = null,            // for normal mode (single door)
    val doorNumbers: List<Int>? = null      // for normal mode (multi doors)
) {
    fun matches(x: Int, y: Int, z: Int): Boolean {
        return this.x == x && this.y == y && this.z == z
    }

    fun isNormal(): Boolean = mode.equals("normal", ignoreCase = true)
    fun isEscape(): Boolean = mode.equals("escape", ignoreCase = true)

    /** 返回所有要触发的门号集合（单门 + 多门） */
    fun getAllDoorNumbers(): List<Int> {
        val list = mutableListOf<Int>()
        doorNumber?.let { list.add(it) }
        doorNumbers?.let { list.addAll(it) }
        return list
    }

    override fun toString(): String {
        return "Button(name='$name', mode=$mode, location=($x, $y, $z), doorNumber=$doorNumber, doorNumbers=$doorNumbers)"
    }
}
