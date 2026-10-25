package adris.altoclef.util;

import adris.altoclef.Debug;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public enum MiningRequirement implements Comparable<MiningRequirement> {
    HAND(Items.AIR.getDefaultInstance()), WOOD(Items.WOODEN_PICKAXE.getDefaultInstance()), STONE(Items.STONE_PICKAXE.getDefaultInstance()), IRON(Items.IRON_PICKAXE.getDefaultInstance()), DIAMOND(Items.DIAMOND_PICKAXE.getDefaultInstance()), NETHERITE(Items.NETHERITE_PICKAXE.getDefaultInstance());

    private final ItemStack _minPickaxe;

    MiningRequirement(ItemStack minPickaxe) {
        _minPickaxe = minPickaxe;
    }

    public static MiningRequirement getMinimumRequirementForBlock(Block block) {
        if (block.defaultBlockState().requiresCorrectToolForDrops()) {
            for (MiningRequirement req : MiningRequirement.values()) {
                if (req == MiningRequirement.HAND) continue;
                ItemStack pick = req.getMinimumPickaxe();
                if (pick.isCorrectToolForDrops(block.defaultBlockState())) {
                    return req;
                }
            }
            Debug.logWarning("Failed to find ANY effective tool against: " + block + ". I assume netherite is not required anywhere, so something else probably went wrong.");
            return MiningRequirement.DIAMOND;
        }
        return MiningRequirement.HAND;
    }

    public ItemStack getMinimumPickaxe() {
        return _minPickaxe;
    }

}
