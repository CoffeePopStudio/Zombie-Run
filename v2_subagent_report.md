# Zombie Run v2 分支特性分析报告

> 分析对象：当前工作区 `v2` 分支  
> `HEAD = 0dd7a10`，`origin/v2 = c7d086a`，本地领先 6 个提交；工作区含未提交修改。  
> 分析方法：实际执行 `git status` / `git diff HEAD`，并阅读 `src/main`、`src/test`、`docs/v2`、构建与 CI 文件。

---

## 0. Git 状态与未提交修改

### 0.1 分支状态

- 当前分支：`v2`
- 领先 `origin/v2` 6 个提交：
  - `0dd7a10 fix(v2): 任务库 SQLite 开启 WAL + busy_timeout，对齐经济库防 database is locked`
  - `e359e27 fix(v2): 补齐状态接管细节 - 观战/母体保护/死亡血量重置 + 开局发弹测试`
  - `0ec7f20 feat(v2): 移植 v1 经济反馈与基建 - 可配置奖励/榜单/黑羊毛/postool`
  - `76d3b7a feat(v2): 移植 v1 游戏层 - 自定义血量/战斗接管/保护监听/状态接管/即时奖励/自动门`
  - `de1e7db feat(v2): 开局自动补弹 + 商店局内补弹 + 潜行+F 快捷开商店`
  - `5929983 fix(gun): fix giveWeapon use displayName instead type`

### 0.2 未提交修改

`git diff HEAD` 显示修改了 3 个源码文件（另有 `.idea` 配置）：

- `src/main/kotlin/cn/oneachina/zombieRun/v2/application/game/GameFlowService.kt`
  - 直升机撤离成功时新增 `mapFlows[worldName]?.onExtraction()`，先同步 MapFlow 状态再统一结算。
  - 最大时长结束时新增 `mapFlows[worldName]?.onTimeUp()`，保证 MapFlow 状态机同步进入 `HUMAN_WIN`。
- `src/main/kotlin/cn/oneachina/zombieRun/v2/application/weapon/WeaponService.kt`
  - `giveRandom` 改为用 `weapon.type` 而非 `weapon.displayName` 调用 QA。
  - 新增 `giveWeaponWithAmmo(playerId, weaponId)`：主动发枪成功后补 1 个弹匣。
- `src/main/kotlin/cn/oneachina/zombieRun/v2/infrastructure/bukkit/command/Zr2Command.kt`
  - `/zr2 weapon give <id>` 改走 `giveWeaponWithAmmo`，保证发枪后可直接开火。

---

## 1. 总体定位与构建

### 1.1 plugin.yml

文件：`src/main/resources/plugin.yml`

- 插件名：`zombie-run-v2`
- 主类：`cn.oneachina.zombierun.v2.plugin.ZombieRunV2Plugin`
- `api-version: '1.21'`，`folia-supported: true`
- 硬依赖：`QualityArmory`
- 软依赖：`Multiverse-Core`、`PlaceholderAPI`
- 命令：`/zr2`，别名 `zombierun2`
- 权限：
  - `zombie.run.v2.admin`：管理命令，默认 OP
  - `zombie.run.v2.player`：玩家基础命令，默认 true（注意：代码中实际上没有使用该节点做任何检查）

### 1.2 构建配置

文件：`build.gradle.kts`

- Kotlin `2.3.20-Beta2`，JDK toolchain 25。
- `paper-api:26.1.2.build.+` compileOnly。
- `PlaceholderAPI`、`QualityArmory`、`Multiverse-Core` 均为 compileOnly。
- 运行期打入 shadow jar 的依赖：Kotlin stdlib、HikariCP 7.0.2、sqlite-jdbc 3.49.1.0。
- 产物：
  - `build/libs/zombie-run-v2-all.jar`（shadow jar，默认构建产物）
  - `build/libs/zombie-run-v2-thin.jar`
- `runPaper { folia.registerTask() }`，`runServer` 使用 Paper 26.1.2 并自动下载 QualityArmory。
- 测试 JVM 参数：`-Dnet.bytebuddy.experimental=true`（Mockito/ByteBuddy 在 JDK 25 下）。

### 1.3 CI

文件：`.github/workflows/build.yml`

- 触发：push/PR 到 `v2` 或 `main`，以及 `workflow_dispatch`。
- Job `build`：JDK 25，`./gradlew test build --no-daemon`，上传 `build/libs/zombie-run-v2-*.jar`。
- Job `release`：打 `v*` tag 时自动创建 GitHub Release 并附带 jar。
- 注意：当前 CI 没有独立的 Folia 烟测，与 `ROADMAP.md` 中“Folia 加载实测待验证”一致。

---

## 2. 分层架构

### 2.1 包结构与依赖方向

源码主包：`cn.oneachina.zombierun.v2`（目录为 `src/main/kotlin/cn/oneachina/zombieRun/v2`，但 package 声明使用 `zombierun`）。

分层如下：

- `plugin/`：`ZombieRunV2Plugin`、`V2CompositionRoot`、`V2ServiceRegistry`
- `application/`：用例编排层，不直接出现 Bukkit 类型
  - `game/GameFlowService`
  - `door/DoorApplicationService`
  - `player/PlayerDataService`
  - `task/TaskService`
  - `weapon/WeaponService`
  - `combat/CombatHealthService`、`StaminaService`
  - `event/ApplicationEventBus`、`ApplicationEvents`
