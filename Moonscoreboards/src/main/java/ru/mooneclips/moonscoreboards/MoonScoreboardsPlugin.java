package ru.mooneclips.moonscoreboards;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MoonScoreboardsPlugin extends JavaPlugin implements Listener, TabExecutor {
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
    private static final Pattern KILLS = Pattern.compile("\\\"minecraft:player_kills\\\"\\s*:\\s*(\\d+)");
    private static final Pattern DEATHS = Pattern.compile("\\\"minecraft:deaths\\\"\\s*:\\s*(\\d+)");
    private static final Pattern PLAYTIME = Pattern.compile("\\\"minecraft:play_time\\\"\\s*:\\s*(\\d+)");

    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();
    private final Map<UUID, String> ranks = new ConcurrentHashMap<>();
    private final Map<UUID, String> rankDays = new ConcurrentHashMap<>();
    private Economy economy;
    private LuckPerms luckPerms;
    private int frame;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        setupIntegrations();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("stats").setExecutor(this);
        getCommand("pstats").setExecutor(this);
        getCommand("pstats").setTabCompleter(this);
        for (Player player : Bukkit.getOnlinePlayers()) createBoard(player);
        long period = Math.max(1L, getConfig().getLong("update-interval-seconds", 5L)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::refreshAll, period, period);
        getLogger().info("Moonscoreboards 1.3.0-beta включён; обновление каждые " + period / 20 + " сек.");
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        boards.clear();
        ranks.clear();
        rankDays.clear();
        Bukkit.getScheduler().cancelTasks(this);
    }

    private void setupIntegrations() {
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            RegisteredServiceProvider<Economy> registration =
                    getServer().getServicesManager().getRegistration(Economy.class);
            if (registration != null) economy = registration.getProvider();
        }
        if (economy == null) getLogger().warning("Vault Economy не найден: монеты будут отображаться как '-'.");
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
            try {
                luckPerms = LuckPermsProvider.get();
            } catch (IllegalStateException ex) {
                getLogger().warning("LuckPerms API недоступен: " + ex.getMessage());
            }
        }
        if (luckPerms == null) getLogger().warning("LuckPerms не найден: привилегии не будут отображаться.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        createBoard(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        boards.remove(id);
        ranks.remove(id);
        rankDays.remove(id);
    }

    private void createBoard(Player player) {
        if (!getConfig().getBoolean("scoreboard.enabled", true)) return;
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective("moonstats", "dummy", color("&5&lMOONECLIPS"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        List<String> lines = getConfig().getStringList("scoreboard.lines");
        int score = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            String entry = ChatColor.values()[i % ChatColor.values().length].toString()
                    + ChatColor.values()[(i + 1) % ChatColor.values().length];
            Team team = board.registerNewTeam("ms" + i);
            team.addEntry(entry);
            team.setPrefix(color(lines.get(i)));
            objective.getScore(entry).setScore(score--);
        }
        boards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
        refreshPlayer(player, board);
    }

    private void refreshAll() {
        List<String> frames = getConfig().getStringList("title-frames");
        String title = color(frames.isEmpty() ? "&5&lMOONECLIPS" : frames.get(frame++ % frames.size()));
        for (Player player : Bukkit.getOnlinePlayers()) {
            Scoreboard board = boards.get(player.getUniqueId());
            if (board == null) {
                createBoard(player);
                board = boards.get(player.getUniqueId());
            }
            if (board == null) continue;
            Objective objective = board.getObjective("moonstats");
            if (objective != null) objective.setDisplayName(title);
            refreshPlayer(player, board);
        }
    }

    private void refreshPlayer(Player player, Scoreboard board) {
        if (luckPerms != null) {
            User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user != null) cacheRank(player.getUniqueId(), user);
            else if (!ranks.containsKey(player.getUniqueId())) loadRank(player.getUniqueId());
        }
        PlayerStats stats = onlineStats(player);
        Map<String, String> values = placeholders(stats);
        List<String> lines = getConfig().getStringList("scoreboard.lines");
        for (int i = 0; i < lines.size(); i++) {
            Team team = board.getTeam("ms" + i);
            if (team != null) team.setPrefix(color(replace(lines.get(i), values)));
        }
    }

    private PlayerStats onlineStats(Player player) {
        int kills = player.getStatistic(Statistic.PLAYER_KILLS);
        int deaths = player.getStatistic(Statistic.DEATHS);
        long playTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
        return new PlayerStats(kills, deaths, playTicks,
                economy == null ? -1 : economy.getBalance(player),
                ranks.getOrDefault(player.getUniqueId(), "Загрузка..."),
                rankDays.getOrDefault(player.getUniqueId(), "..."),
                player.getWorld().getName(), player.getLevel(), player.getPing());
    }

    private void loadRank(UUID uuid) {
        if (luckPerms == null) return;
        luckPerms.getUserManager().loadUser(uuid).thenAccept(user -> {
            if (user != null) cacheRank(uuid, user);
        }).exceptionally(error -> {
            getLogger().fine("LuckPerms profile load failed for " + uuid + ": " + error.getMessage());
            return null;
        });
    }

    private void cacheRank(UUID uuid, User user) {
        String group = user.getPrimaryGroup();
        String rank = group == null || group.isBlank() ? "Не установлена" : group.toUpperCase(Locale.ROOT);
        Instant now = Instant.now();
        Instant expiry = user.getNodes().stream()
                .filter(node -> node instanceof InheritanceNode)
                .map(node -> (InheritanceNode) node)
                .filter(node -> node.getGroupName().equalsIgnoreCase(group) && node.hasExpiry())
                .map(Node::getExpiry)
                .filter(value -> value.isAfter(now))
                .min(Comparator.naturalOrder()).orElse(null);
        String remaining = "Бессрочно";
        if (expiry != null) {
            long days = Math.max(1L, (long) Math.ceil(Duration.between(now, expiry).toSeconds() / 86400.0));
            remaining = days + " дн.";
        }
        ranks.put(uuid, rank);
        rankDays.put(uuid, remaining);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("stats")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(color("&cКоманда доступна только игроку."));
                return true;
            }
            openProfile(player, player);
            return true;
        }
        if (command.getName().equalsIgnoreCase("pstats")) {
            if (!sender.hasPermission("moonscoreboards.admin")) {
                sender.sendMessage(color("&cНедостаточно прав."));
                return true;
            }
            if (args.length != 1) {
                sender.sendMessage(color("&cИспользование: /pstats <ник>"));
                return true;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
            if (!target.isOnline() && !target.hasPlayedBefore()) {
                sender.sendMessage(color("&cИгрок не найден или никогда не заходил на сервер."));
                return true;
            }
            if (sender instanceof Player viewer) openProfile(viewer, target);
            else sendConsoleProfile(sender, target);
            return true;
        }
        return false;
    }

    private void openProfile(Player viewer, OfflinePlayer target) {
        PlayerStats stats = collectStats(target);
        Inventory inventory = Bukkit.createInventory(null, 45,
                color(getConfig().getString("profile.title", "&5Профиль игрока")) + " &8» &f" + safeName(target));
        ItemStack pane = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, pane);

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skull = (SkullMeta) head.getItemMeta();
        skull.setOwningPlayer(target);
        skull.setDisplayName(color("&d&l" + safeName(target)));
        skull.setLore(List.of(color("&7Статус: " + (stats.online ? "&aВ сети" : "&cНе в сети"))));
        head.setItemMeta(skull);
        inventory.setItem(4, head);
        inventory.setItem(10, item(Material.GOLD_INGOT, "&6Монеты", List.of("&f" + money(stats.balance))));
        inventory.setItem(12, item(Material.NETHER_STAR, "&dПривилегия",
                List.of("&f" + stats.rank, "&7Осталось: &e" + stats.rankDays)));
        inventory.setItem(14, item(Material.IRON_SWORD, "&aУбийства", List.of("&f" + stats.kills)));
        inventory.setItem(16, item(Material.SKELETON_SKULL, "&cСмерти", List.of("&f" + stats.deaths)));
        inventory.setItem(20, item(Material.DIAMOND, "&bK/D", List.of("&f" + kd(stats.kills, stats.deaths))));
        inventory.setItem(22, item(Material.CLOCK, "&eВремя игры", List.of("&f" + playtime(stats.playTicks))));
        inventory.setItem(24, item(Material.PAPER, "&aПервый вход", List.of("&f" + date(target.getFirstPlayed()))));
        inventory.setItem(29, item(Material.RECOVERY_COMPASS, "&eПоследний вход", List.of("&f" + date(target.getLastPlayed()))));
        inventory.setItem(31, item(Material.EXPERIENCE_BOTTLE, "&aУровень",
                List.of("&f" + (stats.online ? stats.level : "Недоступен офлайн"))));
        inventory.setItem(33, item(Material.GRASS_BLOCK, "&2Мир",
                List.of("&f" + (stats.online ? stats.world : "Недоступен офлайн"))));
        viewer.openInventory(inventory);
    }

    private PlayerStats collectStats(OfflinePlayer target) {
        Player online = target.getPlayer();
        if (online != null) return onlineStats(online);
        OfflineStats offline = readOfflineStats(target.getUniqueId());
        if (luckPerms != null && !ranks.containsKey(target.getUniqueId())) loadRank(target.getUniqueId());
        return new PlayerStats(offline.kills, offline.deaths, offline.playTicks,
                economy == null ? -1 : economy.getBalance(target),
                ranks.getOrDefault(target.getUniqueId(), "Загрузка..."),
                rankDays.getOrDefault(target.getUniqueId(), "..."), "", 0, -1);
    }

    private OfflineStats readOfflineStats(UUID uuid) {
        try {
            if (Bukkit.getWorlds().isEmpty()) return OfflineStats.EMPTY;
            Path root = Bukkit.getWorlds().get(0).getWorldFolder().toPath().getParent();
            Path file = root.resolve("stats").resolve(uuid + ".json");
            if (!Files.isRegularFile(file)) return OfflineStats.EMPTY;
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return new OfflineStats(readInt(KILLS, json), readInt(DEATHS, json), readInt(PLAYTIME, json));
        } catch (IOException | SecurityException ex) {
            getLogger().fine("Cannot read offline stats for " + uuid + ": " + ex.getMessage());
            return OfflineStats.EMPTY;
        }
    }

    private int readInt(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return 0;
        try { return Integer.parseInt(matcher.group(1)); } catch (NumberFormatException ignored) { return 0; }
    }

    private void sendConsoleProfile(CommandSender sender, OfflinePlayer target) {
        PlayerStats s = collectStats(target);
        sender.sendMessage(color("&8&m──── &5MoonEclips &8• &fПрофиль &8&m────"));
        sender.sendMessage(color("&fИгрок: &b" + safeName(target) + " &7(" + (s.online ? "в сети" : "не в сети") + ")"));
        sender.sendMessage(color("&fМонеты: &6" + money(s.balance)));
        sender.sendMessage(color("&fПривилегия: &d" + s.rank + " &7| Осталось: &e" + s.rankDays));
        sender.sendMessage(color("&fУбийства: &a" + s.kills + " &7| Смерти: &c" + s.deaths + " &7| K/D: &b" + kd(s.kills, s.deaths)));
        sender.sendMessage(color("&fВремя игры: &e" + playtime(s.playTicks)));
        sender.sendMessage(color("&fПервый вход: &7" + date(target.getFirstPlayed()) + " &f| Последний: &7" + date(target.getLastPlayed())));
    }

    private Map<String, String> placeholders(PlayerStats s) {
        return Map.of("%coins%", money(s.balance), "%rank%", s.rank, "%rank_days%", s.rankDays,
                "%kills%", String.valueOf(s.kills), "%deaths%", String.valueOf(s.deaths), "%kd%", kd(s.kills, s.deaths),
                "%online%", Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers(),
                "%ping%", String.valueOf(Math.max(0, s.ping)));
    }

    private String replace(String text, Map<String, String> values) {
        String result = text;
        for (Map.Entry<String, String> entry : values.entrySet()) result = result.replace(entry.getKey(), entry.getValue());
        return result;
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(color(name));
        meta.setLore(lore.stream().map(this::color).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private String money(double value) {
        return value < 0 || !Double.isFinite(value) ? "-" :
                String.format(Locale.US, "%,.2f", value).replace(',', ' ');
    }

    private String kd(int kills, int deaths) {
        return String.format(Locale.US, "%.2f", deaths == 0 ? kills : (double) kills / deaths);
    }

    private String playtime(long ticks) {
        long minutes = Math.max(0, ticks) / 1200;
        long days = minutes / 1440;
        long hours = (minutes % 1440) / 60;
        long mins = minutes % 60;
        return days > 0 ? days + "д " + hours + "ч" : hours + "ч " + mins + "м";
    }

    private String date(long millis) {
        return millis <= 0 ? "Нет данных" : DATE_FORMAT.format(Instant.ofEpochMilli(millis));
    }

    private String safeName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString().substring(0, 8) : player.getName();
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("pstats") && args.length == 1
                && sender.hasPermission("moonscoreboards.admin")) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) names.add(player.getName());
            }
            return names;
        }
        return List.of();
    }

    private record PlayerStats(int kills, int deaths, long playTicks, double balance, String rank,
                               String rankDays, String world, int level, int ping, boolean online) {
        private PlayerStats(int kills, int deaths, long playTicks, double balance, String rank,
                            String rankDays, String world, int level, int ping) {
            this(kills, deaths, playTicks, balance, rank, rankDays, world, level, ping, !world.isBlank());
        }
    }
    private record OfflineStats(int kills, int deaths, long playTicks) {
        private static final OfflineStats EMPTY = new OfflineStats(0, 0, 0);
    }
}
// Trigger CI build for 1.3.0-beta.
