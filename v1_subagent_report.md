# Zombie Run v1（master 分支）特性分析报告

以下分析全部基于 Git `master` 分支（`remotes/origin/master`，HEAD `959a4bf`），未使用当前工作区 v2 检出内容。

---

## 1. 总体定位与构建

### 1.1 项目定位

- 插件名：`zombie-run`
- 类型：非对称 PvP 小游戏
- 玩法：人类按门号顺序逃出/直升机撤离；僵尸（含 1 名母体）感染全部人类
- 语言/平台：Kotlin，Paper / Folia
- 主类：`cn.oneachina.zombieRun.ZombieRun`

### 1.2 `src/main/resources/plugin.yml`

- `name: zombie-run`，`version: '${version}'`（Gradle 构建时替换）
- `api-version: '1.21'`，`folia-supported: true`
- 硬依赖：`QualityArmory`
- 软依赖：`Multiverse-Core`
- 声明的命令：
  - `/zr`
  - `/start`（权限 `zombie.run.start`）
  - `/open`、`/close`、`/door`（权限 `zombie.run.admin`）
- 权限节点：
  - `zombie.run.admin`：默认 `op`
  - `zombie.run.start`：默认 `op`

### 1.3 构建配置（`build.gradle.kts`、`gradle.properties`、`settings.gradle.kts`）

- Kotlin JVM：`2.3.20-Beta2`
- Shadow：`com.gradleup.shadow 8.3.0`
- 本地测试：`xyz.jpenilla.run-paper 2.3.1`
- API 依赖：
  - `io.papermc.paper:paper-api:26.1.2.build.+`（compileOnly）
  - `me.clip:placeholderapi:2.12.2`（compileOnly）
  - `me.zombie_striker:QualityArmory:2.1.3`（compileOnly）
  - `com.onarandombox.multiversecore:multiverse-core:4.3.14`（compileOnly）
  - `org.jetbrains.kotlin:kotlin-stdlib-jdk8`
  - `com.zaxxer:HikariCP:7.0.2`
- Shadow 行为：
  - 完整 jar 直接产出 `zombie-run-<version>.jar`（不带 `-all`）
  - 瘦身 jar 改名为 `-thin`，避免误部署
  - 只打入 Kotlin 与 HikariCP 依赖
- 版本号：通过 PowerShell/git 自动生成 `YYYY.M.commitCount-shortHash`
- Java Toolchain：`25`
- `runServer`：Minecraft `26.1.2`，并从 Modrinth 下载 QualityArmory

### 1.4 文档状态

- 有 `README.md`、`CONTRIBUTING.md`、`LICENSE`、`TACZ_LICENSE`
- README 中“产物”仍写 `zombie-run-<version>-all.jar`，与当前 shadow 配置（无 `-all`）不一致
- README 依赖列表只写 Paper/Folia + 可选 PAPI，未明确写出硬依赖 QualityArmory

---

## 2. 功能模块清单

源码目录：`src/main/kotlin/cn/oneachina/zombieRun/`

### 2.1 核心入口

- `ZombieRun.kt`
  - lazy 初始化所有 Manager/Service/GUI/Listener
  - `onEnable` 注册 PAPI、DoorManager 延迟 reset、事件监听器、`/zr` 命令
  - `onDisable` 统一清理玩家血量和药水，关闭 DB，还原按钮/门/重生点，清理任务
  - 维护 `postoolUsers` 选区棒状态

### 2.2 Manager

