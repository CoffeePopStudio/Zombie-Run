# 自定义枪械系统 + 硬币持久化 + 商店 GUI 设计说明

## 概述

完全脱离 WeaponMechanics 插件，在 zombie-run 内部实现：
- 自定义枪械定义与射击系统
- SQLite 硬币持久化
- GUI 枪械商店 + coins 管理命令

---

## 一、枪械系统

### 1.1 配置格式 (config.yml)

```yaml
custom-weapons:
  pistol:
    material: WOODEN_HOE
    custom-model-data: 10001
    name: "&a手枪"
    lore:
      - "&7一把可靠的基础手枪"
    damage: 5.0
    ammo-type: pistol_ammo
    max-ammo: 12
    price: 300
    cooldown-ticks: 10
    sound: ENTITY_GENERIC_EXPLODE
    knockback: 0.3
    range: 30
  shotgun:
    material: WOODEN_HOE
    custom-model-data: 10002
    name: "&6霰弹枪"
    damage: 3.0
    ammo-type: shotgun_ammo
    max-ammo: 8
    price: 800
    cooldown-ticks: 30
    range: 15
    pellets: 5
  rifle:
    material: WOODEN_HOE
    custom-model-data: 10003
    name: "&c步枪"
    damage: 8.0
    ammo-type: rifle_ammo
    max-ammo: 30
    price: 1500
    cooldown-ticks: 4
    range: 50
    automatic: true
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `material` | String | 是 | Bukkit Material 名 |
| `custom-model-data` | Int | 否 | 材质包的 CustomModelData |
| `name` | String | 是 | 物品显示名 (支持 & 颜色码) |
| `lore` | List | 否 | 物品 lore |
| `damage` | Double | 是 | 子弹命中伤害 |
| `ammo-type` | String | 是 | 弹药类型标识 |
| `max-ammo` | Int | 是 | 最大弹容量 |
| `price` | Int | 是 | 商店价格 (硬币) |
| `cooldown-ticks` | Int | 是 | 射击间隔 (tick) |
| `sound` | String | 否 | 射击音效名，默认不播放 |
| `knockback` | Double | 否 | 击退强度，默认 0 |
| `range` | Int | 否 | 射线最大距离，默认 30 |
| `pellets` | Int | 否 | 霰弹弹丸数，默认 1 |
| `automatic` | Boolean | 否 | 自动射击，默认 false |

### 1.2 PDC 标记

用 `PersistentDataContainer` 区分 zr 枪械与普通物品：

| NamespacedKey | 类型 | 含义 |
|---|---|---|
| `zombie-run:weapon_id` | `STRING` | 武器 ID，如 `"pistol"` |
| `zombie-run:ammo` | `INTEGER` | 当前弹药数 (0-maxAmmo) |

- 判断一把枪：`pdc.has(WEAPON_ID_KEY, PersistentDataType.STRING)`
- 读武器属性：取 `weapon_id`，查 `config.custom-weapons.<weapon_id>` 得到 `WeaponConfig`
- 工具类：`WeaponConfig` data class + `WeaponFactory.buildWeapon(id)` 生成 ItemStack
- 换弹：玩家背包中有对应 `ammo-type` 的弹药 item 时，右键枪械消耗弹药 item 恢复 `ammo` 到 `max-ammo`

### 1.3 射击逻辑

玩家右键手持 zr 枪械时：

1. PDC 读 `weapon_id` + `ammo`，ammo <= 0 → 点击空气声提示空弹
2. 冷却检查：每把枪存 `UUID → lastShotTick` map，当前 tick 与上次差 < cooldown → 阻止
3. 射线追踪 (`player.getTargetBlock(maxRange)`) 从玩家眼睛方向射出
4. 命中实体 (LivingEntity)：造成 `damage` 伤害，应用 `knockback`
5. 播放 `sound`
6. PDC 写 `ammo -= 1`，更新 action bar 显示弹药
7. 霰弹 (pellets > 1)：在 cone 角度内随机偏移扫 5 条线

### 1.4 弹药 Item

```yaml
ammo-items:
  pistol_ammo:
    material: PAPER
    custom-model-data: 20001
    name: "&e手枪弹药"
    lore:
      - "&7装填手枪用"
  shotgun_ammo:
    material: PAPER
    custom-model-data: 20002
    name: "&6霰弹弹药"
  rifle_ammo:
    material: PAPER
    custom-model-data: 20003
    name: "&c步枪弹药"
```

游戏开始时给人类玩家发 3 组对应弹药的 ItemStack（通过 `ammo-type` 匹配）。

### 1.5 武器管理器 (WeaponManager.kt)

```kotlin
class WeaponManager(private val plugin: ZombieRun) {
    private val weapons: Map<String, WeaponConfig>       // 从 config 加载
    private val ammoItems: Map<String, AmmoConfig>       // 弹药定义
    private val cooldowns: ConcurrentHashMap<UUID, Long> // 射击冷却

    fun loadWeapons()
    fun getWeaponConfig(id: String): WeaponConfig?
    fun giveWeapon(player: Player, weaponId: String): Boolean
    fun handleShoot(player: Player, item: ItemStack)
    fun reloadWeapon(player: Player, weaponStack: ItemStack)
    fun isZombieRunWeapon(item: ItemStack): Boolean
    fun getWeaponAmmo(item: ItemStack): Int
    fun setWeaponAmmo(item: ItemStack, ammo: Int)
}
```

---

## 二、硬币持久化

### 2.1 SQLite 方案

`plugins/zombie-run/data/zr_economy.db`，自动建表：

```sql
CREATE TABLE IF NOT EXISTS zr_economy (
    uuid VARCHAR(36) PRIMARY KEY,
    username VARCHAR(32),
    coins INT DEFAULT 0
);
```

### 2.2 CoinManager.kt

```kotlin
class CoinManager(private val plugin: ZombieRun) {
    private val cache: ConcurrentHashMap<UUID, Int>
    private lateinit var dataSource: HikariDataSource

