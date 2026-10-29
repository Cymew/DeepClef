package adris.altoclef.util;

import java.util.List;
import java.util.Locale;

/** Shared, read-only usage guide for the player and the model. No world or task access. */
public final class AgentCapabilityCatalog {
    private record Entry(String id, String title, String usage, String tool, String details,
                         String example, String aliases) {
        String searchable() {
            return String.join(" ", id, title, usage, tool, details, example, aliases).toLowerCase(Locale.ROOT);
        }
        String row() { return "| " + title + " | " + usage + " | " + details + " |\n"; }
    }

    private static final String RELATED = "配套既有能力（10/17–18）：玩家先手动上马，已上鞍且实际可控制的马科坐骑由普通 @goto x z、@follow 玩家和相对移动接管，支持斜向、跳跃、坡道；不支持骆驼导航，也没有自动上马、驯服或装鞍工具。"
            + "长矛刺击/冲锋、骑马冲锋和骑乘弓箭由现有战斗/防御任务按装备自动执行。"
            + "玩家 PvP 用 `@punk <玩家名>`：近距离长矛或剑盾，远距离切弓，被射时举盾走位。"
            + "没有 @ride、@horse、@spear、@bow、@attack 指令，不要编造攻击或实体交互工具。";

    private static final String SOCIAL_USAGE = "10/25新增：多人聊天与动态合作。运行@ds任务时会感知新玩家聊天，模型用altoclef_chat的read/status/send查询与回复，"
            + "可结合反应调整自己的计划。公聊省略recipient，私聊指定在线玩家名；回复受长度/去重/节流限制，任务检查点不等于完成。"
            + "本地玩家用 `@ds chat on` 开启空闲点名/私聊应答，`@ds chat off` 关闭，`@ds chat status` 查看；默认关闭，@stop或Ctrl+K暂停。"
            + "这些开关由本地玩家输入，模型不得用execute递归调用ds。";