| 文件 | 职责 |
|---|---|
| `ConfigManager.kt` | 加载/保存/迁移 `config.yml`，加载门/按钮/重生点，加载 combat/economy/balance 配置 |
| `DatabaseManager.kt` | 统一 SQLite 连接池（HikariCP + WAL + busy_timeout），异步写封装 |
| `DoorManager.kt` | 门会话状态机、按钮触发、开门/关门、落后传送、直升机撤离、门 CRUD |
| `DoorZoneManager.kt` | 32×32 区块空间索引，门按世界+区域分区 |
| `ButtonManager.kt` | 按钮加载/查询/点亮/还原 |
| `RespawnManager.kt` | 重生点索引、按队伍/门号/房间/进度选择传送点 |
| `GameManager.kt` | 多世界游戏状态机、自动开局、倒计时、开局/结算、队伍/房间管理 |
| `HealthManager.kt` | 自定义生命值、伤害、治疗、最后伤害来源记录 |
| `StaminaManager.kt` | 体力恢复/消耗、疲劳、僵尸药水效果、母体粒子任务 |
| `MiscManager.kt` | 枪械选择/发放、击杀/感染统计、爆炸伤害减免、回大厅 |
| `WeaponManager.kt` | QualityArmory 枪械适配层，取枪/发枪/弹药 |
| `CoinManager.kt` | 金币缓存 + SQLite 异步持久化 + 排行榜 |
| `ProgressionManager.kt` | 等级/XP/总击杀/总感染/局数/人类胜场/解锁/称号持久化 |
| `QuestManager.kt` | 每日/每周任务生成、进度、奖励 |
| `TitleManager.kt` | 称号解锁判断、默认称号、装备称号、缓存 |
| `NametagManager.kt` | 头顶队伍标签 + 血量条刷新 |
| `StartEffectManager.kt` | 开局执行配置命令效果 |

### 2.3 Listener / Task / Service / PAPI

- `listener/GameListener.kt`：加入/退出/跨世界、postool 选区、按钮点击、方块/物品限制、聊天处理、过门记录、黑羊毛伤害
- `listener/CombatListener.kt`：QA 枪械拦截、自定义伤害接管、近战/僵尸攻击、死亡/感染/复活、伤害数字
- `listener/ProgressionListener.kt`：击杀/感染/过门/对局/胜利的 XP 与任务桥接
- `listener/StaminaListener.kt`：移动、疾跑、跳跃体力限制
- `listener/PlayerTaskTracker.kt`：按玩家跟踪并取消感染/复活调度任务
- `task/StartCountdownTask.kt`：开始倒计时，选母体，传送出生点
- `task/WaitStartCountdownTask.kt`：等待大厅自动开局倒计时
- `service/WorldService.kt`：Multiverse-Core 软依赖世界解析
- `papi/ZombieRunExpansion.kt`：PlaceholderAPI 占位符
- `util/DebugLogger.kt`：debug 消息发送给在线玩家

### 2.4 GUI

- `gui/ProfileGUI.kt`：玩家档案、解锁展示
- `gui/QuestGUI.kt`：每日/每周任务面板
- `gui/ShopGUI.kt`：QA 枪械商店，点击预购
- `gui/TitleGUI.kt`：称号选择

### 2.5 Command

- `command/ZombieRunCommand.kt`：主命令 `/zr` 全部子命令 + Tab 补全
- `command/CoinCommands.kt`：`/zr coins`
- `command/DoorBehaviorCommands.kt`：`/zr door behavior set/remove/info`
- `command/TabCompleters.kt`：spawn/doors/buttons/doorBehavior 补全

### 2.6 Model

- `model/Door.kt`：门区域、模式、扫描方块、穿越判定
- `model/Button.kt`：普通/撤离按钮，单门/多门
- `model/Respawn.kt`：重生点类型与坐标
- `model/PlayerProfile.kt`：玩家进度档案
- `model/Quest.kt`：任务定义与进度
- `model/SpecialDoorBehavior.kt`：电梯/地铁/机场传送行为

---

## 3. 游戏机制

### 3.1 门流程

- 玩家右键按钮触发 `DoorManager.triggerDoor`
- 同组门共享门号，同世界同一时间只允许一个 `Session`（`activeSessions` 按 world 隔离）
- 全局调度器倒计时：
  - `OPENING`：显示开门倒计时，到 0 后 `openDoorBlocks`
  - `CLOSING`：显示关门倒计时，到 0 后 `closeAllDoors`
- 过门判定：
  - `PlayerMoveEvent` 调用 `DoorManager.tryRecordPlayerCrossing`
  - `Door.crossedBy()` 做平面穿越检测，`session.crossedPlayers` 记录
