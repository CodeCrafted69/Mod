package com.jarvis.commandbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TermuxCommandBridgeClient implements ClientModInitializer {
    public static final String MOD_ID = "termuxcommandbridge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final AtomicBoolean ENABLED = new AtomicBoolean(false);
    private static final AtomicBoolean GAME_READY = new AtomicBoolean(false);
    private static final BlockingQueue<PendingCommand> PENDING_COMMANDS = new LinkedBlockingQueue<>();
    private static final ScheduledExecutorService NETWORK = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Termux-Command-Bridge-Network");
        thread.setDaemon(true);
        return thread;
    });

    private static BridgeConfig config;
    private static KeyMapping toggleKey;

    @Override
    public void onInitializeClient() {
        config = BridgeConfig.loadOrCreate();
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "controls"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.termuxcommandbridge.toggle",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_J,
                category
        ));

        NETWORK.scheduleWithFixedDelay(
                TermuxCommandBridgeClient::pollForCommand,
                config.pollMillis(), config.pollMillis(), TimeUnit.MILLISECONDS);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.consumeClick()) {
                if (!config.isConfigured()) {
                    notifyPlayer(client, "Not configured. Edit config/termux-command-bridge.properties and set the token.");
                    continue;
                }
                boolean nowEnabled = !ENABLED.get();
                ENABLED.set(nowEnabled);
                if (!nowEnabled) {
                    PENDING_COMMANDS.clear();
                }
                notifyPlayer(client, nowEnabled
                        ? "Bridge ENABLED — web commands can be sent while you are connected."
                        : "Bridge DISABLED — queued local commands cleared.");
                LOGGER.info("Command bridge {}", nowEnabled ? "enabled" : "disabled");
            }

            GAME_READY.set(client.player != null && client.getConnection() != null);
            if (!ENABLED.get() || !GAME_READY.get()) {
                PENDING_COMMANDS.clear();
                return;
            }

            PendingCommand pending = PENDING_COMMANDS.poll();
            if (pending == null) {
                return;
            }

            try {
                String command = cleanCommand(pending.command());
                if (command.isEmpty()) {
                    reportResult(pending.id(), "rejected", "Empty command");
                    return;
                }
                // Send as the currently connected player. Server permissions still apply.
                client.getConnection().sendCommand(command);
                reportResult(pending.id(), "sent", "Sent to the connected Minecraft server as your player.");
                LOGGER.info("Sent web-queued Minecraft command id {}", pending.id());
            } catch (Exception exception) {
                LOGGER.warn("Failed to send queued command id {}", pending.id(), exception);
                reportResult(pending.id(), "error", "Could not send command from the client.");
            }
        });

        LOGGER.info("Termux Command Bridge loaded. It is disabled by default; press J to toggle it.");
        LOGGER.info("Bridge config: {}", FabricLoaderPath.configPath());
    }

    private static void pollForCommand() {
        if (!ENABLED.get() || !GAME_READY.get() || !config.isConfigured()) {
            return;
        }
        HttpURLConnection connection = null;
        try {
            connection = openConnection(config.endpoint("/api/next"), "GET");
            int status = connection.getResponseCode();
            if (status != 200) {
                return;
            }
            try (InputStream input = connection.getInputStream()) {
                JsonObject json = JsonParser.parseReader(new java.io.InputStreamReader(input, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (!json.has("command") || json.get("command").isJsonNull()) {
                    return;
                }
                long id = json.has("id") ? json.get("id").getAsLong() : -1L;
                String command = json.get("command").getAsString();
                if (id < 0 || command == null || command.isBlank() || command.length() > 2048
                        || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0 || command.indexOf('\0') >= 0) {
                    if (id >= 0) {
                        postResult(id, "rejected", "Invalid or oversized command payload.");
                    }
                    return;
                }
                if (ENABLED.get() && GAME_READY.get()) {
                    PENDING_COMMANDS.offer(new PendingCommand(id, command));
                } else {
                    postResult(id, "rejected", "Bridge was disabled or Minecraft disconnected.");
                }
            }
        } catch (Exception exception) {
            // Avoid filling the log every polling interval when the site is offline.
            LOGGER.debug("Bridge poll failed: {}", exception.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void reportResult(long id, String status, String message) {
        NETWORK.execute(() -> postResult(id, status, message));
    }

    private static void postResult(long id, String status, String message) {
        HttpURLConnection connection = null;
        try {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("status", status);
            json.addProperty("message", message);
            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
            connection = openConnection(config.endpoint("/api/result"), "POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            connection.getResponseCode();
        } catch (Exception exception) {
            LOGGER.debug("Could not report command result: {}", exception.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static HttpURLConnection openConnection(String endpoint, String method) throws IOException {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(1800);
            connection.setReadTimeout(1800);
            connection.setRequestProperty("Authorization", "Bearer " + config.token());
            connection.setRequestProperty("Accept", "application/json");
            connection.setUseCaches(false);
            return connection;
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid bridge URL: " + endpoint, exception);
        }
    }

    private static String cleanCommand(String raw) {
        if (raw == null) {
            return "";
        }
        String command = raw.trim();
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.length() > 2048 || command.contains("\n") || command.contains("\r") || command.indexOf('\0') >= 0) {
            return "";
        }
        return command;
    }

    private static void notifyPlayer(Minecraft client, String message) {
        if (client.player != null) {
            client.player.displayClientMessage(Component.literal("[Command Bridge] " + message), false);
        }
    }

    private record PendingCommand(long id, String command) { }

    private static final class FabricLoaderPath {
        private static String configPath() {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                    .resolve("termux-command-bridge.properties").toString();
        }
    }
}
