# Zombie Run v2 架构设计（Draft v0.1）

> 状态：**已批准，M0–M17 已按本文档实施**（2026-08-25）。后续模块继续按此演进。
> 关联文档：[ROADMAP.md](./ROADMAP.md)

---

## 1. 目标与非目标

### 1.1 目标

- 在独立 `v2` 分支上构建一个可测试、可扩展、线程安全的 Zombie Run v2。
- `main` 上的 v1 继续保留，可玩、可发布，v2 稳定后再切换。
- 采用轻量分层架构 + 服务注册表，不引入重型 DI 框架。
- 首先落地**可靠的门检测垂直切片**，再逐步迁移全部子系统。
- 配置改为全新 v2 格式，并提供 `/zr v2 migrate` 迁移命令。
- 玩家持久化数据（硬币/XP/任务等 SQLite）保持兼容或提供无损迁移。

### 1.2 非目标

- 不在 v2 中盲目增加新玩法；玩法行为与 v1 对齐，除非是明确 bug。
- 不迁移为纯 Bukkit/Spigot：继续只支持 Paper/Folia 26.1.x。
- 不引入 Koin/kotlin-inject 等第三方 DI 框架。
- 不追求一次提交完成全部模块；按里程碑逐步推进。

---

## 2. v1 现状问题清单

以下问题来自对当前 `main` 分支的代码盘点，是 v2 架构要解决的核心痛点：

### 2.1 插件主类是“万能 God Object”

`ZombieRun.kt` 里 27 个 `by lazy` 单例互相依赖：

- `GameManager` 直接调用 `DoorManager / RespawnManager / HealthManager / QuestManager / StaminaManager`
- `DoorManager` 又反向调用 `GameManager / ButtonManager / RespawnManager / ProgressionListener`
- 对象图无生命周期（`init/start/stop/reload` 不统一），`/zr reload` 只能重载部分配置
- 禁用插件时依赖 Kotlin `lateinit` 初始化顺序，容易 NPE

### 2.2 业务逻辑与 Bukkit API 深度耦合

几乎所有 Manager 都直接操作 `Player / World / Block / Location / ScheduledTask`：

- 门检测、队伍切换、进度、经济逻辑无法脱离服务器做单元测试
- Folia 线程模型靠注释和 `@Volatile` 手工保证，跨线程正确性难以验证
- 领域概念（门、门组、房间、队伍）和基础设施概念（方块快照、region scheduler）混在同一个类里

### 2.3 命令层严重单文件化

`ZombieRunCommand.kt` 1016 行，承载所有子命令、权限判断、参数解析、Tab 补全散落其中；`DoorBehaviorCommands`、`CoinCommands` 又独立于统一命令架构。

### 2.4 配置是一个不断打补丁的 God Config

`ConfigManager` 同时负责：

- 默认模板拷贝
- v1 迁移
- 门/按钮/重生点读写
- 特殊行为解析
- combat/economy/balance 子配置
- 门方块扫描快照文件

没有 schema 版本化、没有严格校验、迁移逻辑和业务读取耦合。

### 2.5 门检测可靠性问题

详见上一轮分析，核心缺陷：

- 穿越方向默认“正坐标 = 前方”，`reverse-direction` 只能手改 YAML，无命令、无文档
- `crossedBy` 有 `axisDelta > 2.5` 的硬性放弃逻辑
- 只检查移动终点，不检查移动线段与门平面的交点
- 关门兜底 `isPlayerPastDoor` 使用 ±5 容差，误判风险高
- 没有“玩家在门后侧/前侧”的连续状态，折返场景结果取决于事件是否被捕获
- 玩家通过后无即时反馈，只有 debug 日志

### 2.6 死代码与遗留结构

- `DoorZoneManager` 的查询方法无任何调用方
- `.trae/specs/door-detection-optimization` 描述的是旧优化方向，当前实现已偏离，缺少权威架构文档
- `GameListener` 同时承担选区棒、按钮交互、移动检测、聊天格式化、方块限制等多个职责

