package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Settings;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.*;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.mojang.datafixers.util.Pair;

import java.util.Optional;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class FoodChain extends SingleTaskChain {
    private static FoodChainConfig _config;
    private static final int SAFE_WINDOW_TICKS = 20;
    private static final int MAX_START_ATTEMPTS = 3;
    private static final int RETRY_DELAY_TICKS = 10;
    private static final int FAILED_MEAL_COOLDOWN_TICKS = 80;

    private enum Plan { NONE, EAT, COLLECT }

    static {
        ConfigHelper.loadConfig("configs/food_chain_settings.json", FoodChainConfig::new, FoodChainConfig.class, newConfig -> _config = newConfig);
    }

    private boolean _hasFood;
    private boolean _isTryingToEat = false;
    private boolean _requestFillup = false;
    private boolean _needsFood = false;
    private Optional<Item> _cachedPerfectFood = Optional.empty();
    private int _cachedPerfectNutrition;
    private boolean shouldStop = false;
    private Plan _plan = Plan.NONE;
    private Item _plannedFood;
    private LocalPlayer _planningPlayer;
    private int _safeAfterTick;
    private int _retryMealAfterTick;
    private LocalPlayer _eatOwner;
    private Item _lockedFood;
    private boolean _ownsControls;
    private boolean _usingFood;
    private int _startAttempts;
    private int _nextStartTick;
    private int _useStartedTick;
    private int _expectedUseTicks;
    private int _countBeforeUse;
    private int _hungerBeforeUse;
    private float _saturationBeforeUse;
    private int _avoidanceChecks;

    public FoodChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        setTask(null);
    }

    private void startEat(AltoClef mod, Item food) {
        _eatOwner = mod.getPlayer();
        _lockedFood = food;
        _isTryingToEat = true;
        _requestFillup = true;
        _ownsControls = true;
        _usingFood = false;
        _startAttempts = 0;
        _nextStartTick = _eatOwner.tickCount;
        _avoidanceChecks = 0;
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        // The previous chain has already relinquished control before this onTick runs.
        mod.getInputControls().release(Input.CLICK_RIGHT);
        mod.getExtraBaritoneSettings().setInteractionPaused(true);
    }

    private void stopEat(AltoClef mod) {
        if (ownsFoodUse(mod.getPlayer())) {
            mod.getController().releaseUsingItem(mod.getPlayer());
        }
        if (_ownsControls) {
            mod.getInputControls().release(Input.CLICK_RIGHT);
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
        }
        // Clear the lease independently of inventory actions or shield availability.
        _isTryingToEat = false;
        _requestFillup = false;
        _ownsControls = false;
        _usingFood = false;
        _eatOwner = null;
        _lockedFood = null;
        _startAttempts = 0;
        _avoidanceChecks = 0;
    }

    public boolean isTryingToEat() {
        return _isTryingToEat && _eatOwner == Minecraft.getInstance().player;
    }

    @Override
    public float getPriority(AltoClef mod) {
        // Scoring only creates a plan. It never changes slots, input, aim, or tasks.
        LocalPlayer player = mod.getPlayer();
        _plan = Plan.NONE;
        _plannedFood = null;
        if (_planningPlayer != player) {
            _planningPlayer = player;
            _safeAfterTick = player == null ? 0 : player.tickCount + SAFE_WINDOW_TICKS;
            _retryMealAfterTick = 0;
            _needsFood = false;
            _hasFood = false;
            _cachedPerfectFood = Optional.empty();
        }
        if (player == null || !player.isAlive() || player.isRemoved()) return Float.NEGATIVE_INFINITY;
        Pair<Integer, Optional<Item>> calculation = calculateFood(mod);
        int _cachedFoodScore = calculation.getFirst();
        _cachedPerfectFood = calculation.getSecond();
        _hasFood = _cachedFoodScore > 0;
        if (shouldStop || environmentPreventsEating(mod) || hasNearbyThreat(mod)) {
            _safeAfterTick = player.tickCount + SAFE_WINDOW_TICKS;
            return Float.NEGATIVE_INFINITY;
        }
        if (!mod.getModSettings().isAutoEat() || player.tickCount < _safeAfterTick) {
            return Float.NEGATIVE_INFINITY;
        }
        boolean ownMeal = isTryingToEat();
        if (ownMeal && ownsFoodUse(player)) {
            _plan = Plan.EAT;
            _plannedFood = _lockedFood;
            return 66f;
        }
        if (player.tickCount >= _retryMealAfterTick && _hasFood && _cachedPerfectFood.isPresent()
                && player.getFoodData().getFoodLevel() < 20
                && (needsToEat() || ownMeal && _requestFillup)) {
            _plan = Plan.EAT;
            _plannedFood = ownMeal && _lockedFood != null && mod.getItemStorage().hasItem(_lockedFood)
                    ? _lockedFood : _cachedPerfectFood.get();
            return 66f;
        }
        Settings settings = mod.getModSettings();
        if (_needsFood || _cachedFoodScore < settings.getMinimumFoodAllowed()) {
            _needsFood = _cachedFoodScore < settings.getFoodUnitsToCollect();
            if (_cachedFoodScore < settings.getFoodUnitsToCollect()) {
                _plan = Plan.COLLECT;
                return 55f;
            }
        }
        return Float.NEGATIVE_INFINITY;
    }

    @Override
    protected void onTick(AltoClef mod) {
        if (_plan == Plan.EAT && _plannedFood != null && _planningPlayer == mod.getPlayer()) {
            // Stop a collection child before acquiring the eating lease and its inputs.
            setTask(null);
            if (shouldStop || !mod.getModSettings().isAutoEat()
                    || environmentPreventsEating(mod) || hasNearbyThreat(mod)) {
                _safeAfterTick = mod.getPlayer().tickCount + SAFE_WINDOW_TICKS;
                stopEat(mod);
                return;
            }
            if (!isTryingToEat()) {
                stopEat(mod);
                startEat(mod, _plannedFood);
            }
            tickEating(mod);
            return;
        }
        stopEat(mod);
        if (_plan == Plan.COLLECT) {
            setTask(new CollectFoodTask(mod.getModSettings().getFoodUnitsToCollect()));
            super.onTick(mod);
        } else {
            setTask(null);
        }
    }

    private void tickEating(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        if (player != _eatOwner) {
            stopEat(mod);
            return;
        }
        mod.getExtraBaritoneSettings().setInteractionPaused(true);
        if (_usingFood) {
            if (ownsFoodUse(player) && player.getMainHandItem().getItem() == _lockedFood) {
                if (player.tickCount - _useStartedTick > _expectedUseTicks + 40) {
                    retryBite(mod);
                } else {
                    mod.getInputControls().hold(Input.CLICK_RIGHT);
                }
                return;
            }
            if (consumedBite(player)) {
                _usingFood = false;
                _startAttempts = 0;
                _nextStartTick = player.tickCount + 1;
                mod.getInputControls().release(Input.CLICK_RIGHT);
                if (player.getFoodData().getFoodLevel() >= 20) stopEat(mod);
            } else {
                retryBite(mod);
            }
            return;
        }
        if (player.getFoodData().getFoodLevel() >= 20) {
            stopEat(mod);
            return;
        }
        if (player.tickCount < _nextStartTick) return;
        // Boat rowing suppresses starting item use in vanilla. The boat controller
        // submits neutral paddles while this chain owns the foreground; wait for
        // the next real rideTick to clear handsBusy without spending a retry.
        if (player.getVehicle() instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat
                && player.isHandsBusy()) return;
        // Lock the food across ticks; select another item only after the old stack is gone.
        if (!mod.getItemStorage().hasItem(_lockedFood)) _lockedFood = _plannedFood;
        boolean equipped = _lockedFood != null
                && mod.getSlotHandler().forceEquipItem(new Item[]{_lockedFood}, true);
        ItemStack hand = player.getMainHandItem();
        if (!equipped || hand.getItem() != _lockedFood || !isEdible(hand)) {
            retryBite(mod);
            return;
        }
        // A former shield owner can remain client-side active for one tick after key release.
        // Do not cancel an unrelated use, and never hold use against the wrong main-hand item.
        if (player.isUsingItem()) {
            retryBite(mod);
            return;
        }
        if (_avoidanceChecks == 0 || _startAttempts > 0 && _avoidanceChecks == 1) {
            LookHelper.tryAvoidingInteractable(mod);
            _avoidanceChecks++;
        }
        _countBeforeUse = hand.getCount();
        _hungerBeforeUse = player.getFoodData().getFoodLevel();
        _saturationBeforeUse = player.getFoodData().getSaturationLevel();
        _expectedUseTicks = Math.max(1, hand.getUseDuration(player));
        _useStartedTick = player.tickCount;
        mod.getController().useItem(player, InteractionHand.MAIN_HAND);
        _usingFood = player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && player.getUseItem().getItem() == _lockedFood && isEdible(player.getUseItem());
        if (_usingFood) {
            mod.getInputControls().hold(Input.CLICK_RIGHT);
        } else if (consumedBite(player)) {
            _startAttempts = 0;
            _nextStartTick = player.tickCount + 1;
            if (player.getFoodData().getFoodLevel() >= 20) stopEat(mod);
        } else {
            retryBite(mod);
        }
    }

    private boolean consumedBite(LocalPlayer player) {
        ItemStack hand = player.getMainHandItem();
        return hand.getItem() != _lockedFood || hand.getCount() < _countBeforeUse
                || player.getFoodData().getFoodLevel() > _hungerBeforeUse
                || player.getFoodData().getSaturationLevel() > _saturationBeforeUse;
    }

    private void retryBite(AltoClef mod) {
        if (ownsFoodUse(mod.getPlayer())) mod.getController().releaseUsingItem(mod.getPlayer());
        mod.getInputControls().release(Input.CLICK_RIGHT);
        _usingFood = false;
        _startAttempts++;
        _nextStartTick = mod.getPlayer().tickCount + RETRY_DELAY_TICKS;
        if (_startAttempts >= MAX_START_ATTEMPTS) {
            _retryMealAfterTick = mod.getPlayer().tickCount + FAILED_MEAL_COOLDOWN_TICKS;
            stopEat(mod);
        }
    }

    private boolean ownsFoodUse(LocalPlayer player) {
        return _usingFood && player != null && player == _eatOwner && player.isUsingItem()
                && player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && player.getUseItem().getItem() == _lockedFood && isEdible(player.getUseItem());
    }

    private static boolean isEdible(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        return !stack.isEmpty() && food != null && food.nutrition() > 0 && stack.has(DataComponents.CONSUMABLE);
    }

    private boolean hasNearbyThreat(AltoClef mod) {
        if (mod.getMobDefenseChain().isEmergencyActionPlanned()) return true;
        for (Entity hostile : mod.getEntityTracker().getHostiles()) {
            if (hostile.isAlive() && !hostile.isRemoved() && hostile.distanceToSqr(mod.getPlayer()) <= 12 * 12
                    && EntityHelper.isAngryAtPlayer(mod, hostile)) return true;
        }
        return false;
    }

    private boolean environmentPreventsEating(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        if (player == null || (ElytraNavigation.ownsFlight(mod) || adris.altoclef.util.helpers.SwimNavigation.needsPriority(mod)) || WorldHelper.isInNetherPortal(mod) || player.isInLava()
                || mod.getMobDefenseChain().isPuttingOutFire() || !mod.getMLGBucketChain().doneMLG()
                || mod.getMLGBucketChain().isFallingOhNo(mod) || mod.getMLGBucketChain().isChorusFruiting()) return true;
        Optional<Entity> cloud = mod.getEntityTracker().getNearestEntity(AreaEffectCloud.class);
        return cloud.isPresent() && cloud.get().isAlive()
                && cloud.get().getBoundingBox().intersects(player.getBoundingBox());
    }

    @Override
    public boolean isActive() {
        // We're always checking for food.
        return true;
    }

    @Override
    public String getName() {
        return "Food";
    }

    @Override
    protected void onStop(AltoClef mod) {
        stopEat(mod);
        super.onStop(mod);
    }

    @Override
    public void onInterrupt(AltoClef mod, TaskChain other) {
        stopEat(mod);
        super.onInterrupt(mod, other);
    }

    /** Nutritional intent only; active input ownership is reported by isTryingToEat(). */
    public boolean needsToEat() {
        if (!hasFood()) {
            return false;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !player.isAlive()) return false;
        int foodLevel = player.getFoodData().getFoodLevel();
        float health = player.getHealth();

        if (health <= 10 && foodLevel <= 19) {
            return true;
        }
        //Debug.logMessage("FOOD: " + foodLevel + " -- HEALTH: " + health);
        if (foodLevel >= 20) {
            // We can't eat.
            return false;
        } else {
            // Eat if we're desperate/need to heal ASAP
            if (player.isOnFire() || player.hasEffect(MobEffects.WITHER) || health < _config.alwaysEatWhenWitherOrFireAndHealthBelow) {
                return true;
            } else if (foodLevel > _config.alwaysEatWhenBelowHunger) {
                if (health < _config.alwaysEatWhenBelowHealth) {
                    return true;
                }
            } else {
                // We have half hunger
                return true;
            }
        }

        // Eat if we're  units hungry and we have a perfect fit.
        if (foodLevel < _config.alwaysEatWhenBelowHungerAndPerfectFit && _cachedPerfectFood.isPresent()) {
            int need = 20 - foodLevel;
            return _cachedPerfectNutrition == need;
        }

        return false;
    }

    private Pair<Integer, Optional<Item>> calculateFood(AltoClef mod) {
        Item bestFood = null;
        _cachedPerfectNutrition = 0;
        double bestFoodScore = Double.NEGATIVE_INFINITY;
        int foodTotal = 0;
        LocalPlayer player = mod.getPlayer();
        float health = player != null ? player.getHealth() : 20;
        //float toHeal = player != null? 20 - player.getHealth() : 0;
        float hunger = player != null ? player.getFoodData().getFoodLevel() : 20;
        float saturation = player != null ? player.getFoodData().getSaturationLevel() : 20;
        // Get best food item + calculate food total
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            if (isEdible(stack)) {
                // Ignore protected items
                if (!ItemHelper.canThrowAwayStack(mod, stack)) continue;

                // Ignore spider eyes
                if (stack.getItem() == Items.SPIDER_EYE) {
                    continue;
                }

                FoodProperties food = stack.get(DataComponents.FOOD);

                assert food != null;
                float hungerIfEaten = Math.min(hunger + food.nutrition(), 20);
                float saturationIfEaten = Math.min(hungerIfEaten, saturation + food.saturation());
                float gainedSaturation = (saturationIfEaten - saturation);
                float gainedHunger = (hungerIfEaten - hunger);
                float hungerNotFilled = 20 - hungerIfEaten;

                float saturationWasted = food.saturation() - gainedSaturation;
                float hungerWasted = food.nutrition() - gainedHunger;

                boolean prioritizeSaturation = health < _config.prioritizeSaturationWhenBelowHealth;
                float saturationGoodScore = prioritizeSaturation ? gainedSaturation * _config.foodPickPrioritizeSaturationSaturationMultiplier : gainedSaturation;
                float saturationLossPenalty = prioritizeSaturation ? 0 : saturationWasted * _config.foodPickSaturationWastePenaltyMultiplier;
                float hungerLossPenalty = hungerWasted * _config.foodPickHungerWastePenaltyMultiplier;
                float hungerNotFilledPenalty = hungerNotFilled * _config.foodPickHungerNotFilledPenaltyMultiplier;

                float score = saturationGoodScore - saturationLossPenalty - hungerLossPenalty - hungerNotFilledPenalty;

                if (stack.getItem() == Items.ROTTEN_FLESH) {
                    score -= _config.foodPickRottenFleshPenalty;
                }
                if (score > bestFoodScore) {
                    bestFoodScore = score;
                    bestFood = stack.getItem();
                    _cachedPerfectNutrition = food.nutrition();
                }

                foodTotal += food.nutrition() * stack.getCount();
            }
        }

        return new Pair<>(foodTotal, Optional.ofNullable(bestFood));
    }

    // If we need to eat like, NOW.
    public boolean needsToEatCritical() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.isAlive() && hasFood() && player.getFoodData().getFoodLevel() < 20
                && (player.getFoodData().getFoodLevel() <= 6 || player.getHealth() <= 8);
    }

    public boolean hasFood() {
        return _hasFood && _planningPlayer == Minecraft.getInstance().player;
    }

    public void shouldStop(boolean shouldStopInput) {
        shouldStop = shouldStopInput;
    }

    public boolean isShouldStop() {
        return shouldStop;
    }

    static class FoodChainConfig {
        public int alwaysEatWhenWitherOrFireAndHealthBelow = 6;
        public int alwaysEatWhenBelowHunger = 10;
        public int alwaysEatWhenBelowHealth = 14;
        public int alwaysEatWhenBelowHungerAndPerfectFit = 20 - 5;
        public int prioritizeSaturationWhenBelowHealth = 8;
        public float foodPickPrioritizeSaturationSaturationMultiplier = 8;
        public float foodPickSaturationWastePenaltyMultiplier = 1;
        public float foodPickHungerWastePenaltyMultiplier = 2;
        public float foodPickHungerNotFilledPenaltyMultiplier = 1;
        public float foodPickRottenFleshPenalty = 100;
        public float runDontEatMaxHealth = 3;
        public int runDontEatMaxHunger = 3;
        public int canTankHitsAndEatArmor = 15;
        public int canTankHitsAndEatMaxHunger = 3;
    }
}
