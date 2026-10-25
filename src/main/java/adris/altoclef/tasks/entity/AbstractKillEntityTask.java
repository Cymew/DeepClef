package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.SpearHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;


import java.util.List;

/**
 * Attacks an entity, but the target entity must be specified.
 */
public abstract class AbstractKillEntityTask extends AbstractDoToEntityTask {
    private static final double OTHER_FORCE_FIELD_RANGE = 2;

    // Not the "striking" distance, but the "ok we're close enough, lower our guard for other mobs and focus on this one" range.
    private static final double CONSIDER_COMBAT_RANGE = 10;

    public AbstractKillEntityTask() {
        this(CONSIDER_COMBAT_RANGE, OTHER_FORCE_FIELD_RANGE);
    }

    public AbstractKillEntityTask(double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
        super(combatGuardLowerRange, combatGuardLowerFieldRadius);
    }

    public AbstractKillEntityTask(double maintainDistance, double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
        super(maintainDistance, combatGuardLowerRange, combatGuardLowerFieldRadius);
    }

    public static Item bestWeapon(AltoClef mod) {
        List<ItemStack> invStacks = mod.getItemStorage().getItemStacksPlayerInventory(true);
        if (invStacks.isEmpty()) {
            return null;
        }
        float bestDamage = getWeaponAttackDamage(StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()));
        Item bestItem = null;
        for (ItemStack invStack : invStacks) {
            if (invStack.isEmpty()) continue;
            float itemDamage = getWeaponAttackDamage(invStack);
            if (itemDamage > bestDamage) {
                bestDamage = itemDamage;
                bestItem = invStack.getItem();
            }
        }
        return bestItem;
    }

    /**
     * Compare weapons using the same main-hand attack-damage attributes.
     * WEAPON.itemDamagePerAttack describes durability cost, not attack damage.
     */
    public static float getWeaponAttackDamage(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Float.NEGATIVE_INFINITY;
        if (!SpearHelper.isSpear(stack) && !stack.has(DataComponents.WEAPON)) {
            return Float.NEGATIVE_INFINITY;
        }
        final double[] damage = {1.0};
        stack.forEachModifier(EquipmentSlotGroup.MAINHAND, (attribute, modifier, display) -> {
            if (attribute.is(Attributes.ATTACK_DAMAGE)
                    && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                damage[0] += modifier.amount();
            }
        });
        return (float) damage[0];
    }

    public static boolean equipWeapon(AltoClef mod) {
        Item bestWeapon = bestWeapon(mod);
        Item equipedWeapon = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
        if (bestWeapon != null && bestWeapon != equipedWeapon) {
            mod.getSlotHandler().forceEquipItem(bestWeapon);
            return true;
        }
        return false;
    }

    @Override
    protected Task onEntityInteract(AltoClef mod, Entity entity) {
        // 长矛走专属 poke 节奏逻辑（接近 → 瞄准 → Charge → 刺 → 后退），
        // 不套用下方剑的贴脸攻击/半冷却逻辑。
        if (SpearHelper.isSpear(StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()))) {
            return SpearHelper.tickSpearCombat(mod, entity);
        }
        // Equip weapon
        if (!equipWeapon(mod)) {
            float hitProg = mod.getPlayer().getAttackStrengthScale(0);
            if (hitProg >= 1) {
                if (mod.getPlayer().onGround() || mod.getPlayer().getDeltaMovement().y < 0 || mod.getPlayer().isInWater()) {
                    LookHelper.lookAt(mod, entity.getEyePosition());
                    mod.getControllerExtras().attack(entity);
                }
            }
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        super.onStop(mod, interruptTask);
        // 战斗结束/被中断时释放长矛右键，避免蓄力卡死。
        SpearHelper.stopCharging(mod);
    }
}
