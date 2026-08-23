# v2 Beta 发布说明

版本：`v2.0.0-beta.1`

分支：`v2`

范围：M0 骨架 / M1 门系统 / M2 游戏状态机 / M3 体力战斗 / M4 武器 / M5 经济等级称号 / M6 GUI / M7 兼容迁移

## 已知限制（Beta 范围）

- v1 经济/任务数据尚未迁移（仅配置迁移可用）。
- 方块扫描快照为 v2 独立 `snapshots/`，v1 旧快照暂未自动转换。
- 任务模板系统尚未实现。
- PAPI/Multiverse 为可选软依赖，未安装时功能自动降级。
- Folia 注册任务已声明，但本轮烟测基于 Paper；Folia 需针对服务器线程模型继续验证。

## 发布物

- `build/libs/zombie-run-v2-all.jar`（shadow jar，含 SQLite JDBC / HikariCP）。

## 安装

见 [UPGRADE.md](./UPGRADE.md) 与 [README.md](../../README.md)。

## 验证情况

- `gradlew test build` 全绿：51 个单元测试。
- Paper 26.1.2 实测：
  - 插件启用/禁用无异常；
  - SQLite 初始化/关闭正常（WAL）；
  - `/zr2 v1 migrate` 样例迁移成功；
  - 对局状态机、门检测、体力、武器、GUI 均有单元测试覆盖。