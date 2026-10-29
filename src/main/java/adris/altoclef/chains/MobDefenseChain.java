package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.control.KillAura;
import adris.altoclef.tasks.entity.AbstractKillEntityTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.movement.CustomBaritoneGoalTask;
import adris.altoclef.tasks.movement.DodgeProjectilesTask;
import adris.altoclef.tasks.movement.RunAwayFromCreepersTask;
import adris.altoclef.tasks.movement.RunAwayFromHostilesTask;
import adris.altoclef.tasks.movement.RunAwayFromSulfurCubesTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.baritone.CachedProjectile;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.ElytraNavigation;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.ProjectileHelper;
import adris.altoclef.util.helpers.SpearHelper;
import adris.altoclef.util.helpers.MountedNavigation;
import adris.altoclef.util.helpers.BowCombat;
import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.function.Predicate;

/** Detect threats during arbitration; acquire inputs only after this chain wins. */
public class MobDefenseChain extends SingleTaskChain {
    private static final double DANGER_KEEP_DISTANCE = 30;
    private static final double CREEPER_KEEP_DISTANCE = 10;
    private static final double ARROW_KEEP_DISTANCE_HORIZONTAL = 2;
    private static final double ARROW_KEEP_DISTANCE_VERTICAL = 10;
    private static final double SAFE_KEEP_DISTANCE = 8;
    private static final double RECOVERY_THREAT_DISTANCE = 12;
    private static final double ESCAPE_CLEAR_DISTANCE = 14;
    private static final int MIN_ESCAPE_TICKS = 10;
    private static final int THREAT_CLEAR_TICKS = 6;
    private static final int RECOVERY_WAIT_TICKS = 25;

    private enum Action { NONE, FIRE, SHIELD, DODGE, ESCAPE, RECOVER, KILL }
    private enum Escape { HOSTILES, CREEPERS, SULFUR_CUBES }

    private record Plan(Action action, float priority, Vec3 lookAt, BlockPos fire,
                        Escape escape, Class<?> combatClass) {
        private static final Plan NONE = new Plan(Action.NONE, Float.NEGATIVE_INFINITY,
                null, null, null, null);

        private boolean emergency() {
            return action == Action.FIRE || action == Action.SHIELD
                    || action == Action.DODGE || action == Action.ESCAPE;
        }
    }

    private final KillAura _killAura = new KillAura();
    private Plan _plan = Plan.NONE;
    private Action _activeAction = Action.NONE;
    private Escape _activeEscape;
    private float _activePriority;
    private int _actionStartedTick;
    private int _lastProjectileThreatTick = Integer.MIN_VALUE / 2;
    private int _lastEscapeThreatTick = Integer.MIN_VALUE / 2;
    private LocalPlayer _observedPlayer;
    private LocalPlayer _controlPlayer;
    private boolean _controlMounted;
    private Entity _targetEntity;
    private Class<?> _combatClass;
    private KillEntitiesTask _combatTask;
    private CustomBaritoneGoalTask _runAwayTask;
    private Escape _runAwayKind;
    private volatile List<Entity> _recoveryThreats = List.of();
    private boolean _shielding;
    private boolean _pausedInteractions;
    private boolean _wasPuttingOutFire;
    private boolean _doingFunkyStuff;

    public MobDefenseChain(TaskRunner runner) { super(runner); }

    public static double getCreeperSafety(Vec3 pos, Creeper creeper) {
        double distance = creeper.distanceToSqr(pos);
        return creeper.getSwelling(1) <= 0.001f ? distance : distance * 0.2;
    }

    @Override
    public float getPriority(AltoClef mod) { return getPriorityInner(mod); }

    /** Caches data only: in particular, never call Task.isFinished here. */
    public float getPriorityInner(AltoClef mod) {
        _plan = detectPlan(mod);
        return _plan.priority;
    }

    public boolean isEmergencyActionPlanned() { return _plan.emergency(); }