---

## 3. v2 架构总览

### 3.1 分层

```
┌──────────────────────────────────────────────────────────────┐
│ plugin bootstrap (JavaPlugin, ServiceRegistry, 生命周期)       │
├──────────────────────────────────────────────────────────────┤
│ infrastructure/adapters (Bukkit 实现)                          │
│   BukkitListeners / Commands / GUI / Scheduler / WorldAdapter │
│   PAPI / Multiverse / QualityArmory 集成                        │
├──────────────────────────────────────────────────────────────┤
│ application (用例编排，无 Bukkit 类型)                          │
│   GameFlowService / DoorApplicationService / CommandHandlers  │
│   ApplicationEventBus                                          │
├──────────────────────────────────────────────────────────────┤
│ domain (纯 Kotlin，可单元测试)                                  │
│   Game / Door / PortalCrossing / Team / Room / Respawn         │
│   Stamina / Combat / Economy / Progression / Quest             │
├──────────────────────────────────────────────────────────────┤
│ ports (领域定义的接口，由 infrastructure 实现)                   │
│   Scheduler / WorldAccess / BlockSnapshotStore / Teleporter    │
│   PlayerRepository / EventDispatcher                            │
└──────────────────────────────────────────────────────────────┘
```

依赖方向：**只允许外层依赖内层，domain 不得 import org.bukkit。**

### 3.2 目标包结构

```text
cn.oneachina.zombierun.v2
├── plugin
│   ├── ZombieRunV2Plugin.kt          # 薄启动器
│   ├── V2ServiceRegistry.kt          # 轻量服务注册表
│   └── V2CompositionRoot.kt          # 唯一对象装配点
├── domain
│   ├── game                          # GameInstance, GameState, Team, Room
│   ├── door                          # DoorDefinition, Portal, DoorSession
│   │   ├── PortalCrossingDetector.kt
│   │   └── DoorSessionStateMachine.kt
│   ├── doorbehavior                  # DoorBehavior 策略接口与实现
│   ├── respawn
│   ├── stamina
│   ├── combat
│   ├── economy
│   ├── progression
│   ├── quest
│   └── title
├── application
│   ├── game                          # GameFlowService 等用例
│   ├── door                          # DoorApplicationService
│   ├── command                       # 每个子命令一个 handler
│   └── event                         # 应用内部事件
├── ports
│   ├── SchedulerPort.kt
│   ├── WorldPort.kt
│   ├── BlockSnapshotPort.kt
│   ├── PlayerPort.kt
│   ├── TeleporterPort.kt
│   ├── PersistencePort.kt
│   └── MessagePort.kt
├── infrastructure
│   ├── bukkit                        # 所有 Bukkit API 实现
│   │   ├── listeners
│   │   ├── commands
│   │   ├── gui
│   │   ├── scheduler
│   │   ├── world
│   │   └── player
│   ├── config                        # v2 schema 读取/校验/迁移
│   ├── persistence                    # SQLite/Hikari 实现
│   └── integration                    # PAPI / Multiverse / QualityArmory
└── support
    ├── logging
    ├── debug
    └── kotlin
```

### 3.3 服务注册表（轻量 DI）

不引入反射式 DI。`V2CompositionRoot` 手工按依赖顺序构造服务：

```kotlin
class V2ServiceRegistry {
    fun <T : Any> register(type: KClass<T>, service: T)
    fun <T : Any> get(type: KClass<T>): T
}

interface V2Service {
    fun onLoad(registry: V2ServiceRegistry)
    fun onEnable()
    fun onDisable()
    fun onReload()   // 只重载可安全热重载的部分
}
```

规则：

1. 构造器注入，不搞字段注入。
2. domain 服务不需要 `onEnable`，保持无状态；application 服务编排流程；infrastructure 负责真实副作用。
3. `onReload` 必须明确声明可重载范围；门会话、玩家战斗状态等运行时状态不通过 reload 破坏。
4. 所有定时任务由 `TaskRegistry` 统一登记，插件禁用时按序取消。

