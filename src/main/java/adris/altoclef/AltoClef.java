package adris.altoclef;

import adris.altoclef.butler.Butler;
import adris.altoclef.chains.*;
import adris.altoclef.commandsystem.CommandExecutor;
import adris.altoclef.control.InputControls;
import adris.altoclef.control.MountedControlListener;
import adris.altoclef.control.PlayerExtraController;
import adris.altoclef.control.SlotHandler;
import adris.altoclef.mode.WorldMode;
import adris.altoclef.mode.WorldModeSession;
import adris.altoclef.mode.WorldModeStore;
import adris.altoclef.ui.WorldModeScreen;
import adris.altoclef.util.DeepSeekAgent;
import adris.altoclef.util.DshContextStore;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ClientRenderEvent;
import adris.altoclef.eventbus.events.ClientTickEvent;
import adris.altoclef.eventbus.events.SendChatEvent;
import adris.altoclef.eventbus.events.TitleScreenEntryEvent;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.trackers.*;
import adris.altoclef.trackers.storage.ContainerSubTracker;
import adris.altoclef.trackers.storage.ItemStorageTracker;
import adris.altoclef.ui.CommandStatusOverlay;
import adris.altoclef.ui.DeepSeekKeyScreen;
import adris.altoclef.ui.DeepSeekThinkingOverlay;
import adris.altoclef.ui.MountedPathRenderer;
import adris.altoclef.ui.MessagePriority;
import adris.altoclef.ui.MessageSender;
import adris.altoclef.util.MultiplayerChatService;
import adris.altoclef.util.helpers.InputHelper;
import adris.altoclef.util.helpers.MountedNavigation;
import adris.altoclef.util.helpers.BoatNavigation;
import adris.altoclef.util.helpers.BoatRecoveryObserver;
import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import net.fabricmc.api.ModInitializer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

/**
 * Central access point for AltoClef
 */
public class AltoClef implements ModInitializer {

    // Static access to altoclef
    private static final Queue<Consumer<AltoClef>> _postInitQueue = new ArrayDeque<>();

    // Central Managers
    private static CommandExecutor _commandExecutor;
    private TaskRunner _taskRunner;
    private TrackerManager _trackerManager;
    private BotBehaviour _botBehaviour;
    private PlayerExtraController _extraController;
    // Task chains
    private UserTaskChain _userTaskChain;
    private FoodChain _foodChain;
    private MobDefenseChain _mobDefenseChain;
    private MLGBucketFallChain _mlgBucketChain;
    // Trackers
    private ItemStorageTracker _storageTracker;
    private ContainerSubTracker _containerSubTracker;
    private EntityTracker _entityTracker;
    private BlockTracker _blockTracker;
    private SimpleChunkTracker _chunkTracker;
    private MiscBlockTracker _miscBlockTracker;
    // Renderers
    private CommandStatusOverlay _commandStatusOverlay;
    // DeepSeek key prompt bookkeeping
    private boolean _deepSeekKeyPrompted = false;
    private final WorldModeSession _modeSession = new WorldModeSession();
    private final adris.altoclef.memory.GameMemory _memory = new adris.altoclef.memory.GameMemory(this);
    private final adris.altoclef.util.SceneObservation _scene = new adris.altoclef.util.SceneObservation();
    private final adris.altoclef.util.PlayerRoutine _playerRoutine = new adris.altoclef.util.PlayerRoutine();
    public adris.altoclef.util.PlayerRoutine playerRoutine() { return _playerRoutine; }
    private boolean _backgroundPaused;
    private net.minecraft.client.gui.screens.PauseScreen backgroundMenu;