- `domain/`：纯 Kotlin 领域模型，禁止依赖 `org.bukkit`
  - `arena`、`combat`、`door`、`game`、`player`、`task`、`weapon`
- `ports/`：应用/领域所需接口
  - `WorldAccessPort`、`BlockOpsPort`、`PlayerMessagePort`、`TeleporterPort`、`GameContextPort`
  - `PlayerDataPort`、`PlayerTaskPort`、`SchedulerPort`、`WeaponIntegrationPort`
- `infrastructure/`：Bukkit/配置/存储实现
  - `bukkit`：ports 实现、listener、command、gui、scheduler、weapon、hook
  - `config`：settings/arena/task/weapon/快照/v1 迁移
  - `storage`：SQLite 玩家数据与任务仓储
- `support/`：`TaskRegistry`、`V2Logger`

### 2.2 服务注册表

文件：`plugin/V2ServiceRegistry.kt`

- 轻量 `LinkedHashMap<KClass<*>, Any>`，`register` 重复注册会 `require` 报错，`get` 未注册会抛错。
- 现状：`V2CompositionRoot.enable()` 注册了全部服务，但**代码中没有调用 `services.get(...)`**，注册表目前更像“服务目录/契约登记”，没有实际被消费。
- 架构文档设计的 `V2Service.onLoad/onEnable/onDisable/onReload` 生命周期接口**并未实现**，实际生命周期由 `V2CompositionRoot` 手工编排。

### 2.3 组合根与生命周期

文件：`plugin/V2CompositionRoot.kt`

- 手工构造器注入，无反射 DI。
- `enable()`：
  - 加载 settings、arena、weapon；
  - 构造 `GameFlowService`；
  - 注册 PAPI 扩展；
  - 注册所有 listener；
  - 注入 `zombieBuffApplier`、`motherReleaseStateSync`、`doorAutoOpener` 钩子；
  - 启动 `gameFlow.start()` 与 `combatListener.start()`；
  - 注册 `/zr2` 命令。
- `disable()`：
  - 停止体力 tick、取消所有门会话、停止对局流程、`taskRegistry.cancelAll()`、关闭任务/玩家数据仓储。
- 风险：`DoorApplicationService.cancelAllSessions()` 只做状态关闭与任务取消，**不会恢复已经打开的门方块**；reload/disable 时可能遗留打开状态的门。

### 2.4 事件总线

文件：`application/event/ApplicationEventBus.kt`

- 内部事件类型：`PlayerPassedDoorEvent`、`GameStartedEvent`、`GameEndedEvent`、`ZombieKilledEvent`、`InfectHumanEvent`。
- `CopyOnWriteArrayList` 存储订阅者；`publish` 遍历 handler 时每个 handler 独立 try/catch，单个异常不影响其他订阅者。
- 不足：异常直接 `e.printStackTrace()`，未走 `V2Logger`；`publish` 在锁外迭代，依赖 COW 列表保证并发安全。

---

## 3. 功能模块清单

### 3.1 Application Service

| 文件 | 职责 |
|---|---|
| `application/game/GameFlowService.kt` | 对局编排：自动开局、倒计时、队伍/母体、感染/击杀、撤离、超时、MapFlow 推进、结算奖励、任务注册、跨世界离开/加入、自动门、母体释放 |
| `application/door/DoorApplicationService.kt` | 门会话编排：触发/联动门组、开关门、移动/传送检测、关门判定、落后传送、特殊门行为传送、事件发布、reload 取消会话 |
| `application/player/PlayerDataService.kt` | 玩家资料缓存、原子读改写、硬币/经验/称号/门数/击杀更新，订阅过门/击杀事件 |
| `application/task/TaskService.kt` | 每日/每周任务进度、事件计数、领取奖励、周期重置 |
| `application/weapon/WeaponService.kt` | 武器配置 CRUD、随机/主动发枪、开局补弹、局内补弹 |
| `application/combat/CombatHealthService.kt` | 自定义血量/最大血量/最近伤害归因，未使用原子复合操作 |
| `application/combat/StaminaService.kt` | 体力状态机按 5 tick 更新 |
| `application/event/ApplicationEventBus.kt` | 应用内部事件分发 |

### 3.2 Domain 模块

- `domain/game/MapFlow.kt`：`MapFlowPhase`、`MapFlowStage`、`MapFlowFinish`、`MapFlowStateMachine`。
- `domain/game/GameInstance.kt`：`GamePhase`、`GameTeam`、`GameRules`、`PlayerAssignment`、`InfectResult`，内部 `synchronized(lock)` 保护队伍/房间/母体状态。
- `domain/door/Portal.kt`：`PortalAxis.X/Z/Y`、`PortalFront.POSITIVE/NEGATIVE`、`Side.BEHIND/FRONT`。
- `domain/door/PortalCrossingDetector.kt`：线段-平面相交检测。
- `domain/door/DoorSessionStateMachine.kt`：单次开门会话状态机。
- `domain/door/DoorDefinition.kt`、`BlockRegion.kt`、`Vec3.kt`、`DoorBehavior.kt`。
- `domain/arena/ArenaDefinition.kt`：Arena、Button、Respawn。
- `domain/combat/Stamina.kt`、`CombatRules.kt`。
- `domain/player/PlayerProfile.kt`：硬币/经验/等级/称号/击杀/门数。
- `domain/task/TaskDefinition.kt`：`DOOR_PASSES` / `ZOMBIE_KILLS`，`DAILY` / `WEEKLY`。
- `domain/weapon/WeaponDefinition.kt`：`GUN/MELEE/SPECIAL`。

