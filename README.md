# Zombie Run

非对称 PvP 小游戏插件 —— 人类通过层层大门逃生，僵尸全力感染。

> 适用于 Paper / Folia，Kotlin 开发。

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
# 1. 创建门
/zr doors add 10 64 10 10 66 20 normal 1 5

# 2. 创建按钮
/zr buttons add 5 64 5 normal 1

# 3. 创建重生点
/zr spawn wait          # 等待大厅
/zr spawn player        # 人类出生点
/zr spawn zombie        # 僵尸出生点
/zr spawn alpha         # 母体出生点

# 4. （可选）电梯/地铁传送
/zr door behavior set door_xxx elevator 48 5
/zr door behavior set door_xxx subway 500 20 500 "1号线" 10

# 5. 开始游戏
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