    private Plan detectPlan(AltoClef mod) {
        if (!AltoClef.inGame() || (ElytraNavigation.ownsFlight(mod) || adris.altoclef.util.helpers.SwimNavigation.needsPriority(mod)) || !mod.getModSettings().isMobDefense()) return Plan.NONE;
        LocalPlayer player = mod.getPlayer();
        int tick = player.tickCount;
        if (_observedPlayer != player) {
            _observedPlayer = player;
            _lastProjectileThreatTick = Integer.MIN_VALUE / 2;
            _lastEscapeThreatTick = Integer.MIN_VALUE / 2;
            _recoveryThreats = List.of();
        }
        BlockPos fire = isInsideFireAndOnFire(mod);
        if (fire != null) return new Plan(Action.FIRE, 90, null, fire, null, null);

        Creeper creeper = getClosestFusingCreeper(mod);
        if (creeper != null) {
            if (canShield(mod)) {
                return new Plan(Action.SHIELD, Math.min(99, 80 + creeper.getSwelling(1) * 19),
                        creeper.getEyePosition(), null, null, null);
            }
            return escapePlan(tick, Escape.CREEPERS, Math.min(99, 80 + creeper.getSwelling(1) * 19));
        }
        if (getClosestPrimedSulfurCube(mod) != null) return escapePlan(tick, Escape.SULFUR_CUBES, 85);

        Vec3 incomingProjectile = mod.getModSettings().isDodgeProjectiles() ? getClosestProjectilePosition(mod) : null;
        boolean projectileThreat = incomingProjectile != null;
        if (projectileThreat) _lastProjectileThreatTick = tick;
        boolean finishingDodge = _controlPlayer == player && _activeAction == Action.DODGE
                && (tick - _actionStartedTick < MIN_ESCAPE_TICKS
                || tick - _lastProjectileThreatTick < THREAT_CLEAR_TICKS);
        if (projectileThreat || finishingDodge) {
            if (!finishingDodge && canShield(mod)) {
                return new Plan(Action.SHIELD, 70, incomingProjectile, null, null, null);
            }
            return new Plan(Action.DODGE, 70, null, null, null, null);
        }

        List<Entity> hostiles = getThreateningHostiles(mod);
        boolean closeThreat = hostiles.stream().anyMatch(entity -> entity.closerThan(player, RECOVERY_THREAT_DISTANCE));
        Optional<Entity> creaking = mod.getEntityTracker().getNearestEntity(Creaking.class);
        if (creaking.isPresent() && creaking.get() instanceof Creaking active && active.isActive()
                && active.closerThan(player, SAFE_KEEP_DISTANCE)) return escapePlan(tick, Escape.HOSTILES, 75);
        // Hunger or damage alone is not a reason to flee an empty area. A current
        // attack target must not prevent abandoning combat when a real threat exists.
        if (closeThreat && (wantsFoodRecovery(mod) && mod.getFoodChain().needsToEatCritical() || player.getHealth() <= 10
                || isInDanger(mod, hostiles))) return escapePlan(tick, Escape.HOSTILES, 75);

        // Geometry replaces side-effecting goal queries while deciding whether
        // the selected escape has had enough time and room to finish.
        if (_controlPlayer == player && _activeAction == Action.ESCAPE
                && (tick - _actionStartedTick < MIN_ESCAPE_TICKS
                || tick - _lastEscapeThreatTick < THREAT_CLEAR_TICKS
                || isEscapeThreatClose(mod, hostiles))) {
            return new Plan(Action.ESCAPE, _activePriority, null, null, _activeEscape, null);
        }
        boolean needsRecoveryFood = wantsFoodRecovery(mod);
        if (_controlPlayer == player && needsRecoveryFood
                && (_activeAction == Action.ESCAPE || _activeAction == Action.DODGE
                || _activeAction == Action.SHIELD || _activeAction == Action.FIRE
                || _activeAction == Action.RECOVER && tick - _actionStartedTick < RECOVERY_WAIT_TICKS)) {
            if (_activeAction != Action.RECOVER && getRecoveryThreats(mod, hostiles).stream()
                    .anyMatch(entity -> entity.closerThan(player, DANGER_KEEP_DISTANCE))) {
                return escapePlan(tick, Escape.HOSTILES, 75);
            }
            // Food needs a quiet observation window after an escape. Letting a
            // kill task approach immediately would trigger another escape before
            // that window can elapse. This plan is deliberately not an emergency.
            return new Plan(Action.RECOVER, 65, null, null, null, null);
        }
        if (mod.getModSettings().shouldDealWithAnnoyingHostiles()) {
            List<Entity> toDealWith = hostiles.stream().filter(entity -> !(entity instanceof Creaking)
                    && !mod.getBehaviour().shouldExcludeFromForcefield(entity)).toList();
            if (!toDealWith.isEmpty()) {
                if (!canDealWith(mod, toDealWith)) return escapePlan(tick, Escape.HOSTILES, 80);
                return new Plan(Action.KILL, 65, null, null, null, chooseCombatClass(mod, toDealWith));
            }
        }
        return Plan.NONE;
    }