    private void updateBackgroundMenu() {
        var mc = Minecraft.getInstance();
        boolean authorized = DeepSeekAgent.hasLocalActionSession();
        if (backgroundMenu != null) {
            if (mc.gui.screen() != null || mc.player == null || mc.player.isDeadOrDying()) {
                backgroundMenu = null;
            } else if (mc.isWindowActive() || !authorized) {
                var restore = backgroundMenu;
                backgroundMenu = null;
                mc.gui.setScreen(restore);
            }
        } else if (authorized && !mc.isWindowActive() && mc.gui.overlay() == null
                && mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen pause
                && mc.player != null && mc.player.isAlive()) {
            // Vanilla routes gameplay keys only with the menu closed. grabMouse() skips inactive windows.
            backgroundMenu = pause;
            mc.gui.setScreen(null);
        }
    }
    private boolean _cancelKeyDown;
    // World/connection identity, used to drop bot state when the save changes
    private net.minecraft.client.multiplayer.ClientLevel _lastWorld;
    private net.minecraft.client.multiplayer.ClientPacketListener _lastConnection;
    // Settings
    private adris.altoclef.Settings _settings;
    // Misc managers/input
    private MessageSender _messageSender;
    private MultiplayerChatService _chatService;
    private InputControls _inputControls;
    private SlotHandler _slotHandler;
    private MountedControlListener _mountedControlListener;
    private MountedPathRenderer _mountedPathRenderer;
    private final BoatRecoveryObserver _boatRecoveryObserver = new BoatRecoveryObserver();
    // Butler
    private Butler _butler;

    // Are we in game (playing in a server/world)
    public static boolean inGame() {
        return Minecraft.getInstance().player != null && Minecraft.getInstance().getConnection() != null;
    }

