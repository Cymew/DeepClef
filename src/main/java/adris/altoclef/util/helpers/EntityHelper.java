package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.DamageTypeTags;

import java.util.Objects;

/**
 * Helper functions to interpret entity state
 */
public class EntityHelper {
    public static final double ENTITY_GRAVITY = 0.08; // per second

    public static boolean isAngryAtPlayer(AltoClef mod, Entity entity) {
        return isGenerallyHostileToPlayer(mod, entity);
    }

    public static boolean isGenerallyHostileToPlayer(AltoClef mod, Entity entity) {
        if (entity instanceof TamableAnimal tameable && tameable.getOwnerReference() != null
                && tameable.getOwnerReference().getUUID().equals(mod.getPlayer().getUUID())) return false;
        // 26.3 Creaking：只有被激活（isActive）时才会攻击玩家。它的 AI 不设置原版
        // aggressive 标志位，所以必须显式判断，否则永远检测不到它的敌意。
        if (entity instanceof Creaking creaking) return creaking.isActive();
        // 26.3 SulfurCube（硫磺方块）：本身被动（跟随手持可吸收方块的玩家），只有
        // 吞下 TNT 并被点燃（isPrimed）时才危险——那属于 creeper 式的逃跑逻辑，不视为敌意。
        if (entity instanceof SulfurCube) return false;
        if (entity instanceof Slime slime && mod.getPlayer().closerThan(slime, 3)) return true;
        if (entity instanceof Raider raider && mod.getPlayer().closerThan(raider, 16)) return true;
        if (entity instanceof Warden warden && mod.getPlayer().closerThan(warden, 16)) return true;
        if (entity instanceof Enderman enderman && enderman.hasLineOfSight(mod.getPlayer()) && enderman.isAggressive())
            return true;
        if (entity instanceof Blaze blaze && mod.getPlayer().closerThan(blaze, 3)) return true;
        if (entity instanceof Slime || entity instanceof Raider || entity instanceof Warden
                || entity instanceof Enderman || entity instanceof Blaze) return false;
        // 26.3 新增的骑乘/中立实体都走这里的通用路径：
        // - CamelHusk（被动，背上才是敌对骑手）、ZombieHorse / ZombieNautilus（被动坐骑）、
        //   Nautilus / HappyGhast / CopperGolem（被动/中立）都不会设置 aggressive 标志 -> 不敌对。
        // - Parched（沙漠骷髅，AbstractSkeleton 子类）沿用骷髅的 Melee/Bow 目标，会设置标志 -> 敌对。
        if (entity instanceof Mob mob && !mob.isAggressive()) return false;
        return !isTradingPiglin(entity);
    }

    public static boolean isTradingPiglin(Entity entity) {
        if (entity instanceof Piglin pig) {
            if (pig.getItemBySlot(EquipmentSlot.MAINHAND).getItem().equals(Items.GOLD_INGOT)
                    || pig.getItemBySlot(EquipmentSlot.OFFHAND).getItem().equals(Items.GOLD_INGOT)) {
                // We're trading with this one, ignore it.
                return true;
            }
        }
        return false;
    }

    /**
     * Calculate the resulting damage dealt to a player as a result of some damage.
     * If this player were to receive this damage, the player's health will be subtracted by the resulting value.
     */
    public static double calculateResultingPlayerDamage(Player player, DamageSource source, double damageAmount) {
        // Copied logic from `Player.applyDamage`

        // 26.3: isInvulnerableTo 需要 ServerLevel（读取游戏规则）。客户端预测（如 MLG 落水桶的
        // 坠落伤害估算）时没有 ServerLevel，传 null 会直接 NPE 崩溃。这里只在实际有
        // ServerLevel 时检查；客户端侧退化为创意/旁观检查。
        if (player.level() instanceof ServerLevel serverLevel && player.isInvulnerableTo(serverLevel, source))
            return 0;
        if (player.isCreative() || player.isSpectator())
            return 0;

        // Armor Base
        if (!source.is(DamageTypeTags.BYPASSES_ARMOR)) {
            damageAmount = CombatRules.getDamageAfterAbsorb(player, (float) damageAmount, source, (float) player.getArmorValue(), (float) player.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        }

        // Enchantments & Potions
        if (!source.is(DamageTypeTags.BYPASSES_SHIELD)) {
            float k;
            if (player.hasEffect(MobEffects.RESISTANCE) && source.is(DamageTypes.FELL_OUT_OF_WORLD)) {
                //noinspection ConstantConditions
                k = (player.getEffect(MobEffects.RESISTANCE).getAmplifier() + 1) * 5;
                float j = 25 - k;
                double f = damageAmount * (double) j;
                double g = damageAmount;
                damageAmount = Math.max(f / 25.0F, 0.0F);
            }

            if (damageAmount <= 0.0) {
                damageAmount = 0.0;
            } else {
                k = EnchantmentHelper.getDamageProtection(Objects.requireNonNull(player.level().getServer()).getLevel(player.level().dimension()), player, source);
                if (k > 0) {
                    damageAmount = CombatRules.getDamageAfterMagicAbsorb((float) damageAmount, k);
                }
            }
        }

        // Absorption
        damageAmount = Math.max(damageAmount - player.getAbsorptionAmount(), 0.0F);
        return damageAmount;
    }
}