    private Plan escapePlan(int tick, Escape kind, float priority) {
        _lastEscapeThreatTick = tick;
        return new Plan(Action.ESCAPE, priority, null, null, kind, null);
    }

    private boolean wantsFoodRecovery(AltoClef mod) {
        return mod.getModSettings().isAutoEat() && !mod.getFoodChain().isShouldStop()
                && (mod.getFoodChain().needsToEat() || mod.getFoodChain().needsToEatCritical());
    }

    private boolean canShield(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        // Sneaking while riding would dismount; main-hand food would consume the
        // right-click intended for the shield. Choose the movement/safe-stop plan.
        return !player.isPassenger() && !SpearHelper.isSpear(player.getMainHandItem())
                && !player.getMainHandItem().has(DataComponents.FOOD) && player.getHealth() > 10
                && !mod.getEntityTracker().entityFound(AbstractThrownPotion.class)
                && (mod.getItemStorage().hasItem(Items.SHIELD) || mod.getItemStorage().hasItemInOffhand(Items.SHIELD))
                && !player.getCooldowns().isOnCooldown(Items.SHIELD.getDefaultInstance())
                && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()
                && _activeAction != Action.DODGE && _activeAction != Action.ESCAPE;
    }

    private boolean isEscapeThreatClose(AltoClef mod, List<Entity> hostiles) {
        if (_activeEscape == Escape.HOSTILES) {
            if (wantsFoodRecovery(mod)) {
                return getRecoveryThreats(mod, hostiles).stream()
                        .anyMatch(entity -> entity.closerThan(mod.getPlayer(), DANGER_KEEP_DISTANCE));
            }
            return hostiles.stream().anyMatch(entity -> entity.closerThan(mod.getPlayer(), ESCAPE_CLEAR_DISTANCE));
        }
        if (_activeEscape == Escape.CREEPERS) {
            return mod.getEntityTracker().getTrackedEntities(Creeper.class).stream()
                    .anyMatch(entity -> entity.isAlive() && entity.closerThan(mod.getPlayer(), CREEPER_KEEP_DISTANCE));
        }
        return getClosestPrimedSulfurCube(mod) != null;
    }

    private List<Entity> getThreateningHostiles(AltoClef mod) {
        List<Entity> result = new ArrayList<>();
        // Snapshot copy: EntityTracker mutates its hostile list from other threads.
        for (Entity entity : new ArrayList<>(mod.getEntityTracker().getHostiles())) {
            if (entity instanceof Mob && entity.isAlive() && !entity.isRemoved()
                    && EntityHelper.isAngryAtPlayer(mod, entity)) result.add(entity);
        }
        return result;
    }

    private List<Entity> getRecoveryThreats(AltoClef mod, List<Entity> currentHostiles) {
        LinkedHashSet<Entity> tracked = new LinkedHashSet<>();
        for (Entity entity : _recoveryThreats) {
            if (isRecoveryThreatValid(mod, entity)) tracked.add(entity);
        }
        for (Entity entity : currentHostiles) {
            if (isRecoveryThreatValid(mod, entity)) tracked.add(entity);
        }
        return List.copyOf(tracked);
    }

    private boolean isRecoveryThreatValid(AltoClef mod, Entity entity) {
        if (!entity.isAlive() || entity.isRemoved() || entity.level() != mod.level()) return false;
        // EntityHelper intentionally recognizes these classes only inside its
        // short proximity radius. Once observed as a threat, crossing that radius
        // must not discard the entity before the 30-block recovery goal is met.
        if (entity instanceof Slime || entity instanceof Blaze || entity instanceof Raider || entity instanceof Warden) return true;
        return EntityHelper.isAngryAtPlayer(mod, entity);
    }

