package com.jellypudding.discordRelay;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import org.geysermc.floodgate.util.DeviceOs;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.EmbedBuilder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class DiscordRelay extends JavaPlugin implements Listener, TabCompleter{

    private JDA jda;
    private String playerListChannelId;
    private String deathChannelId;
    private String eventChannelId;
    private String chatChannelId;
    private String serverPrefix;
    private String mode;
    private boolean isConfigured = false;
    private long startTime;
    private long onlineTime;
    private long[] timeArray;
    private String[] nameArray;

    private void loadConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();
        String token = config.getString("discord-bot-token");
        eventChannelId = config.getString("join-leave-channel-id");
        chatChannelId = config.getString("chat-channel-id");
        deathChannelId = config.getString("death-channel-id");
        playerListChannelId = config.getString("playerlist-channel-id");
        serverPrefix = config.getString("server-prefix-id");
        mode = config.getString("channel-type");

        if (eventChannelId.equals("YOUR_CHANNEL_ID_HERE")||eventChannelId.equals("")) {
            eventChannelId = null;
        }
        if (chatChannelId.equals("YOUR_CHANNEL_ID_HERE")||chatChannelId.equals("")) {
            chatChannelId = null;
        }
        if (deathChannelId.equals("YOUR_CHANNEL_ID_HERE")||deathChannelId.equals("")) {
            deathChannelId = null;
        }
        if (playerListChannelId.equals("YOUR_CHANNEL_ID_HERE")||playerListChannelId.equals("")) {
            playerListChannelId = null;
        }
        if (serverPrefix.equals("YOUR_SERVER_PREFIX_ID_HERE")) {
            serverPrefix = "";
        }
        isConfigured = token != null;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        timeArray = new long[50];
        nameArray = new String[50];
        startTime = System.currentTimeMillis();
        if (isConfigured) {
            initializePlugin(false);
        } else {
            getLogger().warning("The Discord bot is not yet configured. Please check your DiscordRelay/config.yml file.");
        }
    }

    private void initializePlugin(boolean isReload) {
        connectToDiscord(isReload);
        if (isConfigured) {
            registerListeners();
            Objects.requireNonNull(getCommand("discordrelay")).setTabCompleter(this);
        }
    }

    private void registerListeners() {
        HandlerList.unregisterAll((JavaPlugin) this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void connectToDiscord(boolean isReload) {
        try {
            if (jda != null) {
                jda.shutdown();
                jda = null;
            }
            jda = JDABuilder.createDefault(getConfig().getString("discord-bot-token"))
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MEMBERS)
                    .addEventListeners(new DiscordListener())
                    .build();
            jda.awaitReady();

            getLogger().info("Discord bot connected successfully!");

            if (!isReload && playerListChannelId != null) {
                sendToDiscord("**:green_circle:" + serverPrefix +"が起動しました**");
            }
        } catch (Exception e) {
            getLogger().severe("Failed to connect to Discord. Please check your bot token and try again.");
            isConfigured = false;
        }
    }

    @Override
    public void onDisable() {
        if (jda != null) {
            try {
                if (playerListChannelId != null) {
                    sendToDiscord("**:red_circle:" + serverPrefix + "が終了しました**");
                }
                jda.removeEventListener(jda.getRegisteredListeners());
                jda.shutdownNow();
                try {
                    if (!jda.awaitShutdown(Duration.ofSeconds(2))) {
                        getLogger().warning("JDA did not shut down in time. Forcing shutdown.");
                        jda.shutdown();
                    }
                } catch (InterruptedException e) {
                    getLogger().warning("Interrupted while shutting down JDA. Forcing shutdown.");
                    jda.shutdown();
                    Thread.currentThread().interrupt();
                }
                getLogger().info("Discord bot disconnected successfully.");
            } catch (Exception e) {
                getLogger().warning("Error during JDA shutdown: " + e.getMessage());
            }
        }
        HandlerList.unregisterAll((JavaPlugin) this);
    }

    @EventHandler
    public void onPlayerChat(AsyncChatEvent event) {
        if (!isConfigured) return;
        if (chatChannelId != null) {
            if (event.isAsynchronous()) {
                String playerName = event.getPlayer().getName();
                String message = PlainTextComponentSerializer.plainText().serialize(event.message());
                sendPlayerMessageToDiscord(playerName, message);
            } else {
                getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    String playerName = event.getPlayer().getName();
                    String message = PlainTextComponentSerializer.plainText().serialize(event.message());
                    sendPlayerMessageToDiscord(playerName, message);
                });
            }
        }
    }

    private void sendPlayerMessageToDiscord(String playerName, String message) {
        if (jda != null) {
            if (playerName != "Server") {
                TextChannel channel = jda.getTextChannelById(chatChannelId);
                ThreadChannel thread = jda.getThreadChannelById(chatChannelId);
                String avatarUrl = String.format("https://mc-heads.net/avatar/%s", playerName);
                EmbedBuilder embed = new EmbedBuilder()
                        .setAuthor("<" + playerName + ">", null, avatarUrl)
                        .setTitle(message)
                        .setColor(Color.YELLOW);
                if (channel != null && mode.equals("CHANNEL")) {
                    channel.sendMessageEmbeds(embed.build()).queue();
                } else if (thread != null && mode.equals("THREAD")) {
                    thread.sendMessageEmbeds(embed.build()).queue();
                } else {
                    getLogger().info("Discord channel not found!");
                }
            }
        }
    }

    private void sendPlayerEventToDiscord(String playerName, String action, String comment, Color color) {
        if (!isConfigured) return;

        if (jda != null) {
            TextChannel channel = jda.getTextChannelById(eventChannelId);
            ThreadChannel thread = jda.getThreadChannelById(eventChannelId);
            String avatarUrl = String.format("https://mc-heads.net/avatar/%s", playerName);
            EmbedBuilder embed = new EmbedBuilder()
                    .setAuthor("<" + playerName + "> " + action, null, avatarUrl)
                    .setFooter(comment)
                    .setColor(color);
            if (channel != null && mode.equals("CHANNEL")) {
                channel.sendMessageEmbeds(embed.build()).queue();
            } else if (thread != null && mode.equals("THREAD")) {
                thread.sendMessageEmbeds(embed.build()).queue();
            } else {
                getLogger().info("Discord channel not found!");
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        String playerName = event.getPlayer().getName();
        String onDevice;
        int i = 0;
        do {
            if (nameArray[i] == null) {
                nameArray[i] = playerName;
                timeArray[i] = System.currentTimeMillis();
                break;
            } else {
                i++;
            }
        } while ( i < 50);
        FloodgatePlayer onBedrock = FloodgateApi.getInstance().getPlayer(event.getPlayer().getUniqueId());
        if (eventChannelId != null) {
            if (onBedrock != null) {
                DeviceOs device = onBedrock.getDeviceOs();
                if (device == DeviceOs.NX) {
                    onDevice = "Nintendo Switch";
                } else if (device == DeviceOs.PS4) {
                    onDevice = "PlayStation";
                } else if (device == DeviceOs.XBOX) {
                    onDevice = "Microsoft Xbox";
                } else if (device == DeviceOs.GEARVR || device == DeviceOs.HOLOLENS) {
                    onDevice = "VR Device";
                } else if (device == DeviceOs.WIN32 || device == DeviceOs.UWP || device == DeviceOs.WINDOWS_PHONE) {
                    onDevice = "Windows Device";
                } else if (device == DeviceOs.IOS) {
                    onDevice = "Apple Device";
                } else if (device == DeviceOs.GOOGLE || device == DeviceOs.AMAZON) {
                    onDevice = "Android Device";
                } else {
                    onDevice = device.toString();
                }
                sendPlayerEventToDiscord(playerName, "接続しました", "Bedrock Edition - " + onDevice, Color.GREEN);
            } else {
                sendPlayerEventToDiscord(playerName, "接続しました", "Java Edition", Color.GREEN);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        String playerName = event.getPlayer().getName();
        int i = 0;
        long online = System.currentTimeMillis();
        long days = 0;
        long hours = 0;
        long minutes = 0;
        do {
            if (nameArray[i] == playerName) {
                online -= timeArray[i];
                days = online / (1000 * 60 * 60 * 24);
                hours = (online / (1000 * 60 * 60)) % 24;
                minutes = (online / (1000 * 60)) % 60;
                break;
            } else {
                i++;
            }
        } while (i < 50);
        nameArray[i] = null;
        String timeString;
        if (days != 0) {
            timeString = String.format("接続日数: %d日\n接続時間: %d時間 %d分", days,hours,minutes);
        } else if ( hours != 0) {
            timeString = String.format("接続時間: %d時間 %d分", hours,minutes);
        } else if (minutes != 0) {
            timeString = String.format("接続時間: %d分", minutes);
        } else timeString = "";
        if (eventChannelId != null) {
            sendPlayerEventToDiscord(playerName, "切断されました", timeString, Color.RED);
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        String playerName = event.getEntity().getName();
        int playerLevel = event.getEntity().getLevel();
        String deathMessage = event.deathMessage() != null
                ? PlainTextComponentSerializer.plainText().serialize(Objects.requireNonNull(event.deathMessage()))
                : "<" + playerName + "> died";
        if (deathMessage != null || deathChannelId != null) {
            sendDeathMessageToDiscord(playerName, deathMessage, playerLevel);
        }
    }

    private void sendDeathMessageToDiscord(String playerName, String deathMessage, int playerLevel) {
        if (!isConfigured) return;

        if (jda != null) {
            TextChannel channel = jda.getTextChannelById(deathChannelId);
            ThreadChannel thread = jda.getThreadChannelById(deathChannelId);
            String avatarUrl = String.format("https://mc-heads.net/avatar/%s", playerName);
            EmbedBuilder embed = new EmbedBuilder()
                    .setAuthor(deathMessage, null, avatarUrl)
                    .setFooter(String.format("Player Levels: %d",playerLevel))
                    .setColor(Color.GRAY);
            if (channel != null && mode.equals("CHANNEL")) {
                channel.sendMessageEmbeds(embed.build()).queue();
            } else if (thread != null && mode.equals("THREAD")) {
                thread.sendMessageEmbeds(embed.build()).queue();
            } else {
                getLogger().info("Discord channel not found!");
            }
        }
    }

    private void sendToDiscord(String message) {
        if (!isConfigured) return;

        if (jda != null) {
            TextChannel channel = jda.getTextChannelById(playerListChannelId);
            ThreadChannel thread = jda.getThreadChannelById(playerListChannelId);
            if (channel != null && mode.equals("CHANNEL")) {
                channel.sendMessage(message).complete();
            } else if (thread != null && mode.equals("THREAD")) {
                thread.sendMessage(message).complete();
            } else {
                getLogger().info("Discord channel not found!");
            }
        }
    }

    private void sendPlayerListToDiscord() {
        if (jda != null) {
            TextChannel channel = jda.getTextChannelById(playerListChannelId);
            ThreadChannel thread = jda.getThreadChannelById(playerListChannelId);
            List<String> playerNames = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.toList());

            String playerListString = playerNames.isEmpty() ? "現在参加者はいません" : String.join("\n- ", playerNames);
            String message = String.format("- %s",playerListString);
            String title = String.format("%sオンライン人数: %d人", serverPrefix, playerNames.size());

            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle(title)
                    .setDescription(message)
                    .setFooter("プレイヤーリスト")
                    .setColor(Color.BLUE);
            if (channel != null && mode.equals("CHANNEL")) {
                channel.sendMessageEmbeds(embed.build()).queue();
            } else if (thread != null && mode.equals("THREAD")) {
                thread.sendMessageEmbeds(embed.build()).queue();
            } else {
                getLogger().info("Discord channel not found!");
            }
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command command, @NotNull String label, String[] args) {
        if (command.getName().equalsIgnoreCase("discordrelay")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (sender.hasPermission("discordrelay.reload")) {
                    reloadPlugin();
                    sender.sendMessage("DiscordRelay plugin reloaded.");
                    return true;
                } else {
                    sender.sendMessage("You don't have permission to reload the plugin.");
                    return true;
                }
            }
        }
        return false;
    }

    private void reloadPlugin() {
        loadConfig();
        if (isConfigured) {
            initializePlugin(true);
            if (jda != null) {
                getLogger().info("DiscordRelay plugin reloaded successfully.");
            } else {
                getLogger().warning("Failed to connect to Discord after reload. Please check your bot token and try again.");
            }
        } else {
            if (jda != null) {
                jda.shutdown();
                jda = null;
            }
            HandlerList.unregisterAll((JavaPlugin) this);
            getLogger().warning("Failed to reload: Discord bot is not configured properly. Please check your config.yml file.");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("discordrelay")) {
            if (args.length == 1) {
                List<String> completions = new ArrayList<>();
                completions.add("reload");
                return completions;
            }
        }
        return null;
    }

    private class DiscordListener extends ListenerAdapter {

        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (event.getChannel().getId().equals(playerListChannelId)) {
                if (event.getMessage().getContentDisplay().equals("playerlist") || event.getMessage().getContentDisplay().equals("Playerlist") || event.getMessage().getContentDisplay().equals("リスト")) {
                    if (playerListChannelId != null) {
                        sendPlayerListToDiscord();
                    }
                } else if (event.getMessage().getContentDisplay().equals("uptime") && playerListChannelId != null) {
                    sendUptime();
                }
            }
            if (event.getChannel().getId().equals(chatChannelId)) {
                if (!event.getAuthor().isBot()){
                    Member member = event.getMember();
                    String name = (member != null && member.getNickname() != null) ? member.getNickname() : event.getAuthor().getName();
                    String message = String.format("\uE103§6[Discord]:<%s>§f %s", name, event.getMessage().getContentDisplay());
                    Bukkit.getScheduler().runTask(DiscordRelay.this, () -> Bukkit.broadcast(net.kyori.adventure.text.Component.text(message))
                    );
                }
            }
        }

        private void sendUptime() {
            if (jda != null) {
                TextChannel channel = jda.getTextChannelById(playerListChannelId);
                if (channel != null) {
                    long uptime = System.currentTimeMillis() - startTime;
                    long days = uptime / (1000 * 60 * 60 * 24);
                    long hours = (uptime / (1000 * 60 * 60)) % 24;
                    long minutes = (uptime / (1000 * 60)) % 60;
                    long seconds = (uptime / 1000) % 60;

                    String uptimeString = String.format("%d日%d時間%d分%d秒です。", days, hours, minutes, seconds);

                    EmbedBuilder embed = new EmbedBuilder()
                            .setTitle(uptimeString)
                            .setFooter(serverPrefix + ": サーバの起動時間")
                            .setColor(Color.GREEN);
                    channel.sendMessageEmbeds(embed.build()).queue();
                    }
                } else {
                    getLogger().info("Discord channel not found!");
                    }
                }
    }
}