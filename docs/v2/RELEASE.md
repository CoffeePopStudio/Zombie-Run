# v2 Beta 发布说明

版本：`v2.0.0-beta.1`

分支：`v2`

范围：M0 骨架 / M1 门系统 / M2 游戏状态机 / M3 体力战斗 / M4 武器 / M5 经济等级称号 / M6 GUI / M7 兼容迁移 / M11 MapFlow 完整流程 / M13 地图编辑命令 / M14-15 任务称号 GUI / M16 CI / M17 v1 玩家数据+门快照迁移 / M18 出生点与母体机制

## 已知限制（Beta 范围）

- v1 旧快照与当前门位置不重叠时仅导入不自动关联（报告中 attached=0 项需人工补链）。
- 任务模板系统仅支持 DAILY/WEEKLY 门数/击杀两类（可扩展到更多类型）。
- PAPI/Multiverse 为可选软依赖，未安装时功能自动降级。
- Folia 注册任务已声明，但本轮烟测基于 Paper；Folia 需针对服务器线程模型继续验证。

## 发布物

- `build/libs/zombie-run-v2-all.jar`（shadow jar，含 SQLite JDBC / HikariCP）。

## 安装

见 [UPGRADE.md](./UPGRADE.md) 与 [README.md](../../README.md)。

## 验证情况

- `gradlew test build` 全绿（含 v1 数据迁移单测）。
- Paper 26.1.2 实测：
  - 插件启用/禁用无异常；
  - SQLite 初始化/关闭正常（WAL）；
  - `/zr2 v1 migrate` 样例迁移成功；
  - `/zr2 v1 migrate-data` 从 v1 数据库导入 3 位玩家（135,443,145 硬币）成功；
  - 对局状态机、门检测、体力、武器、GUI、MapFlow 均有单元测试覆盖。