### 3.4 运行时：per-world GameRuntime

v1 的 `GameManager` 按世界创建 `GameInstance`，但门、重生点、按钮仍是全局 Manager。v2 改为：

```text
GameInstance (domain aggregate)
  ├── GameStateMachine        WAITING/STARTING/RUNNING/ENDED
  ├── PlayerRoster             teams, rooms, runtime presence
  ├── DoorRuntime              active door sessions
  ├── RespawnTable             arena respawn points
  └── ArenaDefinition          该世界的门/按钮/重生点配置
```

- `ArenaRepository` 启动时加载所有世界的 `ArenaDefinition`。
- 游戏开始时由 `GameFlowService` 创建 `GameRuntime`，结束时销毁；世界无对局时不保留运行时对象。
- 门会话属于 `GameRuntime`，天然按世界隔离，避免 v1 中多个 `ConcurrentHashMap` 按世界打补丁。

---

## 4. 门系统 v2 详细设计（首个垂直切片）

### 4.1 数据模型

```kotlin
data class DoorDefinition(
    val id: DoorId,
    val world: String,
    val number: Int?,
    val group: String?,
    val schedule: DoorSchedule,          // openSeconds / closeSeconds
    val portal: Portal,
    val behavior: DoorBehavior?,
    val blockRegion: BlockRegion,
    val blockSnapshotId: String?,
)

data class Portal(
    val axis: PortalAxis,                 // X 或 Z
    val front: PortalFront,               // POSITIVE 或 NEGATIVE
    val transverseMin: Double,            // 门洞横向下界
    val transverseMax: Double,            // 门洞横向上界
    val yMin: Double,
    val yMax: Double,
)
```

关键变化：

- **穿越轴与前方显式化**：配置直接写 `axis: x | z` 和 `front: positive | negative`，不再靠“区域长边”猜测，不再使用隐藏的 `reverse-direction`。
- **门洞是 Portal，方块区域是 BlockRegion**：检测用 Portal；开关门只操作 BlockRegion。
- `DoorDefinition` 是不可变配置；运行时状态全部放 `DoorSession`。

### 4.2 检测算法：线段-平面相交 + 侧边状态机

`PlayerMoveEvent` 的 `from -> to` 视为有向线段，检测是否与 Portal 平面相交：

```kotlin
fun crossingOnSegment(from: Vec3, to: Vec3, portal: Portal): PortalCrossing? {
    // 1. 计算 from/to 在穿越轴上的位置
    // 2. 计算线段与 portal 平面（center plane）的交点 t ∈ [0,1]
    // 3. 在交点处校验横向/Y 是否落在门洞范围 ± tolerance
    // 4. 校验穿越方向是否等于 portal.front
}
```

相比 v1 的改进：

| v1 问题 | v2 方案 |
|---|---|
| `axisDelta > 2.5` 直接放弃 | 只要 from/to 跨平面就计算交点，不拒绝大位移 |
| 只检查 `to` 是否在门洞范围 | 检查**交点**是否在门洞范围，快速/低 TPS/斜向穿门也不漏 |
| 没有玩家侧边历史 | 每扇 session 维护 `BEHIND / FRONT / UNKNOWN` 状态 |
| 关门时才一次性判定 | 穿越瞬间记录，并立刻发应用事件反馈玩家 |
| ±5 模糊兜底 | 收紧为 ±1.0，且要求“初始在门后侧 + 无传送记录”才允许兜底 |
| 传送可能伪装成穿门 | `PlayerTeleportEvent` 标记该玩家本次会话不参与移动判定 |

### 4.3 DoorSession 状态机

```text
CREATED → OPENING → OPEN(CLOSING) → CLOSED
              │            │
              └─ 所有 player 初始 side 快照
                           │
              ┌────────────┴─────────────┐
        move event:                    close:
        BEHIND→FRONT 且交点合法         transition 记录优先
        → crossedPlayers.add           fallback 仅兜底且打日志
```

