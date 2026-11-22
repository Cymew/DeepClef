package adris.altoclef.memory;

import java.util.*;

/** General observations from one continuous dive. Only these tokens may enter a learned sequence. */
public enum DiveTrace {
    SUBMERGED("潜入水下"), OPEN_CONTAINER("打开水下容器"), LOW_AIR("空气余量变低"),
    CLOSE_CONTAINER("关闭容器"), BREATHABLE("到达可呼吸处"), AIR_RESTORED("空气恢复");

    public final String description;
    DiveTrace(String description) {this.description=description;}
    static final String PREFIX="DIVE-";
    public static String encode(List<DiveTrace> trace) {
        return String.join("-",trace.stream().map(Enum::name).toList());
    }
    public static List<DiveTrace> decode(String encoded) {
        if(encoded==null || encoded.isEmpty()) return List.of();
        return Arrays.stream(encoded.split("-",-1)).map(DiveTrace::valueOf).toList();
    }
    public static boolean supported(List<DiveTrace> trace,List<DiveTrace> chosen) {
        if(chosen.size()<3 || chosen.size()>values().length || chosen.getLast()!=AIR_RESTORED
                || !chosen.contains(LOW_AIR) || !chosen.contains(BREATHABLE)
                || new HashSet<>(chosen).size()!=chosen.size()) return false;
        int index=0;
        for(var step:trace) if(step==chosen.get(index) && ++index==chosen.size()) return true;
        return false;
    }
    static double relevance(String query) {
        return Experience.BREATH.relevance(query)+Experience.DIVE_SEARCH.relevance(query)*.5;
    }
    static String describe(List<DiveTrace> steps) {
        return String.join(" → ",steps.stream().map(step->step.description).toList());
    }
}