    /**
     * Executes commands (ex. `@get`/`@gamer`)
     */
    public static CommandExecutor getCommandExecutor() {
        return _commandExecutor;
    }

    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // As such, nothing will be loaded here but basic initialization.
        EventBus.subscribe(TitleScreenEntryEvent.class, evt -> onInitializeLoad());
    }

    public void onInitializeLoad() {
        // This code should be run after Minecraft loads everything else in.
        // This is the actual start point, controlled by a mixin.

        initializeBaritoneSettings();

        // Central Managers
        _commandExecutor = new CommandExecutor(this);
        _taskRunner = new TaskRunner(this);
        _trackerManager = new TrackerManager(this);
        _botBehaviour = new BotBehaviour(this);
        _extraController = new PlayerExtraController(this);

        // Task chains
        _userTaskChain = new UserTaskChain(_taskRunner);
        _mobDefenseChain = new MobDefenseChain(_taskRunner);
        new DeathMenuChain(_taskRunner);
        new PlayerInteractionFixChain(_taskRunner);
        _mlgBucketChain = new MLGBucketFallChain(_taskRunner);
        new WorldSurvivalChain(_taskRunner);
        _foodChain = new FoodChain(_taskRunner);

        // Trackers
        _storageTracker = new ItemStorageTracker(this, _trackerManager, container -> _containerSubTracker = container);
        _entityTracker = new EntityTracker(_trackerManager);
        _blockTracker = new BlockTracker(this, _trackerManager);
        _chunkTracker = new SimpleChunkTracker(this);
        _miscBlockTracker = new MiscBlockTracker(this);

        // Renderers
        _commandStatusOverlay = new CommandStatusOverlay();

        // Misc managers
        _messageSender = new MessageSender();
        if (_chatService == null) _chatService = new MultiplayerChatService(this);
        _inputControls = new InputControls();
        _slotHandler = new SlotHandler(this);

        _butler = new Butler(this);

        initializeCommands();

        // Load settings
        adris.altoclef.Settings.load(newSettings -> {
            _settings = newSettings;
            // Baritone's `acceptableThrowawayItems` should match our own.
            List<Item> baritoneCanPlace = Arrays.stream(_settings.getThrowawayItems(this, true))
                    .filter(item -> item != Items.SOUL_SAND && item != Items.MAGMA_BLOCK && item != Items.SAND && item
                            != Items.GRAVEL).toList();
            getClientBaritoneSettings().acceptableThrowawayItems.value.addAll(baritoneCanPlace);
            // If we should run an idle command...
            startConfiguredIdleTask();
            // Don't break blocks or place blocks where we are explicitly protected.
            getExtraBaritoneSettings().avoidBlockBreak(blockPos -> _settings.isPositionExplicitlyProtected(blockPos));
            getExtraBaritoneSettings().avoidBlockPlace(blockPos -> _settings.isPositionExplicitlyProtected(blockPos));
        });

        // Receive + cancel chat
        EventBus.subscribe(SendChatEvent.class, evt -> {
            String line = evt.message;
            if (getCommandExecutor().isClientCommand(line)) {
                evt.cancel();
                getCommandExecutor().execute(line);
            }
        });

        // Debug jank/hookup
        Debug.jankModInstance = this;

        // Tick with the client
        EventBus.subscribe(ClientTickEvent.class, evt -> onClientTick());
        // Baritone registers its behaviors during construction. Append this once
        // so mounted input and aim follow walking path cleanup and look updates.
        if (_mountedControlListener == null) {
            _mountedControlListener = new MountedControlListener(this);
            getClientBaritone().getGameEventHandler().registerEventListener(_mountedControlListener);
        }
        if (_mountedPathRenderer == null) {
            _mountedPathRenderer = new MountedPathRenderer(this);
            getClientBaritone().getGameEventHandler().registerEventListener(_mountedPathRenderer);
        }
        // Render
        EventBus.subscribe(ClientRenderEvent.class, evt -> onClientRenderOverlay(evt.extractor));

        // Playground
        Playground.IDLE_TEST_INIT_FUNCTION(this);

        // External mod initialization
        runEnqueuedPostInits();
    }

    // Client tick
    private void onClientTick() {
        runEnqueuedPostInits();

        // Save switch: nothing from the previous world may keep running. Without
        // this the @ds agent (and its conversation/task) survived across saves and
        // the model answered with the old save's context.
        checkWorldSwitch();
        _trackerManager.tick();
        updateModeBinding();
        if (!hasWorldMode()) return;

        _inputControls.onTickPre();

        // Cancel shortcut
        boolean cancelShortcut = InputHelper.isKeyPressed(InputConstants.KEY_LCONTROL) && InputHelper.isKeyPressed(InputConstants.KEY_K);
        if (cancelShortcut && !_cancelKeyDown) stopAutomation();
        _cancelKeyDown = cancelShortcut;
        updateBackgroundMenu();
        var client = Minecraft.getInstance();
        if (DeepSeekAgent.hasLocalActionSession() && (client.isPaused()
                || client.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen)) {
            getUserTaskChain().finishPausedKey(this);
            return;
        }
        _memory.tick();
        _scene.tick(this);

        // TODO: should this go here?
        _storageTracker.setDirty();
        _containerSubTracker.onServerTick();
        _miscBlockTracker.tick();

        // Prompt for the DeepSeek API key once per session after joining a world.
        if (inGame() && _settings != null && !_settings.hasDeepSeekApiKey()
                && Minecraft.getInstance().gui.screen() == null && !_deepSeekKeyPrompted) {
            _deepSeekKeyPrompted = true;
            Minecraft.getInstance().gui.setScreen(new DeepSeekKeyScreen(_settings));
        }

        _blockTracker.preTickTask();
        if (!cancelShortcut && allowsBackgroundMovement()) {
            _boatRecoveryObserver.tick(this);
            _playerRoutine.tick(this);
        }
        _taskRunner.tick();
        _blockTracker.postTickTask();
        MountedNavigation.endTick(this);
        BoatNavigation.endTick(this);
        adris.altoclef.util.helpers.ElytraNavigation.endTick(this);
        adris.altoclef.util.helpers.SwimNavigation.endTick(this);

        _butler.tick();
        if (!cancelShortcut && _chatService != null) _chatService.tick();
        _messageSender.tick();

        _inputControls.onTickPost();
    }

    /// GETTERS AND SETTERS

    public WorldMode worldMode() {
        return inGame() ? _modeSession.current(Minecraft.getInstance().getConnection()) : null;
    }

    public adris.altoclef.memory.GameMemory memory() { return _memory; }
    public adris.altoclef.util.SceneObservation scene() { return _scene; }
    public boolean hasWorldMode() { return worldMode() != null; }
    public boolean backgroundPaused() { return _backgroundPaused; }
    public boolean allowsBackgroundMovement() { return !_backgroundPaused && !DeepSeekAgent.isManuallyPaused() && worldMode() == WorldMode.PLAYER; }

    public void startConfiguredIdleTask() {
        if (allowsBackgroundMovement() && _settings != null && _settings.shouldRunIdleCommandWhenNotActive()
                && _playerRoutine.permitsConfiguredIdle(this) && !_userTaskChain.isActive() && !DeepSeekAgent.isBusy()) {
            _userTaskChain.runConfiguredIdleCommand(this);
        }
    }

    private void updateModeBinding() {
        Minecraft client = Minecraft.getInstance();
        var connection = client.getConnection();
        _modeSession.enter(connection);
        if (!inGame() || client.level == null) return;
        if (_modeSession.needsIdentity()) {
            try {
                WorldModeStore store;
                if (client.hasSingleplayerServer()) {
                    var root = DshContextStore.singleplayerWorldRoot(this);
                    if (root == null) return;
                    store = WorldModeStore.save(root);
                } else {
                    var server = client.getCurrentServer();
                    if (server == null || server.ip == null || server.ip.isBlank()) return;
                    store = WorldModeStore.server(client.gameDirectory.toPath(), server.ip);
                }
                _modeSession.load(store);
                if (hasWorldMode()) modeReady();
            } catch (java.io.IOException | IllegalArgumentException failure) {
                _modeSession.rejectIdentity();
                modeFailure(failure);
            }
        }
        if (client.gui.screen() == null && _modeSession.takePrompt()) {
            client.gui.setScreen(new WorldModeScreen(selection -> {
                if (client.getConnection() != connection || !inGame()) return;
                try {
                    if (_modeSession.select(connection, selection) != null) modeReady();
                } catch (java.io.IOException failure) {
                    modeFailure(failure);
                }
            }));
        }
    }

    private void modeFailure(Exception failure) {
        var store = _modeSession.store();
        Debug.logMessage("DeepClef 保持待命。请检查" + (store == null ? "服务器地址" : store.file())
                + " 后重进。原因：" + failure.getMessage());
    }

    private void modeReady() {
        _memory.join();
        Debug.logMessage("本世界已绑定" + worldMode().label + "。输入 @status 查看状态。");
        startConfiguredIdleTask();
    }

    public void stopAutomation() {
        _backgroundPaused = true;
        _playerRoutine.interrupt();
        _memory.cancelReview();
        releaseAutomation(true);
        if (_chatService != null) _chatService.pauseAfterStop();
    }

    public void resumeBackground() {
        if (!_backgroundPaused) return;
        _backgroundPaused = false;
        startConfiguredIdleTask();
    }

    private void releaseAutomation(boolean stopPath) {
        boolean hadActions = stopPath || _taskRunner.isActive() || DeepSeekAgent.isBusy();
        _commandExecutor.cancelPending();
        _boatRecoveryObserver.reset();
        DeepSeekAgent.cancel();
        _butler.releaseUser();
        _messageSender.clearOnStop();
        if (_taskRunner.isActive()) _taskRunner.disable();
        if (hadActions) {
            getClientBaritone().getPathingBehavior().forceCancel();
            getClientBaritone().getInputOverrideHandler().clearAllKeys();
        }
        _inputControls.releaseAll();
    }

    /**
     * Detects a save/server switch and drops every piece of bot state that
     * belonged to the previous world: the running {@code @ds} agent (its
     * conversation and background task), the user task chain, combat intent,
     * input holds and the thinking overlay. Chat history itself is per-save and
     * lives in the save folder.
     */
    private void checkWorldSwitch() {
        net.minecraft.client.multiplayer.ClientLevel world = level();
        net.minecraft.client.multiplayer.ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (world == _lastWorld && connection == _lastConnection) return;
        backgroundMenu = null;
        _playerRoutine.leaveWorld(connection != _lastConnection);
        _scene.reset();
        boolean switching = _lastWorld != null || _lastConnection != null;
        if (connection != _lastConnection) _memory.leave();
        else { _memory.cancelReview(); _memory.clearObservation(); }
        _lastWorld = world;
        _lastConnection = connection;
        if (!switching) return; // first join of this game session

        releaseAutomation(false);
        _cancelKeyDown = false;
        adris.altoclef.util.helpers.CombatPreferences.setStyle(
                adris.altoclef.util.helpers.CombatPreferences.Style.AUTO);
        adris.altoclef.util.helpers.PvpCombat.reset(this);
        adris.altoclef.util.helpers.BowCombat.reset(this);
        adris.altoclef.util.helpers.SpearHelper.stopCharging(this);
        // 叠加层是进程内单例，存档切换时必须清空，否则上一个存档的思考内容会带进新存档。
        DeepSeekThinkingOverlay.getInstance().clear();
        Debug.logInternal("[DeepClef] world changed: bot state and @ds session reset");
    }

    private void onClientRenderOverlay(GuiGraphicsExtractor extractor) {
        _commandStatusOverlay.render(this, extractor);
        DeepSeekThinkingOverlay.getInstance().render(extractor, this);
    }

    private void initializeBaritoneSettings() {
        getExtraBaritoneSettings().canWalkOnEndPortal(false);
        getClientBaritoneSettings().freeLook.value = false;
        getClientBaritoneSettings().overshootTraverse.value = false;
        getClientBaritoneSettings().allowOvershootDiagonalDescend.value = true;
        getClientBaritoneSettings().allowInventory.value = true;
        getClientBaritoneSettings().allowParkour.value = false;
        getClientBaritoneSettings().allowParkourAscend.value = false;
        getClientBaritoneSettings().allowParkourPlace.value = false;
        getClientBaritoneSettings().allowDiagonalDescend.value = false;
        getClientBaritoneSettings().allowDiagonalAscend.value = false;
        getClientBaritoneSettings().blocksToAvoid.value = List.of(Blocks.FLOWERING_AZALEA, Blocks.AZALEA,
                Blocks.POWDER_SNOW, Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM, Blocks.CAVE_VINES,
                Blocks.CAVE_VINES_PLANT, Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT, Blocks.SWEET_BERRY_BUSH,
                Blocks.WARPED_ROOTS, Blocks.VINE, Blocks.GRASS_BLOCK, Blocks.FERN, Blocks.TALL_GRASS, Blocks.LARGE_FERN,
                Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD,
                Blocks.AMETHYST_CLUSTER, Blocks.SCULK, Blocks.SCULK_VEIN, Blocks.SUNFLOWER, Blocks.LILAC,
                Blocks.ROSE_BUSH, Blocks.PEONY);
        // Let baritone move items to hotbar to use them
        // Reduces a bit of far rendering to save FPS
        getClientBaritoneSettings().fadePath.value = true;
        // Don't let baritone scan dropped items, we handle that ourselves.
        getClientBaritoneSettings().mineScanDroppedItems.value = false;
        // Don't let baritone wait for drops, we handle that ourselves.
        getClientBaritoneSettings().mineDropLoiterDurationMSThanksLouca.value = 0L;

        // Water bucket placement will be handled by us exclusively
        getExtraBaritoneSettings().configurePlaceBucketButDontFall(true);

        // For render smoothing
        getClientBaritoneSettings().randomLooking.value = 0.0;
        getClientBaritoneSettings().randomLooking113.value = 0.0;

        // Give baritone more time to calculate paths. Sometimes they can be really far away.
        // Was: 2000L
        getClientBaritoneSettings().failureTimeoutMS.reset();
        // Was: 5000L
        getClientBaritoneSettings().planAheadFailureTimeoutMS.reset();
        // Was 100
        getClientBaritoneSettings().movementTimeoutTicks.reset();
    }

    // List all command sources here.
    private void initializeCommands() {
        try {
            // This creates the commands. If you want any more commands feel free to initialize new command lists.
            new AltoClefCommands();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Runs the highest priority task chain
     * (task chains run the task tree)
     */
    public TaskRunner getTaskRunner() {
        return _taskRunner;
    }

    /**
     * The user task chain (runs your command. Ex. Get Diamonds, Beat the Game)
     */
    public UserTaskChain getUserTaskChain() {
        return _userTaskChain;
    }

    /**
     * Controls bot behaviours, like whether to temporarily "protect" certain blocks or items
     */
    public BotBehaviour getBehaviour() {
        return _botBehaviour;
    }

    /**
     * Tracks items in your inventory and in storage containers.
     */
    public ItemStorageTracker getItemStorage() {
        return _storageTracker;
    }

    /**
     * Tracks loaded entities
     */
    public EntityTracker getEntityTracker() {
        return _entityTracker;
    }

    /**
     * Tracks blocks and their positions
     */
    public BlockTracker getBlockTracker() {
        return _blockTracker;
    }

    /**
     * Tracks of whether a chunk is loaded/visible or not
     */
    public SimpleChunkTracker getChunkTracker() {
        return _chunkTracker;
    }

    /**
     * Tracks random block things, like the last nether portal we used
     */
    public MiscBlockTracker getMiscBlockTracker() {
        return _miscBlockTracker;
    }

    /**
     * Baritone access (could just be static honestly)
     */
    public Baritone getClientBaritone() {
        if (getPlayer() == null) {
            return (Baritone) BaritoneAPI.getProvider().getPrimaryBaritone();
        }
        return (Baritone) BaritoneAPI.getProvider().getBaritoneForPlayer(getPlayer());
    }

    /**
     * Baritone settings access (could just be static honestly)
     */
    public Settings getClientBaritoneSettings() {
        return Baritone.settings();
    }

    /**
     * Baritone settings special to AltoClef (could just be static honestly)
     */
    public AltoClefSettings getExtraBaritoneSettings() {
        return AltoClefSettings.getInstance();
    }

    /**
     * AltoClef Settings
     */
    public adris.altoclef.Settings getModSettings() {
        return _settings;
    }

    /**
     * Butler controller. Keeps track of users and lets you receive user messages
     */
    public Butler getButler() {
        return _butler;
    }

    /**
     * Sends chat messages (avoids auto-kicking)
     */
    public MessageSender getMessageSender() {
        return _messageSender;
    }

    public MultiplayerChatService getChatService() { return _chatService; }

    /**
     * Does Inventory/container slot actions
     */
    public SlotHandler getSlotHandler() {
        return _slotHandler;
    }

    /**
     * Minecraft player client access (could just be static honestly)
     */
    public LocalPlayer getPlayer() {
        return Minecraft.getInstance().player;
    }

    /**
     * Minecraft world access (could just be static honestly)
     */
    public ClientLevel level() {
        return Minecraft.getInstance().level;
    }

    /**
     * Minecraft client interaction controller access (could just be static honestly)
     */
    public MultiPlayerGameMode getController() {
        return Minecraft.getInstance().gameMode;
    }

    /**
     * Extra controls not present in MultiPlayerGameMode. This REALLY should be made static or combined with something else.
     */
    public PlayerExtraController getControllerExtras() {
        return _extraController;
    }

    /**
     * Manual control over input actions (ex. jumping, attacking)
     */
    public InputControls getInputControls() {
        return _inputControls;
    }

    /**
     * Run a user task
     */
    public void runUserTask(Task task) {
        runUserTask(task, () -> {
        });
    }

    /**
     * Run a user task
     */
    public void runUserTask(Task task, Runnable onFinish) {
        if (!hasWorldMode()) return;
        _userTaskChain.runTask(this, task, onFinish);
    }

    /**
     * Cancel currently running user task
     */
    public void cancelUserTask() {
        _boatRecoveryObserver.reset();
        _userTaskChain.cancel(this);
    }

    /**
     * Takes control away to eat food
     */
    public FoodChain getFoodChain() {
        return _foodChain;
    }

    /**
     * Takes control away to defend against mobs
     */
    public MobDefenseChain getMobDefenseChain() {
        return _mobDefenseChain;
    }

    /**
     * Takes control away to perform bucket saves
     */
    public MLGBucketFallChain getMLGBucketChain() {
        return _mlgBucketChain;
    }

    public void log(String message) {
        log(message, MessagePriority.TIMELY);
    }

    /**
     * Logs to the console and also messages any player using the bot as a butler.
     */
    public void log(String message, MessagePriority priority) {
        Debug.logMessage(message);
        _butler.onLog(message, priority);
    }

    public void logWarning(String message) {
        logWarning(message, MessagePriority.TIMELY);
    }

    /**
     * Logs a warning to the console and also alerts any player using the bot as a butler.
     */
    public void logWarning(String message, MessagePriority priority) {
        Debug.logWarning(message);
        _butler.onLogWarning(message, priority);
    }

    private void runEnqueuedPostInits() {
        synchronized (_postInitQueue) {
            while (!_postInitQueue.isEmpty()) {
                _postInitQueue.poll().accept(this);
            }
        }
    }

}