### 3.3 Infrastructure Listener

| 文件 | 职责 |
|---|---|
| `V2DoorListener.kt` | `PlayerMoveEvent` → 门移动检测；`PlayerTeleportEvent` → 传送标记；按钮/拉杆交互 → 普通门/撤离 |
| `V2GameListener.kt` | 加入/退出/换世界/重生/潜行+F 快捷商店 |
| `V2CombatListener.kt` | 体力 tick、疾跑疲劳拦截、加入/退出重置 |
| `V2BattleListener.kt` | QA 射击、近战/僵尸爪、环境伤害、死亡→感染/击杀/僵尸复活，自定义血量接入 |
| `V2PlayerStateListener.kt` | 对局状态接管：GameMode、背包、药水、血量、母体冻结/释放、结束观战 |
| `V2PlayerDataListener.kt` | 玩家数据加载/保存 |
| `V2TaskListener.kt` | 任务进度加载/保存 |
| `V2ProtectionListener.kt` | 方块/物品/聊天/背包 GUI/副手切换保护 |
| `V2HazardListener.kt` | 黑羊毛机关秒杀 |

### 3.4 Infrastructure GUI / Command / Hook

- `gui/GuiService.kt`：个人资料、武器商店、任务、称号 GUI。
- `command/Zr2Command.kt`：全部 `/zr2` 子命令与 Tab 补全。
- `command/Zr2CommandParsing.kt`：纯参数解析与门添加解析。
- `hook/MultiverseWorldResolver.kt`：Multiverse 别名解析。
- `hook/ZombieRunV2Expansion.kt`：PlaceholderAPI 扩展。
- `weapon/QaWeaponIntegrationPort.kt`：QualityArmory 适配。
- `scheduler/BukkitSchedulerPort.kt`：Paper/Folia Global/Region Scheduler 适配。

---

## 4. 游戏机制

### 4.1 MapFlow 地图流程

文件：`domain/game/MapFlow.kt`、`application/game/GameFlowService.kt`

- 阶段机：`WAITING → STARTING → RUNNING → HUMAN_WIN / ZOMBIE_WIN`。
- `MapFlowStage` 包含 `doorNumbers` 与 `nextStageId`；`MapFlowFinish` 支持 `DOOR` 终点门或 `EXTRACTION` 撤离。
- `MapFlowStateMachine.onDoorPassed()` 只有 RUNNING 且命中当前阶段门才会推进/结算。
- `GameFlowService`：
  - 自动开局时若存在 `map-flow` 则同步 `mapFlows` 状态机；
  - 门按钮通过 `isDoorUnlocked()` 做“当前阶段才解锁”；
  - 过门事件触发 `STAGE_ADVANCED` 或 `FINISHED`；
  - 未提交修改补充了撤离/超时时 `onExtraction()` / `onTimeUp()` 同步。
- 示例配置：`docs/v2/examples/mapflow-demo.yml`，三阶段门 + DOOR 终点。

### 4.2 门系统

- **门模型**：`DoorDefinition` 同时持有 `Portal`（检测用）和 `BlockRegion`（开关方块用）。
- **PortalAxis**：X/Z 竖直门，Y 水平地板/天花板门；`front` 显式 `positive/negative`，替代 v1 的 `reverse-direction`。
- **检测算法**：`PortalCrossingDetector.crossing()` 使用 from→to 线段与门平面求交点，不因大步长放弃；默认容差 `transverse=0.6`、`vertical=0.5`，兜底容差 `1.0`。
- **DoorSessionStateMachine**：
  - 记录每玩家每扇门 `BEHIND/FRONT` 历史；
  - 仅 CLOSING 阶段接受移动穿越判定；
  - 传送玩家 `markTeleported` 不再参与；
  - 关门判定顺序：`PASSED → PASSED_FALLBACK → BEHIND`。
- **门组**：`DoorDefinition.group` 非空时，触发任一扇门会整组联动，任意一扇通过即算通过。
- **落后传送**：关门未通过的玩家进入 10 秒倒计时，传送到 `DOOR_PLAYER` / `DOOR_ZOMBIE`，找不到则 `WAIT`。
- **自动门**：`START` / `PLAYER` 门开局立即开，`ZOMBIE` 门延迟 100 tick 后开。
- 风险：`cancelAllSessions()` 不恢复方块；`close()` 内 `doors.indexOf(door)` 为 O(doors²)。

### 4.3 按钮

- `ButtonMode.NORMAL`：触发 `doorNumbers` 中的门。
- `ButtonMode.ESCAPE`：触发 `GameFlowService.triggerEscape()`，30 秒直升机撤离倒计时。
- 监听器只接受 `REDSTONE_LAMP` / `LEVER` 两种方块。
- 注意：`/zr2 button add` 命令目前只支持 `normal`，但 YAML/迁移可配置 `escape`。

### 4.4 特殊门/传送

- `DoorBehaviorType.ELEVATOR / SUBWAY / AIRPORT`。
- 通过门后按阵营选择 `humanTarget*` / `zombieTarget*`，倒计时后 `TeleporterPort.teleport`。
- 支持 `lineName`、`departure-msg`、`arrival-msg`、`countdown`、`delay-ticks`。

### 4.5 出生点 / 母体

