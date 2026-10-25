package adris.altoclef.control;

import adris.altoclef.AltoClef;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockBreakingCancelEvent;
import adris.altoclef.eventbus.events.BlockBreakingEvent;
import adris.altoclef.util.helpers.SpearHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

public class PlayerExtraController {

    private final AltoClef _mod;
    private BlockPos _blockBreakPos;
    private double _blockBreakProgress;

    public PlayerExtraController(AltoClef mod) {
        _mod = mod;

        EventBus.subscribe(BlockBreakingEvent.class, evt -> onBlockBreak(evt.blockPos, evt.progress));
        EventBus.subscribe(BlockBreakingCancelEvent.class, evt -> onBlockStopBreaking());
    }

    private void onBlockBreak(BlockPos pos, double progress) {
        _blockBreakPos = pos;
        _blockBreakProgress = progress;
    }

    private void onBlockStopBreaking() {
        _blockBreakPos = null;
        _blockBreakProgress = 0;
    }

    public BlockPos getBreakingBlockPos() {
        return _blockBreakPos;
    }

    public boolean isBreakingBlock() {
        return _blockBreakPos != null;
    }

    public double getBreakingBlockProgress() {
        return _blockBreakProgress;
    }

    public boolean inRange(Entity entity) {
        // Broad-phase proximity query used by EntityTracker in every direction.
        // Only tryJab performs the directional/dead-zone attack validation.
        ItemStack hand = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot());
        double reach = SpearHelper.isSpear(hand) ? _mod.getPlayer().getAttackRangeWith(hand).effectiveMaxRange(_mod.getPlayer())
                : _mod.getModSettings().getEntityReachRange();
        return _mod.getPlayer().closerThan(entity, reach);
    }

    public void attack(Entity entity) {
        // 26.3 长矛戳刺（Jab）：必须走专门的 piercingAttack（发送 STAB 动作数据包），
        // 普通 attack() 发送的 AttackPacket 对长矛无效（这就是"点了不出戳刺"的原因）。
        // 右键冲锋则完全由原版物品使用逻辑处理（InputControls.hold(CLICK_RIGHT)）。
        ItemStack hand = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot());
        if (SpearHelper.isSpear(hand)) {
            SpearHelper.tryJab(_mod, entity);
            return;
        }
        if (inRange(entity)) {
            _mod.getController().attack(_mod.getPlayer(), entity);
            _mod.getPlayer().swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        }
    }
}