- 关门时：
  - 通过者更新 room、触发过门 XP/任务
  - 未通过者进入 10 秒传送倒计时（人类/僵尸分别处理）
  - 有特殊行为时执行电梯/地铁/机场传送
- 门模式：`NORMAL`、`PLAYER`、`ZOMBIE`、`START`
  - PLAYER 门开局立即开
  - ZOMBIE 门开局 100 ticks 后开
  - START 门按钮可立即开

### 3.2 按钮

- `normal`：触发单个/多个门号
- `escape`：启动直升机撤离倒计时 30 秒，结束后人类胜利
- 按钮方块限定 `REDSTONE_LAMP` / `LEVER`，点亮后替换为 `SEA_LANTERN`，重置时还原

### 3.3 传送 / 重生点

- 类型：`WAIT`、`PLAYER`、`ZOMBIE`、`ZOMBIE_MAIN`、`DOOR_PLAYER`、`DOOR_ZOMBIE`
- 随机选择同类型同世界重生点
- 僵尸复活按人类推进进度选择点位：
  - 被感染：`teleportZombieByProgress(..., ahead=false)` 就近
  - 僵尸死亡：`teleportZombieByProgress(..., ahead=true)` 布防
- 特殊门行为：
  - `Elevator`：按队伍传送不同 Y
  - `Subway`：按队伍传送到不同 XYZ，即时传送
  - `Airport`：延迟 `delay-ticks` 后按队伍传送

### 3.4 开始 / 结算

- 等待状态在线人数 ≥ `min-players` 后启动 `WaitStartCountdownTask`
- 倒计时结束 `forceStartGame` → `StartCountdownTask` 选母体 → `beginGame`
- `beginGame`：
  - 随机选母体
  - 人类获得冒险模式 + 新手枪械/匕首
  - 打开 PLAYER / ZOMBIE / START 门
  - 启动最大时长定时器
- `endGame`：
  - 设置 `ENDED`，展示标题、音效、清背包/药水
  - 输出击杀/感染排行榜并发放前 3 金币
  - 80 ticks 后传送等待大厅、清名牌、重置为 `WAITING`

### 3.5 僵尸 / 母体

- 母体开局前选为 `ZOMBIE_MAIN`
- 120 ticks 后母体获得行动能力 + 力量/速度/跳跃药水
- 血量：人类 20，普通僵尸 120，母体 300（`combat.yml`）
- 僵尸/母体伤害：普通 5，母体 8
- 僵尸不能拾取物品；人类死亡/被感染后变为僵尸

### 3.6 体力

- 代码硬编码：最大 100，疾跑每 2 tick 消耗 1.0，站立/静止恢复不同
- 耗尽后 `isExhausted=true`，禁止疾跑/跳跃，施加缓慢、虚弱、发光
- 跳跃按当前生命值动态扣体力
- 人类 ActionBar 显示体力条

### 3.7 武器

- 完全依赖 QualityArmory 的枪械、弹药、价格配置
- `WeaponManager` 只负责查询、发放、补弹药
- `MiscManager.giveRandomGun`：
  - 有预选武器且金币足够则自动购买
  - 否则随机枪械
  - 同时发一把铁剑匕首
- 商店为“预购制”：`/zr shop` 选择武器，开局自动发放
- 射击限制：仅人类在 `RUNNING` 状态可开枪
- QA 伤害被接管到 `HealthManager` 自定义生命值

### 3.8 经济

- 金币存 `zr_economy` 表
- 来源：击杀僵尸/母体、感染人类、人类存活到撤离、排行榜奖励、任务奖励
- `/zr transfer` 支持玩家间转账
- `/zr coins top` 异步查询排行榜

### 3.9 等级 / 称号

- XP 公式：`xpForLevel(level) = level*500 + level*level*50`
- 最高 50 级
- 等级解锁称号/皮肤：
  - Lv.5 幸存者、Lv.10 逃生专家、Lv.15 金手枪、Lv.20 精英特工、Lv.25 希望之光、Lv.30 传奇、Lv.35 屠夫、Lv.40 不死之身、Lv.50 金步枪
- 默认称号按等级自动显示