- `RespawnType`：`WAIT / PLAYER / ZOMBIE / ZOMBIE_MAIN / DOOR_PLAYER / DOOR_ZOMBIE`。
- 开局随机选 1 名母体 `ZOMBIE_MAIN`，其余人类。
- 母体晚释放：`mother-release-delay-seconds`（默认 10s），释放前 `SPECTATOR` 冻结且不可攻击。
- 母体离开：从普通僵尸/人类中补位，新母体立即释放。
- 僵尸死亡/被感染复活：延迟 100 tick 传送到当前阶段门后的 `DOOR_ZOMBIE`，加 3 秒保护。
- **已知 bug**：`GameFlowService.scheduleZombieRespawn()` 内只接受 `GameTeam.ZOMBIE`，不接受 `ZOMBIE_MAIN`，因此母体死亡后的复活传送/增益实际不会执行。

### 4.6 自动开局 / 结算

- `autoTick()` 每秒检查 arena 世界人数，达到 `min-players` 开始倒计时；人数不足自动取消。
- `forceStart()` 跳过人数/倒计时直接开局。
- 结束条件：
  - 到达终点门 → HUMAN_WIN；
  - 直升机撤离 → HUMAN_WIN；
  - 时间耗尽 → HUMAN_WIN；
  - 人类被感染殆尽 → ZOMBIE_WIN。
- 结算：存活奖励、击杀/感染榜单 Top3、MapFlow 奖励，5 秒后自动 `reset()` 回 WAITING。
- `endGame` 发布 `GameEndedEvent`。

### 4.7 体力

- `StaminaState`：疾跑每 5 tick 消耗 0.25，静止恢复 0.08，疲劳延迟 40 tick，恢复门槛 4.0。
- `V2CombatListener` 每 5 tick 遍历**所有在线玩家**，疲劳时强制取消疾跑。
- `settings.yml` 可配 `stamina.*`；但 `recover-at` 未暴露配置。

### 4.8 武器

- `WeaponDefinition`：id/displayName/type/category/price/enabled。
- `WeaponService`：`giveStarter` 补 5 个弹匣；`giveWeaponWithAmmo` 补 1 个弹匣；`refillAmmo` 局内补弹。
- `QaWeaponIntegrationPort`：通过 `QualityArmory.getGunByName(type)` 发枪/补弹/持有判断。
- 默认 `weapons.yml`：AK-47、M4A1、战术小刀。

### 4.9 经济 / 等级 / 称号

- `PlayerProfile`：硬币、经验、等级（100×level 升级）、称号、僵尸击杀、门数。
- `PlayerDataService.mutate()` 使用 `ConcurrentHashMap.compute()` 做原子读改写。
- 经济奖励规则：击杀僵尸/母体、感染人类、爆头、过门、存活、榜单 Top3 均可配置。
- 称号：命令设置/清除，GUI 选择，PAPI 输出；称号目录目前硬编码在 `GuiService.titleCatalog()`，未做成 `titles.yml`。

### 4.10 每日/每周任务

- `TaskDefinition`：`DOOR_PASSES`、`ZOMBIE_KILLS`；`DAILY` 自然日重置，`WEEKLY` ISO 周重置。
- `TaskService` 订阅 `PlayerPassedDoorEvent` / `ZombieKilledEvent` 计数，`claim` 防重复领取。
- `tasks.yml` 默认空，管理员手工添加。

### 4.11 商店 GUI

- 商店 54 格：上半区武器购买，下半区补弹与关闭。
- 购买流程：`spendCoins` 成功 → `giveWeapon` → `refillAmmo(1)`；发枪失败回滚扣款。
- 补弹费用 = 枪价 × 20%，向上取整，单把最低 10 硬币；部分失败按比例退款。
- 潜行+F 快捷打开商店，代码限制 `GameTeam.HUMAN` 且 `RUNNING/WAITING`；但等待期玩家在 `GameInstance` 中是 `SPECTATOR`，所以“等待期打开商店”实际不生效。

### 4.12 PAPI

- 前缀 `zombierun`。
- 提供 `profile_level/xp/coins/title/kills/doors`、`game_phase`。
- v1 兼容别名：`level/xp/money/kills/doors/phase` 等。

---

## 5. 配置系统

### 5.1 settings.yml

文件：`infrastructure/config/V2SettingsLoader.kt`

- 自动生成默认 `config/settings.yml`。
- 包含：`schema`、`debug`、`game.*`、`stamina.*`、`economy.*`。
- `CombatRules` 不在 `V2Settings` 数据类中，而是 `V2CompositionRoot.loadCombatRules()` 二次读取同一个 `settings.yml` 的 `combat.*`。
- 没有严格校验 `schema` 是否为 2，也没有校验负数/异常值。

### 5.2 arena.yml

文件：`infrastructure/config/ArenaYamlRepository.kt`

- 目录：`config/arenas/*.yml`。
- 解析：`doors`、`buttons`、`respawns`、`map-flow`。
- 校验：
  - door region 必须 6 个整数；
  - normal door 必须有正数 `number`；
  - portal axis 必须 `x/y/z`，front 必须 `positive/negative`；
  - button normal 必须有 `door-numbers`；
  - DOOR 终点必须有 `door-number`；
  - MapFlow stage id 唯一、next-stage-id 必须存在；
  - 同世界跨 arena 门号重复会抛 `ArenaValidationException`。
- 保存：`YamlConfiguration` round-trip，文件名做安全字符替换。
- 示例：`docs/v2/examples/mapflow-demo.yml`。

### 5.3 tasks.yml / weapons.yml