    /** Keeps real threat references after EntityTracker's hostile list clips at 16. */
    private final class RecoveryEscapeTask extends CustomBaritoneGoalTask {
        @Override
        protected Goal newGoal(AltoClef mod) {
            return new Goal() {
                private double distanceFromBlock(Entity entity, int x, int y, int z) {
                    Vec3 pos = entity.position();
                    double dx = Math.max(Math.max(x - pos.x, 0), pos.x - (x + 1));
                    double dy = Math.max(Math.max(y - pos.y, 0), pos.y - (y + 1));
                    double dz = Math.max(Math.max(z - pos.z, 0), pos.z - (z + 1));
                    return Math.sqrt(dx * dx + dy * dy + dz * dz);
                }

                @Override
                public boolean isInGoal(int x, int y, int z) {
                    for (Entity threat : _recoveryThreats) {
                        if (threat.isAlive() && !threat.isRemoved()
                                && distanceFromBlock(threat, x, y, z) < DANGER_KEEP_DISTANCE) return false;
                    }
                    return true;
                }

                @Override
                public double heuristic(int x, int y, int z) {
                    double nearest = Double.POSITIVE_INFINITY;
                    for (Entity threat : _recoveryThreats) {
                        if (threat.isAlive() && !threat.isRemoved()) {
                            nearest = Math.min(nearest, distanceFromBlock(threat, x, y, z));
                        }
                    }
                    return Math.max(0, DANGER_KEEP_DISTANCE - nearest) * 4;
                }
            };
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            // Real position governs task completion. The path goal checks the
            // entire node block, so rounding cannot finish a path while the
            // player is still just inside the recovery distance.
            return _recoveryThreats.stream().noneMatch(entity -> entity.isAlive() && !entity.isRemoved()
                    && entity.closerThan(mod.getPlayer(), DANGER_KEEP_DISTANCE));
        }

        @Override
        protected boolean isEqual(Task other) { return other instanceof RecoveryEscapeTask; }

        @Override
        protected String toDebugString() { return "Make room for a safe meal"; }
    }