### 3.10 每日 / 每周任务

- 每日固定 2 个 + 随机池 2 个
- 每周 3 个固定任务
- 使用 `Asia/Shanghai` 时区，按日期字符串存储
- 完成奖励 XP/金币
- 每 60 秒检查日期刷新并清空缓存

### 3.11 商店 / 名牌 / PAPI

- 商店：`ShopGUI`，进入等待大厅自动打开
- 名牌：`NametagManager` 每 10 ticks 刷新 `displayName`，显示队伍、名称、10 格血量
- PAPI 标识符 `zombierun`：
  - 世界级：`human_count`、`zombie_count`、`alpha_zombie_*`、`game_state`、`time_left`、`progress`、`bossbar`、`min_players`、`max_players`、`online_players`
  - 玩家级：`level`、`xp`、`title`、`total_kills`、`coins`、`kills`、`infections`、`stamina_*`、`team`、`room`、`selected_weapon` 等

---

## 4. 配置系统

### 4.1 目录与文件

`src/main/resources/config/` 下有：

- `config.yml`
- `combat.yml`
- `economy.yml`
- `balance.yml`
- `licenses/TACZ_LICENSE`

运行时数据目录：

- `plugins/zombie-run/config/config.yml`
- `plugins/zombie-run/config/combat.yml`
- `plugins/zombie-run/config/economy.yml`
- `plugins/zombie-run/config/balance.yml`
- `plugins/zombie-run/config/doors/<门名>.scandata.yml`
- `plugins/zombie-run/data/zr_economy.db`

### 4.2 `config.yml` 主要项

| 节 | 主要键 |
|---|---|
| `game` | `mode`、`world`、`start-delay`、`min-players`、`max-players`、`max-duration` |
| `spawn` | `x/y/z/yaw/pitch` 兜底出生点 |
| `end` | `x/y/z`（注释标为预留） |
| `doors` | `x1/y1/z1/x2/y2/z2`、`open-time`、`close-time`、`door-number`、`group`、`mode`、`use-scan-data`、`blocks`、`special-behavior`、`world` |
| `buttons` | `x/y/z`、`mode`、`door-number`/`door-numbers`、`world` |
| `respawns` | `x/y/z`、`yaw/pitch`、`type`、`door-number`、`room-number`、`world` |
| `zombie` | 生命/速度/伤害倍率（注释明确“暂未启用”） |
| `human` | 速度/跳跃/体力恢复（“暂未启用”） |
| `stamina` | `max/sprint-cost/standing-regen`（“暂未启用”） |
| `misc` | `explosion-damage-reduction` |
| `start-effects` | 代码支持，但默认模板未给出示例条目 |

### 4.3 `combat.yml`

- `sword-damage`
- `zombie-damage`
- `zombie-main-damage`
- `zombie-max-health`
- `zombie-main-max-health`
- `human-max-health`

### 4.4 `economy.yml`

- 金币：`kill-zombie-coins`、`kill-zombie-main-coins`、`infect-human-coins`、`survive-human-coins`、`rank-reward-coins`
- XP：`headshot-xp`、`kill-zombie-xp`、`kill-zombie-main-xp`、`infect-human-xp`、`pass-door-xp`、`human-win-xp`、`participate-xp`

### 4.5 `balance.yml`

- `door-open-cooldown-ms`
- `transfer-countdown-sec`
- `helicopter-countdown-sec`
- `infect-countdown-sec`
- `respawn-delay-ticks`
- `ads-speed-multiplier`
- `default-move-speed`

### 4.6 迁移机制

- `ConfigManager.migrateConfigIfNeeded`
  - 检测旧 `weapons` 节
  - 用户数据节 `doors/buttons/respawns` 整体复制，数值节按新模板 key 过滤
  - 自动补 `world` 字段
  - 备份 `config.yml.bak`
- 门扫描数据支持从内联 `blocks` 迁移到独立 `.scandata.yml`
- `migrateSubConfigIfNeeded` 处理 `combat.yml` / `economy.yml`

---

## 5. 命令与权限

### 5.1 `plugin.yml` 声明命令

