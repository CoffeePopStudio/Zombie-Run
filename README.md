# Zombie Run v2（重构分支）

> ⚠️ 当前为 `v2` 重构分支：v1 稳定版在 `main` 分支。
> 架构设计见 [docs/v2/ARCHITECTURE.md](docs/v2/ARCHITECTURE.md)，路线图见 [docs/v2/ROADMAP.md](docs/v2/ROADMAP.md)。
> 当前进度：**M0 骨架 + M1 门系统 + M2 游戏状态机 + M3 体力/战斗 + M4 武器 + M5 经济/等级/称号 + M6 GUI + M7 兼容/PAPI/Multiverse/v1 迁移**。

非对称 PvP 小游戏插件 —— 人类通过层层大门逃生，僵尸全力感染。

> 适用于 Paper / Folia，Kotlin 开发。

## v2 当前可用命令（垂直切片）

```bash
/zr2 arena create <名称> [世界]
/zr2 door add --arena <名称> <x1> <y1> <z1> <x2> <y2> <z2> <axis:x|z> <front:positive|negative>
/zr2 button add --arena <名称> <x> <y> <z> normal <门号>
/zr2 respawn add --arena <名称> door_player <x> <y> <z> <门号>
/zr2 door trigger <门号>
/zr2 door test <门id>
/zr2 game list | status <世界> | start <世界> | end <世界> <human|zombie> | reset <世界>
/zr2 weapon list | info <id> | add <id> <type> <category> <price> [name] | remove <id> | give <id> | random [category]
/zr2 profile [玩家] | coins add|give|spend | xp add | title set|clear
/zr2 menu profile|shop
/zr2 v1 migrate
/zr2 reload
```

### PlaceholderAPI

`%zombierun_profile_level%`, `%zombierun_profile_xp%`, `%zombierun_profile_coins%`, `%zombierun_profile_title%`, `%zombierun_profile_kills%`, `%zombierun_profile_doors%`, `%zombierun_game_phase%`
兼容别名：`%zombierun_level%`, `%zombierun_money%`, `%zombierun_kills%` 等。

其余 v1 功能将在后续里程碑迁移，v1 稳定版仍可切回 `main` 分支使用。

## 游戏机制

| 阵营 | 目标 |
|---|---|
| 人类 | 按顺序通过大门抵达终点，或用直升机撤离 |
| 僵尸（含 1 名母体） | 近战感染所有人类 |

**特色系统：** 多阶段门流程 · 电梯/地铁/机场传送 · 体力限制 · 自定义枪械 · 硬币经济 · 等级称号 · 每日/每周任务

## 安装

1. 下载 `.jar` 放入 `plugins/`
2. 重启服务器，自动生成 `plugins/zombie-run/config/config.yml`
3. `/zr reload` 热重载

**依赖：** Paper / Folia · 可选 [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/)

## 快速开始

```bash
# 1. 选区
/zr postool                          # 拿到选区棒，左键角1，右键角2

# 2. 创建门
/zr doors add normal                 # 普通门（自动分配门号，duration=15）
/zr doors add normal -g subway_l1    # 地铁门组（同名组自动继承门号）
/zr doors add player                 # 人类起始门
/zr doors add zombie                 # 僵尸起始门

# 3. 创建按钮
/zr buttons add <x> <y> <z> normal <门号>

# 4. 创建重生点
/zr spawn wait          # 等待大厅
/zr spawn player        # 人类出生点
/zr spawn zombie        # 僵尸出生点
/zr spawn alpha         # 母体出生点

# 5. （可选）设置特殊传送 — postool 选区后直接生效
/zr door behavior set <门号> subway "线路名"     # pos1=人类目标, pos2=僵尸目标
/zr door behavior set <门号> elevator            # pos1.y=人类Y, pos2.y=僵尸Y
/zr door behavior set <门号> airport             # pos1=人类目标, pos2=僵尸目标

# 6. 开始游戏
/zr start
```

## 文档

完整文档请查看 [GitHub Wiki](https://github.com/CoffeePopStudio/Zombie-Run/wiki)：

| 页面 | 内容 |
|---|---|
| [Home](https://github.com/CoffeePopStudio/Zombie-Run/wiki) | 游戏流程、安装、权限 |
| [配置指南](https://github.com/CoffeePopStudio/Zombie-Run/wiki/Configuration) | config.yml 完整参考 |
| [命令参考](https://github.com/CoffeePopStudio/Zombie-Run/wiki/Commands) | 所有命令 + PlaceholderAPI |
| [门系统](https://github.com/CoffeePopStudio/Zombie-Run/wiki/Door-System) | 门模式、按钮、电梯/地铁/机场 |
| [武器系统](https://github.com/CoffeePopStudio/Zombie-Run/wiki/Weapons) | 枪械配置、弹药、商店 |
| [进度系统](https://github.com/CoffeePopStudio/Zombie-Run/wiki/Progression) | 等级/XP/称号/任务 |
| [体力系统](https://github.com/CoffeePopStudio/Zombie-Run/wiki/Stamina) | 体力消耗/恢复/疲劳 |

## 权限

| 权限 | 说明 | 默认 |
|---|---|---|
| `zombie.run.admin` | 管理命令 | OP |
| `zombie.run.start` | 开始游戏 | OP |

## 构建

```bash
./gradlew build
```

产物：`build/libs/zombie-run-<version>-all.jar`
