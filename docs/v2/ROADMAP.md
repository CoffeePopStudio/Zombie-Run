# Zombie Run v2 任务拆解与路线图（Draft v0.1）

> 状态：**已批准，M0 + M1 垂直切片已落地**（2026-08-22）。
> 架构说明见 [ARCHITECTURE.md](./ARCHITECTURE.md)。

---

## 执行约定

- `main` 分支冻结为 v1；批准后创建 `v2` 分支。
- 每个里程碑（M）完成后必须满足：
  1. `./gradlew.bat build` 成功
  2. 新增/修改代码有对应单元测试（domain/application 必须，infrastructure 按需）
  3. 更新本路线图勾选状态
  4. 产物可在 Paper/Folia 测试服加载
- 任何未在设计文档中定义的跨模块改动，先补 ADR 再实现。

---

## M0：v2 骨架（批准后第一步）

**目标**：空插件可加载，分层与生命周期就位。

- [x] 创建 `v2` 分支（从当前 `main`）
- [x] 新建 v2 包 `cn.oneachina.zombierun.v2`
- [x] `ZombieRunV2Plugin`：薄启动器，只做注册与生命周期转发
- [x] `V2ServiceRegistry`：类型安全的注册/获取，重复注册报错
- [x] `V2CompositionRoot`：对象装配唯一入口
- [x] `TaskRegistry`：定时任务统一登记与取消
- [x] `SchedulerPort` + Paper/Folia 适配实现
- [x] 日志封装（正常日志 + debug 频道）
- [x] 插件名/数据目录使用 `zombie-run-v2`，与 v1 隔离
- [x] `settings.yml` schema v2 读取与默认模板生成

**验收**：
- [x] 插件在 Paper 26.1.2 上加载/卸载无异常（runServer 实测）
- [ ] Folia 加载实测（待 Folia 测试服验证）
- [x] `zr2 version` 可输出版本（或 `/zr2` 帮助页）
- [x] 单元测试可运行（16 个 domain 测试全部通过）

---

## M1：门系统垂直切片（核心）

**目标**：可靠门检测完整闭环，可独立演示。

### M1.1 配置

- [x] `arenas/*.yml` v2 schema：doors/buttons/respawns
- [x] `DoorDefinition` 与 `Portal` 模型
- [x] 配置校验器：缺失字段/坐标非法/门号重复/方向非法，报错带文件与路径
- [x] 方块快照 `BlockSnapshotPort` 读写（实现为 `BlockSnapshotStore`）

### M1.2 检测核心（纯 Kotlin + 单测）

- [x] `PortalCrossingDetector`：线段-平面交点 + 门洞范围校验
- [x] `DoorSessionStateMachine`：OPENING/CLOSING/CLOSED，玩家侧边状态 BEHIND/FRONT/UNKNOWN
- [x] 穿越记录：`BEHIND→FRONT` 且交点合法
- [x] 关门判定：记录优先；严格兜底 + warning 日志
- [x] 单元测试覆盖 ARCHITECTURE.md 4.4 列出的全部场景

### M1.3 Bukkit 适配

- [x] `PlayerMoveEvent` 监听器 → 领域事件
- [x] `PlayerTeleportEvent` 标记传送，传送不参与穿越判定
- [x] 门开启/关闭方块操作（region scheduler）
- [x] 按钮交互监听（normal 已接通，escape 待 M2 撤离流程）
- [x] 落后传送倒计时（DOOR_PLAYER 已接通；人类/僵尸分流待 M2 队伍系统）

### M1.4 命令

- [x] `/zr2 arena create/info/list/remove`（edit 待后续）
- [x] `/zr2 door add/list/info/test`（edit/remove 待后续）
- [x] `/zr2 door trigger <number>`
- [x] `/zr2 button add`（remove/list 待后续）
- [x] `test` 子命令：显示指定门的 axis/front/门洞范围（可视化框待后续）
- [x] Tab 补全

