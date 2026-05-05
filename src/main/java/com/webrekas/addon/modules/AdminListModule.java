package com.webrekas.addon.modules;

import com.webrekas.addon.OrekasAddon;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;

import java.util.List;

/**
 * Stores a named list of administrators (or any notable players) that other
 * Orekas modules can consult to adjust their behaviour.
 *
 * Usage from another module:
 * <pre>
 *   AdminListModule al = Modules.get().get(AdminListModule.class);
 *   boolean isAdmin = al != null && al.isAdmin(playerName);
 * </pre>
 *
 * The {@link Role} enum lets each consuming module decide whether the list
 * acts as a blacklist (skip / alert) or a whitelist (trust / ignore).
 */
public class AdminListModule extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<List<String>> admins = sgGeneral.add(new StringListSetting.Builder()
        .name("admins")
        .description("Player names treated as administrators by other modules.")
        .defaultValue(List.of())
        .build());

    public AdminListModule() {
        super(OrekasAddon.CATEGORY, "admin-list",
            "Named player list shared with other modules as a blacklist or whitelist.");
    }

    /**
     * Returns true when the module is active and {@code name} is in the list
     * (case-insensitive). Always returns false when the module is disabled so
     * that disabling AdminList is a clean global off-switch.
     */
    public boolean isAdmin(String name) {
        if (!isActive() || name == null) return false;
        return admins.get().stream().anyMatch(a -> a.equalsIgnoreCase(name));
    }

    /**
     * How a consuming module should treat players found in AdminList.
     *
     * <ul>
     *   <li>{@link #OFF} – AdminList is not consulted at all.</li>
     *   <li>{@link #BLACKLIST} – Admin players are treated as blacklisted:
     *       OrderSniper skips their orders; PlayerDetection ignores its own
     *       personal whitelist and always alerts when an admin is present.</li>
     *   <li>{@link #WHITELIST} – Admin players are treated as trusted:
     *       OrderSniper only accepts their orders; PlayerDetection adds them
     *       to the safe/ignore set so no alert fires.</li>
     * </ul>
     */
    public enum Role { OFF, BLACKLIST, WHITELIST }
}