| 命令 | 权限 | 默认 | 实际执行器 |
|---|---|---|---|
| `/zr` | 子命令内自行校验 | - | `ZombieRunCommand` |
| `/start` | `zombie.run.start` | op | 未注册执行器，实际不可用 |
| `/open` | `zombie.run.admin` | op | 未注册执行器 |
| `/close` | `zombie.run.admin` | op | 未注册执行器 |
| `/door` | `zombie.run.admin` | op | 未注册执行器 |

`ZombieRun.onEnable` 只注册了 `getCommand("zr")`，因此 `plugin.yml` 中额外声明的 `/start`、`/open`、`/close`、`/door` 是死命令。

### 5.2 `/zr` 子命令

管理员子命令（`zombie.run.admin`）：

- `/zr start [-w <world>]`
- `/zr spawn wait|player|zombie|alpha|door-player|door-zombie|remove|list`
- `/zr doors add|edit|remove|list|reset|info`
- `/zr buttons add|remove|list`
- `/zr reload`
- `/zr open [-w <world>]`
- `/zr close [-w <world>]`
- `/zr reset <玩家|UUID>`
- `/zr debug`
- `/zr game list`
- `/zr xp add|set <玩家> <数量>`
- `/zr level set <玩家> <等级>`
- `/zr coins add|remove|set|get`
- `/zr postool`
- `/zr door <门号>`
- `/zr door behavior set|remove|info`

玩家子命令：

- `/zr shop`
- `/zr select <编号>`
- `/zr unselect`
- `/zr randomgun`
- `/zr lobby`
- `/zr profile [玩家]`
- `/zr quest`
- `/zr title [称号]`
- `/zr transfer <玩家> <金额>`
- `/zr coins top [数量]`

### 5.3 权限问题/缺口

- `/zr randomgun` 没有状态/权限校验，非游戏状态也能发枪
- `/zr migrate` 在帮助中出现，但 `onCommand` 顶层路由未包含 `migrate`，分支不可达
- 玩家子命令普遍无独立权限节点，任何能执行 `/zr` 的玩家都可用
- `plugin.yml` 中声明的 `/start` 等命令无执行器

---

## 6. 数据持久化

### 6.1 SQLite

- 数据库文件：`plugins/zombie-run/data/zr_economy.db`
- `DatabaseManager`：
  - 单一 HikariCP 连接池，`maximumPoolSize=2`
  - 初始化 SQL：`PRAGMA journal_mode = WAL; PRAGMA busy_timeout = 5000`
  - `runAsync` 通过 `CompletableFuture.runAsync` 执行写操作
- 表：
  - `zr_economy(uuid, username, coins)`
  - `player_progression(uuid, level, xp, total_kills, total_infections, games_played, human_wins, equipped_title)`
  - `player_unlocks(uuid, unlock_id, unlocked_at)`
  - `player_quests(uuid, quest_id, progress, completed, date)`
- 加载策略：
  - 玩家进服异步加载并写入缓存
  - 退出时 `savePlayer` 异步写库并移除缓存
  - 禁用时 `flushAll` 写库

### 6.2 文件持久化

- 门/按钮/重生点存 `config.yml`
- 门扫描方块数据独立存 `config/doors/<name>.scandata.yml`
- 配置迁移会生成 `.bak` 备份

### 6.3 风险

- `CoinManager` 的 `addCoins/takeCoins` 是“读缓存→改缓存→异步写库”，非原子操作，并发事件下可能丢更新
- `ProgressionManager` 多处 `CompletableFuture...get()` 会阻塞调用线程
- `savePlayer` 退出时移除缓存，可能与仍在途的异步 `updateAsync` 产生写顺序不确定性

---

## 7. 多世界支持

### 7.1 WorldService

- `service/WorldService.kt`
- 软依赖 `Multiverse-Core`：先通过 `MVWorldManager.getMVWorld(name).cbWorld` 解析
- 未安装或找不到时回退 `Bukkit.getWorld(name)`
- `getWorldOrFirst` 最终回退服务器第一个已加载世界

### 7.2 数据模型世界隔离

