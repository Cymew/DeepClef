package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.AbstractKillEntityTask;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/**
 * The attack style the model (or the player) asked the bot to use.
 *
 * <p>AUTO keeps the balanced default: spear when one is available, sword
 * otherwise, and the shield/strafing logic of {@link PvpCombat}. SWORD, BOW and
 * SPEAR force a single weapon family so the agent can decide the engagement
 * itself instead of letting the automatic pick decide.
 */
public final class CombatPreferences {
    public enum Style { AUTO, SWORD, BOW, SPEAR }

    private static Style _style = Style.AUTO;

    private CombatPreferences() { }

    public static Style getStyle() {
        return _style;
    }

    public static void setStyle(Style style) {
        _style = style == null ? Style.AUTO : style;
    }

    public static Style parse(String raw) {
        if (raw == null || raw.isBlank()) return Style.AUTO;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "sword", "melee", "剑", "近战" -> Style.SWORD;
            case "bow", "ranged", "弓", "弓箭", "远程" -> Style.BOW;
            case "spear", "矛", "长矛" -> Style.SPEAR;
            default -> Style.AUTO;
        };
    }

    public static String describe() {
        return switch (_style) {
            case SWORD -> "sword（剑/近战武器）";
            case BOW -> "bow（弓箭远程）";
            case SPEAR -> "spear（长矛蓄力/戳刺）";
            default -> "auto（有长矛优先长矛，否则剑；被射时举盾走位）";
        };
    }

    /** Best spear in the inventory (or null). */
    public static Item bestSpear(AltoClef mod) {
        if (mod.getPlayer() == null) return null;
        ItemStack held = mod.getPlayer().getMainHandItem();
        Item best = SpearHelper.isSpear(held) ? held.getItem() : null;
        double bestDamage = best == null ? Double.NEGATIVE_INFINITY : SpearHelper.getSpearAttackDamage(held);
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            if (!SpearHelper.isSpear(stack)) continue;
            double damage = SpearHelper.getSpearAttackDamage(stack);
            if (damage > bestDamage) {
                bestDamage = damage;
                best = stack.getItem();
            }
        }
        return best;
    }

    /**
     * Best non-spear melee weapon in the inventory (or null). Used when the
     * style is explicitly SWORD so an equally strong spear is not picked instead.
     */
    public static Item bestMeleeWeapon(AltoClef mod) {
        if (mod.getPlayer() == null) return null;
        ItemStack held = mod.getPlayer().getMainHandItem();
        Item best = null;
        float bestDamage = Float.NEGATIVE_INFINITY;
        if (!SpearHelper.isSpear(held)) {
            float heldDamage = AbstractKillEntityTask.getWeaponAttackDamage(held);
            if (heldDamage > bestDamage) {
                bestDamage = heldDamage;
                best = held.getItem();
            }
        }
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            if (SpearHelper.isSpear(stack)) continue;
            float damage = AbstractKillEntityTask.getWeaponAttackDamage(stack);
            if (damage > bestDamage) {
                bestDamage = damage;
                best = stack.getItem();
            }
        }
        return best;
    }
}
