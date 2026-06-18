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
> 从 v26.1-snapshot-10 开始，WeaponMechanics 不再是强制依赖——未安装时插件会跳过枪械相关功能，其他核心玩法不受影响。

## 安装与首次启动

1. 构建插件：

```bash
./gradlew.bat shadowJar
```

2. 将产物放入服务器 `plugins` 目录（通常在 `build/libs` 下，带 `-all` 的 jar）。
3. 启动服务器后，插件会生成配置文件：
   - `plugins/zombie-run/config/config.yml`
4. 按你的地图修改门、按钮、区域、重生点等坐标。
5. 使用 `/zr reload` 热重载配置，或重启服务器生效。

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

## 配置说明

配置文件位于 `plugins/zombie-run/config/config.yml`，以下将用完整示例讲解每一部分的配置方法。

### 全局参数

```yaml
game:
  mode: zombie_run
  world: world                # 你的地图所在的世界名
  start-delay: 30             # 达到8人后等待30秒自动开局
  min-players: 8              # 最小启动人数
  max-players: 32             # 最大玩家数
  max-duration: 1800          # 单局最多30分钟，超时强制结束
```

### 门（Doors）

门是地图的核心——玩家通过按钮开启门，进入下一个区域。每扇门是一个长方体区域。

**门模式说明：**
| 模式 | 用途 |
|------|------|
| `normal` | 普通门，由玩家通过按钮触发开门倒计时 |
| `start` | 起始门，游戏开始时自动开启 |
| `player` | 开局时自动对玩家开放（用于玩家出生区） |
| `zombie` | 开局后5秒自动对僵尸开放（用于僵尸出生区） |

**完整示例——添加3扇门：**

```yaml
doors:
  # 起始门——游戏开始时自动开启，不会关闭
  door_start:
    x1: 10
    y1: 64
    z1: 10
    x2: 10
    y2: 66
    z2: 20
    delay: 0
    duration: 99999
    door-number: 0
    mode: start
    material: STONE
    open-time: 0
    close-time: 99999

  # 1号门——按钮触发，5秒开门倒计时，持续10秒后关闭
  door_1:
    x1: 20
    y1: 64
    z1: 10
    x2: 20
    y2: 66
    z2: 20
    delay: 5
    duration: 10
    door-number: 1
    mode: normal
    material: STONE
    open-time: 10
    close-time: 15

  # 2号门——使用扫描数据（恢复原始方块而非统一材质）
  door_2:
    x1: 30
    y1: 64
    z1: 10
    x2: 30
    y2: 66
    z2: 20
    delay: 5
    duration: 10
    door-number: 2
    mode: normal
    material: ""
    open-time: 10
    close-time: 15
    use-scan-data: true       # 开启方块扫描
    blocks:                   # 自动生成，无需手动填写
      "30,64,10": STONE
      "30,64,11": AIR
```

> 提示：使用 `/zr doors add 10 64 10 10 66 20 normal 1 5` 命令可以免手动编辑添加门。
> 在命令末尾加 `auto` 参数会自动扫描方块数据。

### 按钮（Buttons）

按钮是玩家与门的交互载体。按钮必须是 `REDSTONE_LAMP`（红石灯）或 `LEVER`（拉杆）。

**三种按钮模式：**
| 模式 | 功能 |
|------|------|
| `normal` | 人类点击后触发指定门号的开门倒计时 |
| `tp` | 点击后10秒传送玩家到指定坐标，并关闭目标门 |
| `escape` | 人类点击后启动直升机撤离，30秒后人类获胜 |

**完整示例：**

```yaml
buttons:
  # normal 按钮：人类点击开启1号门
  button_normal_1:
    x: 15
    y: 64
    z: 15
    mode: normal
    door-number: 1

  # tp 按钮：传送人类到 (50,64,50)，僵尸到 (40,64,40)
  # 传送后关闭1号门和2号门，并更新玩家房间号为2
  button_tp_1:
    x: 25
    y: 64
    z: 15
    mode: tp
    playerTargetX: 50
    playerTargetY: 64
    playerTargetZ: 50
    zombieTargetX: 40
    zombieTargetY: 64
    zombieTargetZ: 40
    door-numbers:
      - 1
      - 2
    door-number: 2       # 关联区域门号，用于更新房间号

  # escape 按钮：启动直升机撤离
  button_escape:
    x: 100
    y: 64
    z: 100
    mode: escape
```

