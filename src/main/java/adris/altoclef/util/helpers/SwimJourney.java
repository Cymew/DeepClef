package adris.altoclef.util.helpers;

/** Travel strategy, independent of native input: surface transit is not completion of an XYZ goal. */
public final class SwimJourney {
    public enum Phase { DIRECT, ASCENDING, SURFACE, DIVING }
    private Phase phase=Phase.DIRECT;
    public Phase phase() {return phase;}
    public boolean considerSurface(double distance,double horizontal,int air,double speed,boolean protectedAir) {
        if(phase!=Phase.DIRECT || protectedAir || horizontal<=8 || fitsDive(distance,air,speed))return false;
        return requestSurface();
    }
    public boolean requestSurface() {
        if(phase==Phase.ASCENDING || phase==Phase.SURFACE)return false;
        phase=Phase.ASCENDING;return true;
    }
    public boolean observeSurface(boolean physicalSurface) {
        if(phase!=Phase.ASCENDING || !physicalSurface)return false;
        phase=Phase.SURFACE;return true;
    }
    public boolean beginDive(boolean complete,double distance,int air,double speed,boolean protectedAir) {
        if(phase!=Phase.SURFACE || !complete || !Double.isFinite(distance)
                || !protectedAir && !fitsDive(distance,air,speed))return false;
        phase=Phase.DIVING;return true;
    }
    /** A checked route is reversible geometrically; allow time for both legs and a turn/arrival margin. */
    public static boolean fitsDive(double distance,int air,double speed) {
        return Double.isFinite(distance) && distance>=0 && 2*distance/Math.max(.06,Math.min(.15,speed))+45<=air;
    }
}
