# Zombie Run v2 任务拆解与路线图（Draft v0.1）

> 状态：**待确认**。批准后按 M0 开始执行。
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

- [ ] 创建 `v2` 分支（从当前 `main`）
- [ ] 新建 v2 包 `cn.oneachina.zombierun.v2`
- [ ] `ZombieRunV2Plugin`：薄启动器，只做注册与生命周期转发
- [ ] `V2ServiceRegistry`：类型安全的注册/获取，重复注册报错
- [ ] `V2CompositionRoot`：对象装配唯一入口
- [ ] `TaskRegistry`：定时任务统一登记与取消
- [ ] `SchedulerPort` + Paper/Folia 适配实现
- [ ] 日志封装（正常日志 + debug 频道）
- [ ] 插件名/数据目录使用 `zombie-run-v2`，与 v1 隔离
- [ ] `settings.yml` schema v2 读取与默认模板生成

**验收**：
- 空插件在 Paper 26.1.2 与 Folia 上均能加载/卸载，无异常
- `zr2 version` 可输出版本（或 `/zr2` 帮助页）
- 单元测试可运行

---

## M1：门系统垂直切片（核心）

**目标**：可靠门检测完整闭环，可独立演示。

### M1.1 配置

- [ ] `arenas/*.yml` v2 schema：doors/buttons/respawns
- [ ] `DoorDefinition` 与 `Portal` 模型
- [ ] 配置校验器：缺失字段/坐标非法/门号重复/方向非法，报错带文件与路径
- [ ] 方块快照 `BlockSnapshotPort` 读写

### M1.2 检测核心（纯 Kotlin + 单测）

- [ ] `PortalCrossingDetector`：线段-平面交点 + 门洞范围校验
- [ ] `DoorSessionStateMachine`：OPENING/CLOSING/CLOSED，玩家侧边状态 BEHIND/FRONT/UNKNOWN
- [ ] 穿越记录：`BEHIND→FRONT` 且交点合法
- [ ] 关门判定：记录优先；严格兜底 + warning 日志
- [ ] 单元测试覆盖 ARCHITECTURE.md 4.4 列出的全部场景

### M1.3 Bukkit 适配

- [ ] `PlayerMoveEvent` 监听器 → 领域事件
- [ ] `PlayerTeleportEvent` 标记传送，传送不参与穿越判定
- [ ] 门开启/关闭方块操作（region scheduler）
- [ ] 按钮交互监听（人类权限、门号、escape 类型）
- [ ] 落后传送倒计时（人类/僵尸独立）

### M1.4 命令

- [ ] `/zr2 arena create/edit/info/list`
- [ ] `/zr2 door add/edit/remove/list/info/test`
- [ ] `/zr2 door trigger <number>`
- [ ] `/zr2 button add/remove/list`
- [ ] `test` 子命令：显示指定门的 axis/front/门洞范围，并可选开启可视化检测框
- [ ] Tab 补全

### M1.5 玩家反馈

- [ ] 穿越成功即时提示（ActionBar/Title）
- [ ] 关门判定结果显示原因（`passed` / `fallback` / `behind`）

**验收**：
- 用门检测单测矩阵全部通过
- 在测试服完成低 TPS 模拟、穿门折返、门侧站立、传送穿门四类实测
- 单人可以完成：创建 arena → 创建门/按钮 → 触发开门 → 穿门 → 关门判定 → 落后传送

---

## M2：游戏状态机与对局流程

- [ ] `GameInstance` 聚合：WAITING/STARTING/RUNNING/ENDED
- [ ] `PlayerRoster`：队伍/房间/在线状态
- [ ] 自动开局条件与倒计时
- [ ] 母体选择与开局广播
- [ ] 队伍切换与人类清零结算
- [ ] 最大时长与直升机撤离结束
- [ ] 中途加入/换世界接入逻辑
- [ ] 游戏结束结算数据收集（击杀/感染/奖励）

**验收**：
- 最小人数到自动开局、母体解封、全灭结束、超时结束全部可跑
- 多世界同开两局互不影响
- 状态机路径有单元测试

---

## M3：体力/生命/战斗/感染

- [ ] 生命系统：自定义生命值、伤害流、击杀统计
- [ ] 感染逻辑：近战感染、母体/普通僵尸区分
- [ ] 体力系统：消耗/恢复/疲劳
- [ ] 战斗监听迁移为 application 用例
- [ ] 黑羊毛等地图机关保留

**验收**：
- 人类被感染后阵营/计分/任务同步正确
- 体力边界单测覆盖
- Folia 下伤害事件线程安全

---

## M4：武器系统

- [ ] `WeaponDefinition` 领域模型
- [ ] QualityArmory 适配器（`WeaponIntegrationPort`）
- [ ] 选枪/随机枪/武器购买用例
- [ ] 武器伤害与战斗系统解耦

**验收**：
- v1 配置迁移后枪械行为一致
- QualityArmory 缺失时明确报错，不静默失败

---

## M5：经济/进度/任务/称号

- [ ] 硬币系统 + SQLite 持久化（兼容 v1 数据）
- [ ] XP/等级/称号
- [ ] 每日/每周任务定义与进度
- [ ] 过门任务/击杀任务接入应用事件总线
- [ ] 玩家数据仓库抽象与缓存

**验收**：
- v1 SQLite 数据库导入后数据完整
- 任务进度在重载/重进后一致

---

## M6：GUI

- [ ] 菜单框架：菜单 ID + session 注册，替代裸 InventoryHolder
- [ ] 商店 GUI
- [ ] 个人资料 GUI
- [ ] 任务 GUI
- [ ] 称号 GUI
- [ ] GUI 权限与点击安全

**验收**：
- 多玩家同时打开互不串扰
- 关闭/重载/世界切换后菜单状态正确

---

## M7：兼容与特殊功能

- [ ] 特殊门行为：elevator/subway/airport 迁移为 `DoorBehavior` 策略
- [ ] 直升机撤离
- [ ] Multiverse 世界解析
- [ ] PlaceholderAPI 扩展
- [ ] 昵称前缀/称号展示
- [ ] v1 配置迁移命令 `/zr2 v2 migrate` 完整实现与报告

**验收**：
- v1 全套 config 迁移成功，迁移报告含需人工确认项
- PAPI 占位符兼容 v1 主要名称

---

## M8：加固与发布

- [ ] 性能基准：20 玩家 × 100 门配置下移动事件开销
- [ ] 内存/任务泄漏检查（对局重复 100 次）
- [ ] 错误处理与玩家可见提示统一
- [ ] Wiki/命令文档更新
- [ ] v1 → v2 升级指南
- [ ] beta 版本发布

**验收**：
- 无任务泄漏、无配置热重载异常
- 升级指南覆盖：迁移命令、方向确认、备份、回滚 v1

---

## 建议的第一个执行批次（批准后）

1. 创建 `v2` 分支
2. M0 全部任务
3. M1.1 + M1.2（先写检测核心和单测）
4. M1.3 + M1.4 + M1.5
5. 提交可演示垂直切片，再启动 M2
