package adris.altoclef.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

/** Direct game-mode interactions do not send keyboard input to the chat editor. */
public final class WorldInputScreen {
    private static long revision;
    private static final String session = java.util.UUID.randomUUID().toString();
    private static final java.util.ArrayDeque<String> history = new java.util.ArrayDeque<>(4);

    public static void changed(Object current) {
        if (history.size() == 4) history.removeFirst();
        history.addLast(++revision + "=" + name(current));
    }

    private static String ref() { return session + "-" + revision; }

    static boolean closable(net.minecraft.client.gui.screens.Screen screen) {
        return screen != null && !(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)
                && screen.shouldCloseOnEsc();
    }

    static net.minecraft.client.gui.screens.Screen resolveClose(String observed) {
        var mc = Minecraft.getInstance();
        var screen = mc.gui.screen();
        if (!ref().equals(observed) || !closable(screen) || mc.player == null || !mc.player.isAlive()
                || mc.gui.overlay() != null || mc.player.containerMenu != mc.player.inventoryMenu)
            throw new IllegalArgumentException("界面已变化或不可关闭，请读取当前界面：" + describe());
        if (screen instanceof ChatScreen && screen.children().stream().anyMatch(child ->
                child instanceof net.minecraft.client.gui.components.EditBox edit && !edit.getValue().isEmpty()))
            throw new IllegalArgumentException("聊天栏有未发送的输入，请让玩家处理草稿");
        return screen;
    }

    static boolean allowsDirect(Object screen, Object overlay, boolean inventoryMenu) {
        return overlay == null && inventoryMenu && (screen == null || screen.getClass() == ChatScreen.class);
    }

    static boolean allowsDirect() {
        var mc = Minecraft.getInstance();
        return mc.player != null && !mc.isPaused() && allowsDirect(mc.gui.screen(), mc.gui.overlay(),
                mc.player.containerMenu == mc.player.inventoryMenu);
    }

    static String describe() {
        var mc = Minecraft.getInstance();
        return "screen=" + name(mc.gui.screen()) + " overlay=" + name(mc.gui.overlay())
                + " menu=" + (mc.player == null ? "无" : name(mc.player.containerMenu)) + " paused=" + mc.isPaused()
                + " revision=" + revision + " 最近切换[" + String.join(" → ", history) + "]"
                + (mc.gui.overlay() == null && closable(mc.gui.screen()) ? " screen_ref=" + ref() : "");
    }

    private static String name(Object object) { return object == null ? "无" : object.getClass().getSimpleName(); }
}
