package cn.oneachina.zombierun.v2.ports

import cn.oneachina.zombierun.v2.domain.task.TaskProgress
import java.util.UUID

interface PlayerTaskPort {
    fun load(playerId: UUID): Map<String, TaskProgress>
    fun save(playerId: UUID, progress: Map<String, TaskProgress>)
    fun close()
}