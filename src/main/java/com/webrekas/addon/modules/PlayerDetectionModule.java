package com.webrekas.addon.modules;

import com.webrekas.addon.OrekasAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Detects non-whitelisted players entering render distance and triggers
 * configurable alert / defence actions.
 *
 * Improvements over the Glazed reference:
 *  - `known` is a CopyOnWriteArraySet: safe for concurrent read from render
 *    thread and write from game thread without manual synchronisation.
 *  - Detection now runs on TickEvent.Post (game thread) to keep world reads
 *    and chat sends on the same thread as the rest of game logic.
 *  - Only newly arrived players (delta from previous tick snapshot) trigger
 *    the alert, preventing repeated firing while the same player is present.
 *  - Webhook URL is validated (must start with https://) before any HTTP call.
 *  - JSON building escapes backslashes and quotes in player names / server
 *    addresses to prevent malformed payloads.
 *  - The stop command is configurable rather than hardcoded to "#stop".
 *  - Disconnect runs on a background thread after a short delay so that all
 *    synchronous actions (module toggle, command send) complete first.
 */
public class PlayerDetectionModule extends Module {

    // Names that must never trigger detection (e.g. freecam ghost entities)
    private static final Set<String> PERMANENT_WHITELIST = Set.of("FreeCamera");

    // ── Settings ──────────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral   = settings.getDefaultGroup();
    private final SettingGroup sgAlert     = settings.createGroup("Alert");
    private final SettingGroup sgActions   = settings.createGroup("Actions");
    private final SettingGroup sgAdminList = settings.createGroup("Admin List");
    private final SettingGroup sgWebhook   = settings.createGroup("Webhook");

    private final Setting<List<String>> whitelist = sgGeneral.add(new StringListSetting.Builder()
        .name("whitelist")
        .description("Players to always ignore (friends, alts, etc.).")
        .defaultValue(new ArrayList<>())
        .build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Log player arrivals/departures and trigger decisions to chat.")
        .defaultValue(false)
        .build());

    private final Setting<AlertMode> alertMode = sgAlert.add(new EnumSetting.Builder<AlertMode>()
        .name("alert-mode")
        .description("How to notify when a player is detected.")
        .defaultValue(AlertMode.BOTH)
        .build());

    private final Setting<String> stopCommand = sgActions.add(new StringSetting.Builder()
        .name("stop-command")
        .description("Chat command sent on detection (e.g. #stop to halt macros). Leave blank to skip.")
        .defaultValue("#stop")
        .build());

    private final Setting<List<Module>> modulesToToggle = sgActions.add(new ModuleListSetting.Builder()
        .name("toggle-modules")
        .description("Modules to deactivate when a player is detected.")
        .defaultValue(new ArrayList<>())
        .build());

    private final Setting<Boolean> selfDisable = sgActions.add(new BoolSetting.Builder()
        .name("self-disable")
        .description("Deactivate this module after triggering.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> disconnectOnDetect = sgActions.add(new BoolSetting.Builder()
        .name("disconnect")
        .description("Disconnect from the server when a player is detected.")
        .defaultValue(true)
        .build());

    private final Setting<AdminListModule.Role> adminListRole = sgAdminList.add(new EnumSetting.Builder<AdminListModule.Role>()
        .name("role")
        .description("Whitelist: treat admin players as safe (no alert). Blacklist: always alert for admins, overriding personal whitelist.")
        .defaultValue(AdminListModule.Role.OFF)
        .build());

    private final Setting<Boolean> enableWebhook = sgWebhook.add(new BoolSetting.Builder()
        .name("enable")
        .description("Send a Discord webhook notification on detection.")
        .defaultValue(false)
        .build());

    private final Setting<String> webhookUrl = sgWebhook.add(new StringSetting.Builder()
        .name("url")
        .description("Discord webhook URL (must start with https://).")
        .defaultValue("")
        .visible(enableWebhook::get)
        .build());

    private final Setting<Boolean> pingOnDetect = sgWebhook.add(new BoolSetting.Builder()
        .name("ping-me")
        .description("Ping your Discord user ID in the webhook message.")
        .defaultValue(false)
        .visible(enableWebhook::get)
        .build());

    private final Setting<String> discordId = sgWebhook.add(new StringSetting.Builder()
        .name("discord-id")
        .description("Your Discord user ID (for @-mention in webhook).")
        .defaultValue("")
        .visible(() -> enableWebhook.get() && pingOnDetect.get())
        .build());

    // ── State ─────────────────────────────────────────────────────────────────

    /**
     * Snapshot of players visible on the previous tick.
     * CopyOnWriteArraySet gives safe iteration without explicit locking.
     */
    private final CopyOnWriteArraySet<String> known = new CopyOnWriteArraySet<>();

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    // ── Constructor ───────────────────────────────────────────────────────────

    public PlayerDetectionModule() {
        super(OrekasAddon.CATEGORY, "player-detection",
            "Alerts and acts when non-whitelisted players enter render distance.");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override public void onActivate()   { known.clear(); }
    @Override public void onDeactivate() { known.clear(); }

    // ── Tick handler (game thread) ────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // Build the full ignore set
        Set<String> ignoreSet = new HashSet<>(PERMANENT_WHITELIST);
        whitelist.get().forEach(n -> ignoreSet.add(n.toLowerCase(Locale.ROOT)));

        // Apply AdminList policy
        AdminListModule al = Modules.get().get(AdminListModule.class);
        if (al != null && al.isActive()) {
            AdminListModule.Role role = adminListRole.get();
            if (role == AdminListModule.Role.WHITELIST) {
                // Admins are trusted – add to ignore set so no alert fires
                al.admins.get().forEach(n -> ignoreSet.add(n.toLowerCase(Locale.ROOT)));
            } else if (role == AdminListModule.Role.BLACKLIST) {
                // Admins override personal whitelist – remove from ignore set so they always trigger
                al.admins.get().forEach(n -> ignoreSet.remove(n.toLowerCase(Locale.ROOT)));
            }
        }

        // Collect currently visible non-ignored players
        Set<String> current = new HashSet<>();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            String name = p.getGameProfile().name();
            if (!ignoreSet.contains(name.toLowerCase(Locale.ROOT))) current.add(name);
        }

        // Compute newly arrived players (present now but not last tick)
        Set<String> arrived = new HashSet<>(current);
        arrived.removeAll(known);

        if (!arrived.isEmpty()) {
            trigger(arrived);
        }

        // Update known to mirror current state
        known.clear();
        known.addAll(current);
    }

    // ── Alert / action dispatch ───────────────────────────────────────────────

    private void trigger(Set<String> players) {
        String list = String.join(", ", players);

        switch (alertMode.get()) {
            case CHAT -> info("Player(s) detected: (highlight)%s", list);
            case TOAST -> mc.getToastManager().add(
                new MeteorToast.Builder(title).text("Player Detected: " + list).icon(Items.PLAYER_HEAD).build());
            case BOTH -> {
                info("Player(s) detected: (highlight)%s", list);
                mc.getToastManager().add(
                    new MeteorToast.Builder(title).text("Player Detected: " + list).icon(Items.PLAYER_HEAD).build());
            }
        }

        String cmd = stopCommand.get().trim();
        if (!cmd.isEmpty()) ChatUtils.sendPlayerMsg(cmd);

        for (Module m : modulesToToggle.get()) {
            if (m.isActive()) m.toggle();
        }

        if (enableWebhook.get()) sendWebhook(players);

        // Self-disable before disconnect so other modules have already received
        // the toggle signal while the connection is still alive.
        if (selfDisable.get()) toggle();

        if (disconnectOnDetect.get()) {
            CompletableFuture.runAsync(() -> {
                try { Thread.sleep(350); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                disconnect(list);
            });
        }
    }

    private void disconnect(String playerList) {
        if (mc.world != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().getConnection()
                .disconnect(Text.literal("PlayerDetection: " + playerList));
        }
    }

    // ── Webhook ───────────────────────────────────────────────────────────────

    private void sendWebhook(Set<String> players) {
        String url = webhookUrl.get().trim();
        if (!url.startsWith("https://")) {
            warning("Webhook URL must start with https://. Skipping.");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                String playerList = escapeJson(String.join(", ", players));
                String server     = escapeJson(mc.getCurrentServerEntry() != null
                    ? mc.getCurrentServerEntry().address : "Unknown");
                String ping = (pingOnDetect.get() && !discordId.get().isBlank())
                    ? "<@" + discordId.get().trim() + ">" : "";
                long epoch = System.currentTimeMillis() / 1000;

                String body = String.format(
                    "{\"content\":\"%s\",\"embeds\":[{" +
                    "\"title\":\"Player Detected\"," +
                    "\"color\":15158332," +
                    "\"fields\":[" +
                    "{\"name\":\"Players\",\"value\":\"%s\",\"inline\":false}," +
                    "{\"name\":\"Server\",\"value\":\"%s\",\"inline\":true}," +
                    "{\"name\":\"Time\",\"value\":\"<t:%d:R>\",\"inline\":true}" +
                    "]," +
                    "\"footer\":{\"text\":\"Orekas PlayerDetection\"}" +
                    "}]}",
                    escapeJson(ping), playerList, server, epoch);

                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(15))
                    .build();

                http.send(req, HttpResponse.BodyHandlers.discarding());

            } catch (IOException e) {
                // Silently swallow – we're in a background thread after disconnect
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Minimal JSON string escaping for values embedded in string literals. */
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── Info string ───────────────────────────────────────────────────────────

    @Override
    public String getInfoString() {
        int n = known.size();
        return isActive() && n > 0 ? String.valueOf(n) : null;
    }

    public enum AlertMode { CHAT, TOAST, BOTH }
}
