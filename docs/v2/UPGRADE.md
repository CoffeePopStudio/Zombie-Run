# v1 → v2 升级指南

> 适用对象：已有 v1 ZombieRun 存档/配置的服务器。

## 核心变更

- v2 是独立插件 `zombie-run-v2`，与 v1 插件可并行安装调试。
- v2 配置目录为 `plugins/zombie-run-v2/`，不直接修改 v1。
- v2 使用 `player_data_v2` SQLite 表存储经济/进度，v1 旧数据暂不覆盖。

## 推荐升级流程

1. **备份**
   - 备份 v1 `plugins/zombie-run/`（配置、快照、SQLite 数据库）。
   - 备份 v2 `plugins/zombie-run-v2/`（如有测试数据）。
2. **安装**
   - 安装 Paper/Folia + QualityArmory。
   - 放入 `zombie-run-v2.jar`，可选安装 PlaceholderAPI / Multiverse-Core。
3. **迁移配置**
   - 保持 v1 配置在 `plugins/zombie-run/config/config.yml`。
   - 启动服务器后执行：
     ```
     /zr2 v1 migrate
     ```
   - 命令会把门/按钮/重生点写入 v2 arena：`migrated_v1`；特殊门行为（elevator/subway/airport）也会迁移到 `behavior`。
4. **人工检查**
   - 确认 `world` 名称是否正确（Multiverse 可自动按别名解析）。
   - 方块快照暂不自动迁移，需用 v2 命令重建或等待后续工具。
   - 检查迁移报告中的 skipped 项。
5. **验证**
   - `/zr2 arena list` 能看到 `migrated_v1`。
   - `/zr2 door list`、`/zr2 respawn list` 检查数量。
   - 对局/门系统做一轮试玩。
6. **回滚**
   - 删除或禁用 `zombie-run-v2.jar` 即可完全回到 v1。
   - v1 文件在上方备份中未被修改。

## 数据迁移

- 特殊门行为（电梯/地铁/机场）已迁移为 v2 `behavior` 配置。
- 直升机撤离按钮已支持：使用 v1 `buttons` 中 `mode: escape` 的按钮触发。
- v1 经济/任务数据迁移将在后续版本提供独立命令。
- 当前 v2 的硬币/XP/称号从零开始，事件计数（过门/击杀）实时写入 `player_data_v2`。