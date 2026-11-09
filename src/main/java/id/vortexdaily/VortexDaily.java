
package id.vortexdaily;

import net.milkbowl.vault.economy.Economy;
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
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class VortexDaily extends JavaPlugin
        implements Listener, CommandExecutor, TabCompleter {

    private Connection database;
    private Economy economy;
    private ZoneId zone;

    private final int[] DAY_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43,
            44, 45
    };

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            zone = ZoneId.of(
                    getConfig().getString("settings.timezone", "Asia/Jakarta")
            );
        } catch (Exception e) {
            zone = ZoneId.of("Asia/Jakarta");
            getLogger().warning("Invalid timezone. Using Asia/Jakarta.");
        }

        if (!setupDatabase()) {
            getLogger().severe("Could not initialize database!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        setupEconomy();

        getServer().getPluginManager().registerEvents(this, this);

        getCommand("daily").setExecutor(this);
        getCommand("daily").setTabCompleter(this);

        getLogger().info("VortexDaily v1.0.0 enabled!");
        getLogger().info("Daily Reward System: 30 Days");
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

    private boolean setupDatabase() {
        try {
            File folder = getDataFolder();

            if (!folder.exists() && !folder.mkdirs()) {
                return false;
            }

            Class.forName("org.sqlite.JDBC");

            database = DriverManager.getConnection(
                    "jdbc:sqlite:" + new File(folder, "players.db").getAbsolutePath()
            );

            try (Statement statement = database.createStatement()) {
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS daily_players (
                        uuid TEXT PRIMARY KEY,
                        last_claim TEXT NOT NULL DEFAULT '',
                        streak INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            }

            return true;

        } catch (Exception e) {
            getLogger().severe("SQLite error: " + e.getMessage());
            return false;
        }
    }

    private void setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found. Money rewards disabled.");
            return;
        }

        RegisteredServiceProvider<Economy> provider =
                Bukkit.getServicesManager().getRegistration(Economy.class);

        if (provider != null) {
            economy = provider.getProvider();
            getLogger().info("Vault Economy hooked successfully!");
        } else {
            getLogger().warning("No Economy provider found!");
        }
    }

    private LocalDate getToday() {
        int resetHour = getConfig().getInt("settings.reset-hour", 0);
        resetHour = Math.max(0, Math.min(23, resetHour));

        return LocalDate.now(zone).atStartOfDay()
                .plusHours(resetHour).toLocalDate();
    }

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
        int currentDay;
        int nextDay;
        boolean claimedToday;
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

    private void savePlayerData(UUID uuid, long streak, LocalDate date) {
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
            ps.executeUpdate();

        } catch (Exception e) {
            getLogger().severe("Database save error: " + e.getMessage());
        }
    }

    private Progress getProgress(Player player) {
        PlayerData data = getPlayerData(player.getUniqueId());
        LocalDate today = getToday();

        Progress progress = new Progress();
        long streak = data.streak;

        if (!data.lastClaim.isEmpty()) {
            try {
                LocalDate last = LocalDate.parse(data.lastClaim);

                progress.claimedToday = last.equals(today);

                if (!progress.claimedToday
                        && getConfig().getBoolean("settings.streak-reset", true)
                        && !last.plusDays(1).equals(today)) {
                    streak = 0;
                }
            } catch (Exception e) {
                streak = 0;
            }
        }

        progress.streak = streak;
        progress.currentDay = streak == 0 ? 0 : getDay(streak);

        if (progress.claimedToday) {
            progress.nextDay = getDay(Math.max(1, streak));
        } else {
            progress.nextDay = getDay(streak + 1);
        }

        return progress;
    }

    private int getDay(long streak) {
        if (streak <= 0) return 1;
        return (int) (((streak - 1) % 30) + 1);
    }

    private String color(String text) {
        if (text == null) return "";

        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private String prefix() {
        return color(getConfig().getString(
                "settings.prefix", "&8[&bVortexDaily&8] "
        ));
    }

    private void message(Player player, String key) {
        player.sendMessage(prefix() + color(
                getConfig().getString("messages." + key, "")
        ));
    }

    private void claim(Player player) {
        Progress progress = getProgress(player);

        if (progress.claimedToday) {
            message(player, "already-claimed");
            return;
        }

        long newStreak = progress.streak + 1;
        int day = getDay(newStreak);

        LocalDate today = getToday();

        savePlayerData(player.getUniqueId(), newStreak, today);

        ConfigurationSection daily = getConfig()
                .getConfigurationSection("daily-rewards." + day);

        if (daily != null) {
            executeRewards(player, daily.getStringList("rewards"));
        }

        boolean weekly = day % 7 == 0;

        if (weekly) {
            ConfigurationSection bonus = getConfig()
                    .getConfigurationSection("weekly-rewards." + day);

            if (bonus != null) {
                executeRewards(player, bonus.getStringList("rewards"));

                player.sendMessage(prefix()
                        + color("&6&lWEEKLY BONUS &7Day " + day + " berhasil diklaim!"));
            }
        }

        String msg = getConfig().getString(
                "messages.claimed",
                "&aBerhasil mengambil hadiah Day %day%!"
        );

        player.sendMessage(prefix() + color(
                msg.replace("%day%", String.valueOf(day))
        ));
    }

    private void executeRewards(Player player, List<String> rewards) {
        for (String reward : rewards) {
            try {
                String[] parts = reward.split(":", 3);

                if (parts.length < 2) continue;

                String type = parts[0].toLowerCase(Locale.ROOT);
                String value = parts[1];

                switch (type) {
                    case "money" -> {
                        if (economy == null) {
                            getLogger().warning("Money reward skipped: Vault unavailable.");
                            continue;
                        }

                        double amount = Double.parseDouble(value);

                        if (amount > 0) {
                            economy.depositPlayer(player, amount);
                        }
                    }

                    case "item" -> {
                        if (parts.length < 3) continue;

                        Material material = Material.matchMaterial(value);
                        int amount = Integer.parseInt(parts[2]);

                        if (material == null || amount <= 0) continue;

                        int maxStack = material.getMaxStackSize();

                        while (amount > 0) {
                            int give = Math.min(amount, maxStack);

                            ItemStack item = new ItemStack(material, give);

                            player.getInventory().addItem(item)
                                    .values()
                                    .forEach(left ->
                                            player.getWorld().dropItemNaturally(
                                                    player.getLocation(), left
                                            )
                                    );

                            amount -= give;
                        }
                    }

                    case "exp" -> {
                        int amount = Integer.parseInt(value);

                        if (amount > 0) {
                            player.giveExp(amount);
                        }
                    }

                    case "command" -> {
                        String command = reward.substring("command:".length())
                                .replace("%player%", player.getName());

                        Bukkit.dispatchCommand(
                                Bukkit.getConsoleSender(), command
                        );
                    }

                    default -> getLogger().warning(
                            "Unknown reward type: " + type
                    );
                }

            } catch (Exception e) {
                getLogger().warning(
                        "Could not execute reward '" + reward + "': " + e.getMessage()
                );
            }
        }
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
            meta.setDisplayName(color(name
                    .replace("%day%", String.valueOf(day))
                    .replace("%streak%", String.valueOf(streak))));

            List<String> coloredLore = new ArrayList<>();

            for (String line : lore) {
                coloredLore.add(color(line
                        .replace("%day%", String.valueOf(day))
                        .replace("%streak%", String.valueOf(streak))));
            }

            meta.setLore(coloredLore);
            item.setItemMeta(meta);
        }

        return item;
    }

    private String guiText(String path, String fallback) {
        return getConfig().getString(path, fallback);
    }

    private void openGUI(Player player) {
        Progress progress = getProgress(player);

        int rows = getConfig().getInt("gui.rows", 6);
        rows = Math.max(1, Math.min(6, rows));

        DailyHolder holder = new DailyHolder();

        Inventory inventory = Bukkit.createInventory(
                holder,
                rows * 9,
                color(getConfig().getString(
                        "settings.gui-title", "&8Daily Rewards"
                ))
        );

        holder.inventory = inventory;

        if (getConfig().getBoolean("gui.filler.enabled", true)) {
            ItemStack filler = createItem(
                    guiText("gui.filler.material", "BLACK_STAINED_GLASS_PANE"),
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

            if (slot >= inventory.getSize()) continue;

            String path;
            String material;
            String name;
            List<String> lore;

            if (day <= progress.currentDay) {
                path = "gui.claimed";
            } else if (!progress.claimedToday && day == progress.nextDay) {
                path = "gui.available";
            } else {
                path = "gui.locked";
            }

            material = guiText(path + ".material", "CHEST");
            name = guiText(path + ".name", "&fDay %day%");
            lore = getConfig().getStringList(path + ".lore");

            inventory.setItem(
                    slot,
                    createItem(material, name, lore, day, progress.streak)
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

    private static class DailyHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
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
                || event.getClickedInventory() != event.getView().getTopInventory()) {
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

            player.sendMessage(prefix() + color(
                    "&bStreak: &f" + progress.streak
                            + " &7| Next: &eDay " + progress.nextDay
            ));
        }
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can open Daily GUI.");
                return true;
            }

            if (!player.hasPermission("vortexdaily.use")) {
                player.sendMessage(prefix()
                        + color(getConfig().getString("messages.no-permission")));
                return true;
            }

            openGUI(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "claim" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can claim rewards.");
                    return true;
                }

                if (!player.hasPermission("vortexdaily.use")) {
                    message(player, "no-permission");
                    return true;
                }

                claim(player);
            }

            case "streak" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can view streak.");
                    return true;
                }

                Progress progress = getProgress(player);

                player.sendMessage(prefix() + color(
                        getConfig().getString("messages.streak-info")
                                .replace("%streak%", String.valueOf(progress.streak))
                ));

                player.sendMessage(prefix() + color(
                        "&7Hari berikutnya: &eDay " + progress.nextDay
                ));
            }

            case "reload" -> {
                if (!sender.hasPermission("vortexdaily.admin")) {
                    sender.sendMessage(prefix()
                            + color(getConfig().getString("messages.no-permission")));
                    return true;
                }

                reloadConfig();

                try {
                    zone = ZoneId.of(getConfig().getString(
                            "settings.timezone", "Asia/Jakarta"
                    ));
                } catch (Exception e) {
                    zone = ZoneId.of("Asia/Jakarta");
                }

                sender.sendMessage(prefix()
                        + color(getConfig().getString("messages.reload")));
            }

            case "reset" -> {
                if (!sender.hasPermission("vortexdaily.admin")) {
                    sender.sendMessage(prefix()
                            + color(getConfig().getString("messages.no-permission")));
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage(color("&c/daily reset <player>"));
                    return true;
                }

                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);

                try (PreparedStatement ps = database.prepareStatement(
                        "DELETE FROM daily_players WHERE uuid = ?"
                )) {
                    ps.setString(1, target.getUniqueId().toString());
                    ps.executeUpdate();

                    sender.sendMessage(prefix() + color(
                            getConfig().getString("messages.reset")
                                    .replace("%player%", args[1])
                    ));

                } catch (Exception e) {
                    sender.sendMessage(prefix() + color("&cDatabase error."));
                    getLogger().severe("Reset error: " + e.getMessage());
                }
            }

            default -> sender.sendMessage(color(
                    "&bVortexDaily &7Commands: /daily, /daily claim, "
                            + "/daily streak, /daily reload, /daily reset <player>"
            ));
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
            return Arrays.asList("claim", "streak", "reload", "reset")
                    .stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("reset")
                && sender.hasPermission("vortexdaily.admin")) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(s -> s.toLowerCase(Locale.ROOT)
                            .startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        return List.of();
    }
}
