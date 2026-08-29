# ZombieRun v2 命令参考

权限：
- `zombie.run.v2.admin`：管理命令（默认 OP）
- `zombie.run.v2.player`：玩家基础命令（默认所有玩家）

## 基础

| 命令 | 说明 |
| --- | --- |
| `/zr2 help` | 查看帮助 |
| `/zr2 version` | 查看 v2 版本 |
| `/zr2 reload` | 管理员，重新加载配置/武器/竞技场 |
| `/zr2 v1 migrate` | 管理员，从 v1 `config/config.yml` 迁移到 v2 arena |
| `/zr2 v1 migrate-data [--overwrite]` | 管理员，从 v1 `data/zr_economy.db` 导入玩家硬币/等级/经验/称号/击杀到 v2 |

## 竞技场/门/按钮/重生点

| 命令 | 说明 |
| --- | --- |
| `/zr2 arena list` | 列出竞技场 |
| `/zr2 arena create <id> <world>` | 创建竞技场 |
| `/zr2 door add --arena <名称> <x1> <y1> <z1> <x2> <y2> <z2> <axis> <front> [--number N]` | 添加门；axis 支持 `x`/`y`/`z`（`y`=水平地板/天花板门），front 支持 positive/negative |
| `/zr2 door list [世界]` | 列出门 |
| `/zr2 door info <id>` / `/zr2 door test <id>` | 查看/测试门 |
| `/zr2 door trigger <门号>` | 触发门 |
| `/zr2 door remove <id>` | 删除门 |
| `/zr2 door edit <id> [--open N] [--close N] [--group G] [--mode MODE] [--number N]` | 编辑门参数 |
| `/zr2 door reset` | 重新加载全部门/arena 配置 |
| `/zr2 door behavior info <id>` / `set <id> <type> ...` / `remove <id>` | 查看/设置/移除特殊门行为 |
| `/zr2 button add --arena <名称> <x> <y> <z> <normal\|escape> [门号...]` | 添加普通/撤离按钮 |
| `/zr2 button remove <id>` | 删除按钮 |
| `/zr2 button list [世界]` | 列出按钮 |
| `/zr2 respawn add --arena <名称> <type> <x> <y> <z> [door-number] [yaw] [pitch]` | 添加重生点 |
| `/zr2 respawn remove <id>` | 删除重生点 |
| `/zr2 respawn list [世界]` | 列出重生点 |

## 对局

| 命令 | 说明 |
| --- | --- |
| `/zr2 game status [世界]` | 查看对局状态（含 MapFlow 阶段/当前门） |
| `/zr2 game start [世界]` | 强制开始 |
| `/zr2 game end [世界]` | 强制结束 |
| `/zr2 game reset [世界]` | 重置对局 |

## 武器

| 命令 | 说明 |
| --- | --- |
| `/zr2 weapon list` | 列出武器 |
| `/zr2 weapon info <id>` | 查看武器信息 |
| `/zr2 weapon add <id> <type> <category> <price> [name]` | 添加武器 |
| `/zr2 weapon remove <id>` | 删除武器 |
| `/zr2 weapon give <id>` | 给予自己武器（自动补 1 个弹匣） |
| `/zr2 weapon random [category]` | 随机武器 |
| `/zr2 weapon select <id>` | 预选武器，开局自动扣款购买并发放 |
| `/zr2 weapon unselect` | 取消预选武器 |

## 玩家数据

| 命令 | 说明 |
| --- | --- |
| `/zr2 profile [玩家]` | 查看资料（等级/经验/硬币/称号/门数/击杀/感染/场次/胜场/解锁称号） |
| `/zr2 coins add <数量>` | 给自己加硬币 |
| `/zr2 coins give <玩家> <数量>` | 给玩家硬币 |
| `/zr2 coins remove <玩家> <数量>` | 扣除玩家硬币 |
| `/zr2 coins set <玩家> <数量>` | 设置玩家硬币 |
| `/zr2 coins get <玩家>` | 查看玩家硬币 |
| `/zr2 coins spend <数量>` | 花费硬币 |
| `/zr2 coins transfer <玩家> <数量>` | 转账给玩家 |
| `/zr2 coins top [数量]` | 查看金币排行榜 |
| `/zr2 xp add <玩家> <数量>` | 加经验（可升级） |
| `/zr2 xp set <玩家> <数量>` | 设置经验 |
| `/zr2 level set <玩家> <等级>` | 设置等级 |
| `/zr2 reset <玩家>` | 重置玩家数据 |
| `/zr2 title set <玩家> [称号]` | 设置称号 |
| `/zr2 title clear <玩家>` | 清除称号 |

