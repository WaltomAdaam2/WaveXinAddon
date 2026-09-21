package me.waltom.wavexin.modules.craftinggrid;

import me.waltom.wavexin.WaveXinAddon;
import me.waltom.wavexin.core.WaveXinDataPaths;
import me.waltom.wavexin.core.WaveXinModule;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

public final class CraftingGridAutoRefill extends WaveXinModule {
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<String> kitName = general.add(new StringSetting.Builder()
        .name("Kit Name").description("Kit filename in meteor-client/wavexin/kits, without .kit.")
        .defaultValue("default").onChanged(value -> { if (isActive()) loadKit(value); }).build());
    private final Setting<Integer> delayMs = general.add(new IntSetting.Builder()
        .name("Delay").description("Minimum milliseconds between grid refill actions.")
        .defaultValue(25).range(0, 1000).sliderRange(0, 1000).build());
    private final Setting<Integer> safeRange = general.add(new IntSetting.Builder()
        .name("Safe Range").description("Pauses while a non-friend player is closer than this distance; zero disables the check.")
        .defaultValue(10).range(0, 64).sliderRange(0, 64).build());
    private final Map<Integer, Item> layout = new TreeMap<>();
    private long lastAction;

    public CraftingGridAutoRefill() {
        super(WaveXinAddon.CATEGORY, "crafting-grid-auto-refill", "Refills a configured 2x2 crafting grid from your inventory.");
    }

    @Override
    public void onActivate() {
        lastAction = System.nanoTime() / 1_000_000L - 1000;
        loadKit(kitName.get());
    }

    @Override
    public void onDeactivate() {
        layout.clear();
    }

    private void loadKit(String name) {
        layout.clear();
        if (!CraftingKit.validName(name)) {
            stop("warning.wavexin.crafting_grid.invalid_name", "Kit names may contain only letters, digits, hyphens and underscores.");
            return;
        }
        Path path = WaveXinDataPaths.KITS_DIRECTORY.resolve(name + ".kit");
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > 65_536) {
                stop("warning.wavexin.crafting_grid.missing_kit", "Create a kit (up to 64 KiB) in meteor-client/wavexin/kits before enabling this module.");
                return;
            }
            for (var entry : CraftingKit.parse(Files.readString(path, StandardCharsets.UTF_8)).entrySet()) {
                Item item = resolveItem(entry.getValue());
                if (item != null && item != Items.AIR) layout.put(entry.getKey(), item);
            }
            if (layout.isEmpty()) stop("warning.wavexin.crafting_grid.empty_kit", "The kit has no valid crafting slots. Use entries such as 500:minecraft:obsidian.");
        } catch (IOException | RuntimeException failure) {
            stop("warning.wavexin.crafting_grid.read_failed", "Could not read the crafting kit.");
        }
    }

    private static Item resolveItem(String value) {
        if (value.matches("[0-9]{1,9}")) return Item.byRawId(Integer.parseInt(value));
        Identifier id = Identifier.tryParse(value);
        if (id != null && Registries.ITEM.containsId(id)) return Registries.ITEM.get(id);
        for (Item item : Registries.ITEM) if (item.getTranslationKey().equals(value)) return item;
        return null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || layout.isEmpty()) return;
        var handler = mc.player.playerScreenHandler;
        long now = System.nanoTime() / 1_000_000L;
        if (!CraftingKit.canRefill(mc.player.currentScreenHandler == handler, handler.getCursorStack().isEmpty(),
            nearbyNonFriend(), now - lastAction, delayMs.get())) return;
        for (var entry : layout.entrySet()) {
            int target = entry.getKey();
            Item wanted = entry.getValue();
            ItemStack present = handler.getSlot(target).getStack();
            if (!present.isEmpty() && !matchesLayout(present.getItem(), wanted)) {
                // Do not repeatedly quick-move when the player's inventory cannot accept it.
                if (!canStore(present)) continue;
                click(target, SlotActionType.QUICK_MOVE);
                lastAction = now;
                return;
            }
            int donor = -1;
            int largest = -1;
            for (int slot = 9; slot <= 44; slot++) {
                ItemStack stack = handler.getSlot(slot).getStack();
                if (stack.isEmpty() || stack.getItem() != wanted) continue;
                if (present.isEmpty()) { donor = slot; break; }
                if (ItemStack.areItemsAndComponentsEqual(present, stack) && present.isStackable()
                    && CraftingKit.donorSufficient(present.getCount(), present.getMaxCount(), stack.getCount())
                    && stack.getCount() > largest) {
                    donor = slot;
                    largest = stack.getCount();
                }
            }
            if (donor == -1) continue;
            click(donor, SlotActionType.PICKUP);
            click(target, SlotActionType.PICKUP);
            if (!handler.getCursorStack().isEmpty()) click(donor, SlotActionType.PICKUP);
            lastAction = now;
            return;
        }
    }

    private boolean canStore(ItemStack input) {
        int remaining = input.getCount();
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = mc.player.playerScreenHandler.getSlot(slot).getStack();
            if (stack.isEmpty()) return true;
            if (ItemStack.areItemsAndComponentsEqual(input, stack)) remaining -= stack.getMaxCount() - stack.getCount();
            if (remaining <= 0) return true;
        }
        return false;
    }

    private static boolean matchesLayout(Item actual, Item wanted) {
        if (actual == wanted) return true;
        if ((actual == Items.PISTON || actual == Items.STICKY_PISTON)
            && (wanted == Items.PISTON || wanted == Items.STICKY_PISTON)) return true;
        return chestEquipment(actual) && chestEquipment(wanted);
    }

    private static boolean chestEquipment(Item item) {
        return item == Items.ELYTRA || item == Items.LEATHER_CHESTPLATE || item == Items.CHAINMAIL_CHESTPLATE
            || item == Items.IRON_CHESTPLATE || item == Items.GOLDEN_CHESTPLATE
            || item == Items.DIAMOND_CHESTPLATE || item == Items.NETHERITE_CHESTPLATE;
    }

    private boolean nearbyNonFriend() {
        if (safeRange.get() == 0) return false;
        double rangeSquared = (double) safeRange.get() * safeRange.get();
        for (PlayerEntity other : mc.world.getPlayers()) {
            if (other != mc.player && other.isAlive() && !other.isSpectator()
                && !Friends.get().isFriend(other) && mc.player.squaredDistanceTo(other) < rangeSquared) return true;
        }
        return false;
    }

    private void click(int slot, SlotActionType action) {
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, slot, 0, action, mc.player);
    }

    private void stop(String key, String fallback) {
        warningKey(key, fallback);
        if (isActive()) toggle();
    }
}