关闭判定顺序：

1. 已记录穿越的玩家 → `PASSED`
2. 未记录但满足全部兜底条件的玩家：
   - 会话开始时位于门后侧
   - 关闭瞬间位于门前侧
   - 当前位置在门洞投影范围内（收紧容差）
   - 本会话内没有发生 `PlayerTeleportEvent`
   - → `PASSED_FALLBACK`（控制台 warning + debug）
3. 其余 → `BEHIND`，进入落后传送流程

### 4.4 可测试性

`PortalCrossingDetector` 与 `DoorSessionStateMachine` 是纯 Kotlin，不依赖 Bukkit。单元测试至少覆盖：

- 正常正面穿门
- 斜向穿门
- 5 TPS 大位移穿门（线段很长但跨平面）
- 击退/爆炸导致的大位移穿门
- 穿门后折返（必须仍然 PASSED）
- 站在门洞旁边但未穿越（不得 PASSED）
- 传送越过门（不得 PASSED）
- 反向门配置（front=negative）
- 门组多入口同时开放
- Y 轴容差边界

### 4.5 开关门

- `BlockSnapshotPort` 负责扫描/恢复方块快照，v2 快照文件独立存放。
- 快照恢复失败逐块上报（不再静默 catch），debug 模式可视化。
- 门开启/关闭的所有方块操作统一走 region scheduler，由 `DoorApplicationService` 编排。

---

## 5. 配置 v2

### 5.1 文件布局

```text
plugins/zombie-run-v2/
├── config/
│   ├── settings.yml              # 插件级设置（语言、调试、数据库）
│   ├── arenas/
│   │   ├── arena1.yml            # 一个游戏世界 = 一个 arena 文件
│   │   └── arena2.yml
│   └── doors/
│       ├── door_1.scandata.yml   # 方块快照
│       └── door_2.scandata.yml
└── data/
    └── zombie-run.db             # 玩家持久化数据（兼容 v1）
```

### 5.2 settings.yml 示例

```yaml
schema: 2
debug: false
game:
  default-world: world
  start-delay: 30
  min-players: 8
  max-duration: 1800
```

### 5.3 arena.yml 示例

```yaml
schema: 2
name: arena1
world: world

doors:
  door_1:
    number: 1
    mode: normal                 # normal | start | player | zombie
    group: null
    region: [10, 64, 10, 10, 66, 20]
    portal:
      axis: x
      front: positive
      y: [64, 66]
      transverse: [10, 20]       # 沿门洞长边
    schedule:
      open: 15
      close: 15
    snapshot: door_1.scandata.yml
    behavior: null

buttons:
  btn_1:
    at: [12, 64, 12]
    mode: normal
    door-numbers: [1]

respawns:
  wait_1:
    type: wait
    at: [0, 64, 0]
```

### 5.4 迁移命令

```text
/zr v2 migrate
```

行为：

1. 读取 `plugins/zombie-run/config/config.yml` 与子配置
2. 读取 v1 scandata 文件
3. 转换为 v2 schema（对每个门根据其 thin axis 推断 `portal.axis`，`front` 默认 positive 并在报告中提示需要人工确认）
4. 输出迁移报告：成功项、需要人工确认项、失败项
5. 只写 v2 目录，**不改动 v1 文件**；自动生成 `.bak` 不会覆盖已有备份

注意：v1 的 `reverse-direction` 会正确映射为 `front: negative`。

---

## 6. 子系统迁移顺序

详细任务见 [ROADMAP.md](./ROADMAP.md)。粗粒度顺序：