- `TaskYamlRepository`：默认 `tasks: {}`；非法 type/period 跳过并 warn。
- `WeaponYamlRepository`：默认 3 把武器；按 id 覆盖保存/删除。

### 5.4 方块快照

- `BlockSnapshotStore`：`config/doors/<id>.scandata.yml`，key 为 `"x,y,z"`，value 为材质名。
- 添加门时 `addDoor()` 会同步扫描 `BlockRegion` 并保存快照。
- 关门时优先恢复快照，快照为空则用 `fallback-material`（默认 STONE）。

### 5.5 v1 迁移

- `V1MigrationService.migrate()`：读取 v1 `config/config.yml`，迁移门/按钮/重生点到 `migrated_v1` arena；同时导入 v1 `config/doors/*.scandata.yml` 并按方块坐标落在门区域做最佳匹配关联。
- `V1MigrationService.migrateData(overwriteExisting)`：从 v1 `data/zr_economy.db` 的 `zr_economy` / `player_progression` 导入硬币/等级/经验/称号/击杀；默认跳过已存在玩家，`--overwrite` 强制覆盖。

---

## 6. 命令与权限

### 6.1 `/zr2` 子命令（代码实际实现）

| 分组 | 子命令 |
|---|---|
| 基础 | `help`、`version`、`reload`、`postool` |
| arena | `list`、`info`、`create`、`remove` |
| door | `list`、`info`、`test`、`trigger`、`add` |
| button | `add`（仅 normal） |
| respawn | `add` |
| game | `list`、`status`、`start`、`end <human\|zombie>`、`reset` |
| weapon | `list`、`info`、`add`、`remove`、`give`、`random` |
| profile / economy | `profile [玩家]`、`coins add/give/spend`、`xp add`、`title set/clear` |
| menu | `profile`、`shop`、`tasks`、`titles` |
| task | `list`、`claim <id>` |
| mapflow | `list`、`info`、`init`、`set`、`stage add/set/next/remove`、`finish door/extraction`、`remove` |
| v1 | `v1 migrate`、`v1 migrate-data [--overwrite]` |

### 6.2 权限

- 只有 `zombie.run.v2.admin` 被代码检查，覆盖：
  - reload、arena create/remove、door add/trigger、button add、respawn add、game start/end/reset、weapon add/remove/give、coins add/give、xp add、title set/clear、mapflow 全部、v1 全部。
- 未做 admin 检查但属于玩家命令：
  - `arena list/info`、`door list/info/test`、`weapon list/info/random`、`profile`、`coins spend`、`menu *`、`task *`、`postool`。
- `zombie.run.v2.player` 权限节点定义了但**代码从未 `hasPermission` 检查**，因此即使服务器回收该权限，玩家命令仍可用。

### 6.3 文档与代码不一致

- `docs/v2/COMMANDS.md` 中的 `/zr2 arena setspawn`、`/zr2 door remove`、`/zr2 button add escape`、`/zr2 respawn list` 在代码中未实现。
- `postool` 命令已实现，但 `COMMANDS.md` 与 `/zr2 help` 的 Tab 首层补全中未列出。

---

## 7. 数据持久化与迁移

### 7.1 SQLite 玩家数据

文件：`infrastructure/storage/SqlitePlayerDataRepository.kt`

- 数据库：`plugins/zombie-run-v2/data/zr_economy.db`，表 `player_data_v2`。
- HikariCP：`maximumPoolSize=2`，`connectionTimeout=5000`，`PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000`。
- `save` 使用 `INSERT ... ON CONFLICT DO UPDATE`。
- 与 v1 共用数据库文件名但使用独立表，避免互相污染。

### 7.2 SQLite 任务数据

文件：`infrastructure/storage/SqlitePlayerTaskRepository.kt`

- 数据库：`data/zr_tasks.db`，表 `player_tasks_v2`。
- 最新提交 `0dd7a10` 已对齐 WAL + busy_timeout。
- 保存使用事务 + batch `INSERT OR REPLACE`。
- 仓储自带 `ConcurrentHashMap` 缓存，与 `TaskService` 缓存存在共享可变 `TaskProgress` 引用，需要注意。

### 7.3 v1 玩家数据迁移

- `/zr2 v1 migrate-data`：只读 v1 SQLite，写 v2 `player_data_v2`。
- 默认不覆盖；`--overwrite` 覆盖。
- 测试覆盖：`V1MigrationServiceTest` 中导入、防覆盖、快照关联、reverse-direction 映射。

### 7.4 门快照迁移

- `/zr2 v1 migrate` 会导入全部 v1 `.scandata.yml` 到 v2 快照存储。
- 按“方块坐标落在门区域内”的最大重叠快照自动关联到门；无重叠则仅导入不关联。
- 已知限制：`UPGRADE.md` 中仍写着“方块快照暂不自动迁移”，与代码/ROADMAP M17 不一致，文档需更新。

### 7.5 reload

- `/zr2 reload` 实际执行：
  - `DoorApplicationService.reload()`：取消全部门会话 + `arenaRepository.loadAll()`
  - `WeaponService.reload()`：重载 `weapons.yml`
- 不重载 `settings.yml`、`tasks.yml`、`CombatRules`、`StaminaRules`；热重载范围比文档声称的小。

---

## 8. 多世界支持

### 8.1 Multiverse 解析

文件：`infrastructure/bukkit/hook/MultiverseWorldResolver.kt`