### M1.5 玩家反馈

- [x] 穿越成功即时提示（ActionBar/Title）
- [x] 关门判定结果显示原因（`passed` / `fallback` / `behind`）

**验收**：
- [x] 用门检测单测矩阵全部通过
- [x] 单元测试覆盖低 TPS 大位移、折返、门侧站立、传送穿门等场景
- [x] runServer 实测：创建 arena → 创建门/按钮/重生点 → 触发开门 → 15s 开门 → 15s 关门 → 会话结束
- [ ] 真实玩家客户端穿门/落后传送实测（待联机）

---

## M2：游戏状态机与对局流程

- [x] `GameInstance` 聚合：WAITING/STARTING/RUNNING/ENDED（含队伍/房间/在线状态维护）
- [x] 自动开局条件与倒计时（人数不足自动取消）
- [x] 母体选择与开局广播（随机母体 + 队伍重生点分配）
- [x] 队伍切换与人类清零结算（僵尸濒死攻击转感染）
- [x] 最大时长结束（时间耗尽人类获胜；直升机撤离待 M6 撤离流程）
- [x] 中途加入/换世界接入逻辑（进行中加入为僵尸，母体离开自动补位）
- [ ] 游戏结束结算数据收集（事件已发布 GameEndedEvent，击杀/感染/奖励统计待 M3/M5）
- [x] `/zr2 game list|status|start|end|reset` 管理命令

**验收**：
- [x] 最小人数自动开局、母体分配、感染全灭结束、超时结束——单元测试覆盖
- [ ] 多世界同开两局互不影响（实例按世界隔离已实现，待联机实测）
- [x] 状态机路径有单元测试（GameInstanceTest + GameFlowServiceTest，共 9 个新测试）

---

## M3：体力/生命/战斗/感染

- [ ] 生命系统：自定义生命值、伤害流（击杀统计已实现；自定义生命值与伤害规则待 M4 武器伤害联调）
- [x] 感染逻辑：近战感染、母体/普通僵尸区分（僵尸濒死攻击转感染，人类濒死不感染）
- [x] 体力系统：消耗/恢复/疲劳（`StaminaState` + `StaminaService` + 疾跑监听，settings.yml 可配）
- [x] 战斗监听迁移为 application 用例（感染/击杀统计走 `GameFlowService`）
- [ ] 黑羊毛等地图机关保留（待地图机关里程碑）

**验收**：
- [x] 人类被感染后阵营同步正确（单测覆盖）
- [x] 体力边界单测覆盖（StaminaStateTest 6 个用例）
- [x] 感染在事件线程内完成状态切换后取消伤害事件（Folia 安全路径与 M2 一致）

---

## M4：武器系统

- [x] `WeaponDefinition` 领域模型（类型/分类/价格/启用）
- [x] QualityArmory 适配器（`WeaponIntegrationPort` + `QaWeaponIntegrationPort`）
- [x] 选枪/随机枪用例（`WeaponService`：add/give/random/remove/reload）
- [x] `/zr2 weapon list|info|add|remove|give|random` 管理命令
- [ ] 武器购买结算（价格字段已定义，扣款/购买界面待 M5 经济）
- [ ] 武器伤害与战斗系统解耦（伤害数值仍由 QualityArmory 负责，事件接入待 M5）

**验收**：
- [x] v2 weapons.yml 配置可加载（默认 3 把武器），QA 缺失时 `give` 返回失败并提示
- [x] QualityArmory 缺失时不静默失败（`QaWeaponIntegrationPort` 返回 false 并 warn）
- [x] 单元测试覆盖随机/过滤/发放用例（45 个测试全部通过）

---

## M5：经济/进度/任务/称号