- `Door`、`Button`、`Respawn` 均有 `world` 字段
- `GameManager` 按世界创建 `GameInstance`
- `DoorManager` 的 `activeSessions`、`doorTasks`、`transferTasks`、`endtimes` 均以 world 为 key
- `RespawnManager` 按世界建立 `WAIT/PLAYER/ZOMBIE/ZOMBIE_MAIN/DOOR_*` 索引
- `ButtonManager.originalButtonBlocks` 按世界重置
- `HealthManager.clearWorld` 只清理指定世界在线玩家
- PAPI 默认取玩家所在世界，控制台/离线玩家取配置默认世界

### 7.3 跨世界迁移

- `GameListener.onPlayerChangedWorld`
  - 先 `removePlayerFromWorld(event.from)` 清理旧世界实例
  - 再按新世界状态加入
- 避免旧世界对局因“幽灵玩家”无法结算

### 7.4 风险

- `getWorldOrFirst` 在配置世界未加载时会静默使用第一个世界，可能导致玩家被传到错误世界
- 聊天仍使用 `Bukkit.broadcast` 全局广播，跨世界消息不隔离

---

## 8. 安全性与权限校验

### 8.1 已实现的安全措施

- 管理命令集中校验 `zombie.run.admin`
- 玩家命令限制 `CommandSender` 必须是 `Player`
- 数值输入大多使用 `toIntOrNull` 校验（坐标、门号、金额、XP、等级）
- 非创造模式禁止：
  - 破坏/放置方块
  - 丢弃物品
  - 点击背包
  - 交换主副手
- postool 玩家例外（可破坏/可 F 键）
- 游戏内伤害仅 `RUNNING` 状态生效
- QA 射击仅人类且 `RUNNING` 状态
- 同队攻击取消
- 僵尸禁止拾取物品
- 聊天取消原事件并移除 `&`，避免传统颜色/格式注入
- 玩家退出时清理任务、血量、体力、存档

### 8.2 安全缺口/风险

- `/zr randomgun` 未限制游戏状态，可能被用于非对局场景刷枪
- `/zr shop` 在等待大厅自动打开，但未校验地图/世界是否配置
- 命令权限完全依赖 `/zr` 子命令内检查，`plugin.yml` 权限模型不完整
- `/zr coins get` 对无权限玩家直接 `return`，无提示（小问题）
- `handleDoorsEdit door-number` 对非法值静默返回，无错误消息
- 配置 `start-effects` 支持执行任意控制台命令，但只有管理员可改配置，属预期风险

---

## 9. 稳定性与并发

### 9.1 Folia 兼容

- 定时任务统一使用 `Bukkit.getGlobalRegionScheduler()`
- 方块读写通过 `Bukkit.getRegionScheduler().execute(plugin, location)`
- 实体操作通过 `player.scheduler.run(...)`
- 传送使用 `teleportAsync`
- `plugin.yml` 声明 `folia-supported: true`

### 9.2 线程安全设计

- 并发容器：
  - `ConcurrentHashMap`、`CopyOnWriteArrayList`、`ConcurrentHashMap.newKeySet`
- 跨线程共享状态使用 `@Volatile`：
  - `GameManager.GameInstance.status/gameStartTime/alphaZombie/manuallyForced`
  - `Door.isOpen/isActive`
  - `DoorManager.Session.phase`
  - `StartCountdownTask.countdown`
- 玩家任务跟踪：
  - `PlayerTaskTracker` 使用 `synchronized(tasks)` 防止不同 region 线程同时改列表
- 门会话：
  - `crossedPlayers` 为并发 Set
  - `phase` volatile 供区域线程与全局调度线程共享
- 按世界隔离避免跨世界状态泄漏

### 9.3 状态机与竞态处理

- `GameStatus`：`WAITING → STARTING → RUNNING → ENDED → WAITING`
- `beginGame` 使用 `gameStartTime` 标记，延迟任务检查 `gameStartTime != startTime` 防止旧任务影响新对局
- `endGame` 延迟 80 ticks 收尾，同样用 `gameStartTime` 做防覆盖检查
- `forceStartGame` 拒绝 0 玩家开局，避免 `selectAlphaZombie` 异常
- `DoorManager.reset(world)` 取消该世界门任务/传送任务，避免跨世界误取消