    private static final List<Entry> ENTRIES = List.of(
            new Entry("swimming", "三维游泳与补氧",
                    "`@swim status`；`@swim goto 100 45 200`；`@swim surface 500 200`；`@swim ascend`",
                    "altoclef_swim",
                    "显式游泳需先在水中；普通goto缺船时自动沿表层跨水并接回陆路。仅规划已加载水体，连续跟随三维路线。"
                    + "提前按剩余氧气和撤回路程补氧：水面、门内空气、灵魂沙/岩浆气泡柱；岩浆附近潜行。必要时使用携带的木门，不采集材料。"
                    + "模型水下操作前检查氧气；不要把附魔随机延长或尚未成功放置的门当作已补氧。goto目标需真实水体，不能穿墙。",
                    "@ds 游到水下 X=100 Y=45 Z=200，注意补充氧气", "swim swimming oxygen 游泳 氧气 补氧 水下 潜水 表层 气泡 门"),
            new Entry("boat", "船与竹筏驾驶",
                    "`@boat status`；`@boat board [ID]`；`@boat goto 100 200`；`@boat dismount`",
                    "altoclef_boat",
                    "水路寻路、驾驶与安全下船；到达水上目标后仍留船上。已乘船时普通 @goto 也使用船导航。",
                    "@ds 坐船去 X=100 Z=200", "水路 航行 boat raft"),
            new Entry("nether_portal", "浇筑下界传送门",
                    "`@portal`（前往/浇筑）；`@portal cast`（就地浇筑）；或 `@goto nether`",
                    "altoclef_execute",
                    "主世界的浇筑法：用水桶 + 附近岩浆自动搭浇筑框、舀岩浆、浇水成黑曜石、点火。"
                            + "缺工具时它会自己收集桶、水桶、打火石；**不需要钻石镐**。只有在拿不到水或附近没有岩浆时，"
                            + "才会退回挖黑曜石路线（那时才需要钻石镐）。已有传送门时 @portal 会直接走过去。",
                    "@ds 用水桶浇筑一个下界传送门", "下界 传送门 浇筑 黑曜石 岩浆 水桶 portal nether obsidian"),
            new Entry("auto_boat", "长水路自动放船",
                    "普通 `@goto 100 200`，或 `@boat board` / `@boat goto 100 200`",
                    "altoclef_execute / altoclef_boat",
                    "普通坐标旅行发现至少32格连续水路时尝试；使用背包普通船/筏，或已有合适木板制作。不自动采木；自动放船仅支持安全水面，不能在纯冰面自动放船。指定船ID不会换船。",
                    "@ds 去 X=100 Z=200，遇到长水路时自动放船", "自动造船 自动登船 放置 木板 auto boat"),
            new Entry("boat_recovery", "下船自动回收",
                    "`@boat dismount`，或普通旅行结束后下船",
                    "altoclef_boat",
                    "背包有空间时尝试回收刚驾驶的原空普通船/筏；运输船、被占用、系绳、沉水、创造模式等跳过。没有单独的回收指令。",
                    "@ds 下船，如果能回收就收回这艘船", "回收 拾取 箱船 chest boat recovery recycle"),
            new Entry("ice_boat", "冰路滚动导航",
                    "先放好冰上的船并登船，再 `@boat goto 100 200`；已有船可 `@boat board ID`",
                    "altoclef_boat",
                    "冰、浮冰、蓝冰、霜冰按同层道路规划；中央曲线、惯性预测和滚动扩展自动启用。推进按船头方向；无需逐键操作。纯冰面需玩家先放船。",
                    "@ds 沿这条冰路驾船到 X=100 Z=200", "冰道 滑行 漂移 ice blue packed frosted"),
            new Entry("elytra", "鞘翅起飞、巡航和着陆",
                    "`@elytra status`；`@elytra goto 1000 200`；`@elytra land`",
                    "altoclef_elytra",
                    "已有可用鞘翅和无爆炸烟花才飞；在地面自动装备背包中的鞘翅、起飞、经济推进、三维滚动寻路和目标附近降落，着陆后才完成。不自动采集装备/烟花；短途可拒绝起飞。",
                    "@ds 用鞘翅飞到 X=1000 Z=200 并降落", "烟花 飞行 起降 火箭 elytra flight rocket"),
            new Entry("smithing_upgrade", "下界合金升级",
                    "`@smithing status`；`@smithing upgrade diamond_spear 1`",
                    "altoclef_smithing",
                    "使用升级模板、原钻石装备和下界合金锭；需要时先补模板并保留一份复制种子。count是最终目标成品数量，保留原装备组件。",
                    "@ds 把我的钻石长矛升级为下界合金", "锻造台 钻石 工具 netherite upgrade smithing"),
            new Entry("templates", "模板获取与复制",
                    "`@smithing templates`；`@smithing source silence`；`@smithing get silence 2`；`@smithing duplicate silence 1`",
                    "altoclef_smithing",
                    "支持19种模板来源；get count是最终至少持有数，duplicate count是净增加数且必须已有原模板，按真实工作台配方复制。稀有首份获取有随机性。",
                    "@ds 查一下幽静模板来源，已有模板就复制一份", "模板 存根 template duplicate source silence"),
            new Entry("armor_trim", "盔甲纹饰",
                    "`@smithing trim diamond_helmet silence amethyst_shard 1`",
                    "altoclef_smithing",
                    "给指定盔甲使用纹饰模板和原料；count是最终指定纹饰成品数量，已有同款不会重复消耗。",
                    "@ds 给钻石头盔添加紫水晶幽静纹饰", "装饰 染色 armor trim amethyst"),
            new Entry("pvp", "玩家 PvP 击杀",
                    "`@punk <玩家名> [auto|sword|bow|spear]`",
                    "altoclef_execute",
                    "近距离自动用长矛（poke 蓄力/戳刺）或剑盾近战，目标拉开距离后自动切弓射击；"
                            + "对方拉弓或箭飞来时举盾推进，近战会绕圈走位。第二个参数可强制攻击方式（sword 只用近战武器、"
                            + "bow 只用弓、spear 只用长矛），与 altoclef_combat style 是同一套设置。目标必须在已加载范围"
                            + "（视野/渲染距离）内，30 秒看不到会放弃并说明。只用于允许 PvP 的服务器。",
                    "@ds 用剑杀掉玩家 Steve", "pvp 打架 击杀玩家 punk kill player 单挑"),
            new Entry("combat", "战斗方式与攻击目标",
                    "`altoclef_combat` action=style style=sword|bow|spear|auto；action=attack target=zombie|玩家名；action=stop",
                    "altoclef_combat",
                    "让模型自己决定用剑、弓还是长矛：style=sword 只用近战武器（剑盾+走位），style=bow 用弓箭（拉开距离、找射界），"
                            + "style=spear 用长矛蓄力/戳刺，auto 保持默认（有长矛优先长矛，否则剑；中距离自动切弓只在 auto 下生效）。"
                            + "attack 的 target 可以是玩家名或实体类型（zombie/skeleton/creeper…）；被打时会举盾。目标需在视野内，20 秒找不到会放弃。",
                    "@ds 用剑杀掉附近那只僵尸", "战斗 攻击 武器 剑 弓 长矛 combat style attack sword bow spear"));