| 里程碑 | 内容 | 是否可独立验收 |
|---|---|---|
| M0 | v2 分支、插件骨架、ServiceRegistry、TaskRegistry | 可启动空插件 |
| M1 | 门系统垂直切片（配置、门检测、按钮、重生点） | 单人可完整体验开关门 |
| M2 | 游戏状态机 + 队伍 + 房间 + 结算 | 可跑完整对局 |
| M3 | 体力/生命/战斗/感染 | 僵尸与人类核心对抗 |
| M4 | 武器系统 + QualityArmory 适配 | 枪械可用 |
| M5 | 经济/进度/任务/称号 | 成长闭环 |
| M6 | GUI（商店/个人/任务/称号） | 交互完整 |
| M7 | PAPI/Multiverse/直升机/特殊门行为 | 兼容面完整 |
| M8 | 迁移工具完善、性能验证、文档 | 可发布 beta |

---

## 7. 跨切面设计

### 7.1 调度器抽象

```kotlin
interface SchedulerPort {
    fun global(): TickScheduler
    fun regionAt(location: Location): TickScheduler
    fun entity(player: Player): TickScheduler
}

interface TickScheduler {
    fun every(delay: Long, period: Long, task: TickTask): TaskHandle
    fun later(delay: Long, task: TickTask): TaskHandle
}
```

- domain 只依赖 `SchedulerPort`，不感知 Folia/Paper 差异。
- 所有 `TaskHandle` 注册到 `TaskRegistry`，保证插件禁用、游戏结束、玩家退出三条路径都能取消任务。

### 7.2 应用事件总线

- Bukkit 事件 → Listener 翻译为领域事件 → ApplicationEventBus 分发。
- 跨模块通知（例如“玩家通过 3 号门”）不再直接调用对方 Manager。
- 关键事件：`GameStarted / GameEnded / DoorOpened / DoorClosed / PlayerPassedDoor / PlayerInfected / PlayerKilled / RoomChanged`。

### 7.3 消息与调试

- `MessagePort` 统一 Title/ActionBar/Chat/Sound 输出，domain 只描述“发生了什么”。
- Debug 模式按频道输出：`door / game / damage / room / thread`，不再全局一个布尔值广播到所有玩家。

### 7.4 Folia 线程约定

- domain/application 代码**禁止直接调用 Bukkit**。
- 所有 Bukkit 副作用集中到 infrastructure 的 `WorldPort / PlayerPort` 实现。
- 跨线程可变状态只出现在 infrastructure；domain 的会话状态通过端口调度到固定线程，或使用不可变快照 + 原子更新。
- 任何 `@Volatile / ConcurrentHashMap` 出现必须写清读写线程。

---

## 8. 兼容性决策

| 项 | v2 决策 |
|---|---|
| v1 插件 jar | 不共存运行；v2 使用 `zombie-run-v2` 作为插件名与数据目录 |
| v1 地图配置 | 不直接读取；通过 `/zr v2 migrate` 转换 |
| v1 SQLite 玩家数据 | 保持兼容，v2 复用原库或迁移复制 |
| QualityArmory | 保留 hard dependency，封装为 `WeaponIntegrationPort` |
| Multiverse | soft depend，封装为 `WorldResolvePort` |
| PlaceholderAPI | 保留，占位符前缀可配置，v1 占位符名尽量兼容 |

---

## 9. 风险与开放问题

1. **迁移的 portal.front 推断**：v1 没有显式方向，迁移只能按正方向默认，必须人工确认或加 `--dry-run` 报告。
2. **reload 边界**：v2 只保证配置/方块快照可重载；进行中的对局门会话不热重载。
3. **门组与多入口**：v2 将门组作为一级概念，需要确认“组内任一门穿越即算过门”的规则是否维持。
4. **数据库双版本**：v1/v2 并行期间玩家数据如何同步，建议 v2 只读复制库，不写回 v1。
5. **GUI 事件模型**：InventoryHolder 是否保留，或改成菜单 ID + session 注册表，需在 M6 确认。

---

## 10. 文档维护

- 本文档是 v2 架构的单一事实来源，后续 ADR 放 `docs/v2/adr/`。
- 每次跨模块行为变更先更新本文档/ROADMAP，再改代码。
- 分支策略：`main` = v1 稳定线；`v2` = 架构重构线；功能 PR 先合 `v2`。