    private boolean canDealWith(AltoClef mod, List<Entity> hostiles) {
        ItemStack held = mod.getPlayer().getMainHandItem();
        ItemStack expectedWeapon = held;
        float bestWeaponDamage = AbstractKillEntityTask.getWeaponAttackDamage(held);
        // Match bestWeapon: inspect actual stacks, retain the held weapon on a
        // damage tie, and replace it only for a strictly stronger inventory item.
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            float damage = AbstractKillEntityTask.getWeaponAttackDamage(stack);
            if (damage > bestWeaponDamage) {
                bestWeaponDamage = damage;
                expectedWeapon = stack;
            }
        }
        if (!Float.isFinite(bestWeaponDamage)) bestWeaponDamage = 0;
        // Already-held spears stay in their dedicated task branch, even if a
        // stronger sword exists. Neither that case nor a predicted spear permits
        // budgeting the offhand shield as if it were a sword fight.
        boolean hasShield = !mod.getPlayer().isPassenger() && !SpearHelper.isSpear(held) && !SpearHelper.isSpear(expectedWeapon)
                && (mod.getItemStorage().hasItem(Items.SHIELD) || mod.getItemStorage().hasItemInOffhand(Items.SHIELD));
        int capacity = (int) Math.ceil(mod.getPlayer().getArmorValue() * 3.6 / 20.0
                + bestWeaponDamage * 0.8 + (hasShield ? 20 : 0)) + 1;
        if (MountedNavigation.getControlledHorse(mod.getPlayer()) != null && BowCombat.canUse(mod)) {
            capacity = Math.max(capacity, 2);
        }
        // Retain the old small-slime allowance without making an unrelated crowd harmless.
        long count = hostiles.stream().filter(entity -> !(entity instanceof Slime)).count();
        if (count == 0) count = 1;
        return capacity > count;
    }

    private Class<?> chooseCombatClass(AltoClef mod, List<Entity> hostiles) {
        // EntityTracker indexes exact runtime classes. Retain a live class rather
        // than replacing the whole kill task when entity iteration order changes.
        if (_combatClass != null && hostiles.stream().anyMatch(entity -> entity.getClass() == _combatClass)) return _combatClass;
        if (_targetEntity != null && hostiles.contains(_targetEntity)) return _targetEntity.getClass();
        Entity closest = hostiles.stream().min((a, b) -> Double.compare(
                a.distanceToSqr(mod.getPlayer()), b.distanceToSqr(mod.getPlayer()))).orElseThrow();
        return closest.getClass();
    }

    private boolean isInDanger(AltoClef mod, List<Entity> hostiles) {
        LocalPlayer player = mod.getPlayer();
        if (player.hasEffect(MobEffects.WITHER) || player.hasEffect(MobEffects.POISON)) return true;
        int armor = player.getArmorValue();
        float health = player.getHealth();
        boolean vulnerable = armor <= 15 && health < 3 || armor < 10 && health < 10 || armor < 5 && health < 18;
        return vulnerable && hostiles.stream().anyMatch(entity -> entity.closerThan(player, SAFE_KEEP_DISTANCE));
    }

    @Override
    protected void onTick(AltoClef mod) {
        if (!AltoClef.inGame()) return;
        Plan selected = _plan;
        boolean changed = _controlPlayer != mod.getPlayer() || _controlMounted != mod.getPlayer().isPassenger()
                || selected.action != _activeAction
                || selected.action == Action.ESCAPE && selected.escape != _activeEscape
                || selected.action == Action.KILL && selected.combatClass != _combatClass;
        if (changed) {
            // Stop the old task and its Baritone child before acquiring any new
            // keys, including Kill -> Dodge transitions inside this same chain.
            setTask(null);
            releaseOwnedControls(mod);
            SpearHelper.stopCharging(mod);
            _controlPlayer = mod.getPlayer();
            _controlMounted = mod.getPlayer().isPassenger();
            _activeAction = selected.action;
            _activeEscape = selected.escape;
            _actionStartedTick = mod.getPlayer().tickCount;
        }
        _activePriority = selected.priority;
        _doingFunkyStuff = selected.emergency();
        if (selected.emergency()) _recoveryThreats = getRecoveryThreats(mod, getThreateningHostiles(mod));
        if (selected.action == Action.KILL) {
            if (_combatTask == null || _combatClass != selected.combatClass) {
                _combatClass = selected.combatClass;
                Predicate<Entity> valid = entity -> entity instanceof Mob && !(entity instanceof Creaking)
                        && !mod.getBehaviour().shouldExcludeFromForcefield(entity)
                        && EntityHelper.isAngryAtPlayer(mod, entity);
                _combatTask = new KillEntitiesTask(valid, _combatClass);
            }
            setTask(_combatTask);
            super.onTick(mod);
        } else if (selected.action == Action.FIRE) {
            putOutFire(mod, selected.fire);
        } else if (selected.action == Action.SHIELD) {
            if (selected.lookAt != null) LookHelper.lookAt(mod, selected.lookAt);
            startShielding(mod);
        } else if (selected.action == Action.RECOVER) {
            mod.getClientBaritone().getPathingBehavior().requestPause();
        } else if (selected.action == Action.DODGE || selected.action == Action.ESCAPE) {
            // Controlled horses now use the common mounted goal adapter. Other
            // vehicles still cannot run a player's walking escape process.
            if (mod.getPlayer().isPassenger() && MountedNavigation.getControlledHorse(mod.getPlayer()) == null) {
                mod.getClientBaritone().getPathingBehavior().requestPause();
                return;
            }
            if (selected.action == Action.DODGE) {
                if (!(_runAwayTask instanceof DodgeProjectilesTask)) {
                    _runAwayTask = new DodgeProjectilesTask(ARROW_KEEP_DISTANCE_HORIZONTAL, ARROW_KEEP_DISTANCE_VERTICAL);
                    _runAwayKind = null;
                }
            } else if (selected.escape == Escape.HOSTILES && wantsFoodRecovery(mod)) {
                if (!(_runAwayTask instanceof RecoveryEscapeTask)) {
                    _runAwayTask = new RecoveryEscapeTask();
                    _runAwayKind = selected.escape;
                }
            } else if (_runAwayTask == null || _runAwayTask instanceof DodgeProjectilesTask
                    || _runAwayTask instanceof RecoveryEscapeTask || _runAwayKind != selected.escape) {
                _runAwayTask = switch (selected.escape) {
                    case CREEPERS -> new RunAwayFromCreepersTask(CREEPER_KEEP_DISTANCE);
                    case SULFUR_CUBES -> new RunAwayFromSulfurCubesTask(SAFE_KEEP_DISTANCE);
                    case HOSTILES -> new RunAwayFromHostilesTask(DANGER_KEEP_DISTANCE, true);
                };
                _runAwayKind = selected.escape;
            }
            setTask(_runAwayTask);
            super.onTick(mod);
        }
    }

    private void startShielding(AltoClef mod) {
        if (mod.getPlayer().isPassenger() || SpearHelper.isSpear(mod.getPlayer().getMainHandItem())) return;
        if (!mod.getItemStorage().hasItemInOffhand(Items.SHIELD)) {
            mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
            return;
        }
        _shielding = true;
        mod.getInputControls().hold(Input.SNEAK);
        mod.getInputControls().hold(Input.CLICK_RIGHT);
        mod.getClientBaritone().getPathingBehavior().requestPause();
        mod.getExtraBaritoneSettings().setInteractionPaused(true);
        _pausedInteractions = true;
    }

    private void releaseOwnedControls(AltoClef mod) {
        _killAura.stopShielding(mod);
        if (_shielding) {
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.CLICK_RIGHT);
            if (AltoClef.inGame() && mod.getPlayer().isBlocking()) mod.getController().releaseUsingItem(mod.getPlayer());
            _shielding = false;
        }
        if (_wasPuttingOutFire) {
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            _wasPuttingOutFire = false;
        }
        if (_pausedInteractions) {
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
            _pausedInteractions = false;
        }
        _doingFunkyStuff = false;
    }

    @Override
    public void onInterrupt(AltoClef mod, TaskChain other) {
        super.onInterrupt(mod, other);
        releaseOwnedControls(mod);
        SpearHelper.stopCharging(mod);
        _activeAction = Action.NONE;
        _controlPlayer = null;
        _controlMounted = false;
    }

    @Override
    protected void onStop(AltoClef mod) {
        super.onStop(mod);
        releaseOwnedControls(mod);
        SpearHelper.stopCharging(mod);
        _activeAction = Action.NONE;
        _controlPlayer = null;
        _controlMounted = false;
        _observedPlayer = null;
        _runAwayTask = null;
        _runAwayKind = null;
        _recoveryThreats = List.of();
        _combatTask = null;
        _combatClass = null;
        _targetEntity = null;
        _plan = Plan.NONE;
        _lastProjectileThreatTick = Integer.MIN_VALUE / 2;
        _lastEscapeThreatTick = Integer.MIN_VALUE / 2;
    }

    @Override
    public void onTickFinished(AltoClef mod, TaskChain selected) {
        if (!AltoClef.inGame() || (ElytraNavigation.ownsFlight(mod) || adris.altoclef.util.helpers.SwimNavigation.needsPriority(mod)) || !mod.getModSettings().isMobDefense()
                || !(selected instanceof UserTaskChain) || SpearHelper.isCombatActive()
                || _targetEntity != null || mod.getPlayer().isUsingItem()
                || selected.getTasks().stream().anyMatch(task -> task instanceof AbstractKillEntityTask)) return;
        // Borrow only an otherwise idle user's attack, without shield keys that
        // would outlive a chain this forcefield does not own.
        doForceField(mod);
    }

    private BlockPos isInsideFireAndOnFire(AltoClef mod) {
        if (!mod.getPlayer().isOnFire()) return null;
        BlockPos center = mod.getPlayer().blockPosition();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos check = center.offset(x, 0, z);
                if (mod.level().getBlockState(check).getBlock() instanceof BaseFireBlock) return check;
            }
        }
        return null;
    }

    private void putOutFire(AltoClef mod, BlockPos pos) {
        _wasPuttingOutFire = true;
        Optional<Rotation> reach = LookHelper.getReach(pos);
        if (reach.isEmpty()) return;
        Baritone baritone = mod.getClientBaritone();
        baritone.getPathingBehavior().requestPause();
        if (LookHelper.isLookingAt(mod, pos)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
        } else {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            LookHelper.lookAt(mod, reach.get());
        }
    }

    private void doForceField(AltoClef mod) {
        _killAura.tickStart();
        for (Entity entity : mod.getEntityTracker().getCloseEntities()) {
            if (mod.getBehaviour().shouldExcludeFromForcefield(entity)) continue;
            boolean shouldForce = entity instanceof Mob && EntityHelper.isGenerallyHostileToPlayer(mod, entity)
                    && LookHelper.seesPlayer(entity, mod.getPlayer(), 10);
            if (entity instanceof Fireball) shouldForce = true;
            if (entity instanceof Player player && !player.equals(mod.getPlayer())
                    && mod.getBehaviour().shouldForceFieldPlayers()
                    && !mod.getButler().isUserAuthorized(player.getName().getString())) shouldForce = true;
            if (shouldForce) _killAura.applyAura(entity);
        }
        _killAura.tickEnd(mod, false);
    }

    private Creeper getClosestFusingCreeper(AltoClef mod) {
        Creeper result = null;
        double worstSafety = Double.POSITIVE_INFINITY;
        for (Creeper creeper : mod.getEntityTracker().getTrackedEntities(Creeper.class)) {
            if (!creeper.isAlive() || creeper.getSwelling(1) <= 0.001f
                    || !creeper.closerThan(mod.getPlayer(), CREEPER_KEEP_DISTANCE)) continue;
            double safety = getCreeperSafety(mod.getPlayer().position(), creeper);
            if (safety < worstSafety) {
                result = creeper;
                worstSafety = safety;
            }
        }
        return result;
    }

    private SulfurCube getClosestPrimedSulfurCube(AltoClef mod) {
        return mod.getEntityTracker().getTrackedEntities(SulfurCube.class).stream()
                .filter(cube -> cube.isAlive() && cube.isPrimed() && cube.closerThan(mod.getPlayer(), SAFE_KEEP_DISTANCE))
                .min((a, b) -> Double.compare(a.distanceToSqr(mod.getPlayer()), b.distanceToSqr(mod.getPlayer())))
                .orElse(null);
    }

    public static boolean isProjectileApproaching(Vec3 projectilePos, Vec3 velocity, Vec3 playerPos) {
        return velocity.dot(playerPos.subtract(projectilePos)) > 0.25;
    }

    /** Only reads trajectory samples: no facing, pause or equipment changes. */
    private Vec3 getClosestProjectilePosition(AltoClef mod) {
        Vec3 playerPos = mod.getPlayer().position();
        Vec3 closest = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        try {
            for (CachedProjectile projectile : mod.getEntityTracker().getProjectiles()) {
                if (projectile == null || projectile.position.distanceToSqr(playerPos) >= 150
                        || projectile.projectileType == Fireball.class || projectile.projectileType == DragonFireball.class) continue;
                // Negative projection is already moving away. The previous
                // abs(dot) test accidentally treated these as incoming.
                if (!isProjectileApproaching(projectile.position, projectile.velocity, playerPos)
                        || projectile.velocity.horizontalDistanceSqr() < 1.0e-8) continue;
                Vec3 expectedHit = ProjectileHelper.calculateArrowClosestApproach(projectile, mod.getPlayer());
                Vec3 offset = playerPos.subtract(expectedHit);
                if (offset.x * offset.x + offset.z * offset.z < ARROW_KEEP_DISTANCE_HORIZONTAL * ARROW_KEEP_DISTANCE_HORIZONTAL
                        && Math.abs(offset.y) < ARROW_KEEP_DISTANCE_VERTICAL) {
                    double distance = projectile.position.distanceToSqr(playerPos);
                    if (distance < bestDistance) {
                        closest = projectile.position;
                        bestDistance = distance;
                    }
                }
            }
        } catch (ConcurrentModificationException ignored) {
            // Keep any threat already found instead of mutating controls from a scan.
        }
        return closest;
    }

    public void setTargetEntity(Entity entity) { _targetEntity = entity; }
    public void resetTargetEntity() { _targetEntity = null; }
    public void setForceFieldRange(double range) { _killAura.setRange(range); }
    public void resetForceField() { _killAura.setRange(Double.POSITIVE_INFINITY); }
    public boolean isDoingAcrobatics() { return _doingFunkyStuff; }
    public boolean isPuttingOutFire() { return _wasPuttingOutFire; }
    @Override public boolean isActive() { return true; }
    @Override protected void onTaskFinish(AltoClef mod) { setTask(null); }
    @Override public String getName() { return "Mob Defense"; }
}