    private AgentCapabilityCatalog() { }

    /** A literal keyword filters documentation only; it can never dispatch a command. */
    public static String query(String keyword) {
        String filter = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder("新增功能与用法（更新至2026-10-29；坐标均为绝对值，游泳goto含Y）：\n\n");
        out.append("| 功能 | 使用方法 | 条件与行为 |\n|---|---|---|\n");
        int matches = 0;
        for (Entry entry : ENTRIES) {
            if (!filter.isEmpty() && !entry.searchable().contains(filter)) continue;
            out.append(entry.row()).append("模型：").append(entry.tool()).append("；自然语言：`")
                    .append(entry.example()).append("`\n\n");
            matches++;
        }
        boolean related = filter.isEmpty() || RELATED.toLowerCase(Locale.ROOT).contains(filter)
                || List.of("mounted", "horse", "spear", "bow", "combat").contains(filter);
        boolean social = filter.isEmpty() || SOCIAL_USAGE.toLowerCase(Locale.ROOT).contains(filter)
                || List.of("chat","multiplayer","聊天","多人").contains(filter);
        if (matches == 0 && !related && !social) return "没有匹配的功能。可查询 boat、冰路、鞘翅、游泳、模板、锻造、骑马、聊天或省略keyword查看全部。";
        if (matches == 0) out = new StringBuilder();
        if (related) out.append(RELATED).append('\n');
        if (social) out.append(SOCIAL_USAGE).append('\n');
        return out.toString();
    }

    /** Short routing summary, re-injected with the system prompt even after restoring history. */
    public static String prompt() {
        StringBuilder out = new StringBuilder("【本周功能目录与调用入口】\n");
        for (Entry entry : ENTRIES) out.append("- ").append(entry.title()).append("：")
                .append(entry.tool()).append("。 ").append(entry.details()).append('\n');
        return out.append("用法/条件不确定时先调用 altoclef_capabilities，可用keyword过滤；按清单中的真实工具执行，不得编造新指令。\n")
                .append(RELATED).append('\n')
                .append(SOCIAL_USAGE).append('\n')
                .append("普通顶层坐标goto会优先继续已骑乘的船/可控马；未骑乘且在地面，距离至少256格、可用鞘翅和至少8枚安全烟花时可自动尝试飞行，失败后按实际反馈处理，再考虑长水路自动放船。\n")
                .append("用户明确要求坐船/走冰路时调用altoclef_boat，明确要求飞行用altoclef_elytra；不要用普通goto替代其指定方式。\n")
                .toString();
    }

    public static String markdown() {
        StringBuilder out = new StringBuilder("# 新增功能速查（更新至2026-10-29）\n\n");
        out.append("| 功能 | 使用方法 | 条件与行为 |\n|---|---|---|\n");
        for (Entry entry : ENTRIES) out.append(entry.row());
        out.append("\nDeepSeek直接输入：\n\n");
        for (Entry entry : ENTRIES) out.append("- `").append(entry.example()).append("`\n");
        return out.append("\n").append(RELATED).append("\n\n")
                .append("## 10/25 多人聊天\n\n").append(SOCIAL_USAGE).append("\n\n")
                .append("模型新增只读altoclef_capabilities用法查询，执行仍使用原有船/飞行/锻造工具；每步读取真实结果，船/鞘翅/锻造不要合并成分号链。用法查询不会启动游戏动作，执行动作仍需满足实际条件。\n\n")
                .append("普通goto为自动旅行，可在满足条件时选择骑乘、鞘翅或船，缺船进入水域后沿表层游泳。要固定使用船或鞘翅，请用对应专用命令。飞行中需要换任务时先请求着陆并读结果。\n\n")
                .append("本文件由AgentCapabilityCatalog.markdown()生成，与模型目录共用数据。\n").toString();
    }
}