### 9.4 SQLite 锁处理

- 共享单一连接池，避免多连接池写同一文件
- WAL + `busy_timeout=5000`
- 异步写不阻塞主线程
- 但仍存在：
  - `.get()` 同步等待（`getUnlocks`、`loadQuestProgress`、`resetPlayer`）
  - `flushAll` 在禁用时同步使用连接池

### 9.5 reload / disable 安全

- `handleReload` 重新加载：
  - `config.yml`
  - `doorManager.loadDoors()`
  - `respawnManager.loadRespawns()`
  - `buttonManager.loadButtons()`
  - `startEffectManager.loadEffects()`
- `onDisable` 清理顺序：
  - 恢复在线玩家血量/药水
  - `coinManager.close()`、`databaseManager.close()`
  - `buttonManager.clear()`（同步还原方块）
  - `doorManager.reset()`、`respawnManager.clear()`、`gameManager.clear()`、`staminaManager.clear()`、`progressionManager.close()`、`nametagManager.clearAll()`、`healthManager.clearAll()`

### 9.6 稳定性风险

- `reload` 不取消活跃门会话/任务，加载新门列表时旧 `activeSessions` 可能残留
- `reload` 不重新加载 `combat.yml/economy.yml/balance.yml`，修改这些文件必须重启
- `onDisable` 中 `doorManager.reset()` 仍通过 region scheduler 调度方块还原，但代码注释已说明禁用后 region 任务可能不执行，门方块存在不还原风险
- `StartEffectManager` 的延迟任务没有跟踪/取消，reload/disable 后可能仍执行
- `CoinManager` 读改写非原子，多线程并发加币可能丢失
- `DoorManager.getDoorByNumber` / `ButtonManager.getButton` 使用线性扫描，门/按钮数量大时有性能压力
- `GameManager` 的 `games` 会自动剪枝幽灵 WAITING 实例，但 `getGame(world)` 仍会为任意世界创建实例，非游戏世界可能短暂进入 `games`

---

## 10. 性能

### 10.1 已优化点

- 过门检测从“每 tick 全玩家轮询”改为 `PlayerMoveEvent` 平面穿越记录（提交历史：`190de85 fix(door): 过门检测改为平面穿越状态机，移除每tick全玩家轮询`）
- `DoorZoneManager` 提供 32×32 空间分区索引
- 金币/进度使用内存缓存，DB 读写异步
- 排行榜异步查询并切回全局调度线程渲染
- 体力/名牌/僵尸效果使用低频定时任务（2/10/20 ticks）
- `Door` 方块读写调度到 region 线程，避免 Folia 线程错误
- 多世界按世界隔离任务，避免世界间互相拖累

### 10.2 仍存在的性能问题

- `DoorZoneManager` 虽然初始化/维护，但 `getDoorsInZone/getDoorsInArea` 没有被实际使用，属于“死索引”
- `getDoorByNumber`、`getButton`、`getDoorsInWorld` 都是线性遍历
- 体力 ActionBar 任务每 2 ticks 遍历所有在线玩家，服务器人数大时 O(n)
- `ProgressionManager.getUnlocks`、`QuestManager.loadQuestProgress` 使用 `CompletableFuture.get()`，可能阻塞主线程/PAPI 线程
- `NametagManager` 每 10 ticks 全服刷新 `displayName`
- `DebugLogger` 会在 debug 模式向所有在线玩家广播调试消息，开启时影响性能

---

## 11. 测试与构建

### 11.1 测试

- master 源码树中没有 `src/test` 或任何测试文件
- 提交历史中曾出现 `DoorPerformanceTest` 相关提交（`b15a0aa refactor: simplify standard deviation calculation in DoorPerformanceTest`），但当前 master 已不存在该测试
- 没有单元测试、集成测试、性能回归测试

### 11.2 CI