- [x] 硬币系统 + SQLite 持久化（v2 独立 `player_data_v2` 表；v1 数据迁移待 M7）
- [x] XP/等级/称号（升级制 100*level/级，称号可设置/清除）
- [x] 每日/每周任务定义与进度（TaskYamlRepository + PlayerTaskPort + TaskService）
- [x] 过门任务/击杀任务计数接入应用事件总线（PlayerPassedDoorEvent / ZombieKilledEvent）
- [x] 玩家数据仓库抽象与缓存（PlayerDataPort / PlayerDataService / SqlitePlayerDataRepository）
- [x] `/zr2 task list|claim`
- [x] 任务奖励领取：硬币/经验发放并防止重复领取

**验收**：
- [x] SQLite 初始化/WAL/关闭正常（Paper 实测）
- [x] 事件计数在服务重启后持久化（单测覆盖存储）
- [x] 每日/每周任务完成 + 领取奖励单测通过
- [ ] v1 SQLite 数据库导入后数据完整（待 M7 迁移）

---

## M6：GUI

- [x] 菜单框架：菜单 ID + session 注册（Holder + ConcurrentHashMap 点击回调）
- [x] 商店 GUI（点击购买武器，扣硬币 + 发枪）
- [x] 个人资料 GUI（等级/经验/硬币/称号/门数/击杀）
- [ ] 任务 GUI（任务模板待 M5 扩展后接入）
- [ ] 称号 GUI（命令已支持 set/clear，选择界面待接入）
- [x] GUI 权限与点击安全（点击取消搬运，关闭清理 session）

**验收**：
- [x] 多玩家同时打开互不串扰（按 UUID 隔离）
- [x] 关闭/重载/世界切换后菜单状态清理（InventoryCloseEvent 清理）
- [x] `/zr2 menu profile|shop` 命令可用，Paper 加载无异常

---

## M7：兼容与特殊功能

- [x] 特殊门行为：elevator/subway/airport 迁移为 `DoorBehavior` 策略（通过后按阵营传送+倒计时）
- [x] 直升机撤离（ESCAPE 按钮 30 秒倒计时后人类获胜）
- [x] Multiverse 世界解析（`MultiverseWorldResolver`，忽略大小写别名匹配）
- [x] PlaceholderAPI 扩展（`%zombierun_profile_*%` + v1 兼容别名）
- [x] 昵称前缀/称号展示（PAPI 提供称号占位符，GUI 展示）
- [x] v1 配置迁移命令 `/zr2 v1 migrate`（门/按钮/重生点写入 `migrated_v1` arena，报告输出）

**验收**：
- [x] 样例 v1 config 迁移成功：doors=1 buttons=2 respawns=1（含 ELEVATOR 行为，Paper 实测）
- [x] PAPI 占位符兼容 v1 主要名称（level/xp/coins/kills/doors/phase 别名）
- [ ] v1 全套 config 迁移报告含需人工确认项（方块快照待扩展）

---

## M8：加固与发布

- [x] 性能基准：20 玩家 × 100 门移动检测开销（PortalCrossingPerformanceTest，4万次检测 < 2s）
- [x] 内存/任务泄漏检查：对局重复 100 次（TaskRegistry 自动清理已取消句柄，泄漏测试通过）
- [x] 错误处理与玩家可见提示统一（命令顶层 try/catch，出错可见不崩服）
- [x] Wiki/命令文档更新（COMMANDS.md）
- [x] v1 → v2 升级指南（UPGRADE.md）
- [x] beta 版本发布（RELEASE.md，`v2.0.0-beta.1`）

**验收**：
- [x] 无任务泄漏、无配置热重载异常（Paper 实测干净启动/关闭）
- [x] 升级指南覆盖：迁移命令、方向确认、备份、回滚 v1
- [x] 单元测试 54 个全绿 + Paper 烟测

---

## 建议的第一个执行批次（批准后）

1. 创建 `v2` 分支
2. M0 全部任务
3. M1.1 + M1.2（先写检测核心和单测）
4. M1.3 + M1.4 + M1.5
5. 提交可演示垂直切片，再启动 M2
