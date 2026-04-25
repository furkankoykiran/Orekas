package com.webrekas.addon.data;

import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

import java.util.Set;
import java.util.function.Supplier;

/**
 * The 9 ore types orefinder.gg supports. Order matches the website's radio-group layout.
 * The {@code wasmId} values come from the Rust enum {@code q} in the WASM worker:
 * <pre>DIAMONDS=1, ANCIENT_DEBRIS=2, REDSTONE=3, IRON=4, EMERALD=5, GOLD=6, LAPIS=7, COAL=8, COPPER=9</pre>
 *
 * <p>The {@code targetBlocks} set is built lazily: {@link net.minecraft.block.Blocks}
 * static fields require the game's registry bootstrap to have run, which isn't
 * available in unit tests. By deferring the lookup to the first in-game call,
 * we keep {@code OreType.values()} safe to use from any JUnit context.
 */
public enum OreType {
    DIAMOND("Diamond",              1, new SettingColor(  0, 255, 255, 255),
        () -> Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE)),
    ANCIENT_DEBRIS("Ancient Debris",2, new SettingColor(136,  80,  60, 255),
        () -> Set.of(Blocks.ANCIENT_DEBRIS)),
    GOLD("Gold",                    6, new SettingColor(255, 215,   0, 255),
        () -> Set.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE)),
    IRON("Iron",                    4, new SettingColor(220, 220, 210, 255),
        () -> Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE)),
    COPPER("Copper",                9, new SettingColor(255, 130,  50, 255),
        () -> Set.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE)),
    EMERALD("Emerald",              5, new SettingColor(  0, 220,  90, 255),
        () -> Set.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE)),
    REDSTONE("Redstone",            3, new SettingColor(220,  40,  40, 255),
        () -> Set.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE)),
    LAPIS("Lapis",                  7, new SettingColor( 50,  90, 220, 255),
        () -> Set.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE)),
    COAL("Coal",                    8, new SettingColor( 60,  60,  60, 255),
        () -> Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE));

    private final String label;
    private final int wasmId;
    private final SettingColor defaultColor;
    private final Supplier<Set<Block>> targetBlocksSupplier;
    private volatile Set<Block> targetBlocks;

    OreType(String label, int wasmId, SettingColor defaultColor, Supplier<Set<Block>> targetBlocksSupplier) {
        this.label = label;
        this.wasmId = wasmId;
        this.defaultColor = defaultColor;
        this.targetBlocksSupplier = targetBlocksSupplier;
    }

    public String label()              { return label; }
    public int wasmId()                { return wasmId; }
    public SettingColor defaultColor() { return new SettingColor(defaultColor); }

    public Set<Block> targetBlocks() {
        Set<Block> s = targetBlocks;
        if (s == null) {
            synchronized (this) {
                s = targetBlocks;
                if (s == null) s = targetBlocks = targetBlocksSupplier.get();
            }
        }
        return s;
    }

    @Override
    public String toString() { return label; }
}
