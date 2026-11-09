
package id.vortexdaily;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class VortexDaily extends JavaPlugin
        implements Listener, CommandExecutor, TabCompleter {

    private Connection database;
    private ZoneId zone;
    private HttpClient httpClient;

    private static final int[] DAY_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43,
            44, 45
    };

    private static class PlayerData {
        long streak;
        String lastClaim;

        PlayerData(long streak, String lastClaim) {
            this.streak = streak;
            this.lastClaim = lastClaim;
        }
    }

    private static class Progress {
        long streak;
        long previousStreak;
        int currentDay;
        int nextDay;
        boolean claimedToday;
        boolean streakReset;
    }

    private static class DailyHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        setupTimezone();

        if (!setupDatabase()) {
            getLogger().severe("VortexDaily database initialization failed!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        checkMonthlyReset();

        Bukkit.getScheduler().runTaskTimer(
                this,
                this::checkMonthlyReset,
                1200L,
                1200L
        );

        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("daily") != null) {
            getCommand("daily").setExecutor(this);
            getCommand("daily").setTabCompleter(this);
        }

        getLogger().info("--------------------------------");
        getLogger().info(" VortexDaily v1.2.0");
        getLogger().info(" Minecraft API: 26.3");
        getLogger().info(" Daily Rewards: 30 Days");
        getLogger().info(" Weekly Rewards: Enabled");
        getLogger().info(" Missed Day Reset: Enabled");
        getLogger().info(" Monthly Reset: "
                + getConfig().getBoolean(
                        "settings.monthly-reset.enabled", true
                ));
        getLogger().info(" SQLite: Enabled");
        getLogger().info(" Discord Webhook: "
                + getConfig().getBoolean("discord-webhook.enabled"));
        getLogger().info("--------------------------------");
    }

    @Override
    public void onDisable() {
        try {
            if (database != null && !database.isClosed()) {
                database.close();
            }
        } catch (Exception e) {
            getLogger().severe("Database shutdown error: " + e.getMessage());
        }
    }

    // ==================================================
    // TIMEZONE
    // ==================================================

    private void setupTimezone() {
        try {
            zone = ZoneId.of(
                    getConfig().getString(
                            "settings.timezone",
                            "Asia/Jakarta"
                    )
            );
        } catch (Exception e) {
            zone = ZoneId.of("Asia/Jakarta");
            getLogger().warning("Invalid timezone. Using Asia/Jakarta.");
        }
    }

    private LocalDate getToday() {
        int resetHour = Math.max(
                0,
                Math.min(23, getConfig().getInt("settings.reset-hour", 0))
        );

        LocalDate today = LocalDate.now(zone);

        if (LocalTime.now(zone).getHour() < resetHour) {
            today = today.minusDays(1);
        }

        return today;
    }

    // ==================================================
    // DATABASE
    // ==================================================

    private boolean setupDatabase() {
        try {
            File folder = getDataFolder();

            if (!folder.exists() && !folder.mkdirs()) {
                return false;
            }

            Class.forName("org.sqlite.JDBC");

            database = DriverManager.getConnection(
                    "jdbc:sqlite:" +
                    new File(folder, "players.db").getAbsolutePath()
            );

            try (Statement statement = database.createStatement()) {
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS daily_players (
                        uuid TEXT PRIMARY KEY,
                        last_claim TEXT NOT NULL DEFAULT '',
                        streak INTEGER NOT NULL DEFAULT 0
                    )
                    """);

                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS daily_meta (
                        meta_key TEXT PRIMARY KEY,
                        meta_value TEXT NOT NULL
                    )
                    """);
            }

            return true;

        } catch (Exception e) {
            getLogger().severe("SQLite error: " + e.getMessage());
            return false;
        }
    }

    private PlayerData getPlayerData(UUID uuid) {
        try (PreparedStatement ps = database.prepareStatement(
                "SELECT streak, last_claim FROM daily_players WHERE uuid = ?"
        )) {
            ps.setString(1, uuid.toString());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PlayerData(
                            rs.getLong("streak"),
                            rs.getString("last_claim")
                    );
                }
            }

        } catch (Exception e) {
            getLogger().severe("Database read error: " + e.getMessage());
        }

        return new PlayerData(0, "");
    }

    private boolean savePlayerData(
            UUID uuid,
            long streak,
            LocalDate date
    ) {
        try (PreparedStatement ps = database.prepareStatement("""
                INSERT INTO daily_players(uuid, last_claim, streak)
                VALUES(?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    last_claim = excluded.last_claim,
                    streak = excluded.streak
                """)) {

            ps.setString(1, uuid.toString());
            ps.setString(2, date.toString());
            ps.setLong(3, streak);

            return ps.executeUpdate() > 0;

        } catch (Exception e) {
            getLogger().severe("Database save error: " + e.getMessage());
            return false;
        }
    }

    // ==================================================
    // MONTHLY RESET
    // ==================================================

    private void checkMonthlyReset() {
        if (!getConfig().getBoolean(
                "settings.monthly-reset.enabled", true
        )) {
            return;
        }

        int resetDay = Math.max(
                1,
                Math.min(
                        28,
                        getConfig().getInt(
                                "settings.monthly-reset.day", 1
                        )
                )
        );

        LocalDate today = getToday();

        if (today.getDayOfMonth() < resetDay) {
            return;
        }

        String currentMonth = YearMonth.from(today).toString();

        try {
            String previousMonth = null;

            try (PreparedStatement ps = database.prepareStatement(
                    "SELECT meta_value FROM daily_meta WHERE meta_key = ?"
            )) {
                ps.setString(1, "monthly_reset");

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        previousMonth = rs.getString("meta_value");
                    }
                }
            }

            // Initialize tracker without deleting existing data.
            if (previousMonth == null) {
                try (PreparedStatement ps = database.prepareStatement(
                        "INSERT INTO daily_meta(meta_key, meta_value) VALUES(?, ?)"
                )) {
                    ps.setString(1, "monthly_reset");
                    ps.setString(2, currentMonth);
                    ps.executeUpdate();
                }

                getLogger().info(
                        "Monthly reset tracker initialized: " + currentMonth
                );
                return;
            }

            if (previousMonth.equals(currentMonth)) {
                return;
            }

            database.setAutoCommit(false);

            try {
                int affected;

                try (PreparedStatement ps = database.prepareStatement(
                        "UPDATE daily_players SET streak = 0, last_claim = ''"
                )) {
                    affected = ps.executeUpdate();
                }

                try (PreparedStatement ps = database.prepareStatement(
                        "UPDATE daily_meta SET meta_value = ? WHERE meta_key = ?"
                )) {
                    ps.setString(1, currentMonth);
                    ps.setString(2, "monthly_reset");
                    ps.executeUpdate();
                }

                database.commit();

                getLogger().info(
                        "========================================"
                );
                getLogger().info(
                        " VortexDaily Monthly Reset Completed"
                );
                getLogger().info(
                        " Month: " + currentMonth
                );
                getLogger().info(
                        " Players Reset: " + affected
                );
                getLogger().info(
                        " All players can claim Day 1 again."
                );
                getLogger().info(
                        "========================================"
                );

            } catch (Exception e) {
                database.rollback();
                throw e;

            } finally {
                database.setAutoCommit(true);
            }

        } catch (Exception e) {
            getLogger().severe(
                    "Monthly reset failed: " + e.getMessage()
            );
        }
    }

    // ==================================================
    // PROGRESS / STREAK
    // ==================================================

    private int getDay(long streak) {
        if (streak <= 0) {
            return 1;
        }

        return (int) (((streak - 1) % 30) + 1);
    }

    private Progress getProgress(Player player) {
        PlayerData data = getPlayerData(player.getUniqueId());

        LocalDate today = getToday();

        Progress progress = new Progress();

        long streak = data.streak;
        progress.previousStreak = streak;

        if (!data.lastClaim.isEmpty()) {
            try {
                LocalDate last = LocalDate.parse(data.lastClaim);

                progress.claimedToday = last.equals(today);

                if (!progress.claimedToday
                        && getConfig().getBoolean(
                                "settings.streak-reset", true
                        )
                        && !last.plusDays(1).equals(today)) {

                    progress.streakReset = streak > 0;
                    streak = 0;
                }

            } catch (Exception e) {
                progress.streakReset = streak > 0;
                streak = 0;
            }
        }

        progress.streak = streak;

        if (progress.claimedToday) {
            progress.currentDay = getDay(streak);
            progress.nextDay = getDay(streak);
        } else {
            progress.currentDay = (int) (streak % 30);
            progress.nextDay = getDay(streak + 1);
        }

        return progress;
    }

    // ==================================================
    // CLAIM SYSTEM
    // ==================================================

    private void claim(Player player) {
        Progress progress = getProgress(player);

        if (progress.claimedToday) {
            sendMessage(player, "already-claimed");
            return;
        }

        if (progress.streakReset) {
            sendStreakResetWebhook(
                    player,
                    progress.previousStreak
            );
        }

        long newStreak = progress.streak + 1;
        int day = getDay(newStreak);

        LocalDate today = getToday();

        if (!savePlayerData(
                player.getUniqueId(),
                newStreak,
                today
        )) {
            player.sendMessage(
                    prefix() + color("&cGagal menyimpan data reward!")
            );
            return;
        }

        String dailyPath = "daily-rewards." + day;

        ConfigurationSection dailySection =
                getConfig().getConfigurationSection(dailyPath);

        List<String> dailyCommands = dailySection == null
                ? List.of()
                : dailySection.getStringList("commands");

        executeCommands(
                player,
                dailyCommands,
                day,
                newStreak
        );

        boolean weeklyEnabled = getConfig().getBoolean(
                "settings.streak-enabled", true
        );

        ConfigurationSection weeklySection = null;

        if (weeklyEnabled && day % 7 == 0) {
            weeklySection = getConfig().getConfigurationSection(
                    "weekly-rewards." + day
            );

            if (weeklySection != null) {
                executeCommands(
                        player,
                        weeklySection.getStringList("commands"),
                        day,
                        newStreak
                );

                player.sendMessage(
                        prefix() + color(
                                "&6&lWEEKLY BONUS &7Day "
                                        + day + " berhasil diklaim!"
                        )
                );
            }
        }

        sendClaimWebhook(
                player,
                day,
                newStreak,
                dailySection,
                weeklySection
        );

        String message = getConfig().getString(
                "messages.claimed",
                "&aBerhasil mengambil hadiah Day %day%!"
        );

        player.sendMessage(
                prefix() + color(
                        message.replace(
                                "%day%",
                                String.valueOf(day)
                        )
                )
        );
    }

    private void executeCommands(
            Player player,
            List<String> commands,
            int day,
            long streak
    ) {
        for (String rawCommand : commands) {
            if (rawCommand == null || rawCommand.isBlank()) {
                continue;
            }

            String command = rawCommand
                    .replace("%player%", player.getName())
                    .replace("%uuid%", player.getUniqueId().toString())
                    .replace("%day%", String.valueOf(day))
                    .replace("%streak%", String.valueOf(streak))
                    .trim();

            if (command.startsWith("/")) {
                command = command.substring(1);
            }

            try {
                Bukkit.dispatchCommand(
                        Bukkit.getConsoleSender(),
                        command
                );

            } catch (Exception e) {
                getLogger().warning(
                        "Failed to execute reward command: " + command
                );
                getLogger().warning(e.getMessage());
            }
        }
    }

    // ==================================================
    // GUI
    // ==================================================

    private String color(String text) {
        if (text == null) {
            return "";
        }

        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private String prefix() {
        return color(
                getConfig().getString(
                        "settings.prefix",
                        "&8[&bVortexDaily&8] "
                )
        );
    }

    private void sendMessage(Player player, String key) {
        player.sendMessage(
                prefix() + color(
                        getConfig().getString("messages." + key, "")
                )
        );
    }

    private String guiText(String path, String fallback) {
        return getConfig().getString(path, fallback);
    }

    private ItemStack createItem(
            String materialName,
            String name,
            List<String> lore,
            int day,
            long streak
    ) {
        Material material = Material.matchMaterial(materialName);

        if (material == null) {
            material = Material.BARRIER;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(
                    color(name
                            .replace("%day%", String.valueOf(day))
                            .replace("%streak%", String.valueOf(streak)))
            );

            List<String> coloredLore = new ArrayList<>();

            for (String line : lore) {
                coloredLore.add(
                        color(line
                                .replace("%day%", String.valueOf(day))
                                .replace("%streak%", String.valueOf(streak)))
                );
            }

            meta.setLore(coloredLore);
            item.setItemMeta(meta);
        }

        return item;
    }

    private void openGUI(Player player) {
        Progress progress = getProgress(player);

        int rows = Math.max(
                1,
                Math.min(6, getConfig().getInt("gui.rows", 6))
        );

        DailyHolder holder = new DailyHolder();

        Inventory inventory = Bukkit.createInventory(
                holder,
                rows * 9,
                color(getConfig().getString(
                        "settings.gui-title",
                        "&8Daily Rewards"
                ))
        );

        holder.inventory = inventory;

        if (getConfig().getBoolean("gui.filler.enabled", true)) {
            ItemStack filler = createItem(
                    guiText(
                            "gui.filler.material",
                            "BLACK_STAINED_GLASS_PANE"
                    ),
                    guiText("gui.filler.name", " "),
                    List.of(),
                    0,
                    progress.streak
            );

            for (int i = 0; i < inventory.getSize(); i++) {
                inventory.setItem(i, filler);
            }
        }

        for (int day = 1; day <= 30; day++) {
            int slot = DAY_SLOTS[day - 1];

            if (slot >= inventory.getSize()) {
                continue;
            }

            String path;

            if (day <= progress.currentDay) {
                path = "gui.claimed";
            } else if (!progress.claimedToday
                    && day == progress.nextDay) {
                path = "gui.available";
            } else {
                path = "gui.locked";
            }

            inventory.setItem(
                    slot,
                    createItem(
                            guiText(path + ".material", "CHEST"),
                            guiText(path + ".name", "&fDay %day%"),
                            getConfig().getStringList(path + ".lore"),
                            day,
                            progress.streak
                    )
            );
        }

        int claimSlot = getConfig().getInt("gui.claim.slot", 49);

        if (claimSlot >= 0 && claimSlot < inventory.getSize()) {
            inventory.setItem(
                    claimSlot,
                    createItem(
                            guiText("gui.claim.material", "CHEST"),
                            guiText("gui.claim.name", "&aCLAIM"),
                            getConfig().getStringList("gui.claim.lore"),
                            progress.nextDay,
                            progress.streak
                    )
            );
        }

        int streakSlot = getConfig().getInt("gui.streak.slot", 4);

        if (streakSlot >= 0 && streakSlot < inventory.getSize()) {
            inventory.setItem(
                    streakSlot,
                    createItem(
                            guiText("gui.streak.material", "FIREWORK_STAR"),
                            guiText("gui.streak.name", "&6YOUR STREAK"),
                            getConfig().getStringList("gui.streak.lore"),
                            progress.nextDay,
                            progress.streak
                    )
            );
        }

        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder()
                instanceof DailyHolder)) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (event.getClickedInventory() == null
                || event.getClickedInventory()
                != event.getView().getTopInventory()) {
            return;
        }

        int claimSlot = getConfig().getInt("gui.claim.slot", 49);

        if (event.getRawSlot() == claimSlot) {
            player.closeInventory();
            claim(player);
            return;
        }

        int streakSlot = getConfig().getInt("gui.streak.slot", 4);

        if (event.getRawSlot() == streakSlot) {
            Progress progress = getProgress(player);

            player.sendMessage(
                    prefix() + color(
                            "&bStreak: &f" + progress.streak
                                    + " &7| Next: &eDay "
                                    + progress.nextDay
                    )
            );
        }
    }

    // ==================================================
    // DISCORD WEBHOOK
    // ==================================================

    private boolean webhookEnabled(String event) {
        return getConfig().getBoolean(
                "discord-webhook.enabled", false
        ) && getConfig().getBoolean(
                "discord-webhook.events." + event, true
        );
    }

    private String escapeJson(String input) {
        if (input == null) {
            return "";
        }

        StringBuilder result = new StringBuilder();

        for (char c : input.toCharArray()) {
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");

                default -> {
                    if (c < 32) {
                        result.append(
                                String.format("\\u%04x", (int) c)
                        );
                    } else {
                        result.append(c);
                    }
                }
            }
        }

        return result.toString();
    }

    private String replacePlaceholders(
            String text,
            Player player,
            int day,
            long streak
    ) {
        return text
                .replace("%player%", player.getName())
                .replace("%uuid%", player.getUniqueId().toString())
                .replace("%day%", String.valueOf(day))
                .replace("%streak%", String.valueOf(streak));
    }

    private String formatDisplay(
            ConfigurationSection section,
            Player player,
            int day,
            long streak
    ) {
        if (section == null) {
            return "• No reward details configured";
        }

        List<String> display = section.getStringList("display");

        if (display.isEmpty()) {
            return "• No reward details configured";
        }

        StringBuilder result = new StringBuilder();

        for (String reward : display) {
            String line = replacePlaceholders(
                    reward,
                    player,
                    day,
                    streak
            );

            if (result.length() + line.length() + 4 > 950) {
                result.append("\n• More rewards...");
                break;
            }

            if (!result.isEmpty()) {
                result.append("\n");
            }

            result.append("• ").append(line);
        }

        return result.toString();
    }

    private String discordField(
            String name,
            String value,
            boolean inline
    ) {
        return "{"
                + "\"name\":\"" + escapeJson(name) + "\","
                + "\"value\":\"" + escapeJson(value) + "\","
                + "\"inline\":" + inline
                + "}";
    }

    private void sendWebhook(
            String event,
            String title,
            String description,
            int color,
            List<String> fields
    ) {
        if (!webhookEnabled(event)) {
            return;
        }

        String url = getConfig().getString(
                "discord-webhook.url",
                ""
        );

        if (url == null || url.isBlank()) {
            return;
        }

        if (!(url.startsWith("https://discord.com/api/webhooks/")
                || url.startsWith("https://discordapp.com/api/webhooks/"))) {

            getLogger().warning(
                    "Invalid Discord webhook URL. Check config.yml."
            );
            return;
        }

        String username = getConfig().getString(
                "discord-webhook.username",
                "VortexDaily"
        );

        String avatar = getConfig().getString(
                "discord-webhook.avatar-url",
                ""
        );

        String footer = getConfig().getString(
                "discord-webhook.embed.footer",
                "VortexDaily • Automatic Notification"
        );

        StringBuilder json = new StringBuilder();

        json.append("{")
                .append("\"username\":\"")
                .append(escapeJson(username))
                .append("\",");

        if (avatar != null && !avatar.isBlank()) {
            json.append("\"avatar_url\":\"")
                    .append(escapeJson(avatar))
                    .append("\",");
        }

        json.append("\"allowed_mentions\":{\"parse\":[]},")
                .append("\"embeds\":[{")
                .append("\"title\":\"")
                .append(escapeJson(title))
                .append("\",")
                .append("\"description\":\"")
                .append(escapeJson(description))
                .append("\",")
                .append("\"color\":")
                .append(color)
                .append(",");

        json.append("\"fields\":[");

        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                json.append(",");
            }

            json.append(fields.get(i));
        }

        json.append("],");

        json.append("\"footer\":{\"text\":\"")
                .append(escapeJson(footer))
                .append("\"},");

        json.append("\"timestamp\":\"")
                .append(Instant.now())
                .append("\"");

        json.append("}]}");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            json.toString(),
                            StandardCharsets.UTF_8
                    ))
                    .build();

            httpClient.sendAsync(
                    request,
                    HttpResponse.BodyHandlers.discarding()
            ).thenAccept(response -> {
                if (response.statusCode() < 200
                        || response.statusCode() >= 300) {
                    getLogger().warning(
                            "Discord webhook returned HTTP "
                                    + response.statusCode()
                    );
                }
            }).exceptionally(error -> {
                getLogger().warning(
                        "Discord webhook failed: " + error.getMessage()
                );
                return null;
            });

        } catch (Exception e) {
            getLogger().warning(
                    "Could not send Discord webhook: " + e.getMessage()
            );
        }
    }

    private List<String> createPlayerFields(
            Player player,
            int day,
            long streak
    ) {
        List<String> fields = new ArrayList<>();

        fields.add(discordField(
                "👤 Player",
                "**" + player.getName() + "**",
                true
        ));

        fields.add(discordField(
                "📅 Daily Progress",
                "**Day " + day + " / 30**",
                true
        ));

        fields.add(discordField(
                "🔥 Current Streak",
                "**" + streak + " Days**",
                true
        ));

        fields.add(discordField(
                "🆔 Player UUID",
                "`" + player.getUniqueId() + "`",
                false
        ));

        return fields;
    }

    private void sendClaimWebhook(
            Player player,
            int day,
            long streak,
            ConfigurationSection dailySection,
            ConfigurationSection weeklySection
    ) {
        List<String> dailyFields = createPlayerFields(
                player,
                day,
                streak
        );

        dailyFields.add(discordField(
                "🎁 Received Rewards",
                formatDisplay(
                        dailySection,
                        player,
                        day,
                        streak
                ),
                false
        ));

        sendWebhook(
                "daily-claim",
                "🎁 Daily Reward Claimed!",
                "A player has successfully claimed their daily rewards.",
                5793266,
                dailyFields
        );

        if (weeklySection != null) {
            List<String> weeklyFields = createPlayerFields(
                    player,
                    day,
                    streak
            );

            weeklyFields.add(discordField(
                    "🏆 Weekly Bonus Rewards",
                    formatDisplay(
                            weeklySection,
                            player,
                            day,
                            streak
                    ),
                    false
            ));

            sendWebhook(
                    "weekly-bonus",
                    "🏆 Weekly Bonus Claimed!",
                    "Congratulations! A player has completed another 7-day streak.",
                    16766720,
                    weeklyFields
            );
        }
    }

    private void sendStreakResetWebhook(
            Player player,
            long previousStreak
    ) {
        List<String> fields = new ArrayList<>();

        fields.add(discordField(
                "👤 Player",
                "**" + player.getName() + "**",
                true
        ));

        fields.add(discordField(
                "🔥 Previous Streak",
                "**" + previousStreak + " Days**",
                true
        ));

        fields.add(discordField(
                "📅 New Progress",
                "**Day 1 / 30**",
                true
        ));

        sendWebhook(
                "streak-reset",
                "⚠️ Daily Streak Reset",
                "A player missed their daily claim period. Their streak has been reset.",
                15158332,
                fields
        );
    }

    // ==================================================
    // COMMANDS
    // ==================================================

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(
                        "Only players can open Daily GUI."
                );
                return true;
            }

            if (!player.hasPermission("vortexdaily.use")) {
                sendMessage(player, "no-permission");
                return true;
            }

            openGUI(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "claim" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(
                            "Only players can claim rewards."
                    );
                    return true;
                }

                if (!player.hasPermission("vortexdaily.use")) {
                    sendMessage(player, "no-permission");
                    return true;
                }

                claim(player);
            }

            case "streak" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(
                            "Only players can view streak."
                    );
                    return true;
                }

                Progress progress = getProgress(player);

                player.sendMessage(
                        prefix() + color(
                                getConfig().getString(
                                        "messages.streak-info",
                                        "&bStreak: &f%streak%"
                                ).replace(
                                        "%streak%",
                                        String.valueOf(progress.streak)
                                )
                        )
                );

                player.sendMessage(
                        prefix() + color(
                                "&7Hari berikutnya: &eDay "
                                        + progress.nextDay
                        )
                );
            }

            case "reload" -> {
                if (!sender.hasPermission("vortexdaily.admin")) {
                    sender.sendMessage(
                            prefix() + color(
                                    getConfig().getString(
                                            "messages.no-permission",
                                            "&cKamu tidak memiliki permission!"
                                    )
                            )
                    );
                    return true;
                }

                reloadConfig();
                setupTimezone();

                sender.sendMessage(
                        prefix() + color(
                                getConfig().getString(
                                        "messages.reload",
                                        "&aConfig berhasil dimuat ulang!"
                                )
                        )
                );

                checkMonthlyReset();
            }

            case "reset" -> {
                if (!sender.hasPermission("vortexdaily.admin")) {
                    sender.sendMessage(
                            prefix() + color(
                                    getConfig().getString(
                                            "messages.no-permission",
                                            "&cKamu tidak memiliki permission!"
                                    )
                            )
                    );
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage(
                            color("&c/daily reset <player>")
                    );
                    return true;
                }

                OfflinePlayer target =
                        Bukkit.getOfflinePlayer(args[1]);

                try (PreparedStatement ps = database.prepareStatement(
                        "DELETE FROM daily_players WHERE uuid = ?"
                )) {
                    ps.setString(
                            1,
                            target.getUniqueId().toString()
                    );

                    ps.executeUpdate();

                    sender.sendMessage(
                            prefix() + color(
                                    getConfig().getString(
                                            "messages.reset",
                                            "&aData Daily %player% berhasil direset!"
                                    ).replace(
                                            "%player%",
                                            args[1]
                                    )
                            )
                    );

                } catch (Exception e) {
                    sender.sendMessage(
                            prefix() + color("&cDatabase error.")
                    );

                    getLogger().severe(
                            "Reset error: " + e.getMessage()
                    );
                }
            }

            default -> sender.sendMessage(
                    color(
                            "&bVortexDaily &7Commands:\n"
                                    + "&f/daily\n"
                                    + "&f/daily claim\n"
                                    + "&f/daily streak\n"
                                    + "&f/daily reload\n"
                                    + "&f/daily reset <player>"
                    )
            );
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (args.length == 1) {
            return Arrays.asList(
                            "claim",
                            "streak",
                            "reload",
                            "reset"
                    )
                    .stream()
                    .filter(s -> s.startsWith(
                            args[0].toLowerCase(Locale.ROOT)
                    ))
                    .toList();
        }

        if (args.length == 2
                && args[0].equalsIgnoreCase("reset")
                && sender.hasPermission("vortexdaily.admin")) {

            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(s -> s.toLowerCase(Locale.ROOT)
                            .startsWith(
                                    args[1].toLowerCase(Locale.ROOT)
                            ))
                    .toList();
        }

        return List.of();
    }
}