- `resolve(name)`：先按 Bukkit 世界名精确匹配；未命中时若存在 Multiverse-Core，则在 MV 世界表中忽略大小写匹配。
- `BukkitWorldAccessPort`、`BukkitPlayerMessagePort`、`BukkitTeleporterPort` 均通过 `resolve()` 解析。

### 8.2 跨世界状态处理

- `V2GameListener.onWorldChange()`：先 `gameFlow.onPlayerLeaveWorld(oldWorld)`，再 `gameFlow.onPlayerJoin(newWorld)`。
- `GameFlowService.handlePlayerGone()`：离开对局时移除玩家；母体离开会补位；最后人类离开会结算。
- `GameInstance` 按 `worldName` 隔离；`GameFlowService` 的 `games`、`mapFlows`、`countdowns` 均按世界 key。

### 8.3 已知多世界问题

- `BukkitSchedulerPort.regionExecute()` 使用 `Bukkit.getWorld(location.worldName)`，**没有走 `MultiverseWorldResolver.resolve()`**。
- 因此若 arena 配置的世界名是 Multiverse 别名/大小写差异，`BukkitBlockOpsPort` 的 `regionExecute` 在创建 `Location` 前就可能拿到 null 并直接返回，导致门方块开关静默失败。
- `CombatHealthService`、`StaminaService`、`protectedUntil` 等按 UUID 而不是按“世界+UUID”保存，玩家跨世界切换时可能残留旧世界血量/保护状态；`onWorldChange` 没有重新初始化血量。

---

## 9. 安全性与权限校验

### 9.1 命令权限

- 管理命令有 `zombie.run.v2.admin` 检查，但整体是“粗粒度单节点”，没有细分权限。
- `zombie.run.v2.player` 未使用，玩家命令缺少可撤销能力。

### 9.2 数据校验

- Arena/门/按钮/重生点解析有较完整字段校验。
- `/zr2 door add` 解析在纯函数 `Zr2CommandParsing.parseDoorAdd` 中校验坐标、轴、方向、门号重复。
- 玩家数据操作：
  - `spendCoins` 拒绝负数和余额不足；
  - `addCoins` 不限制负数，管理员可用负数扣币；
  - GUI 购买失败会回滚扣款。
- 缺少：
  - 命令参数没有“门区域体积上限”校验，管理员可创建超大区域并同步扫描大量方块造成卡服。
  - `coins add/give` 未做溢出/上下限保护。

### 9.3 全局副作用与越权风险（重要）

以下 listener 注册在服务器全局，但没有 arena 世界过滤，可能影响非游戏世界：

- `V2ProtectionListener.kt`
  - 所有世界非 CREATIVE 玩家禁止破坏/放置/丢弃物品（`onBlockBreak`、`onBlockPlace`、`onDropItem`）。
  - 全局接管聊天（`onChat`），非 arena 世界也会被加 `[等待]` 前缀广播。
  - 全局取消非潜行 F 副手切换（`onSwapHandItems`）。
- `V2BattleListener.kt`
  - 全局取消所有摔落伤害（`onEntityDamage` 中 `FALL` 直接 cancel）。
  - 全局接管所有 `PlayerDeathEvent`：取消死亡、清空掉落、清空死亡消息；若玩家不在任何对局（`victimTeam == null`），会走 `else -> convertToZombie()`，清空背包并设为 `SPECTATOR`。这是非常严重的越权行为。
- `V2CombatListener.kt`
  - 每 5 tick 给**所有在线玩家**做体力更新，非 arena 世界玩家也会疲劳/无法疾跑。

这些都是 v2 当前稳定性和“不干扰服务器其他玩法”的主要风险点。

### 9.4 世界/玩家数据隔离

- 对局数据按世界隔离做得较好。
- 但血量、体力、保护状态按 UUID 全局保存，缺少“世界”维度；跨世界切换可能串状态。
- 多个 arena 配置到同一世界时，`GameFlowService.mapFlowDef()` 使用 `firstNotNullOfOrNull` 取第一个 MapFlow，行为不确定。

---

## 10. 稳定性与并发

### 10.1 Folia 兼容

- `plugin.yml` 声明 `folia-supported: true`。
- `BukkitSchedulerPort` 使用：
  - `Bukkit.getGlobalRegionScheduler()` 做全局定时/延时任务；
  - `Bukkit.getRegionScheduler().execute()` 做方块区域操作。
- 所有定时任务登记到 `TaskRegistry`，插件禁用/游戏结束统一取消。
- 文档/ROADMAP 明确：目前烟测基于 Paper，Folia 尚未完整实测。

### 10.2 已做的并发保护

- `GameInstance` 所有状态修改在 `synchronized(lock)` 内。
- `DoorSessionStateMachine` 所有可变状态用单锁保护，明确考虑 Folia 多 region 线程。
- `PlayerDataService` 用 `ConcurrentHashMap.compute()` 实现原子读改写，避免并发扣款/加款丢更新。
- `ApplicationEventBus` 用 `CopyOnWriteArrayList`。
- SQLite 两个库均 WAL + busy_timeout。

### 10.3 并发/线程安全风险