## GUI

| 命令 | 说明 |
| --- | --- |
| `/zr2 menu profile` | 打开个人资料菜单 |
| `/zr2 menu shop` | 打开武器商店菜单 |
| `/zr2 menu tasks` | 查看任务进度并点击领取 |
| `/zr2 menu titles` | 选择/清除称号 |

## 地图流程（MapFlow）编辑

| 命令 | 说明 |
| --- | --- |
| `/zr2 mapflow list` | 列出已配置 MapFlow 的 arena |
| `/zr2 mapflow info <arena>` | 查看 arena 的 MapFlow 详情 |
| `/zr2 mapflow init <arena>` | 按现有门号自动生成阶段链 |
| `/zr2 mapflow set <arena> <key> <value>` | 设置人数/倒计时/时长/母体释放延迟/奖励/初始武器 |
| `/zr2 mapflow stage add <arena> <id> <门号...> [label]` | 添加阶段 |
| `/zr2 mapflow stage set <arena> <id> <门号...>` | 修改阶段门号 |
| `/zr2 mapflow stage next <arena> <id> <nextId\|none>` | 设置阶段下一跳 |
| `/zr2 mapflow stage remove <arena> <id>` | 删除阶段 |
| `/zr2 mapflow finish <arena> door <门号>` | 终点=门式 |
| `/zr2 mapflow finish <arena> extraction` | 终点=撤离 |
| `/zr2 mapflow remove <arena>` | 移除整个 MapFlow |

**`set` 可用 key**：`min-players` `start-delay-seconds` `max-duration-seconds` `mother-release-delay-seconds` `reward-coins-human` `reward-xp-human` `reward-coins-zombie` `reward-xp-zombie` `starter-weapon`

## 任务

| 命令 | 说明 |
| --- | --- |
| `/zr2 task list` | 查看每日/每周任务进度 |
| `/zr2 task claim <任务id>` | 领取已完成任务奖励 |

任务定义在 `plugins/zombie-run-v2/config/tasks.yml`；未配置时也会启用内置每日/每周固定+随机任务池。
```yaml
tasks:
  daily_doors:
    description: 通过 5 扇门
    type: DOOR_PASSES
    target: 5
    reward-coins: 100
    reward-xp: 50
    period: DAILY
```

支持的任务类型：`DOOR_PASSES`、`ZOMBIE_KILLS`、`KILL_ALPHA`、`INFECT_HUMAN`、`PLAY_GAME`、`HUMAN_WIN`、`SURVIVE_TIME`、`DEAL_DAMAGE`。

## PlaceholderAPI

前缀 `zombierun`，示例：

- `%zombierun_profile_level%`
- `%zombierun_profile_xp%`
- `%zombierun_profile_coins%`
- `%zombierun_profile_title%`
- `%zombierun_profile_kills%`
- `%zombierun_profile_doors%`
- `%zombierun_total_kills%` / `%zombierun_total_infections%`
- `%zombierun_games_played%` / `%zombierun_human_wins%`
- `%zombierun_human_count%` / `%zombierun_zombie_count%`
- `%zombierun_alpha_zombie_name%`
- `%zombierun_game_phase%` / `%zombierun_game_state%`
- `%zombierun_time_left%` / `%zombierun_progress%` / `%zombierun_bossbar%`
- `%zombierun_min_players%` / `%zombierun_max_players%` / `%zombierun_online_players%`
- `%zombierun_team%` / `%zombierun_room%` / `%zombierun_selected_weapon%`
- `%zombierun_stamina%` / `%zombierun_stamina_bar%` / `%zombierun_max_stamina%` / `%zombierun_stamina_state%`

兼容 v1 常用别名：`%zombierun_level%`, `%zombierun_xp%`, `%zombierun_money%`, `%zombierun_kills%`, `%zombierun_doors%`, `%zombierun_phase%`。