### 重生点（Respawns）

重生点用于控制不同队伍的传送落点。共有6种类型：

| 类型 | 用途 |
|------|------|
| `RESPAWN_WAIT` | 等待大厅——玩家加入/游戏结束时的传送点 |
| `RESPAWN_PLAYER` | 人类重生点——开局时传送人类 |
| `RESPAWN_ZOMBIE` | 僵尸重生点——僵尸死后复活点 |
| `RESPAWN_ALPHA` | 母体僵尸重生点 |
| `RESPAWN_DOOR_PLAYER` | 被关在门外的玩家的传送落点 |
| `RESPAWN_DOOR_ZOMBIE` | 被关在门外的僵尸的传送落点 |

**完整示例：**

```yaml
respawns:
  wait_lobby:
    x: 0
    y: 64
    z: 0
    yaw: 0.0
    pitch: 0.0
    type: RESPAWN_WAIT

  human_spawn:
    x: 10
    y: 64
    z: 5
    type: RESPAWN_PLAYER

  zombie_spawn:
    x: 20
    y: 64
    z: 5
    type: RESPAWN_ZOMBIE

  alpha_spawn:
    x: 15
    y: 64
    z: 5
    type: RESPAWN_ALPHA
```

### 枪械价格配置

如果你安装了 WeaponMechanics，玩家可以在游戏开始前使用 `/zr select <编号>` 选择偏好枪械，游戏开始时从硬币中自动扣除价格。

**默认价格 vs 按名称覆写：**

```yaml
weapons:
  default-price: 600          # 未单独配置的枪械统一价600硬币
  prices:
    Pistol: 300               # 手枪300硬币
    Shotgun: 800             # 霰弹枪800硬币
    AK-47: 1000              # AK-47步枪1000硬币
    Sniper: 1500             # 狙击枪1500硬币
```

> `prices` 中的键名（如 `Pistol`、`Shotgun`）必须与 WeaponMechanics 中定义的 weapon title 完全一致。
> 可在服务器执行 WM 相关指令查看已加载的武器列表来确认名称。

### 属性与体力

```yaml
zombie:
  health-multiplier: 2.0     # 僵尸2倍血量
  speed-multiplier: 1.2      # 僵尸1.2倍速度
  damage-multiplier: 1.5     # 僵尸1.5倍伤害

human:
  speed-multiplier: 1.0      # 人类正常速度
  jump-multiplier: 1.0       # 人类正常跳跃
  stamina-regen: 0.1         # 每tick恢复0.1体力

stamina:
  max: 100.0                 # 满体力100
  sprint-cost: 0.5           # 疾跑每tick消耗0.5体力
  standing-regen: 0.2        # 站立不动时每tick恢复0.2体力
```

### 杂项

```yaml
misc:
  explosion-damage-reduction: 0.5   # 爆炸伤害减半
  knockback-reduction: 0.3          # 击退效果减少30%
  zombie-knockback-force: 0.8       # 僵尸被击退的力度
```

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

## 参与开发

开发环境搭建、调试与提交流程请查看 [CONTRIBUTING.md](./CONTRIBUTING.md)。

## 常见问题

### 枪械列表为空
请确认 `WeaponMechanics` 已正确安装并加载武器配置。未安装 WM 时插件会跳过枪械功能。

### 占位符无效
请确认 `PlaceholderAPI` 已安装并在插件启动后注册成功。

### 人数达到后不自动开始
检查 `game.min-players`、`game.start-delay` 以及玩家是否在线于同一服务器实例。

### 如何确认武器名称？
在服务器中执行 WM 的武器列表指令查看已加载的 weapon title，然后将名称填入配置文件的 `weapons.prices` 中。

### 游戏无限进行不会结束？
检查 `game.max-duration` 是否设置了合理的值（建议 1800，即30分钟）。超时后游戏会自动结束。
