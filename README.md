# DeepClef

**MINECRAFT AI AGENT**

**English** | [简体中文](README.zh-CN.md)

## Don't Just Automate. Think.

> **Note:** This is a vibe coding project — built through AI-assisted, conversation-driven iteration. Expect rough edges.

DeepClef is an AI Minecraft agent for **Java Edition 26.3 (Wilderness Bound)** on Fabric, built upon [AltoClef](https://github.com/gaucho-matrero/altoclef). Powered by the **DeepSeek API**, it perceives what is actually around it, plans in steps, acts through native game inputs, and confirms every action against the real result instead of trusting its own narration.

**Release `26.3-M1`.** The first release in which DeepClef can play by itself: it gets through the first day, decides what to do next, remembers what happened, and talks to the people around it.

---

## Two modes, one per world

The first time you enter a save or a server, DeepClef asks which behaviour system that world should use:

| | **Assistant mode** | **Player mode** |
|---|---|---|
| When idle | Waits for your instructions | Chooses its own next goal and plays |
| `@ds ...` means | A task to plan and execute | Something you say to it |
| Multiplayer chat | Auto-replies only if enabled (`@ds chat on`) | Joins public chat by default |
| Typical use | On-demand automation | Watching an AI actually live in your world |

The choice is bound to that save (single-player) or that `host:port` (multiplayer) and reused after rejoining, respawning or changing dimension. Closing the prompt keeps the world manual for that session. To switch later, edit the binding while out of game — see [Choosing a mode](#choosing-a-mode).

---

## ⚠️ Disclaimer

Like AltoClef, DeepClef fully automates a Minecraft account. **Do not use it on servers where automation is against the rules — you may get banned.** You are responsible for how and where you run it. Use single-player or servers where you have permission.

---

## Highlights

- **Player mode** — an autonomous loop: observe, choose one short goal with a stated reason, act, verify, record, repeat. Local survival reflexes, visible-only gathering, portable crafting, long-running projects and the vanilla advancement tree.
- **Memory that outlives the session** — per-save facts (places, containers, project progress, promises) and a per-character memory that crosses saves (lessons, preferences, thinking habits), consolidated in the background.
- **Seed knowledge** — 12 curated cards about the first night, mining, the Nether, the End and post-dragon goals, shipped inside the jar and recalled from a local index.
- **Runtime generalization** — items, recipes, menus and machine GUIs are discovered at runtime through `altoclef_action`, so new or modded content does not need dedicated code.
- **Multiplayer social behaviour** — in player mode it joins public chat, answers direct questions, follows group conversation, and treats help requests as its own commitments.
- **The full 26.3 AltoClef feature set** — spear combat, oxygen-aware swimming, boats and ice roads, elytra, smithing, multi-step tasks, and the 26.3 content port.

---

## Player mode

### A round of life

The bot always has one current intention and at most one body action. When a task ends, it fails, or something meaningful changes, it asks the model for **one short goal plus a reason**, then repeats. Waiting for dawn, a friend or a material is itself an activity with a wake-up condition — it never falls back to "waiting for orders" just because a plan finished.

- **Observation** — from one eye position it samples 32 horizontal directions × 8 pitches. The near field is 16 blocks of actually lit surfaces, visible mobs and interactive blocks; the far field follows compass directions up to render distance and fog. Every query re-projects rays; a previously seen canopy only suggests where to look.
- **Local reflex layer** — runs every tick under the planner: head underwater with less than a third of its air left triggers an immediate surface (it may interrupt its own plan), damage or low health or nightfall can trigger a four-second rest, hunger eats carried food when the spot is safe.
- **Verified results** — walking is confirmed against actual coordinates, gathering against real inventory deltas, crafting against the output slot and consumed materials. A receipt that only says "submitted" is not a success.
- **Decision panel** — the top-right overlay shows the current stage, the reason for the choice, the last action receipt and the chat state. `F1` hides it.

### Surviving and gathering

It starts from what it can see. Logs, stone and ores are the first harvest targets; it checks the lit face, reach distance and the tool in hand, then mines and picks up **the same visible drop within 8 blocks**, losing it when it leaves sight. With materials it already has, it crafts basic tools in the portable 2×2 grid one batch at a time, and the receipt lists the net inventory change on both sides.

Perception is deliberately bounded: hidden ores, mobs behind walls and unopened containers never enter the model, and navigation may only read collision geometry in a small box around the body (4 blocks horizontally, 2 down, 3 up) to step and swim safely.

### Projects and milestones

Three layers of state are kept per save:

1. **Survival milestones** — iron, the Nether, the End, the dragon, elytra, beacon, in order.
2. **A project** — up to four verifiable stages, e.g. "set up a crafting table and sticks, then finish this stretch of exploration".
3. **The vanilla advancement tree** — the client's synced progress and real parent/child links supply visible candidates whose parent is already done.

Progress lives in `player_project.json` in that save's character directory and is shown by the HUD and `@status`. Milestones stay recorded even if the items are used up or the dimension is left; a project that stalls for three autonomous attempts is postponed instead of repeating forever; side branches (an unfired ancient-city clue, a visible trial spawner) are considered while the main project waits.

### Friends and multiplayer

In player mode, multiplayer chat is on by default. With one other player, everything they say is treated as addressed to the bot; with three or more, public conversation is considered and the bot decides whether to join or stay quiet. Whispers are answered privately. Requests are queued (up to eight, duplicates merged), answered when safe, and kept as commitments in that save — it checks the delivery against chest and inventory changes rather than claiming it is done.

### Talking to it

| Command | Effect |
|---|---|
| `@ds 你在做什么？` / `@ds progress` | What it is doing right now, current step and last receipt |
| `@ds pause` | Hands control to you and stops the autonomous loop |
| `@ds resume` | Re-reads the world and continues on its own |
| `@status` | Mode, position, current project and milestone |
| `@stop` (or `Ctrl+K`) | Stops all automation |
| `@memory ...` | Inspect or correct what it remembers (see below) |

Anything else you type after `@ds` in player mode is a message to the bot, not a task for it.

### Cost

Player decisions run on a rolling one-hour budget of **1,000,000 tokens**, with **768 output tokens** per decision. When multiplayer chat is enabled, autonomous decisions are capped at 800,000 so 20% stays reserved for conversation. There is no fixed requests-per-hour cap. If the key is missing, the endpoint keeps failing or the budget runs out, it falls back to configured local idle commands and known activities instead of burning requests.

---

## Assistant mode

`@ds <instruction>` plans and executes through function calling. The model works in steps, reads each tool result, and adjusts. There are **24 tools**:

| Group | Tools |
|---|---|
| Perception | `altoclef_screenshot`, `altoclef_look`, `altoclef_entities`, `altoclef_block_at`, `altoclef_status`, `altoclef_inventory`, `altoclef_equipment`, `altoclef_list_resources`, `altoclef_capabilities`, `altoclef_knowledge` |
| Direct action | `altoclef_walk` (with `auto`/`sneak`/`sprint` gait), `altoclef_break`, `altoclef_place` |
| Atomic interaction | `altoclef_action` — see [Atomic interaction](#atomic-interaction-and-runtime-generalization) |
| Skills | `altoclef_execute` (any `@` command as a macro), `altoclef_skill` (take over / resume native goals), `altoclef_boat`, `altoclef_elytra`, `altoclef_swim`, `altoclef_smithing`, `altoclef_combat` |
| Social | `altoclef_chat` (read / status / send), `altoclef_feedback` (append a development report to the local feedback file) |
| Control | `altoclef_stop` |

- **Interjection** — sending another `@ds` instruction while a task runs feeds it to the running task instead of colliding with it.
- **Long tasks** — `@ds progress` reports the original instruction, the current model step, the running tool and the native task chain; `@ds pause` lets you take over by hand; `@ds resume` re-reads position, inventory and screen and finishes the remaining work.
- **Skill handoff** — a native gathering or crafting goal can be paused at a partial count, handled by hand or by other tools, then resumed against the same goal; deliveries are booked only when a chest window shows matching net gain and inventory net loss.
- **Vision** — screenshots are sent only when appearance matters, one frame at a time, and the panel checks image capability on the configured endpoint.

---

## Atomic interaction and runtime generalization

`altoclef_action` gives the model the same low-level controls a player has, so unfamiliar content does not need a dedicated task:

- `observe`, `keys`, `equip`, `wear`, `aim`, `use_item`, `use_target`, `attack`, `press`, `hold`, `release`
- `open_inventory`, `menu`, `inspect`, `slot_options`, `slot_click`, `button_click`, `close_menu`, `close_screen`
- `recipes` (by output, by ingredient, or what the inventory can craft), `recipe_place`, `menu_view`, `menu_click`, `menu_scroll`
- `sequence` — 2–4 steps with checkpoints, run one at a time by the client

Details that matter:

- Items are addressed by **runtime stack references** with real components, so the same registry ID with different enchantments or mod components stays distinguishable.
- Recipes come from the server-synced recipe book; when a layout is known but not unlocked, the client can also read standard vanilla/mod recipe JSON and place materials by hand, verifying the actual output.
- Standard menu slots, slots that accept a given stack (`slot_options`) and buttons identified by their hover text are handled natively; a fully self-drawn panel can be cropped with `menu_view` and clicked once with `menu_click`, re-checked against the current window, scale and nearby pixels.
- Every side-effecting call takes an `action_id`; retries return the original receipt, and receipts distinguish `submitted`, `running`, `observed`, `failed`, `unknown` and `interrupted`. References expire when the world, window or observation changes, so a stale click cannot land on a new screen.

---

## Memory

Two layers with a strict boundary:

- **Save context** — what happened *here*: locations, containers, project stages, promises, observations. Stored under the save's `altoclef/actors/<profile>/` (`dsh_context.json`, `dsh_shots/`, `world_context.sqlite`).
- **Character memory** — what carries across saves: verified lessons, preferences, thinking habits, with conditions and evidence. Stored in the game directory under `altoclef/memory/<profile>/memory.sqlite`.

Coarse world facts stay local; only generalizable knowledge crosses saves. Background consolidation reads at most eight verified events per batch and reserves at most four requests per character per rolling hour. `deepseekMemoryReview=false` disables cloud review; `deepseekMemoryIdleOnly=true` only runs it while idle.

**Seed knowledge** is the curated part: 12 cards about the first night, food and tools, mining and bases, the Nether and strongholds, the End and the dragon, post-dragon goals and optional branches. They ship inside the jar as read-only public knowledge — no teleporting, no coordinates, no loot claims.

| Command | Purpose |
|---|---|
| `@memory seed <topic or card id>` | Read a seed card (e.g. `@memory seed 没有床`) |
| `@memory world [keyword]` | Search this save's observed facts (supports 2-character Chinese) |
| `@memory recall [keyword]` | Search this character's cross-save lessons |
| `@memory mark <name>` | Mark your current position as a landmark in this save |
| `@memory note <progress>` | Record project progress you state yourself |
| `@memory map [landmark]` | List this dimension's known places |
| `@memory status` | Backlog and background usage |
| `@memory forget <id>` | Retire one remembered lesson and block late review from reviving it |
| `@memory import-legacy` | Import old conversation history for the current world/profile |

---

## Beyond the AI

The whole AltoClef task system was ported to 26.3, not just the model part:

- **Spear combat** — all seven materials, with approach → aim → charge → jab → retreat, real occlusion checks against the vanilla ray, half-block recovery spacing, and mounted spear charges on a controlled horse.
- **Swimming and oxygen** — `@swim goto x y z` / `surface x z` / `ascend` / `status`; surface sprinting when the water allows it, staged ascent → surface approach → final dive for long underwater goals, doors and bubble columns as air refuges, magma sneaking.
- **Boats and ice roads** — auto placing and boarding, continuous rolling routes, ice-road driving with braking, and handing back to walking on the far bank.
- **Elytra** — flight, landing, and an economy policy for fireworks.
- **Smithing** — all 19 templates, netherite upgrades, armor trims, duplication.
- **26.3 content** — pale oak and poplar woods, copper equipment and building families, sulfur, leaf litter, straw beds, cushions, harnesses, craftable saddle/name tag/lodestone, and the new mobs, as item and recipe entries the agent actually uses.

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) **0.19.5+** for **Minecraft 26.3** (Java 25 runtime).
2. Download `deepclef-26.3-M1.jar` from [Releases](../../releases/latest) and put it into your `mods` folder.
3. Baritone is **bundled** — do not install a second Baritone.
4. Remove any previous `altoclef-*.jar` or older DeepClef package to avoid conflicts. Fabric API is not required.
5. Launch the game and join a world. On first use of `@ds` a screen asks for your DeepSeek API key.

Get a key at [platform.deepseek.com](https://platform.deepseek.com/). The key is stored locally in your settings file and sent only to the configured DeepSeek endpoint.

---

## Quick start

| Command | What it does |
|---|---|
| `@ds 帮我挖64个铁锭` | Natural-language task through DeepSeek (works in Chinese and English) |
| `@ds 坐船去 x=100 z=200` | Plan and execute boat navigation |
| `@ds 用鞘翅飞到 1000 200 并降落` | Elytra flight with a safe landing |
| `@ds 把我的钻石长矛升级为下界合金` | Smithing upgrades and armor trims |
| `@ds chat on` / `off` / `status` | Idle multiplayer auto-reply (assistant mode) |
| `@dsh` | DeepSeek configuration, token usage and history |
| `@dssize 1.2` | Resize the thinking overlay |
| `@stop` (or `Ctrl+K`) | Stop the current task |

Classic AltoClef commands all still work: `@help`, `@get`, `@goto`, `@food`, `@equip`, `@deposit`, `@follow`, `@hero`, `@boat`, `@elytra`, `@smithing`, `@locate_structure`, `@opendoor`, `@swim`, `@memory`, … — see [usage.md](usage.md) for the full reference.

Multi-line and chained commands are supported, e.g. `@get iron_axe; get log 100`.

---

## Choosing a mode

The selection is read on join and is never changed from inside the game:

- **Single-player** — `<save folder>/altoclef/mode.json` with `{"mode":"assistant"}` or `{"mode":"player"}`.
- **Multiplayer** — `<game dir>/altoclef/server_modes.json`, keyed by normalised address, e.g. `{"example.org:25565":"player"}`. Changing the server's display name keeps the same binding; a different port is a different entry.

Edit the file while out of the world and restart. If the file is unreadable, DeepClef reports the path and stays idle instead of overwriting an existing choice.

---

## Configuration

Settings live in `.minecraft/versions/<version>/altoclef/altoclef_settings.json` (same path as AltoClef; existing files keep working). Notable DeepSeek options:

| Setting | Default | Meaning |
|---|---|---|
| `deepseekApiKey` | *(empty)* | Your API key (also settable via the in-game screen) |
| `deepseekBaseUrl` | `https://api.deepseek.com/v1` | OpenAI-compatible endpoint (can point to any compatible provider) |
| `deepseekModel` | `deepseek-flash` | Model id (default has a 1M context window) |
| `deepseekMaxTokens` | `4096` | Max output tokens per request |
| `deepseekTimeoutSeconds` | `300` | HTTP timeout |
| `deepseekCompactionThreshold` | `0.8` | Fraction of context window that triggers automatic compaction |
| `deepseekMaxSteps` | `160` | Foreground model rounds per `@ds` run (1–400). A tool batch counts once. |
| `deepseekOverlayScale` | `0.75` | Thinking overlay font scale |
| `deepseekMemoryReview` | `true` | Allow background consolidation of lessons and preferences |
| `deepseekMemoryIdleOnly` | `false` | Only run background review while the player is idle |

Conversation history is stored per world/save and restored when you reconnect.

---

## Building from source

Requirements: **JDK 25** (Minecraft 26.3 runs on Java 25), Gradle (wrapper included).

```bash
./gradlew build
```

The playable mod jar is produced as `build/libs/deepclef-<version>.jar`. The bundled Baritone is shaded into the jar automatically (jar-in-jar).

---

## Compatibility

- Minecraft `26.3`, Fabric Loader `>= 0.19.5`, Java 25.
- Fabric mod id is `deepclef`; `altoclef` is registered as a compatibility alias, and internal Java packages, `altoclef_*` model-tool names, settings files and saved context paths are unchanged — custom setups keep working.
- Older DeepClef/AltoClef jars should be removed; two copies of the agent in one instance will fight over control.

---

## Credits

**DeepClef — Cymew**

Built upon [AltoClef](https://github.com/gaucho-matrero/altoclef):

- AltoClef original authors — **TacoTechnica** and **James Green**
- AltoClef port contributor — **Marvion Kirito**
- AltoClef MIT copyright holder — **Adris Jautakas**

Bundled [Baritone](https://github.com/cabaletta/baritone):

- Baritone original authors — **leijurv** and **Brady**, with the Baritone contributors

## License

- DeepClef / AltoClef code: **MIT** (see [LICENSE](LICENSE))
- Bundled Baritone: **LGPL-3.0** (see `src/main/resources/licenses/`)

The AI feature is optional — without an API key, DeepClef behaves as a fully-offline 26.3 port of AltoClef.
