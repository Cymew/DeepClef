package adris.altoclef.util;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ToggleKeyMapping;
import net.minecraft.client.resources.language.I18n;

import java.util.Locale;
import java.util.Arrays;

/** Bindings advertised by the current client, including mod registrations. */
final class RuntimeBindings {
    static String list(String query) {
        KeyMapping[] bindings = Minecraft.getInstance().options.keyMappings;
        String filter = query.toLowerCase(Locale.ROOT);
        StringBuilder rows = new StringBuilder();
        int matches = 0;
        for (KeyMapping key : bindings) {
            String row = key.getName() + " " + I18n.get(key.getName()) + " " + key.getCategory().id()
                    + " " + key.getTranslatedKeyMessage().getString() + " " + key.saveString();
            if (!row.toLowerCase(Locale.ROOT).contains(filter)) continue;
            if (++matches <= 32) {
                rows.append(row);
                if (key.isUnbound()) rows.append(" 未绑定");
                else if (conflict(key, bindings)) rows.append(" 有冲突");
                rows.append('\n');
            }
        }
        return rows + "匹配 " + matches + " 条，最多显示 32 条，可用 binding 按名称、类别或键位筛选。";
    }

    static boolean conflict(KeyMapping key, KeyMapping[] bindings) {
        return Arrays.stream(bindings).anyMatch(other -> other != key && key.same(other));
    }

    static KeyMapping resolve(String name) {
        KeyMapping[] keys = Minecraft.getInstance().options.keyMappings;
        KeyMapping key = Arrays.stream(keys).filter(k -> k.getName().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("当前客户端没有这个绑定"));
        if (key.isUnbound() || conflict(key, keys)) throw new IllegalArgumentException("键位未绑定或存在冲突");
        return key;
    }

    static void press(KeyMapping key) {
        if (key.isDown()) throw new IllegalArgumentException("键位已有控制者");
        KeyMapping.click(InputConstants.getKey(key.saveString()));
        key.setDown(true);
    }

    static void release(KeyMapping key) {
        key.setDown(false);
        if (key instanceof ToggleKeyMapping && key.isDown()) key.setDown(true);
    }
}
