package net.neonation.discordbridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.advancement.Advancement;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public class DiscordBridgePlugin extends JavaPlugin implements Listener {
    private WebSocketClient webSocket;
    private final Gson gson = new Gson();
    private String websocketUrl;
    private int reconnectDelay;
    
    // discord role colour to minecraft map
    private static final Map<String, ChatColor> COLOR_MAP = new HashMap<String, ChatColor>() {{
        put("white", ChatColor.WHITE);
        put("light_gray", ChatColor.GRAY);
        put("gray", ChatColor.DARK_GRAY);
        put("dark_gray", ChatColor.DARK_GRAY);
        put("black", ChatColor.BLACK);
        put("red", ChatColor.RED);
        put("dark_red", ChatColor.DARK_RED);
        put("gold", ChatColor.GOLD);
        put("yellow", ChatColor.YELLOW);
        put("dark_green", ChatColor.DARK_GREEN);
        put("green", ChatColor.GREEN);
        put("aqua", ChatColor.AQUA);
        put("dark_aqua", ChatColor.DARK_AQUA);
        put("blue", ChatColor.BLUE);
        put("dark_blue", ChatColor.DARK_BLUE);
        put("light_purple", ChatColor.LIGHT_PURPLE);
        put("dark_purple", ChatColor.DARK_PURPLE);
    }};

    @Override
    public void onEnable() {
        saveDefaultConfig();
        
        websocketUrl = getConfig().getString("websocket.url", "ws://127.0.0.1:8080");
        reconnectDelay = getConfig().getInt("websocket.reconnect-delay", 5);
        
        Bukkit.getPluginManager().registerEvents(this, this);
        
        connectWebSocket();
        
        getLogger().info("Discord Bridge Plugin enabled!");
    }

    @Override
    public void onDisable() {
        if (webSocket != null && !webSocket.isClosed()) {
            webSocket.close();
        }
        getLogger().info("Discord Bridge Plugin disabled!");
    }

    private void connectWebSocket() {
        try {
            URI serverUri = new URI(websocketUrl);
            
            webSocket = new WebSocketClient(serverUri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    getLogger().info("Connected to WebSocket server at " + websocketUrl);
                }

                @Override
                public void onMessage(String message) {
                    handleDiscordMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    getLogger().warning("WebSocket closed: " + reason);
                    Bukkit.getScheduler().runTaskLater(DiscordBridgePlugin.this, 
                        () -> connectWebSocket(), reconnectDelay * 20L);
                }

                @Override
                public void onError(Exception ex) {
                    getLogger().severe("WebSocket error: " + ex.getMessage());
                }
            };
            
            webSocket.connect();
            
        } catch (Exception e) {
            getLogger().severe("Failed to connect to WebSocket: " + e.getMessage());
            Bukkit.getScheduler().runTaskLater(this, 
                () -> connectWebSocket(), reconnectDelay * 20L);
        }
    }

    private void handleDiscordMessage(String json) {
        try {
            JsonObject msg = gson.fromJson(json, JsonObject.class);
            
            String username = msg.get("username").getAsString();
            String colorName = msg.get("userColor").getAsString();
            String content = msg.get("content").getAsString();
            
            ChatColor color = COLOR_MAP.getOrDefault(colorName, ChatColor.AQUA);
            
            String formatted = color + username + ChatColor.RESET + ": " + content;

            Bukkit.getScheduler().runTask(this, () -> {
                Bukkit.broadcastMessage(formatted);
            });
            
        } catch (Exception e) {
            getLogger().warning("Failed to parse Discord message: " + e.getMessage());
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (webSocket == null || !webSocket.isOpen()) {
            return;
        }
        
        try {
            String playerName = event.getPlayer().getName();
            String message = event.getMessage();

            JsonObject payload = new JsonObject();
            payload.addProperty("username", playerName);
            payload.addProperty("avatarURL", "https://mc-heads.net/avatar/" + playerName);
            payload.addProperty("content", message);
            
            webSocket.send(gson.toJson(payload));
            
        } catch (Exception e) {
            getLogger().warning("Failed to send message to Discord: " + e.getMessage());
        }
    }

    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!getConfig().getBoolean("features.join-leave-messages", true)) {
            return;
        }
        
        String playerName = event.getPlayer().getName();
        String message = playerName + " joined the server";
        sendToDiscord("Server", "https://neonation.net/assets/server.png", message);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!getConfig().getBoolean("features.join-leave-messages", true)) {
            return;
        }
        
        String playerName = event.getPlayer().getName();
        String message = playerName + " left the server";
        sendToDiscord("Server", "https://neonation.net/assets/server.png", message);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!getConfig().getBoolean("features.death-messages", true)) {
            return;
        }
        
        String deathMessage = event.getDeathMessage();
        if (deathMessage != null && !deathMessage.isEmpty()) {
            sendToDiscord("Server", "https://neonation.net/assets/server.png", deathMessage);
        }
    }

    @EventHandler
    public void onPlayerAdvancement(PlayerAdvancementDoneEvent event) {
        if (!getConfig().getBoolean("features.advancements", true)) {
            return;
        }
        
        Advancement advancement = event.getAdvancement();
        
        if (advancement.getDisplay() == null) {
            return;
        }
        
        String playerName = event.getPlayer().getName();
        String advancementTitle = advancement.getDisplay().getTitle();
        String message = playerName + " has made the advancement [" + advancementTitle + "]";
        
        sendToDiscord(playerName, 
            "https://mc-heads.net/avatar/" + playerName,
            message);
    }

    /**
     * Helper method to send messages to Discord
     * 
     * @param username Display name in Discord
     * @param avatarURL Avatar URL for the webhook
     * @param content Message content
     */
    private void sendToDiscord(String username, String avatarURL, String content) {
        if (webSocket == null || !webSocket.isOpen()) {
            return;
        }
        
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("username", username);
            payload.addProperty("avatarURL", avatarURL);
            payload.addProperty("content", content);
            
            webSocket.send(gson.toJson(payload));
        } catch (Exception e) {
            getLogger().warning("Failed to send message to Discord: " + e.getMessage());
        }
    }
}
