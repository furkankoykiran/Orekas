package com.webrekas.addon.modules;

import com.webrekas.addon.OrekasAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.List;

/**
 * Sells items from the player's inventory using DonutSMP's /sell command.
 *
 * Improvements over the Glazed reference:
 *  - Slot mapping uses inventory-identity comparison (s.inventory == playerInv)
 *    instead of a hardcoded offset, so it works for any chest-row count.
 *  - Whitelist / Blacklist sell modes are respected correctly.
 *  - Full state machine with per-stage timeouts instead of a flat tick counter.
 *  - Sell counter shown in the module info string.
 */
public class AutoSellModule extends Module {

    private enum Stage { IDLE, OPEN_SELL, WAIT_GUI, SELLING }

    private volatile Stage stage      = Stage.IDLE;
    private volatile long  stageStart = 0L;
    private int             stacksSold = 0;
    private int             delayCounter = 0;

    // ── Settings ──────────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<SellMode> mode = sgGeneral.add(new EnumSetting.Builder<SellMode>()
        .name("mode")
        .description("Whitelist: sell only listed items. Blacklist: sell everything except listed items.")
        .defaultValue(SellMode.WHITELIST)
        .build());

    private final Setting<List<Item>> itemList = sgGeneral.add(new ItemListSetting.Builder()
        .name("items")
        .description("Items included in the whitelist or blacklist.")
        .defaultValue(List.of(Items.SEA_PICKLE))
        .build());

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks")
        .description("Ticks to wait between individual sell actions.")
        .defaultValue(1)
        .min(0).max(20).sliderMax(10)
        .build());

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Show status messages in chat.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Log detailed state and slot decisions to chat.")
        .defaultValue(false)
        .build());

    // ── Constructor ───────────────────────────────────────────────────────────

    public AutoSellModule() {
        super(OrekasAddon.CATEGORY, "auto-sell",
            "Automatically sells items via DonutSMP /sell command.");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        stacksSold   = 0;
        delayCounter = 20; // initial settle delay
        advance(Stage.OPEN_SELL);
    }

    @Override
    public void onDeactivate() {
        stage = Stage.IDLE;
    }

    // ── Tick handler ──────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.interactionManager == null) return;

        if (delayCounter > 0) { delayCounter--; return; }

        switch (stage) {
            case OPEN_SELL -> {
                mc.getNetworkHandler().sendChatCommand("sell");
                advance(Stage.WAIT_GUI);
                delayCounter = 20;
            }

            case WAIT_GUI -> {
                if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
                    advance(Stage.SELLING);
                } else if (elapsed() > 3_000) {
                    advance(Stage.OPEN_SELL); // timed out – retry
                }
            }

            case SELLING -> doSell();

            default -> {}
        }
    }

    // ── Sell logic ────────────────────────────────────────────────────────────

    private void doSell() {
        ScreenHandler h = mc.player.currentScreenHandler;
        if (!(h instanceof GenericContainerScreenHandler)) {
            advance(Stage.OPEN_SELL);
            return;
        }

        PlayerInventory playerInv = mc.player.getInventory();

        // If every non-player slot (the sell area) is occupied, reopen the GUI
        boolean sellAreaFull = h.slots.stream()
            .filter(s -> s.inventory != playerInv)
            .noneMatch(s -> s.getStack().isEmpty());
        if (sellAreaFull) {
            if (notifications.get()) info("Sell area full – reopening /sell GUI.");
            mc.player.closeHandledScreen();
            advance(Stage.OPEN_SELL);
            delayCounter = actionDelay.get();
            return;
        }

        // Find the next player-inventory slot with a sellable item
        for (Slot slot : h.slots) {
            if (slot.inventory != playerInv) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || !shouldSell(stack.getItem())) continue;

            mc.interactionManager.clickSlot(
                h.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
            delayCounter = actionDelay.get();
            stacksSold++;
            return;
        }

        // Nothing left to sell
        if (notifications.get()) info("Done. Moved %d stack(s) to sell GUI.", stacksSold);
        mc.player.closeHandledScreen();
        toggle();
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private boolean shouldSell(Item item) {
        boolean listed = itemList.get().contains(item);
        return mode.get() == SellMode.WHITELIST ? listed : !listed;
    }

    private void advance(Stage next) {
        stage      = next;
        stageStart = System.currentTimeMillis();
    }

    private long elapsed() { return System.currentTimeMillis() - stageStart; }

    @Override
    public String getInfoString() {
        if (!isActive() || stage == Stage.IDLE) return null;
        return stage.name() + " (" + stacksSold + ")";
    }

    public enum SellMode { WHITELIST, BLACKLIST }
}