- **`MapFlowStateMachine` 不是线程安全**：`GameFlowService.mapFlows` 是 `ConcurrentHashMap`，但内部 `phase/currentStage/passedStageIds` 没有锁。门事件、战斗感染、全局定时结算可能在不同线程同时调用。
- **`CombatHealthService.damage()` 非原子**：`health[playerId] ?: 0.0 - amount` 后再 `health[playerId] = clamped`，复合读改写存在并发丢失伤害/血量错乱风险。
- `CombatHealthService.rules`、`StaminaService.rules` 不是 `@Volatile`。
- `GameFlowService` 的多个 `ConcurrentHashMap` 之间存在 check-then-act（如 `phaseSnapshot()` 与后续 `endGame()`），没有跨集合锁。
- `DoorApplicationService.activeSessions` 只允许每世界一个会话，但 `triggerDoor` 的“检查 containsKey → put”不是原子操作，极端并发可能创建两个会话。
- `cancelAllSessions()` 不恢复方块，reload/disable 期间可能遗留开放门。

### 10.4 reload / disable 安全

- `disable()` 顺序：停止体力 tick → 取消门会话 → `gameFlow.stop()` → `taskRegistry.cancelAll()` → 关闭任务/玩家数据。
- `/zr2 reload` 只重载 arena/weapon，不重载 settings/tasks；门会话被取消但方块不恢复。
- `ApplicationEventBus` 没有在 disable 时 `clear()`，但每次启用会新建组合根，影响不大。

---

## 11. 性能

### 11.1 门检测性能

- `PortalCrossingDetector.crossing()` 每扇门 O(1) 数学计算。
- 移动事件只会在有 active session 的世界调用 `DoorApplicationService.onPlayerMove`，无会话时直接返回。
- `PortalCrossingPerformanceTest`：20 玩家 × 100 门 × 20 次移动 = 40,000 次检测，断言 < 2s。
- `DoorSessionStateMachine.close()` 内 `doors.indexOf(door)` 导致 O(玩家 × 门²)，门组门数多时有关门判定放大。

### 11.2 缓存与内存

- `PlayerDataService` 按 UUID 缓存 `PlayerProfile`，join 加载、quit 保存。
- `TaskService` 按 UUID 缓存任务进度。
- `ArenaYamlRepository`、`WeaponYamlRepository` 使用 `ConcurrentHashMap` 缓存。
- `TaskRegistry.register()` 会清理已取消句柄，`GameFlowServiceTest` 有“100 局重复对局后 taskRegistry.size ≤ 5”的泄漏测试。

### 11.3 潜在性能风险

- `BukkitBlockOpsPort.openRegion/closeRegion` 逐方块同步循环，无分块/批量发包优化，大门区域可能造成卡顿。
- 玩家数据每次 `addCoins/addXp` 都会同步写 SQLite（虽然 Hikari 池化），高频奖励/过门场景可能成为瓶颈。
- `BukkitWorldAccessPort.playersIn()` 每次都 `Bukkit.getOnlinePlayers()` 全量过滤；`ArenaYamlRepository.byWorld()` 每次遍历全部 arena。
- `GuiService` 的 `integration` 字段直接 new `QaWeaponIntegrationPort`，未走构造注入，测试与生产行为不一致。

---

## 12. 测试与文档

### 12.1 测试清单（源码 `src/test/kotlin`）

当前源码中统计到 **129 个 `@Test`**：

| 测试类 | 数量 | 覆盖点 |
|---|---|---|
| `application/door/DoorApplicationServiceTest` | 9 | 门触发/开关、过门事件、门组、锁门、fallback、传送、特殊门、reload |
| `application/game/GameFlowServiceTest` | 12 | 自动开局、人数不足取消、感染结束、中途加入、超时、MapFlow、撤离、母体离开、100 局任务泄漏 |
| `application/player/PlayerDataServiceTest` | 2 | 过门/击杀更新、余额不足 |
| `application/task/TaskServiceTest` | 3 | 事件计数、领取一次、过期重置 |
| `application/weapon/WeaponServiceTest` | 6 | 添加/发放、随机过滤、删除、开局补弹、未知武器、补弹委托 |
| `domain/combat/StaminaStateTest` | 6 | 消耗、疲劳、恢复、上限、比例 |
| `domain/door/DoorSessionStateMachineTest` | 10 | 正常穿越、大步长、传送、fallback、门组、Y 轴 |
| `domain/door/PortalCrossingDetectorTest` | 19 | 正反方向、斜向、容差、Y 轴、极端位移 |
| `domain/door/PortalCrossingPerformanceTest` | 1 | 40k 次检测性能 |
| `domain/game/GameInstanceTest` | 12 | 状态机、队伍、感染、母体、观战、房间、胜利 |
| `domain/game/MapFlowStateMachineTest` | 5 | 完整流程、错误门、感染/超时/撤离、reset |
| `domain/player/PlayerProfileTest` | 4 | 硬币/经验/称号/计数 |
| `domain/weapon/WeaponDefinitionTest` | 3 | 随机武器 |
| `infrastructure/bukkit/command/Zr2CommandParsingTest` | 9 | 参数解析、门解析、Y 轴、错误校验 |
| `infrastructure/bukkit/gui/GuiServiceTest` | 5 | 购买、退款、余额不足、关闭清理、外来库存 |
| `infrastructure/bukkit/listener/V2CombatListenerTest` | 4 | 疾跑疲劳、加入重置、退出清理 |
| `infrastructure/bukkit/listener/V2DoorListenerTest` | 5 | 移动翻译、传送标记、普通/撤离按钮、非按钮 |
| `infrastructure/bukkit/listener/V2GameListenerTest` | 3 | 加入、换世界、重生 |
| `infrastructure/bukkit/listener/V2PlayerDataListenerTest` | 2 | 加载/保存 |
| `infrastructure/bukkit/listener/V2TaskListenerTest` | 2 | 加载/保存 |
| `infrastructure/config/ArenaYamlRepositoryTest` | 3 | MapFlow round-trip、Y 轴门、行为/撤离按钮 |
| `infrastructure/config/V1MigrationServiceTest` | 4 | 数据导入、防覆盖、快照关联、reverse-direction |

