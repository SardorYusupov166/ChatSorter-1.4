package dev.chatsorter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Per-server local chat archive. Text components are stored as Minecraft JSON. */
public final class ChatHistoryStorage {
    private static final Path DIRECTORY = FabricLoader.getInstance().getConfigDir().resolve("chatsorter").resolve("history");
    private static long lastSaveMillis;

    public static synchronized List<ChatHistoryEntry> load(String serverKey, int limit) {
        if (!ChatExperienceSettings.restoreHistory || serverKey == null) return List.of();
        Path file = file(serverKey);
        if (!Files.isRegularFile(file)) return List.of();
        List<ChatHistoryEntry> loaded = new ArrayList<>();
        try {
            JsonArray array = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            int start = Math.max(0, array.size() - ChatExperienceSettings.clamp(limit));
            MinecraftClient mc = MinecraftClient.getInstance();
            RegistryWrapper.WrapperLookup registries = mc.world == null ? null : mc.world.getRegistryManager();
            for (int i = start; i < array.size(); i++) {
                JsonObject o = array.get(i).getAsJsonObject();
                String json = o.has("component") ? o.get("component").getAsString() : "";
                Text message = null;
                if (registries != null && !json.isBlank()) {
                    try { message = Text.Serialization.fromJson(json, registries); } catch (Exception ignored) { }
                }
                if (message == null) message = Text.literal(o.has("plain") ? o.get("plain").getAsString() : "");
                ChatSorter.Tab tab;
                try { tab = ChatSorter.Tab.valueOf(o.get("tab").getAsString()); }
                catch (Exception e) { tab = ChatSorter.Tab.CONSOLE; }
                ChatHistoryEntry entry = new ChatHistoryEntry(message, o.get("time").getAsString(), tab,
                    o.has("sender") ? o.get("sender").getAsString() : "",
                    "Восстановлено из локальной истории", o.has("repeats") ? o.get("repeats").getAsInt() : 1, true);
                entry.componentJson = json;
                loaded.add(entry);
            }
        } catch (Exception e) {
            System.err.println("[ChatSorter] Could not load chat history: " + e.getMessage());
        }
        return loaded;
    }

    public static synchronized void save(String serverKey, List<ChatHistoryEntry> entries, boolean force) {
        if (serverKey == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastSaveMillis < 2000) return;
        lastSaveMillis = now;
        try {
            Files.createDirectories(DIRECTORY);
            JsonArray array = new JsonArray();
            MinecraftClient mc = MinecraftClient.getInstance();
            RegistryWrapper.WrapperLookup registries = mc.world == null ? null : mc.world.getRegistryManager();
            int start = Math.max(0, entries.size() - ChatExperienceSettings.clamp(ChatExperienceSettings.restoreLimit));
            for (int i = start; i < entries.size(); i++) {
                ChatHistoryEntry entry = entries.get(i);
                JsonObject o = new JsonObject();
                o.addProperty("time", entry.time);
                o.addProperty("tab", entry.tab.name());
                o.addProperty("sender", entry.sender);
                o.addProperty("repeats", entry.repeats);
                o.addProperty("plain", entry.message.getString());
                if (entry.componentJson.isBlank() && registries != null) {
                    try { entry.componentJson = Text.Serialization.toJsonString(entry.message, registries); }
                    catch (Exception ignored) { }
                }
                if (!entry.componentJson.isBlank()) o.addProperty("component", entry.componentJson);
                array.add(o);
            }
            Path target = file(serverKey);
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary, array.toString(), StandardCharsets.UTF_8);
            try { Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) { System.err.println("[ChatSorter] Could not save chat history: " + e.getMessage()); }
    }

    public static synchronized void clear(String serverKey) {
        if (serverKey == null) return;
        try { Files.deleteIfExists(file(serverKey)); }
        catch (Exception e) { System.err.println("[ChatSorter] Could not clear chat history: " + e.getMessage()); }
    }

    private static Path file(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return DIRECTORY.resolve(HexFormat.of().formatHex(hash) + ".json");
        } catch (Exception e) { return DIRECTORY.resolve("unknown.json"); }
    }
    private ChatHistoryStorage() { }
}
