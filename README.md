# Zombie Run Plugin

一个基于 Paper 1.21 的僵尸逃生小游戏插件，包含以下核心机制：

- 自动开局与倒计时（达到最小人数后自动进入准备阶段）
- 人类/僵尸/母体阵营与回合结算
- 门、按钮、区域、重生点驱动的地图流程控制
- 体力系统、硬币/击杀/感染统计
- PlaceholderAPI 占位符扩展（前缀 `zombierun`）
- 与 WeaponMechanics 联动发放/购买枪械

## 运行环境

- Java: `21`
- 服务端: `Paper 1.21.11`（或同 API 版本的 1.21.x）
- 构建: `Gradle (Kotlin DSL)`
- 可选依赖:
  - `PlaceholderAPI`（启用占位符）
  - `WeaponMechanics`（枪械购买与发放）

> 说明：`PlaceholderAPI` 未安装时插件会正常启动，但占位符不会注册。

## 安装与首次启动

1. 构建插件：

```bash
./gradlew.bat shadowJar
```

1. 将产物放入服务器 `plugins` 目录（通常在 `build/libs` 下，带 `-all` 的 jar）。
2. 启动服务器后，插件会生成配置文件：
   - `plugins/zombie-run/config/config.yml`
3. 按你的地图修改门、按钮、区域、重生点等坐标。
4. 使用 `/zr reload` 热重载配置，或重启服务器生效。

## 命令说明

### 主命令

- `/zr`：显示帮助
- `/zr start`：管理员强制开始
- `/zr reload`：重载配置
- `/zr door <门号>`：触发指定门
- `/zr open`：控制台开局（仅控制台）
- `/zr close`：结束当前对局

### 配置编辑命令（管理员）

- `/zr spawn add|remove|list ...`：管理重生点
- `/zr doors add|remove|list ...`：管理门
- `/zr buttons add|remove|list ...`：管理按钮（`normal` / `tp` / `escape`）

### 玩家功能命令

- `/zr select <编号>`：选择偏好枪械（下次随机时优先购买）
- `/zr unselect`：取消偏好选择
- `/zr randomgun`：随机发放枪械（人类）
- `/zr lobby`：返回大厅
- `/zr transfer <玩家> <金额>`：转账硬币

### 其他

- `/doorperf`：门区域检测性能测试（管理员）

## 配置结构速览

配置文件：`plugins/zombie-run/config/config.yml`

- `game`：开局人数、开始延迟、最大回合时长
- `spawn`：默认出生点（兜底）
- `doors`：门区域与行为参数（`mode`/`delay`/`material` 等）
- `buttons`：按钮定义（普通门按钮、传送按钮、撤离按钮）
- `regions`：区域房间号判定
- `respawns`：等待区、人类、僵尸、母体等重生点
- `start-effects`：开场指令序列
- `zombie` / `human` / `stamina` / `misc`：属性与体力、杂项参数
- `weapons`：枪械默认价格与按名称覆写价格

## PlaceholderAPI 占位符

> 标识符：`zombierun`  
> 示例：`%zombierun_human_count%`

| 占位符                                     | 描述                       | 是否需要玩家 | 示例                       |
|-----------------------------------------|--------------------------|--------|--------------------------|
| `%zombierun_human_count%`                 | 当前人类数量                   | 否      | 3                        |
| `%zombierun_zombie_count%`                | 当前僵尸数量（含母体）              | 否      | 5                        |
| `%zombierun_alpha_zombie_name%`           | 母体玩家名                    | 否      | Steve                    |
| `%zombierun_alpha_zombie_health%`         | 母体当前血量（整数）               | 否      | 450                      |
| `%zombierun_alpha_zombie_max_health%`     | 母体最大血量                   | 否      | 500                      |
| `%zombierun_alpha_zombie_health_percent%` | 母体血量百分比（0.0~1.0）         | 否      | 0.9                      |
| `%zombierun_game_state%`                  | 游戏状态英文大写                 | 否      | RUNNING                  |
| `%zombierun_game_state_formatted%`        | 游戏状态带颜色中文                | 否      | §a进行中                    |
| `%zombierun_time_left%`                   | 游戏剩余秒数（仅运行中）             | 否      | 120                      |
| `%zombierun_time_left_formatted%`         | 格式化剩余时间 mm:ss            | 否      | 02:00                    |
| `%zombierun_min_players%`                 | 最小开始人数                   | 否      | 8                        |
| `%zombierun_max_players%`                 | 最大玩家数                    | 否      | 32                       |
| `%zombierun_online_players%`              | 当前在线玩家总数                 | 否      | 15                       |
| `%zombierun_progress%`                    | 进度（等待时为在线/最少，运行时为已过/总时长） | 否      | 0.5                      |
| `%zombierun_bossbar%`                     | 整合状态信息（含颜色格式，适合 BossBar） | 否      | `<aqua>人类: 3 <red>僵尸: 5` |
| `%zombierun_coins%`                       | 玩家硬币数量                   | 是      | 1200                     |
| `%zombierun_kills%`                       | 玩家击杀僵尸数                  | 是      | 7                        |
| `%zombierun_infections%`                  | 玩家感染人类数                  | 是      | 3                        |
| `%zombierun_selected_weapon%`             | 玩家选择枪械编号（未选为 0）          | 是      | 5                        |
| `%zombierun_room%`                        | 玩家当前房间号                  | 是      | 3                        |
| `%zombierun_team%`                        | 玩家队伍英文大写                 | 是      | HUMAN                    |
| `%zombierun_team_formatted%`              | 玩家队伍带颜色中文                | 是      | §b人类                     |
| `%zombierun_stamina%`                     | 玩家当前体力值（整数）              | 是      | 80                       |
| `%zombierun_max_stamina%`                 | 玩家最大体力值                  | 是      | 100                      |
| `%zombierun_stamina_percent%`             | 玩家体力百分比                  | 是      | 0.8                      |
| `%zombierun_stamina_bar%`                 | 玩家体力条（20 格，带颜色）          | 是      | `§a████████████████§7████` |
| `%zombierun_stamina_state%`               | 体力状态（1 正常，2 耗尽）          | 是      | 1                        |

## 开发

### 本地运行测试服

```bash
./gradlew.bat runServer
```

### 构建

```bash
./gradlew.bat build
```

## 常见问题

- 枪械列表为空：请确认 `WeaponMechanics` 已正确安装并加载武器配置。
- 占位符无效：请确认 `PlaceholderAPI` 已安装并在插件启动后注册成功。
- 人数达到后不自动开始：检查 `game.min-players`、`game.start-delay` 以及玩家是否在线于同一服务器实例。
