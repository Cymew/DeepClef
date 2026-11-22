package adris.altoclef.mode;

public enum WorldMode {
    ASSISTANT("assistant", "助手模式"),
    PLAYER("player", "玩家模式");

    public final String id;
    public final String label;

    WorldMode(String id, String label) {
        this.id = id;
        this.label = label;
    }
}
