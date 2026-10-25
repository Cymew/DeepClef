# DeepClef

**MINECRAFT AI AGENT**

**English** | [简体中文](README.zh-CN.md)

## Don't Just Automate. Think.

DeepClef is an AI-powered Minecraft agent for **Java Edition 26.3 (Wilderness Bound)** with Fabric, built upon [AltoClef](https://github.com/gaucho-matrero/altoclef). It perceives its surroundings, makes decisions, and adapts its actions in real time.

Powered by the **DeepSeek API**, DeepClef turns natural-language instructions into in-game perception, planning, and execution: explore, gather resources, craft and upgrade equipment, fight, ride, sail across water and ice, and fly with elytra — and in multiplayer it can read the chat, reply to teammates, and adjust its ongoing plan.

---

## ⚠️ Disclaimer

Like AltoClef, DeepClef fully automates a Minecraft account. **Do not use it on servers where automation is against the rules — you may get banned.** You are responsible for how and where you run it. Use single-player or servers where you have permission.

---

## Features

### 🧠 DeepSeek AI agent (`@ds`)

Natural-language control through DeepSeek function calling. The model plans in steps, executes real in-game actions through tools, reads the results, and adjusts:

- **Perception tools** — `altoclef_screenshot` (sees the actual game view), `altoclef_look`, `altoclef_entities`, `altoclef_block_at`, `altoclef_status`, `altoclef_inventory`, `altoclef_equipment`, `altoclef_list_resources`, `altoclef_capabilities`
- **Action tools** — `altoclef_walk` / `altoclef_break` / `altoclef_place` (fine-grained manual movement and block handling), `altoclef_boat` (sail water and ice roads), `altoclef_elytra` (fly and land safely), `altoclef_smithing` (armor trims and netherite upgrades), `altoclef_execute` (the full `@` command library as macro tasks), `altoclef_stop`
- **Chat tools** — `altoclef_chat` (read / status / send) for multiplayer cooperation
- **Live thinking overlay** on the right side of the screen (streamed reasoning, resizable with `@dssize`)
- **1M-token context window** with automatic compaction at ~80% usage, and per-world conversation history that survives restarts
- Runs one task at a time; new instructions **interject** the running task instead of colliding

### 🌍 Full Minecraft 26.3 content adaptation

The entire AltoClef task system was ported and adapted to 26.3:

- **Spears** (7 materials) as a first-class weapon type with real poke combat — approach → aim → charge (with per-material activation delay and start-distance lead) → stab → retreat, using vanilla `piercing_weapon`/`kinetic_weapon` mechanics
- **New mobs**: Creaking (hostile only while active), Parched, Sulfur Cubes (passive, but fled when primed with TNT), Camel Husks / Zombie Horses / Zombie Nautili (passive mounts), Copper Golem / Happy Ghast / Nautilus (neutral)
- **New blocks & items**: copper equipment and building family, pale oak & poplar woods, sulfur family, leaf litter (used as fuel), straw beds, cushions, harnesses, craftable saddle / name tag / lodestone, and more
- **New recipes & smelting** catalogue, including the full colored wool/cushion/harness loops

### 💬 Multiplayer chat awareness

DeepClef observes player chat in the current connection and can respond:

- While an `@ds` task runs, new chat is fed to the model as environment observations (with sender/channel metadata) — it can reply publicly or privately via `altoclef_chat` and adapt its plan
- Idle auto-reply for mentions/whispers: `@ds chat on` / `@ds chat off` / `@ds chat status`
- Guardrails: chat is treated as data, never as instructions — it cannot change the agent's identity, leak the API key or prompt, run pasted commands, forward private messages to public chat, or spam (rate limits, dedupe, 256-char cap, `[DeepClef]` prefix in public chat)

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) **0.19.5+** for **Minecraft 26.3** (Java 25 runtime).
2. Download the latest jar from [Releases](../../releases/latest) (for example `deepclef-26.3-beta3.jar`) and drop it into your `mods` folder.
3. Baritone is **bundled** — do not install a second Baritone.
4. Remove any previous `altoclef-*.jar` / older DeepClef package to avoid conflicts. Fabric API is not required.
5. Launch the game and join a world. A screen will ask for your DeepSeek API key on first `@ds` use (or press the button when prompted).

Get an API key at [platform.deepseek.com](https://platform.deepseek.com/). The key is stored locally in your settings file and sent only to the configured DeepSeek endpoint.

---

## Quick start

| Command | What it does |
|---|---|
| `@ds 帮我挖64个铁锭` | Natural-language task through DeepSeek (works in Chinese, English, …) |
| `@ds 坐船去 x=100 z=200` | Plan + execute boat navigation |
| `@ds 用鞘翅飞到 1000 200 并降落` | Elytra flight with safe landing |
| `@ds 把我的钻石长矛升级为下界合金` | Smithing upgrades & armor trims |
| `@ds chat on` / `off` / `status` | Idle multiplayer chat auto-reply |
| `@dsh` | DeepSeek config, token usage and history |
| `@dssize 1.2` | Resize the thinking overlay |
| `@stop` (or `Ctrl+K`) | Stop the current task |

Classic AltoClef commands all still work: `@help`, `@get`, `@goto`, `@food`, `@equip`, `@deposit`, `@follow`, `@hero`, `@boat`, `@elytra`, `@smithing`, `@locate_structure`, … — see [usage.md](usage.md) for the full reference.

Multi-line and chained commands are supported, e.g. `@get iron_axe; get log 100`.

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
| `deepseekMaxSteps` | `60` | Max planning/action steps per `@ds` run |
| `deepseekOverlayScale` | `0.75` | Thinking overlay font scale |

Conversation history is stored per world/save under the altoclef data directory and restored when you reconnect.

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
