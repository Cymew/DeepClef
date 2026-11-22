package adris.altoclef.memory;

import java.util.Arrays;
import java.util.Locale;
import java.util.List;

/** The complete vocabulary allowed to cross a world boundary. Raw world facts never enter this type. */
public enum Experience {
    ARRIVAL("技巧", "导航", "到达 坐标 路标 走路 短走 导航 跑图 目的地 寻路 就位", "走到目标后，用实际所在方块核对目标位置。", .45,.12,.05,.12),
    BREATH("技巧", "潜水", "缺氧 氧气 换气 上浮 水下 沉船 潜水 返航 喘气 呼吸", "水下搜索时留意剩余空气，能返回水面时先换气。", .90,.80,.10,.90),
    DIVE_STORY("往事", "潜水", "曾经 以前 脱险 回忆 往事 紧张 缺氧 呛水 潜水 换气", "我曾在水下空气紧张，后来回到水面恢复了呼吸。", .90,.80,.10,.90),
    WRECK_STORY("往事","沉船","沉船 失事船只 船舱 残骸 以前 往事 探船","我曾在标为沉船的地方水下搜箱，后来需要回去换气。",.83,.74,.17,.77),
    DIVE_SEARCH("技巧", "潜水", "沉船 舱室 海底 水下 搜箱 搜索 搜刮 退路", "在水下翻容器时，留意空气，并给返回水面留出时间。", .78,.64,.12,.72),
    CONTAINER("技巧", "容器", "箱子 战利品 物资 箱内 收获 盘点 背包 物品 拿到 库存", "核对物资时要区分容器里看见的物品和自己背包里的增量。", .48,.19,.12,.20),
    CHECK_EXIT("思考习惯", "潜水", "思路 习惯 退路 计划 怎么想 返航", "准备继续下潜前，我习惯先问自己能在哪里换气。", .65,.25,.18,.40),
    CAUTIOUS("偏好", "潜水", "性格 谨慎 偏好 风险 冒险 习性", "有选择余地时，我在陌生水域倾向于留出换气余量。", .65,.25,.18,.40);

    /** Innate, world-independent operating habit, available before the first learned memory. */
    public static final String BASE_EXECUTION_HABIT =
            "熟悉的操作按最近成功的步骤继续，用本次回执里的增减量记进度。界面变化就更新引用，产物或材料变了再检查配方。";

    public enum Signal {
        NONE, ARRIVED, RECOVERED_AIR, CHECKED_INVENTORY, UNDERWATER_CONTAINER, MARKED_WRECK_RECOVERY, CHOSE_EARLY_RETURN, CHOSE_CONTINUE;
        public List<Experience> candidates() {
            return switch (this) {
                case ARRIVED -> List.of(ARRIVAL);
                case RECOVERED_AIR -> List.of(BREATH, DIVE_STORY);
                case CHECKED_INVENTORY -> List.of(CONTAINER);
                case UNDERWATER_CONTAINER -> List.of(DIVE_STORY, DIVE_SEARCH);
                case MARKED_WRECK_RECOVERY -> List.of(WRECK_STORY, DIVE_SEARCH, BREATH);
                case CHOSE_EARLY_RETURN, CHOSE_CONTINUE -> List.of(CHECK_EXIT, CAUTIOUS);
                default -> List.of();
            };
        }
    }

    public final String kind, topic, aliases, body;
    public final double importance;
    Experience(String kind,String topic,String aliases,String body,double consequence,double unexpected,double delight,double emotion) {
        this.kind=kind; this.topic=topic; this.aliases=aliases; this.body=body;
        importance=.40*consequence+.25*unexpected+.15*delight+.20*emotion;
    }
    public boolean disposition() { return this==CHECK_EXIT || this==CAUTIOUS; }
    public int relevance(String query) {
        String q=query.toLowerCase(Locale.ROOT);
        return (int)Arrays.stream((aliases+" "+topic+" "+kind).split(" ")).distinct().filter(q::contains).count();
    }
    public static double strength(double importance, int successes, long elapsedTicks) {
        double initial = Math.min(.99,.32+.58*importance+.09*Math.log1p(successes));
        double halfLife = 72000*(2+8*importance+Math.min(12,successes));
        return initial*Math.pow(.5,Math.max(0,elapsedTicks)/halfLife);
    }
}
