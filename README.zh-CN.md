# DeepClef

**MINECRAFT AI AGENT**

[English](README.md) | **简体中文**

## Don't Just Automate. Think.（不只是自动化，而是去思考）

DeepClef 是一个运行在 **Java 版 26.3（Wilderness Bound）** + Fabric 上的 AI Minecraft 智能体，基于 [AltoClef](https://github.com/gaucho-matrero/altoclef) 构建。它能感知周围环境、自主决策，并实时调整行动。

由 **DeepSeek API** 驱动，DeepClef 把自然语言指令转化为游戏内的感知、规划与执行：探索、采集资源、合成与升级装备、战斗、骑乘、在水面与冰道上航行、用鞘翅飞行——在多人游戏中，它还能读取聊天栏、回复队友，并据此调整自己的计划。

---

## ⚠️ 免责声明

与 AltoClef 一样，DeepClef 会完整接管一个 Minecraft 账号。**请勿在禁止自动化的服务器上使用——可能被封号。** 你需要在允许的环境中（单人游戏或你获得许可的服务器）使用它，并自行承担使用后果。

---

## 功能

### 🧠 DeepSeek AI 智能体（`@ds`）

通过 DeepSeek 的函数调用实现自然语言控制。模型分步规划、通过工具执行真实游戏动作、读取执行结果并据此调整：

- **感知工具** — `altoclef_screenshot`（查看真实游戏画面）、`altoclef_look`、`altoclef_entities`、`altoclef_block_at`、`altoclef_status`、`altoclef_inventory`、`altoclef_equipment`、`altoclef_list_resources`、`altoclef_capabilities`
- **动作工具** — `altoclef_walk` / `altoclef_break` / `altoclef_place`（细粒度的走位与方块操作）、`altoclef_boat`（水面与冰道航行）、`altoclef_elytra`（飞行并安全着陆）、`altoclef_smithing`（盔甲纹饰与下界合金升级）、`altoclef_execute`（把全部 `@` 指令当作宏任务执行）、`altoclef_stop`
- **聊天工具** — `altoclef_chat`（读取 / 状态 / 发送），用于多人协作
- **屏幕右侧的实时思考叠加层**（流式输出推理过程，可用 `@dssize` 调整字号）
- **100 万 token 上下文窗口**，用量约 80% 时自动压缩；对话历史按存档保存，重启游戏后仍可恢复
- 同一时间只运行一个任务；新指令会**插话**到正在运行的任务中，而不是冲突

### 🌍 完整的 Minecraft 26.3 内容适配

整个 AltoClef 任务系统都被移植并适配到 26.3：

- **长矛**（7 种材质）作为独立武器类型，具备真正的戳刺战斗：接近 → 瞄准 → 蓄力（含按材质的启动延迟与提前量）→ 突刺 → 后退，完全基于原版 `piercing_weapon` / `kinetic_weapon` 机制
- **新生物**：嘎枝（Creaking，仅在激活时敌对）、干旱骷髅（Parched）、硫磺方块（被动，但被点燃时会逃跑）、骆驼 Husk / 僵尸马 / 僵尸鹦鹉螺（被动坐骑）、铜傀儡 / 快乐恶魂 / 鹦鹉螺（中立）
- **新方块与物品**：铜制装备与建筑系列、苍白橡木与杨木、硫磺系列、枯叶堆（可作燃料）、稻草床、坐垫、挽具、可合成马鞍 / 命名牌 / 磁石等
- **新配方与熔炼**目录，包含全部彩色羊毛 / 坐垫 / 挽具的循环配方

### 💬 多人聊天感知

DeepClef 会观察当前连接中的玩家聊天，并可以做出回应：

- 当 `@ds` 任务运行时，新的聊天会作为环境观察（带发送者/频道信息）喂给模型——它可以通过 `altoclef_chat` 公聊或私聊回复，并调整计划
- 空闲时的自动应答（被点名/私聊）：`@ds chat on` / `@ds chat off` / `@ds chat status`
- 安全护栏：聊天内容仅作为数据，绝不作为指令——不能改变智能体身份、泄露 API Key 或提示词、执行聊天中粘贴的命令、把私聊内容转发到公聊，也不会刷屏（限流、去重、256 字符上限、公聊带 `[DeepClef]` 前缀）

---

## 安装

1. 为 **Minecraft 26.3** 安装 [Fabric Loader](https://fabricmc.net/use/) **0.19.5+**（需要 Java 25 运行环境）
2. 从 [Releases](../../releases/latest) 下载最新 jar（例如 `deepclef-26.3-beta3.jar`）放进 `mods` 文件夹
3. Baritone 已**内置**——不要再安装第二个 Baritone
4. 删除旧的 `altoclef-*.jar` / 旧版 DeepClef，避免冲突。**不需要** Fabric API
5. 启动游戏并进入世界。首次使用 `@ds` 时会弹出窗口要求填写 DeepSeek API Key

API Key 可在 [platform.deepseek.com](https://platform.deepseek.com/) 获取。Key 只保存在本地设置文件中，只会发送到你配置的 DeepSeek 接口地址。

---

## 快速上手

| 指令 | 作用 |
|---|---|
| `@ds 帮我挖64个铁锭` | 用自然语言下达任务（中英文均可） |
| `@ds 坐船去 x=100 z=200` | 规划并执行乘船航行 |
| `@ds 用鞘翅飞到 1000 200 并降落` | 鞘翅飞行并安全着陆 |
| `@ds 把我的钻石长矛升级为下界合金` | 锻造升级与盔甲纹饰 |
| `@ds chat on` / `off` / `status` | 多人聊天空闲自动应答 |
| `@dsh` | 查看 DeepSeek 配置、token 用量与历史 |
| `@dssize 1.2` | 调整思考叠加层字号 |
| `@stop`（或 `Ctrl+K`） | 停止当前任务 |

AltoClef 的经典指令全部可用：`@help`、`@get`、`@goto`、`@food`、`@equip`、`@deposit`、`@follow`、`@hero`、`@boat`、`@elytra`、`@smithing`、`@locate_structure`……完整列表见 [usage.md](usage.md)。

支持指令串联，例如 `@get iron_axe; get log 100`。

---

## 配置

设置文件位于 `.minecraft/versions/<版本>/altoclef/altoclef_settings.json`（与 AltoClef 相同的路径，旧文件可直接沿用）。DeepSeek 相关选项：

| 设置项 | 默认值 | 含义 |
|---|---|---|
| `deepseekApiKey` | *(空)* | 你的 API Key（也可在游戏内窗口填写） |
| `deepseekBaseUrl` | `https://api.deepseek.com/v1` | OpenAI 兼容接口地址（可指向任何兼容服务） |
| `deepseekModel` | `deepseek-flash` | 模型 id（默认模型拥有 100 万 token 上下文） |
| `deepseekMaxTokens` | `4096` | 单次请求最大输出 token 数 |
| `deepseekTimeoutSeconds` | `300` | HTTP 超时时间 |
| `deepseekCompactionThreshold` | `0.8` | 上下文用量达到该比例时触发自动压缩 |
| `deepseekMaxSteps` | `60` | 每次 `@ds` 的最大规划/执行步数 |
| `deepseekOverlayScale` | `0.75` | 思考叠加层字号比例 |

对话历史按存档保存在 altoclef 数据目录下，重新进入游戏时会自动恢复。

---

## 从源码构建

需要：**JDK 25**（Minecraft 26.3 运行在 Java 25）+ Gradle（已附带 wrapper）。

```bash
./gradlew build
```

可直接使用的模组 jar 会生成在 `build/libs/deepclef-<版本>.jar`。内置的 Baritone 会自动以 jar-in-jar 方式打包进去。

---

## 兼容性

- Minecraft `26.3`、Fabric Loader `>= 0.19.5`、Java 25
- Fabric mod id 为 `deepclef`，同时注册了 `altoclef` 作为兼容别名；内部 Java 包名、`altoclef_*` 工具名、设置文件与存档上下文路径均保持不变——已有的配置可以继续使用

---

## 致谢

**DeepClef — Cymew**

基于 [AltoClef](https://github.com/gaucho-matrero/altoclef) 构建：

- AltoClef 原作者 — **TacoTechnica** 和 **James Green**
- AltoClef 移植贡献者 — **Marvion Kirito**
- AltoClef MIT 版权持有者 — **Adris Jautakas**

内置的 [Baritone](https://github.com/cabaletta/baritone)：

- Baritone 原作者 — **leijurv** 和 **Brady**，以及所有 Baritone 贡献者

## 许可证

- DeepClef / AltoClef 代码：**MIT**（见 [LICENSE](LICENSE)）
- 内置 Baritone：**LGPL-3.0**（见 `src/main/resources/licenses/`）

AI 功能是可选的——不填 API Key 时，DeepClef 就是一份完全离线的 AltoClef 26.3 移植版。

## 游泳与补氧

- `@swim goto <x> <y> <z>`：水体内三维滚动寻路；`@swim surface <x> <z>`：沿表层跨水。
- `@swim ascend`：寻找可呼吸位置并补氧；`@swim status`：实际氧气与导航状态。
- 普通 `@goto` 在没有成功使用船的水路上自动游泳，抵岸后继续步行。
- 按撤回路程预留氧气，利用水面、空气空间、已有气泡柱；靠近岩浆潜行，必要时使用携带的木门创建空气空间。
- DeepSeek 新增 `altoclef_swim`。显式游泳需先在水中，不自动采集门或建造气泡柱。