package me.waltom.wavexin;

import baritone.api.BaritoneAPI;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AutoRestockCoreXin extends Module {
    private static final int SHULKER_SIZE = 27;
    private static final Set<String> HELPER_BARITONE_COMMANDS = Set.of("#pause", "#p", "#resume", "#r", "#cancel", "#forcecancel");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDebug = settings.createGroup("Debug");

    private final Setting<Boolean> monitoring = sgGeneral.add(new BoolSetting.Builder()
        .name("enabled-monitoring")
        .description("Monitors Baritone/building state for restock opportunities.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> dryRun = sgGeneral.add(new BoolSetting.Builder()
        .name("dry-run")
        .description("Only scans and prints plans without moving, placing, opening, clicking, or breaking.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoResumeBaritone = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-resume-baritone")
        .description("Resumes the last recorded Baritone command after restocking.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minimumFreeSlots = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-free-slots")
        .description("Free inventory slots to keep when taking items.")
        .defaultValue(2)
        .min(0)
        .max(20)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> maxRestockSlotsUsed = sgGeneral.add(new IntSetting.Builder()
        .name("max-restock-slots-used")
        .description("Maximum empty slots this module may fill during one plan.")
        .defaultValue(8)
        .min(1)
        .max(27)
        .sliderMax(27)
        .build()
    );

    private final Setting<Integer> maxRetries = sgGeneral.add(new IntSetting.Builder()
        .name("max-retries")
        .description("Maximum retries for placement/opening/taking steps.")
        .defaultValue(3)
        .min(1)
        .max(10)
        .sliderMax(10)
        .build()
    );

    private final Setting<Integer> actionDelayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay-ticks")
        .description("Delay between state-machine actions.")
        .defaultValue(6)
        .min(0)
        .max(40)
        .sliderMax(40)
        .build()
    );

    private final Setting<Integer> stallDetectionSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("stall-detection-seconds")
        .description("Seconds without movement before trying demand analysis.")
        .defaultValue(8)
        .min(2)
        .max(60)
        .sliderMax(60)
        .build()
    );

    private final Setting<Boolean> debug = sgDebug.add(new BoolSetting.Builder()
        .name("debug")
        .description("Prints compact restock diagnostics.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> debugInterval = sgDebug.add(new IntSetting.Builder()
        .name("debug-interval")
        .description("Minimum ticks between repeated debug messages.")
        .defaultValue(40)
        .min(5)
        .max(200)
        .sliderMax(200)
        .build()
    );

    private final Setting<Boolean> showPlan = sgDebug.add(new BoolSetting.Builder()
        .name("show-plan")
        .description("Prints selected restock plans.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgDebug.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Shows important restock messages in chat.")
        .defaultValue(true)
        .build()
    );

    private RestockState state = RestockState.IDLE;
    private final Map<Item, Integer> inventoryCounts = new HashMap<>();
    private final List<ShulkerInfo> shulkerIndex = new ArrayList<>();
    private final Map<Item, RestockItemDemand> currentDemands = new LinkedHashMap<>();
    private RestockPlan activePlan;
    private ShulkerInfo chosenShulker;
    private BlockPos placedShulkerPos;
    private int plannedHotbarSlot = -1;
    private ItemStack plannedShulkerStack = ItemStack.EMPTY;
    private String savedBaritoneCommand;
    private int tickCounter;
    private int actionDelay;
    private int stateTicks;
    private int retryCount;
    private int lastDebugTick = -1000;
    private int lastInventoryRefresh = -1000;
    private int lastShulkerRefresh = -1000;
    private int lastDemandRefresh = -1000;
    private Vec3d lastPlayerPos;
    private int stillTicks;
    private boolean reliableDemandDetected;
    private boolean openInteractionSent;

    public AutoRestockCoreXin() {
        super(WaveXinAddon.CATEGORY, "auto-restock-core-xin", "Automatically plans and performs shulker restocks for building tasks.");
    }

    @Override
    public void onActivate() {
        resetRuntime();
        state = RestockState.MONITORING;
        debugLine("AutoRestock enabled.");
    }

    @Override
    public void onDeactivate() {
        closeScreenIfHandled();
        if (placedShulkerPos != null && hasGame() && isPlacedShulkerPresent()) {
            if (BlockUtils.breakBlock(placedShulkerPos, true)) {
                warning("AutoRestock is trying to break the placed shulker at " + placedShulkerPos + ". If it remains, pick it up manually.");
            } else {
                warning("AutoRestock could not break the placed shulker at " + placedShulkerPos + ". Pick it up manually.");
            }
            resetRuntimeKeepingPlacedShulker();
            return;
        }
        resetRuntime();
    }

    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        if (event.message == null) return;
        String message = event.message.trim();
        if (!message.startsWith("#")) return;

        String lower = message.toLowerCase();
        if (HELPER_BARITONE_COMMANDS.contains(lower)) return;
        if (isRecordableBaritoneCommand(lower)) {
            savedBaritoneCommand = message;
            debugLine("Saved Baritone command: " + savedBaritoneCommand);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        tickCounter++;
        if (!monitoring.get()) return;

        if (!hasGame()) {
            resetActionState();
            state = RestockState.IDLE;
            return;
        }

        updateStallTracker();
        refreshCachesIfNeeded();

        if (actionDelay > 0) {
            actionDelay--;
            return;
        }

        stateTicks++;
        if (stateTicks > 20 * 30) {
            fail("State timed out: " + state);
            return;
        }

        switch (state) {
            case IDLE -> state = RestockState.MONITORING;
            case MONITORING -> handleMonitoring();
            case NEED_RESTOCK -> transition(RestockState.SAVE_BARITONE_STATE);
            case SAVE_BARITONE_STATE -> transition(RestockState.PAUSE_BARITONE);
            case PAUSE_BARITONE -> handlePauseBaritone();
            case CHOOSE_SHULKER -> handleChooseShulker();
            case MOVE_SHULKER_TO_HOTBAR -> handleMoveShulkerToHotbar();
            case PLACE_SHULKER -> handlePlaceShulker();
            case OPEN_SHULKER -> handleOpenShulker();
            case TAKE_ITEMS -> handleTakeItems();
            case VERIFY_ITEMS -> handleVerifyItems();
            case CLOSE_CONTAINER -> handleCloseContainer();
            case BREAK_SHULKER -> handleBreakShulker();
            case PICKUP_SHULKER -> handlePickupShulker();
            case RESUME_BARITONE -> handleResumeBaritone();
            case ERROR_RECOVERY -> handleErrorRecovery();
        }
    }

    private void handleMonitoring() {
        if (tickCounter - lastDemandRefresh >= 100 || isBaritoneLikelyStalled()) {
            currentDemands.clear();
            currentDemands.putAll(buildDemandModel());
            lastDemandRefresh = tickCounter;
        }

        if (!reliableDemandDetected) {
            debugLineThrottled("No reliable Baritone/Litematica demand source found. Waiting.");
            return;
        }

        if (currentDemands.isEmpty()) {
            debugLineThrottled("No reliable demand found. Waiting.");
            return;
        }

        RestockPlan plan = RestockPlanner.createPlan(currentDemands, inventoryCounts, shulkerIndex, countFreeSlots(), minimumFreeSlots.get(), maxRestockSlotsUsed.get());
        if (plan == null || plan.score <= 0) {
            debugLineThrottled("No positive shulker plan. demands=" + currentDemands.size() + " shulkers=" + shulkerIndex.size());
            return;
        }

        activePlan = plan;
        chosenShulker = plan.shulker;
        if (showPlan.get()) debugLine("Plan: shulkerSlot=" + chosenShulker.inventorySlot + " score=" + plan.score + " items=" + plan.amounts.size());
        if (dryRun.get()) {
            debugLine("Dry-run only: would restock from slot " + chosenShulker.inventorySlot + " items=" + describePlan(plan));
            activePlan = null;
            chosenShulker = null;
            return;
        }
        transition(RestockState.NEED_RESTOCK);
    }

    private void handlePauseBaritone() {
        if (dryRun.get()) {
            transition(RestockState.MONITORING);
            return;
        }

        try {
            BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess().pause();
            BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
        } catch (Exception e) {
            debugLine("Baritone pause failed: " + e.getMessage());
        }
        transition(RestockState.CHOOSE_SHULKER);
    }

    private void handleChooseShulker() {
        if (chosenShulker == null || activePlan == null) {
            fail("No shulker plan available.");
            return;
        }
        transition(RestockState.MOVE_SHULKER_TO_HOTBAR);
    }

    private void handleMoveShulkerToHotbar() {
        int selected = mc.player.getInventory().getSelectedSlot();
        plannedHotbarSlot = selected;
        plannedShulkerStack = chosenShulker.stack.copy();
        InvUtils.move().from(chosenShulker.inventorySlot).toHotbar(selected);
        delay();
        transition(RestockState.PLACE_SHULKER);
    }

    private void handlePlaceShulker() {
        BlockPos pos = findPlacementPos();
        if (pos == null) {
            retryOrFail("No safe shulker placement position.");
            return;
        }

        if (!isValidPlannedHotbarSlot()) {
            retryOrFail("Planned hotbar slot is invalid.");
            return;
        }

        ItemStack hotbarStack = mc.player.getInventory().getStack(plannedHotbarSlot);
        if (!isPlannedShulkerStack(hotbarStack)) {
            retryOrFail("Planned shulker changed before placement.");
            return;
        }

        FindItemResult shulkerItem = InvUtils.findInHotbar(this::isPlannedShulkerStack);
        if (!shulkerItem.found()) {
            retryOrFail("Planned shulker is not in hotbar.");
            return;
        }

        if (shulkerItem.slot() != plannedHotbarSlot) {
            retryOrFail("Planned shulker is not in the selected hotbar slot.");
            return;
        }

        if (BlockUtils.place(pos, shulkerItem, true, 50, true, true)) {
            placedShulkerPos = pos;
            retryCount = 0;
            openInteractionSent = false;
            delay();
            transition(RestockState.OPEN_SHULKER);
        } else {
            retryOrFail("Could not place shulker.");
        }
    }

    private void handleOpenShulker() {
        if (placedShulkerPos == null || !(mc.world.getBlockState(placedShulkerPos).getBlock() instanceof ShulkerBoxBlock)) {
            retryOrFail("Placed shulker was not found.");
            return;
        }

        if (mc.currentScreen instanceof HandledScreen<?>) {
            retryCount = 0;
            openInteractionSent = false;
            transition(RestockState.TAKE_ITEMS);
            return;
        }

        if (openInteractionSent) {
            retryCount++;
            if (retryCount > maxRetries.get()) {
                fail("Shulker screen did not open.");
                return;
            }
            debugLine("Shulker did not open yet. retry=" + retryCount);
            openInteractionSent = false;
            delay();
            return;
        }

        Rotations.rotate(Rotations.getYaw(placedShulkerPos), Rotations.getPitch(placedShulkerPos), () -> {
            BlockHitResult hitResult = new BlockHitResult(Vec3d.ofCenter(placedShulkerPos), Direction.UP, placedShulkerPos, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
        });
        openInteractionSent = true;
        delay();
    }

    private void handleTakeItems() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            retryOrFail("Shulker screen is not open.");
            return;
        }

        if (activePlan == null || activePlan.remainingToTake.isEmpty()) {
            transition(RestockState.VERIFY_ITEMS);
            return;
        }

        if (!hasInventoryCapacityForPlan()) {
            debugLine("Inventory is full for remaining plan; stopping take step safely.");
            transition(RestockState.VERIFY_ITEMS);
            return;
        }

        var handler = screen.getScreenHandler();
        int playerInventoryStart = Math.max(0, handler.slots.size() - 36);
        if (playerInventoryStart <= 0) {
            fail("Could not detect container slots.");
            return;
        }

        boolean clicked = false;
        for (int i = 0; i < playerInventoryStart; i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) continue;

            Integer remaining = activePlan.remainingToTake.get(stack.getItem());
            if (remaining == null || remaining <= 0) continue;

            int beforeCount = stack.getCount();
            mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            activePlan.remainingToTake.put(stack.getItem(), Math.max(0, remaining - beforeCount));
            clicked = true;
            delay();
            break;
        }

        if (!clicked) {
            transition(RestockState.VERIFY_ITEMS);
        }
    }

    private void handleVerifyItems() {
        refreshInventoryCounts();
        boolean satisfiedAny = false;
        for (Map.Entry<Item, Integer> entry : activePlan.beforeCounts.entrySet()) {
            int before = entry.getValue();
            int after = inventoryCounts.getOrDefault(entry.getKey(), 0);
            if (after > before) {
                satisfiedAny = true;
                break;
            }
        }

        if (satisfiedAny || activePlan.remainingToTake.values().stream().allMatch(v -> v <= 0)) {
            transition(RestockState.CLOSE_CONTAINER);
        } else {
            retryOrFail("Inventory count did not increase.");
        }
    }

    private void handleCloseContainer() {
        closeScreenIfHandled();
        delay();
        transition(RestockState.BREAK_SHULKER);
    }

    private void handleBreakShulker() {
        if (placedShulkerPos != null && mc.world.getBlockState(placedShulkerPos).getBlock() instanceof ShulkerBoxBlock) {
            if (!BlockUtils.breakBlock(placedShulkerPos, true)) {
                fail("Could not break placed shulker; pick it up manually.");
                return;
            }
        }
        delay();
        transition(RestockState.PICKUP_SHULKER);
    }

    private void handlePickupShulker() {
        placedShulkerPos = null;
        delay();
        transition(RestockState.RESUME_BARITONE);
    }

    private void handleResumeBaritone() {
        if (autoResumeBaritone.get()) {
            try {
                BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess().resume();
                if (savedBaritoneCommand != null && !savedBaritoneCommand.isBlank()) {
                    BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute(savedBaritoneCommand.substring(1));
                }
            } catch (Exception e) {
                debugLine("Baritone resume failed: " + e.getMessage());
            }
        }
        activePlan = null;
        chosenShulker = null;
        transition(RestockState.MONITORING);
    }

    private void handleErrorRecovery() {
        closeScreenIfHandled();

        if (placedShulkerPos != null && hasGame() && mc.world.getBlockState(placedShulkerPos).getBlock() instanceof ShulkerBoxBlock) {
            if (BlockUtils.breakBlock(placedShulkerPos, true)) {
                debugLine("Recovery is breaking placed shulker before resetting.");
                delay();
                return;
            }

            warning("AutoRestock could not break the placed shulker at " + placedShulkerPos + ". Pick it up manually.");
            toggle();
            return;
        }

        activePlan = null;
        chosenShulker = null;
        placedShulkerPos = null;
        plannedHotbarSlot = -1;
        plannedShulkerStack = ItemStack.EMPTY;
        openInteractionSent = false;
        transition(RestockState.MONITORING);
    }

    private Map<Item, RestockItemDemand> buildDemandModel() {
        Map<Item, RestockItemDemand> demands = new LinkedHashMap<>();
        reliableDemandDetected = false;
        reliableDemandDetected |= collectBaritoneBuilderDemands(demands);
        reliableDemandDetected |= collectLitematicaDemandsStub(demands);
        return demands;
    }

    private boolean collectBaritoneBuilderDemands(Map<Item, RestockItemDemand> demands) {
        boolean foundDemand = false;
        try {
            Object builder = BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess();
            Method method = builder.getClass().getMethod("getApproxPlaceable");
            Object result = method.invoke(builder);
            if (!(result instanceof Iterable<?> iterable)) return false;

            for (Object object : iterable) {
                if (!(object instanceof BlockState blockState)) continue;
                Item item = blockState.getBlock().asItem();
                if (item == null) continue;
                int current = inventoryCounts.getOrDefault(item, 0);
                if (current >= 128) continue;

                RestockItemDemand demand = demands.computeIfAbsent(item, RestockItemDemand::new);
                demand.neededSoon += 64;
                demand.neededTotal += 256;
                demand.inventoryShortage += Math.max(64, 256 - current);
                demand.recomputePriority(shulkerIndex);
                foundDemand = true;
            }
        } catch (Exception ignored) {
        }
        return foundDemand;
    }

    private boolean collectLitematicaDemandsStub(Map<Item, RestockItemDemand> demands) {
        // Safe optional integration point: intentionally no hard Litematica dependency yet.
        return false;
    }

    private void refreshCachesIfNeeded() {
        if (tickCounter - lastInventoryRefresh >= 10) {
            refreshInventoryCounts();
            lastInventoryRefresh = tickCounter;
        }
        if (tickCounter - lastShulkerRefresh >= 40) {
            refreshShulkerIndex();
            lastShulkerRefresh = tickCounter;
        }
    }

    private void refreshInventoryCounts() {
        inventoryCounts.clear();
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || isShulkerStack(stack)) continue;
            inventoryCounts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
    }

    private void refreshShulkerIndex() {
        shulkerIndex.clear();
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!isShulkerStack(stack)) continue;

            ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
            if (container == null) continue;

            ShulkerInfo info = new ShulkerInfo(i, stack.copy());
            for (ItemStack contained : container.iterateNonEmpty()) {
                if (!contained.isEmpty()) {
                    info.itemCounts.merge(contained.getItem(), contained.getCount(), Integer::sum);
                }
            }
            shulkerIndex.add(info);
        }
    }

    private boolean isShulkerStack(ItemStack stack) {
        return stack != null
            && !stack.isEmpty()
            && stack.getItem() instanceof BlockItem blockItem
            && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private boolean isPlannedShulkerStack(ItemStack stack) {
        return isShulkerStack(stack)
            && !plannedShulkerStack.isEmpty()
            && stack.getCount() == plannedShulkerStack.getCount()
            && ItemStack.areItemsAndComponentsEqual(stack, plannedShulkerStack);
    }

    private boolean isValidPlannedHotbarSlot() {
        return plannedHotbarSlot >= 0 && plannedHotbarSlot < 9;
    }

    private int countFreeSlots() {
        int free = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) free++;
        }
        return free;
    }

    private BlockPos findPlacementPos() {
        BlockPos playerPos = mc.player.getBlockPos();
        int[][] offsets = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
            {2, 0}, {-2, 0}, {0, 2}, {0, -2}
        };

        for (int yOffset = 0; yOffset >= -1; yOffset--) {
            for (int[] offset : offsets) {
                BlockPos candidate = playerPos.add(offset[0], yOffset, offset[1]);
                if (BlockUtils.canPlace(candidate, true)) return candidate;
            }
        }
        return null;
    }

    private boolean hasInventoryCapacityForPlan() {
        if (activePlan == null) return false;
        if (countFreeSlots() > 0) return true;

        for (Map.Entry<Item, Integer> entry : activePlan.remainingToTake.entrySet()) {
            if (entry.getValue() <= 0) continue;

            for (int i = 0; i < mc.player.getInventory().size(); i++) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (!stack.isEmpty() && stack.isOf(entry.getKey()) && stack.getCount() < stack.getMaxCount()) return true;
            }
        }
        return false;
    }

    private boolean hasGame() {
        return mc.player != null && mc.world != null && mc.interactionManager != null;
    }

    private boolean isPlacedShulkerPresent() {
        return placedShulkerPos != null
            && mc.world != null
            && mc.world.getBlockState(placedShulkerPos).getBlock() instanceof ShulkerBoxBlock;
    }

    private boolean isBaritoneLikelyStalled() {
        try {
            boolean pathing = BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing();
            return pathing && stillTicks >= stallDetectionSeconds.get() * 20;
        } catch (Exception e) {
            return false;
        }
    }

    private void updateStallTracker() {
        Vec3d pos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        if (lastPlayerPos != null && pos.squaredDistanceTo(lastPlayerPos) < 0.0004) stillTicks++;
        else stillTicks = 0;
        lastPlayerPos = pos;
    }

    private boolean isRecordableBaritoneCommand(String lower) {
        return lower.equals("#litematica")
            || lower.startsWith("#build ")
            || lower.startsWith("#schematica ")
            || lower.startsWith("#s ");
    }

    private void transition(RestockState next) {
        state = next;
        stateTicks = 0;
    }

    private void delay() {
        actionDelay = actionDelayTicks.get();
    }

    private void retryOrFail(String reason) {
        retryCount++;
        if (retryCount > maxRetries.get()) fail(reason);
        else {
            debugLine(reason + " retry=" + retryCount);
            delay();
        }
    }

    private void fail(String reason) {
        if (chatFeedback.get()) warning(reason);
        debugLine("ERROR: " + reason);
        transition(RestockState.ERROR_RECOVERY);
    }

    private void resetRuntime() {
        resetActionState();
        state = RestockState.IDLE;
        inventoryCounts.clear();
        shulkerIndex.clear();
        currentDemands.clear();
        lastPlayerPos = null;
        stillTicks = 0;
    }

    private void resetRuntimeKeepingPlacedShulker() {
        activePlan = null;
        chosenShulker = null;
        plannedHotbarSlot = -1;
        plannedShulkerStack = ItemStack.EMPTY;
        openInteractionSent = false;
        actionDelay = 0;
        stateTicks = 0;
        retryCount = 0;
        state = RestockState.IDLE;
        inventoryCounts.clear();
        shulkerIndex.clear();
        currentDemands.clear();
        lastPlayerPos = null;
        stillTicks = 0;
    }

    private void resetActionState() {
        activePlan = null;
        chosenShulker = null;
        placedShulkerPos = null;
        plannedHotbarSlot = -1;
        plannedShulkerStack = ItemStack.EMPTY;
        openInteractionSent = false;
        actionDelay = 0;
        stateTicks = 0;
        retryCount = 0;
    }

    private void closeScreenIfHandled() {
        if (mc.player != null && mc.currentScreen instanceof HandledScreen<?>) {
            mc.player.closeHandledScreen();
        }
    }

    private void debugLine(String message) {
        if (debug.get() && chatFeedback.get()) info("[Restock] " + message);
    }

    private void debugLineThrottled(String message) {
        if (tickCounter - lastDebugTick < debugInterval.get()) return;
        lastDebugTick = tickCounter;
        debugLine(message + " state=" + state + " command=" + savedBaritoneCommand + " shulkers=" + shulkerIndex.size());
    }

    private String describePlan(RestockPlan plan) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : plan.amounts.entrySet()) {
            parts.add(entry.getKey().getName().getString() + " x" + entry.getValue());
        }
        return String.join(", ", parts);
    }

    @Override
    public String getInfoString() {
        return state.name();
    }

    private enum RestockState {
        IDLE,
        MONITORING,
        NEED_RESTOCK,
        SAVE_BARITONE_STATE,
        PAUSE_BARITONE,
        CHOOSE_SHULKER,
        MOVE_SHULKER_TO_HOTBAR,
        PLACE_SHULKER,
        OPEN_SHULKER,
        TAKE_ITEMS,
        VERIFY_ITEMS,
        CLOSE_CONTAINER,
        BREAK_SHULKER,
        PICKUP_SHULKER,
        RESUME_BARITONE,
        ERROR_RECOVERY
    }

    private static class RestockItemDemand {
        final Item item;
        int neededNow;
        int neededSoon;
        int neededTotal;
        int inventoryShortage;
        long priority;

        RestockItemDemand(Item item) {
            this.item = item;
        }

        void recomputePriority(List<ShulkerInfo> shulkers) {
            int available = shulkers.stream().mapToInt(s -> s.itemCounts.getOrDefault(item, 0)).sum();
            int rarityBonus = available <= 64 ? 500 : available <= 256 ? 100 : 0;
            priority = neededNow * 10000L + neededSoon * 100L + neededTotal + inventoryShortage * 50L + rarityBonus;
        }
    }

    private static class ShulkerInfo {
        final int inventorySlot;
        final ItemStack stack;
        final Map<Item, Integer> itemCounts = new HashMap<>();

        ShulkerInfo(int inventorySlot, ItemStack stack) {
            this.inventorySlot = inventorySlot;
            this.stack = stack;
        }
    }

    private static class RestockPlan {
        final ShulkerInfo shulker;
        final Map<Item, Integer> amounts = new LinkedHashMap<>();
        final Map<Item, Integer> remainingToTake = new HashMap<>();
        final Map<Item, Integer> beforeCounts = new HashMap<>();
        long score;

        RestockPlan(ShulkerInfo shulker) {
            this.shulker = shulker;
        }
    }

    private static class RestockPlanner {
        static RestockPlan createPlan(Map<Item, RestockItemDemand> demands, Map<Item, Integer> inventoryCounts, List<ShulkerInfo> shulkers, int freeSlots, int minimumFreeSlots, int maxSlotsUsed) {
            if (demands.isEmpty() || shulkers.isEmpty()) return null;

            ShulkerInfo best = null;
            long bestScore = 0;
            for (ShulkerInfo shulker : shulkers) {
                long score = scoreShulker(shulker, demands, freeSlots, minimumFreeSlots);
                if (score > bestScore) {
                    bestScore = score;
                    best = shulker;
                }
            }
            if (best == null || bestScore <= 0) return null;

            RestockPlan plan = new RestockPlan(best);
            plan.score = bestScore;

            int usableSlots = Math.max(0, Math.min(maxSlotsUsed, freeSlots - minimumFreeSlots));
            Set<Item> newStackItems = new HashSet<>();
            List<RestockItemDemand> sortedDemands = new ArrayList<>(demands.values());
            sortedDemands.sort(Comparator.comparingLong((RestockItemDemand d) -> d.priority).reversed());

            for (RestockItemDemand demand : sortedDemands) {
                int shulkerAmount = best.itemCounts.getOrDefault(demand.item, 0);
                if (shulkerAmount <= 0) continue;
                int current = inventoryCounts.getOrDefault(demand.item, 0);
                int targetCarry = isLargeBuildingItem(demand.item) ? 512 : 128;
                int shortage = Math.max(demand.inventoryShortage, targetCarry - current);
                int take = Math.min(shulkerAmount, Math.max(0, shortage));
                if (take <= 0) continue;

                if (current <= 0 && !newStackItems.contains(demand.item)) {
                    if (usableSlots <= 0) continue;
                    usableSlots--;
                    newStackItems.add(demand.item);
                }

                plan.amounts.put(demand.item, take);
                plan.remainingToTake.put(demand.item, take);
                plan.beforeCounts.put(demand.item, current);
            }
            return plan.amounts.isEmpty() ? null : plan;
        }

        private static long scoreShulker(ShulkerInfo shulker, Map<Item, RestockItemDemand> demands, int freeSlots, int minimumFreeSlots) {
            long score = 0;
            int unrelatedStacks = 0;
            for (Map.Entry<Item, Integer> entry : shulker.itemCounts.entrySet()) {
                RestockItemDemand demand = demands.get(entry.getKey());
                if (demand == null) {
                    unrelatedStacks++;
                    continue;
                }
                int shortage = Math.max(1, demand.inventoryShortage);
                score += (long) Math.min(entry.getValue(), shortage) * demand.priority;
            }
            score -= 1000;
            score -= unrelatedStacks * 25L;
            if (freeSlots <= minimumFreeSlots) score -= 5000;
            return score;
        }

        private static boolean isLargeBuildingItem(Item item) {
            return item instanceof BlockItem;
        }
    }
}
