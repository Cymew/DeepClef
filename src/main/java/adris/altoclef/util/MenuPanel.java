package adris.altoclef.util;

import adris.altoclef.mixins.MenuScreenAccess;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.awt.image.BufferedImage;

/** Geometry shared by the image sent to the model and the event sent to the game. */
record MenuPanel(int left, int top, int width, int height, int guiWidth, int guiHeight,
                 int framebufferWidth, int framebufferHeight, int guiScale) {
    static MenuPanel of(AbstractContainerScreen<?> screen) {
        var layout = (MenuScreenAccess) screen;
        var window = Minecraft.getInstance().getWindow();
        return new MenuPanel(layout.deepclefLeft(), layout.deepclefTop(), layout.deepclefPanelWidth(),
                layout.deepclefPanelHeight(), screen.width, screen.height, window.getWidth(), window.getHeight(), window.getGuiScale());
    }

    MenuPanel {
        if (width < 1 || height < 1 || width > 1024 || height > 1024 || left < 0 || top < 0
                || (long) left + width > guiWidth || (long) top + height > guiHeight || framebufferWidth < 1 || framebufferHeight < 1)
            throw new IllegalArgumentException("当前面板未完整显示或超出可用图像尺寸");
    }

    void checkPoint(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) throw new IllegalArgumentException("坐标超出本张面板图片");
    }

    double guiX(int x) { return left + x + 0.5; }
    double guiY(int y) { return top + y + 0.5; }

    private int pixel(NativeImage source, int x, int y) {
        return source.getPixel((int)(guiX(x) * framebufferWidth / guiWidth), (int)(guiY(y) * framebufferHeight / guiHeight));
    }

    private void checkSize(NativeImage source) {
        if (source.getWidth() != framebufferWidth || source.getHeight() != framebufferHeight)
            throw new IllegalArgumentException("画面分辨率已变化，请重新观察菜单");
    }

    BufferedImage crop(NativeImage source) {
        checkSize(source);
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) image.setRGB(x, y, pixel(source, x, y));
        return image;
    }

    boolean sameTarget(BufferedImage before, NativeImage now, int x, int y) {
        checkPoint(x, y);
        checkSize(now);
        for (int row = Math.max(0, y - 8); row < Math.min(height, y + 9); row++) {
            for (int col = Math.max(0, x - 8); col < Math.min(width, x + 9); col++) {
                if (((before.getRGB(col, row) ^ pixel(now, col, row)) & 0xffffff) != 0) return false;
            }
        }
        return true;
    }
}