- 仓库中未见 `.github/workflows` 或其他 CI 配置
- 无自动构建、无自动测试、无 PR 检查

### 11.3 构建与本地开发

- `./gradlew build` / `./gradlew shadowJar`
- `./gradlew runServer` 可启动本地 Paper 测试服并自动下载 QA
- `CONTRIBUTING.md` 要求提交前至少 `build` 成功、本地 `runServer` 冒烟
- Gradle Wrapper：`gradle-9.4.0-bin.zip`

### 11.4 文档

- `README.md`：快速开始、门/按钮/重生点创建流程、权限表、构建
- GitHub Wiki 链接：Configuration / Commands / Door-System / Weapons / Progression / Stamina
- 配置注释非常详细，但部分注释与代码不一致（如“预留未启用”配置项、README 产物名）

---

## 12. 已知缺口 / 风险

### 12.1 命令与权限

1. `/zr migrate` 不可达
   - `onCommand` 顶层 `when` 没有 `migrate`
   - `handleAdminCommand` 内有 `migrate` 分支，但永远不会被调用
   - 帮助文本却显示该命令
2. `plugin.yml` 的 `/start`、`/open`、`/close`、`/door` 未注册执行器，属于死命令
3. `/zr randomgun` 无游戏状态/世界/权限限制，可能被滥用发枪
4. 玩家子命令无独立权限节点，权限模型较粗糙

### 12.2 配置

5. `combat.yml / economy.yml / balance.yml` 只在插件启动时 lazy 加载，`/zr reload` 不重载
6. `balance.yml` 中多个字段未接入逻辑：
   - `door-open-cooldown-ms`
   - `transfer-countdown-sec`
   - `helicopter-countdown-sec`
   - `ads-speed-multiplier`
   - `default-move-speed`
   - 实际传送倒计时硬编码 10 秒，直升机硬编码 30 秒
7. `config.yml` 中 `zombie/human/stamina` 倍率注释为“未启用”，代码确实未使用
8. `reload` 不会取消活跃门会话/门任务，可能造成旧任务操作新门对象

### 12.3 数据与并发

9. `CoinManager` 加减金币不是原子操作，多事件并发可能丢更新
10. `ProgressionManager` / `QuestManager` 存在 `.get()` 阻塞点，可能卡主线程或 PAPI 线程
11. 退出存档与异步写库之间缺少顺序保证，极端情况下可能写入旧数据
12. `onDisable` 中门方块还原依赖 region scheduler，插件禁用后可能不执行，门可能残留打开状态
13. `StartEffectManager` 延迟命令任务未跟踪，禁用后可能继续执行

### 12.4 游戏逻辑

14. `onGameEnd` 会给世界内所有在线玩家加“参与对局”XP/任务进度，包含旁观者/中途加入者，可能被刷
15. `DoorManager` 同一世界同一时间只允许一个门会话，属于设计限制；若地图需要并行门流程会阻塞
16. 聊天全局广播，多世界下消息不隔离
17. `handleDoorsEdit door-number` 未校验正整数，输入非法值静默返回

### 12.5 构建与文档

18. README 产物名仍写 `-all.jar`，与实际 shadow 输出不一致
19. README 未列出硬依赖 QualityArmory
20. 使用 Kotlin `2.3.20-Beta2`、Paper API `26.1.2.build.+` 和 Java 25，存在 API/工具链漂移风险
21. 无 CI/测试，历史测试已从 master 移除

---

## 总结

master 分支（v1）已经是一套功能较完整、明确面向 Paper/Folia 的异步 PvP 小游戏插件：

- 多世界支持、门状态机、QA 枪械、经济/等级/任务/称号/PAPI 等模块齐全；
- 最近的提交（尤其 `959a4bf`）集中修复了跨世界状态泄漏、状态机竞态、Folia 线程安全、SQLite 锁等问题；
- 但稳定版仍存在 `reload` 不完整、命令死节点、配置未生效、金币并发更新非原子、`CompletableFuture.get()` 阻塞、部分任务未跟踪等可观察风险。

如果 v2 分支需要从 v1 迁移或对照验收，建议优先关注第 12 节列出的缺口。