未覆盖/缺少测试的关键基础设施：
- `V2BattleListener`（全局死亡/伤害接管逻辑）
- `V2ProtectionListener`（全局方块/聊天保护）
- `V2PlayerStateListener`（状态接管）
- `V2HazardListener`（黑羊毛）
- 多线程/并发正确性测试（MapFlow、CombatHealthService 并发损伤）

### 12.2 docs/v2 文档

- `ARCHITECTURE.md`：分层架构、门检测设计、配置 v2、迁移顺序。
- `COMMANDS.md`：命令参考（部分命令与代码不符）。
- `GAME_FLOW.md`：MapFlow 完整流程、玩家生命周期、母体机制。
- `ROADMAP.md`：M0–M19 进度（部分勾选状态与代码不一致）。
- `UPGRADE.md`：v1 → v2 升级指南（快照迁移描述过时）。
- `RELEASE.md`：`v2.0.0-beta.1` 发布说明与已知限制。
- `examples/mapflow-demo.yml`：MapFlow 示例。

### 12.3 CI

- `.github/workflows/build.yml`：见第 1.3 节。

---

## 13. 已知缺口 / 风险

### 13.1 代码级缺陷 / 风险

1. **全局 listener 越权**（最严重）
   - `V2BattleListener` 全局取消摔落伤害、全局接管所有玩家死亡；非 arena 玩家死亡会被清背包并切观战。
   - `V2ProtectionListener` 全局禁止非 CREATIVE 破坏/放置/丢弃、全局接管聊天、全局取消非潜行 F。
   - `V2CombatListener` 对所有在线玩家生效体力系统。
2. **MapFlowStateMachine 非线程安全**
   - 与 Folia 多 region 线程模型存在竞态。
3. **CombatHealthService 读改写非原子**
   - 并发伤害可能丢更新。
4. **母体死亡复活不生效**
   - `scheduleZombieRespawn()` 只处理 `ZOMBIE`，不处理 `ZOMBIE_MAIN`。
5. **Multiverse 别名下门方块操作可能失败**
   - `BukkitSchedulerPort.regionExecute()` 未走 `MultiverseWorldResolver`。
6. **门会话取消不恢复方块**
   - `cancelAllSessions()` 只取消任务，不执行关门恢复。
7. **reload 范围不完整**
   - settings/tasks/combat/stamina 不重载；`V2SettingsLoader` 与 `loadCombatRules()` 二次读取同一文件，存在重复解析。
8. **服务注册表“只注册不消费”**
   - `V2ServiceRegistry.get` 无调用方；文档中的生命周期接口未落地。
9. **`onCombat` / `onHumanDied` 死代码**
   - 新战斗链路走 `onCombatInfection` / `onHumanDiedByEnvironment`，旧入口未删除。
10. **`PlayerDataService.addCoins` 允许负数、无溢出保护**；管理命令参数校验偏弱。
11. **`V2GameListener` 等待期商店逻辑与团队模型不符**
    - WAITING 玩家是 `SPECTATOR`，潜行+F 的 `team == HUMAN` 条件不会成立。

### 13.2 文档/路线图缺口

- `ROADMAP.md` 中 M3 的“自定义生命值/伤害规则”仍标记未完成，但代码已有 `CombatHealthService` / `V2BattleListener`；M4 的“武器购买结算”仍标记未完成，但 GUI 商店已实现扣款。
- `UPGRADE.md` 与 M17 代码矛盾：升级指南仍称快照不自动迁移。
- `COMMANDS.md` 列出的若干命令未实现（`arena setspawn`、`door remove`、`button add escape`、`respawn list`）。
- `README.md` 的快速开始仍以 v1 `/zr` 命令为主，不是 v2 `/zr2` 命令。
- Folia 实测、真实玩家客户端穿门/落后传送实测、多世界同开多局联机实测仍未完成。

### 13.3 功能范围限制

- 任务类型仅 `DOOR_PASSES` / `ZOMBIE_KILLS`。
- 称号目录硬编码，无 `titles.yml`。
- `settings.yml` 的 `schema` 未校验。
- `MapFlowDefinition` 未校验 stage 链是否成环/可达。
- `V1MigrationService.migrateData` 使用裸 JDBC 读取 v1 库，没有 busy_timeout，若 v1 插件同时运行可能遇到锁。
- 特殊门行为 `delayTicks` 字段已定义，但 `DoorApplicationService.startSpecialBehavior()` 未真正使用 `delayTicks`。

---

## 总结

v2 分支已经完成了从骨架到完整对局闭环的大部分工作：分层清晰、门检测算法可靠、MapFlow 流程可跑、SQLite/WAL/原子玩家数据更新等基础设施较扎实，测试覆盖 129 个用例，CI 也已建立。

但当前代码仍带有明显的“v1 游戏层移植痕迹”：多个全局 listener 没有 arena 世界隔离，会对非游戏世界产生破坏性副作用；部分领域状态机未做到真正的 Folia 线程安全；reload/disable 边界、母体复活、Multiverse 区域调度、文档与代码一致性等仍存在需要补齐的缺口。以上内容可作为 v2 继续加固和发布前评审的直接依据。
