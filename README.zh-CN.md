# DeepClef

**MINECRAFT AI AGENT**

[English](README.md) | **简体中文**

## Don't Just Automate. Think.（不只是自动化，而是去思考）

> **提示：** 这是一个 vibe coding 项目——由 AI 辅助、对话驱动迭代开发，可能存在粗糙之处。

DeepClef 是一个运行在 **Java 版 26.3（Wilderness Bound）** + Fabric 上的 AI Minecraft 智能体，基于 [AltoClef](https://github.com/gaucho-matrero/altoclef) 构建。由 **DeepSeek API** 驱动，它会先看自己周围真正有什么，再分步规划，通过原版输入执行动作，并用真实的执行结果核对每一步，而不是相信自己的叙述。

**本次发布 `26.3-M1`。** 这是 DeepClef 第一次能够自己玩游戏：它要度过第一天，自己决定下一件事，记住发生过什么，并和身边的人聊天。

---

## 两种模式，一个世界一种

第一次进入存档或服务器时，DeepClef 会让你选择这个世界采用哪套行为系统：

| | **助手模式** | **玩家模式** |
|---|---|---|
| 空闲时 | 等候你的指令 | 自己挑选下一个目标并行动 |
| `@ds ...` 的含义 | 一个需要规划执行的任务 | 你对它说的话 |
| 多人聊天 | 仅在开启时自动回复（`@ds chat on`） | 默认参与公聊 |
| 适合 | 按需自动化 | 看一个 AI 真正在你的世界里生活 |

选择会绑定到该存档（单人）或该 `host:port`（多人），重新进入、重生、跨维度后继续沿用。关闭选择框则本次手动游玩。以后想更换，请在退出游戏后修改绑定文件——见[选择模式](#选择模式)。

---

## ⚠️ 免责声明

与 AltoClef 一样，DeepClef 会完整接管一个 Minecraft 账号。**请勿在禁止自动化的服务器上使用——可能被封号。** 你需要在允许的环境中（单人游戏或你获得许可的服务器）使用它，并自行承担使用后果。

---

## 版本亮点

- **玩家模式**——一条自主循环：观察 → 带着理由选定一个短目标 → 执行 → 核对 → 记录 → 再决定。本地生存反射、仅可见采集、随身合成、长期项目与原版进度树。
- **能跨越会话的记忆**——本档事实（地点、容器、项目进度、承诺）与跨存档的角色记忆（经验、偏好、思考习惯），由后台整理。
- **预置知识库**——12 张卡片，覆盖第一夜、挖矿、下界、末地与屠龙后的目标，随 JAR 分发，从本地索引召回。
- **运行时泛化**——物品、配方、菜单与机器界面都通过 `altoclef_action` 在运行时发现，新内容或模组内容不需要专门的代码。
- **多人社交行为**——玩家模式下会参与公聊、回答直接提问、跟进群聊，并把求助当作自己在本档的承诺。
- **完整的 26.3 AltoClef 功能**——长矛战斗、带氧气计算的游泳、船与冰道、鞘翅、锻造、多步任务，以及 26.3 的内容移植。

---

## 玩家模式

### 一个回合

它始终有一个当前打算，最多一个身体动作。任务结束、受阻或出现有意义的变化时，它会向模型请求**一个短目标和一个理由**，然后继续。等天亮、等朋友、等材料也是有唤醒条件的活动——不会因为一份计划做完就退回“等指令”的状态。

- **观察**——在同一个眼位沿 32 个水平方向 × 8 个俯仰角采样。近景是 16 格内确实受光的表面、可见生物和交互方块；远景沿罗盘方向延伸到渲染距离与雾。每次查询都重新投射射线，以前见过的树冠只用来提示该往哪看。
- **本地反射层**——每个 tick 在规划器下层运行：头部在水下且剩余氧气不足三分之一时立即上浮（可以打断它自己的计划）；受伤、生命未满或主世界入夜可选原地休整 4 秒；饥饿时在安全的现场吃随身食物。
- **核对结果**——走路对照实际坐标，采集对照真实背包增量，制作对照输出槽与原料消耗。回执只写“已提交”不算成功。
- **决策面板**——右上角叠加层显示当前阶段、选择理由、上一动作回执和聊天状态，`F1` 可隐藏。

### 生存与采集

它从看得见的东西开始。原木、石头、矿石是第一批采收目标：先检查受光面、触及距离和手持工具，再挖掘，并拾取 **8 格内同一个可见掉落物**，途中失去视野就结束。用已有材料时，它会在随身 2×2 网格里一次做一份基础用品，回执分别列出原料减少与成品增加。

感知是刻意设限的：墙后的矿、墙后的生物、没有打开的容器都不会进入模型；寻路只能在身体周围的小盒子里（水平 4 格、向下 2 格、向上 3 格）读取碰撞几何，用来安全迈步和入水。

### 项目与里程碑

每个存档保留三层状态：

1. **生存里程碑**——铁、下界、末地、末影龙、鞘翅、信标，按顺序推进。
2. **一个项目**——最多四个可验收阶段，例如“准备好工作台和木棍，再走完这段探索”。
3. **原版进度树**——客户端已同步的进度与真实父子关系给出“父节点已完成、当前节点可见”的候选。

进度保存在该存档角色目录的 `player_project.json` 中，右上角面板与 `@status` 会显示。物品用掉或离开维度后，已达成的里程碑仍会保留；一个项目连续三次自主尝试没有进展就暂缓，而不是无限重复；主线等待期间会考虑支线（看得见的遗迹线索、试炼刷怪笼等）。

### 朋友与多人

玩家模式默认开启多人聊天。只有两个人时，对方说的每句话都当作对你说的；三人及以上时，普通公聊同样进入考虑范围，由它自己决定搭话还是沉默。私聊会在原频道回复。求助最多排队八条（重复合并），安全时答复，并作为本档承诺保留——交付时用箱子和背包的实际变化核对，而不是口头宣称完成。

### 和它说话

| 指令 | 作用 |
|---|---|
| `@ds 你在做什么？` / `@ds progress` | 当前在做什么、执行到哪一步、上一动作回执 |
| `@ds pause` | 把控制权交给你，停止自主循环 |
| `@ds resume` | 重新读取现场并继续自主行动 |
| `@status` | 模式、坐标、当前项目与里程碑 |
| `@stop`（或 `Ctrl+K`） | 停止全部自动化 |
| `@memory ...` | 查询或更正它记住的内容（见下文） |

在玩家模式下，`@ds` 后面的其他内容都是发给它的留言，而不是任务。

### 开销

玩家的自主决策使用滚动一小时的 **1,000,000 token** 预算，单次决策输出上限 **768 token**。开启多人聊天时，自主选事最多占用 800,000，其余 20% 留给聊天。没有每小时请求次数上限。缺少 Key、接口持续异常或预算耗尽时，它会退回到已配置的本地空闲指令和已知活动，而不是继续空耗请求。

---

## 助手模式

`@ds <自然语言指令>` 通过函数调用规划并执行。模型分步工作，读取每次工具结果后调整。共 **24 个工具**：

| 分组 | 工具 |
|---|---|
| 感知 | `altoclef_screenshot`、`altoclef_look`、`altoclef_entities`、`altoclef_block_at`、`altoclef_status`、`altoclef_inventory`、`altoclef_equipment`、`altoclef_list_resources`、`altoclef_capabilities`、`altoclef_knowledge` |
| 直接动作 | `altoclef_walk`（可选 `auto`/`sneak`/`sprint` 步态）、`altoclef_break`、`altoclef_place` |
| 原子交互 | `altoclef_action`——见[原子交互与运行时泛化](#原子交互与运行时泛化) |
| 技能 | `altoclef_execute`（把任意 `@` 指令当宏任务）、`altoclef_skill`（接管/续做原生目标）、`altoclef_boat`、`altoclef_elytra`、`altoclef_swim`、`altoclef_smithing`、`altoclef_combat` |
| 社交 | `altoclef_chat`（读取 / 状态 / 发送）、`altoclef_feedback`（把开发问题追加到本机反馈文件） |
| 控制 | `altoclef_stop` |

- **插话**——任务运行中再发一条 `@ds` 指令，会送进正在运行的任务，而不是和它冲突。
- **长任务**——`@ds progress` 报告原指令、当前模型步骤、正在执行的工具和原生任务链；`@ds pause` 让你手动接管；`@ds resume` 重新读取位置、背包和界面，完成剩余部分。
- **技能交权**——原生采集或合成目标可以停在某个中途数量，交给你或其他工具处理，再凭同一目标续做；分批交付只在箱子窗口显示“箱内净增 = 背包净减”时记账。
- **画面**——只有确实需要看外观时才传单帧截图，并按所配置端点核验图像能力。

---

## 原子交互与运行时泛化

`altoclef_action` 把玩家手里的底层操作交给模型，因此陌生内容不需要专门写任务：

- `observe`、`keys`、`equip`、`wear`、`aim`、`use_item`、`use_target`、`attack`、`press`、`hold`、`release`
- `open_inventory`、`menu`、`inspect`、`slot_options`、`slot_click`、`button_click`、`close_menu`、`close_screen`
- `recipes`（按产物、按材料，或查当前背包能凑齐什么）、`recipe_place`、`menu_view`、`menu_click`、`menu_scroll`
- `sequence`——2–4 步带检查点的短序列，由客户端逐步执行

关键细节：

- 物品用**运行时栈引用**加真实组件标识，同一个注册 ID 的不同附魔或模组组件不会混淆。
- 配方来自服务器已同步的配方书；布局已知但未解锁时，客户端还能读取本机原版/模组的标准配方 JSON 手工摆放，并以实际产物验收。
- 标准菜单槽位、能接受某叠材料的槽位（`slot_options`）和带悬停文字的标准按钮都走原生接口；完全自绘的面板可以用 `menu_view` 裁出局部图，再用 `menu_click` 点一次，点击前会重新核对窗口、缩放和目标附近像素。
- 每个有副作用的调用都带 `action_id`，重试返回原回执；回执区分 `submitted`、`running`、`observed`、`failed`、`unknown` 和 `interrupted`。世界、窗口或观察变化后旧引用失效，过时的点击不会落到新界面上。

---

## 记忆

两层，边界明确：

- **本档上下文**——*这里*发生过什么：地点、容器、项目阶段、承诺、观察。保存在该存档的 `altoclef/actors/<档案>/`（`dsh_context.json`、`dsh_shots/`、`world_context.sqlite`）。
- **角色记忆**——跨存档带走的东西：带条件与证据的经验、偏好、思考习惯。保存在游戏目录的 `altoclef/memory/<档案>/memory.sqlite`。

具体世界的事实留在本地，只有可泛化的知识才跨档。后台整理每批最多读取八条已核验事件，每个角色每滚动小时最多预留四次请求。`deepseekMemoryReview=false` 关闭云端整理；`deepseekMemoryIdleOnly=true` 只在空闲时整理。

**预置知识库**是其中受控的那部分：12 张卡片，覆盖第一夜、食物与工具、挖矿与基地、下界与要塞、末地与末影龙、屠龙后的目标与支线。它们以只读公共知识随 JAR 分发——不提供坐标，不声称亲眼见过，也不编造战利品。

| 指令 | 用途 |
|---|---|
| `@memory seed <主题或卡片 ID>` | 阅读一张预置卡（例如 `@memory seed 没有床`） |
| `@memory world [关键词]` | 查询本档观察到的事实（支持两字中文） |
| `@memory recall [关键词]` | 查询本角色的跨档经验 |
| `@memory mark <名称>` | 把当前位置标记为本档地标 |
| `@memory note <进度>` | 记录你自己陈述的项目进度 |
| `@memory map [地标]` | 列出本维度已知地点 |
| `@memory status` | 本档积压与后台用量 |
| `@memory forget <ID>` | 撤下一条记忆，并阻止迟到的整理把它恢复 |
| `@memory import-legacy` | 为当前世界/档案导入旧对话历史 |

---

## AI 之外：完整的 26.3 移植

整个 AltoClef 任务系统都适配到了 26.3，不只是模型部分：

- **长矛战斗**——全部七种材质，接近 → 瞄准 → 蓄力 → 突刺 → 后退，按原版射线做真实遮挡检查，半格恢复间距，并支持骑马冲锋。
- **游泳与补氧**——`@swim goto x y z` / `surface x z` / `ascend` / `status`；条件允许时水线疾游；长距离水下目标分“上浮 → 沿水面接近 → 末段下潜”，门内空气与气泡柱可补氧，靠近岩浆潜行。
- **船与冰道**——自动放船、登船、滚动航线连续航行，冰道带漂移与制动的驾驶，靠岸后交还步行。
- **鞘翅**——飞行、降落与烟花经济策略。
- **锻造**——19 种模板、下界合金升级、盔甲纹饰、模板复制。
- **26.3 内容**——苍白橡木与杨木、铜制装备与建筑系列、硫磺系列、枯叶堆、稻草床、坐垫、挽具、可合成马鞍/命名牌/磁石，以及新生物，都作为智能体真正会使用的物品与配方接入。

---

## 安装

1. 为 **Minecraft 26.3** 安装 [Fabric Loader](https://fabricmc.net/use/) **0.19.5+**（需要 Java 25 运行环境）。
2. 从 [Releases](../../releases/latest) 下载 `deepclef-26.3-M1.jar` 并放进 `mods` 文件夹。
3. Baritone 已**内置**——不要再安装第二个 Baritone。
4. 删除旧的 `altoclef-*.jar` 或旧版 DeepClef，避免冲突。**不需要** Fabric API。
5. 启动游戏并进入世界。首次使用 `@ds` 时会弹出窗口要求填写 DeepSeek API Key。

API Key 可在 [platform.deepseek.com](https://platform.deepseek.com/) 获取。Key 只保存在本地设置文件中，只会发送到你配置的 DeepSeek 接口地址。

---

## 快速上手

| 指令 | 作用 |
|---|---|
| `@ds 帮我挖64个铁锭` | 用自然语言下达任务（中英文均可） |
| `@ds 坐船去 x=100 z=200` | 规划并执行乘船航行 |
| `@ds 用鞘翅飞到 1000 200 并降落` | 鞘翅飞行并安全着陆 |
| `@ds 把我的钻石长矛升级为下界合金` | 锻造升级与盔甲纹饰 |
| `@ds chat on` / `off` / `status` | 多人聊天空闲自动应答（助手模式） |
| `@dsh` | 查看 DeepSeek 配置、token 用量与历史 |
| `@dssize 1.2` | 调整思考叠加层字号 |
| `@stop`（或 `Ctrl+K`） | 停止当前任务 |

AltoClef 的经典指令全部可用：`@help`、`@get`、`@goto`、`@food`、`@equip`、`@deposit`、`@follow`、`@hero`、`@boat`、`@elytra`、`@smithing`、`@locate_structure`、`@opendoor`、`@swim`、`@memory`……完整列表见 [usage.md](usage.md)。

支持指令串联，例如 `@get iron_axe; get log 100`。

---

## 选择模式

绑定在进入世界时读取，游戏内不提供切换入口：

- **单人**——`<存档目录>/altoclef/mode.json`，内容为 `{"mode":"assistant"}` 或 `{"mode":"player"}`。
- **多人**——`<游戏目录>/altoclef/server_modes.json`，按规范化地址作键，例如 `{"example.org:25565":"player"}`。改服务器显示名仍命中原绑定；不同端口是不同的条目。

请在世界之外编辑文件后重启。文件损坏时，DeepClef 会显示路径并保持待命，而不是覆盖已有选择。

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
| `deepseekMaxSteps` | `160` | 每次 `@ds` 最多请求主模型的轮数（1–400），同轮的多个工具调用合计一步。 |
| `deepseekOverlayScale` | `0.75` | 思考叠加层字号比例 |
| `deepseekMemoryReview` | `true` | 允许后台整理角色经验与偏好 |
| `deepseekMemoryIdleOnly` | `false` | 只在空闲时执行后台整理 |

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
- 请删除旧的 DeepClef/AltoClef jar；同一实例里放两份智能体会互相争夺控制权

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