    fun init()                    // 建表 + HikariCP 初始化
    fun loadPlayer(uuid: UUID, username: String): Int
    fun getCoins(uuid: UUID): Int
    fun addCoins(uuid: UUID, amount: Int)
    fun takeCoins(uuid: UUID, amount: Int): Boolean
    fun setCoins(uuid: UUID, amount: Int)
    fun savePlayer(uuid: UUID, username: String)  // 异步 flush
    fun flushAll()               // onDisable 时全部刷盘
    fun getTopCoins(limit: Int): List<Pair<String, Int>>
}
```

### 2.3 生命周期

```
PlayerJoin  → loadPlayer(uuid) → 缓存到 ConcurrentHashMap
addCoins    → 更新缓存 + 异步 UPDATE
takeCoins   → 更新缓存 + 异步 UPDATE
PlayerQuit  → savePlayer → 异步 UPDATE + 移除缓存
onDisable   → flushAll → 同步全部 UPDATE
```

### 2.4 coins 管理员命令

```
/zr coins add <玩家> <金额>      增加 coins
/zr coins remove <玩家> <金额>   扣除 coins
/zr coins set <玩家> <金额>      直接设置 coins 值
/zr coins get <玩家>             查询玩家 coins (无需在线也可查)
/zr coins top <数量>             硬币排行 (默认前10)
```

---

## 三、商店 GUI

### 3.1 ShopGUI.kt

```kotlin
class ShopGUI(private val plugin: ZombieRun) {
    fun open(player: Player)    // 构建库存 UI 并打开
    fun onAutoOpen(player: Player)  // 等待阶段自动弹出
}
```

- 9×N 箱子库存 UI (N = ceil(weapons.size / 9.0))
- 每个武器占一格: 图标 (material + custom-model-data) + 名称 + price lore
- 点击：检查背包空间 → 检查 coins → 扣币 → 调 WeaponManager.giveWeapon → 关闭 GUI → 发送提示
- 背包满了 → 不扣币，提示"背包已满"
- coins 不足 → 提示"硬币不足 (需要:x 当前:y)"

### 3.2 入口

| 入口 | 触发 |
|------|------|
| `/zr shop` | 玩家主动命令 |
| `PlayerJoinEvent` + `WAITING` 状态 | 延迟 1s 自动弹出 |

### 3.3 GUI 底部栏

最下面一行放控制项：
- 关闭按钮 (barrier item，点击关闭 GUI)

---

## 四、命令汇总

| 命令 | 权限 | 功能 |
|------|------|------|
| `/zr shop` | 无 | 打开枪械商店 |
| `/zr coins add <玩家> <金额>` | zombie.run.admin | 增加硬币 |
| `/zr coins remove <玩家> <金额>` | zombie.run.admin | 扣除硬币 |
| `/zr coins set <玩家> <金额>` | zombie.run.admin | 直接设值 |
| `/zr coins get [玩家]` | zombie.run.admin | 查询硬币 (无参数查自己) |
| `/zr coins top [数量]` | 无 | 硬币排行榜 |
| `/zr weapon create <id> <material> <damage> <price>` | zombie.run.admin | 生成武器配置模板 |

---

## 五、改动清单

| 文件 | 操作 | 内容 |
|------|------|------|
| `manager/WeaponManager.kt` | **新建** | 武器加载、射击、换弹、弹药发放 |
| `manager/CoinManager.kt` | **新建** | SQLite 持久化、缓存、flush |
| `gui/ShopGUI.kt` | **新建** | Inventory GUI 商店 |
| `listener/WeaponListener.kt` | **新建** | 右键射击、换弹交互 |
| `model/WeaponConfig.kt` | **新建** | 武器配置 data class |
| `model/AmmoConfig.kt` | **新建** | 弹药配置 data class |
| `command/ZombieRunCommand.kt` | **修改** | +shop / +coins / +weapon 命令 |
| `manager/MiscManager.kt` | **修改** | 移除 coins/武器相关，精简化 |
| `manager/WeaponHelper.kt` | **删除** | 不再需要 WM 反射 |
| `manager/SQLManager.kt` | **改造** | 重写为 CoinManager，用 SQLite |
| `listener/CombatListener.kt` | **修改** | coins 调用改为 CoinManager (UUID key) |
| `manager/GameManager.kt` | **修改** | 结算奖励改为 CoinManager |
| `listener/GameListener.kt` | **修改** | 枪械发放改为 WeaponManager |
| `ZombieRun.kt` | **修改** | 移除 WM 检测，初始化 CoinManager/WeaponManager |
| `config.yml` | **修改** | +custom-weapons / +ammo-items |
| `plugin.yml` | **修改** | 移除 WeaponMechanics 依赖项声明 |
| `build.gradle.kts` | **修改** | 移除 WeaponMechanics compileOnly |

---

## 六、边界与约束

- PDC key 使用 `NamespacedKey("zombie-run", "weapon_id")` 和 `("zombie-run", "ammo")`
- 武器射击仅限运行中的游戏人类玩家
- 冷却用系统 tick 时间戳，不创建 BukkitRunnable
- coins 异步 UPDATE 用 `CompletableFuture.runAsync`
- CoinManager 的所有 public 方法是同步的（缓存操作），DB 写入异步
- 无 WM 安装时，MiscManager 的 `giveRandomGun` 改为调 WeaponManager
