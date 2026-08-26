# ZombieRun v2 完整游戏流程框架（MapFlow）

> 目标：让 v2 从“功能模块集合”变成“可完整开局/玩到结束”的游戏。
> 原则：先立流程框架，再把现有门/对局/经济/任务通过事件总线接入。

## 1. 核心概念

一张地图（arena）在 v2 中不再只是一堆门/按钮/重生点，而是一条 **MapFlow**：

```
world
  └── MapFlowDefinition（地图流程定义）
        ├── waiting-stage     等待阶段配置：人数/倒计时/大厅重生点
        ├── stages            门推进阶段列表（每阶段=要穿过的一扇/一组门）
        ├── finish            终点/胜利配置
        ├── spawns            双方重生点复用现有 RespawnDefinition
        └── rewards           结算奖励（硬币/经验）
```

运行时每个世界有一个 `MapFlowStateMachine`：

```
WAITING ──人数够/手动──▶ STARTING ──倒计时──▶ RUNNING
RUNNING ──到达终点/撤离──▶ HUMAN_WIN
RUNNING ──人类被感染殆尽──▶ ZOMBIE_WIN
RUNNING ──时间耗尽──▶ HUMAN_WIN
任何非结算阶段 ──玩家全离场/重置──▶ WAITING
```

## 2. 领域模型（纯 Kotlin，不依赖 Bukkit）

```kotlin
enum class MapFlowPhase { WAITING, STARTING, RUNNING, HUMAN_WIN, ZOMBIE_WIN }

data class MapFlowStage(
    val id: String,
    val label: String,
    val doorNumbers: List<Int>,      // 本阶段需要穿过的一扇/一组门
    val nextStageId: String? = null, // 穿过后进入的阶段；null = 到达终点
)

data class MapFlowFinish(
    val type: FinishType,            // DOOR 或 EXTRACTION
    val doorNumber: Int? = null,     // DOOR：哪扇门是终点门
)

enum class FinishType { DOOR, EXTRACTION }

data class MapFlowDefinition(
    val arenaName: String,
    val world: String,
    val minPlayers: Int,
    val startDelaySeconds: Int,
    val maxDurationSeconds: Int,
    val stages: List<MapFlowStage>,
    val finish: MapFlowFinish,
)
```

### 状态机职责

- `enterWaiting()` / `beginStart()` / `start()` 阶段切换
- `onDoorPassed(doorNumbers)`：
  - 判断是否命中当前阶段门号
  - 命中 → 记录通过 → 进入下一阶段 / 触发 `HUMAN_WIN`
- `onAllHumansInfected()` → `ZOMBIE_WIN`
- `onTimeUp()` / `onExtraction()` → `HUMAN_WIN`
- 保存当前阶段、已通过集合、当前门编号

## 3. 与现有模块的接入

```
PlayerMoveEvent ─▶ DoorApplicationService ─▶ MapFlowStateMachine.onDoorPassed
                                             ├─▶ 进入下一阶段：门系统只开放下一阶段门
                                             ├─▶ 到达终点：GameFlowService.endGame(HUMAN)
                                             └─▶ 事件总线：StageAdvancedEvent / MapFlowFinishedEvent

PlayerInteractEvent(ESCAPE) ─▶ GameFlowService.triggerEscape ─▶ MapFlowStateMachine.onExtraction
Zombie感染人类 ─▶ GameFlowService.onCombat ─▶ onAllHumansInfected
```

### 门解锁策略

- **开始对局前**：所有 `normal` 门默认关闭
- **当前阶段门**：允许触发/开启；阶段门通过后立即进入下一阶段
- **非当前阶段门**：按钮触发返回“该门尚未解锁”
- 终点门通过时直接结算人类胜利

## 4. 玩家生命周期（PlayerCycle）

- 玩家进入 arena 世界：
  - `WAITING/STARTING`：加入人类/观战，等待开局
  - `RUNNING`：加入僵尸方（沿用现有规则）
- 感染/死亡：
  - 人类被僵尸/母体击杀（濒死）→ **被感染转为普通僵尸**，传送到当前阶段门后的僵尸检查点，3 秒无敌
  - 人类因非感染原因死亡（摔死/环境/第三方）→ **直接淘汰转观战**，不再复活回人类
  - 普通僵尸被人类击杀 → **传回当前阶段门后的僵尸检查点**，3 秒无敌后继续追击（不算淘汰）
  - 所有人类被感染/阵亡 → `ZOMBIE_WIN`
- 退出：从当前对局移除；若母体退出则从僵尸中选替补成为新母体（立即释放，不再锁开局倒计时）

### 出生点与母体机制

| 类型 | 用途 | 分配规则 |
| --- | --- | --- |
| `WAIT` | 等待大厅/兜底 | 无更优解时兜底 |
| `PLAYER` | 人类开局出生点 | 开局随机选一个 |
| `ZOMBIE_MAIN` | 母体初始出生点 | 开局随机选一个；与人类起点相近但隔开 |
| `ZOMBIE` | 普通僵尸出生点 | 随机池（多点随机，防守尸） |
| `DOOR_PLAYER` | 门后人类检查点 | 门关闭落后的玩家传送用 |
| `DOOR_ZOMBIE` | 门后僵尸检查点 | 僵尸/母体复活、被感染转化、落后传送共用 |

- **母体身份固定**：开局随机一名玩家为母体，整局不变（被击杀不会变普通僵尸）。
- **母体晚释放**：开局母体在 `ZOMBIE_MAIN` 出生，处于无敌锁定状态，`mother-release-delay-seconds`（默认 10s）倒计时结束才可攻击（`/zr2 mapflow set <arena> mother-release-delay-seconds <秒>`）。
- **母体死亡/普通僵尸死亡**：不淘汰，传回**当前 MapFlow 阶段门号对应的 `DOOR_ZOMBIE`**（避免被已关闭的门卡在起点），3 秒无敌。
- **门关闭落后的玩家**：保留 10 秒传送倒计时，传送到该门号对应的 `DOOR_PLAYER` / `DOOR_ZOMBIE`，找不到才兜底 `WAIT`。

## 5. 结算与奖励

- `HUMAN_WIN`：全体人类获得 `reward-coins-human` / `reward-xp-human`（通过 `PlayerDataService` 发放）
- `ZOMBIE_WIN`：存活僵尸获得 `reward-coins-zombie` / `reward-xp-zombie`
- 结算后 `MapFlowStateMachine` 复位到 `WAITING`（5 秒后自动重置，准备下一局）
- 开局时人类可自动获得 `starter-weapon` 指定武器（通过 `WeaponService`）

## 6. 配置示例

```yaml
# config/arenas/map1.yml
schema: 2
name: map1
world: world
map-flow:
  min-players: 2
  start-delay-seconds: 10
  max-duration-seconds: 600
  mother-release-delay-seconds: 10
  stages:
    - id: s1
      label: 大门
      door-numbers: [1]
      next-stage-id: s2
    - id: s2
      label: 地铁
      door-numbers: [2, 3]
      next-stage-id: s3
    - id: s3
      label: 终点线
      door-numbers: [4]
  finish:
    type: DOOR
    door-number: 4
```

## 7. 里程碑验收

- [ ] 单元测试：完整一局（WAITING→RUNNING→过门推进→终点→HUMAN_WIN）
- [ ] 单元测试：感染殆尽→ZOMBIE_WIN
- [ ] 单元测试：非当前阶段门不推进
- [ ] 示例 arena 可真实开局并通过门到达终点
- [ ] Paper 烟测：自动开局、门推进、结算均无异常