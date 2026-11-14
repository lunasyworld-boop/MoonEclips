package ru.mooneclips.moonwipe;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class WipeManager implements Listener {
    private final MoonWipePlugin plugin;
    private Economy economy;
    private BukkitTask scheduleTask;
    private BukkitTask countdownTask;
    private boolean countdownRunning;
    private boolean wipeExecuting;
    private int remainingSeconds;
    private long confirmationExpiresAt;

    public WipeManager(MoonWipePlugin plugin) {
        this.plugin = plugin;
        refreshEconomy();
    }

    public void startScheduler() {
        if (scheduleTask != null) scheduleTask.cancel();
        scheduleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkSchedule, 20L, 20L);
    }

    public void shutdown() {
        if (scheduleTask != null) scheduleTask.cancel();
        if (countdownTask != null) countdownTask.cancel();
    }

    public void reloadSettings() {
        refreshEconomy();
        startScheduler();
    }

    private void refreshEconomy() {
        RegisteredServiceProvider<Economy> registration =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        economy = registration == null ? null : registration.getProvider();
    }

    public void armConfirmation() {
        confirmationExpiresAt = System.currentTimeMillis() + 30_000L;
    }

    public void confirm(CommandSender sender) {
        if (countdownRunning || wipeExecuting) {
            sender.sendMessage(msg("&cВайп уже выполняется или запланирован."));
            return;
        }
        if (System.currentTimeMillis() > confirmationExpiresAt) {
            sender.sendMessage(msg("&cПодтверждение истекло. Выполни /mwipe start ещё раз."));
            return;
        }
        confirmationExpiresAt = 0L;
        int seconds = Math.max(5, plugin.getConfig().getInt("settings.countdown-seconds", 60));
        startCountdown(seconds, "Ручной запуск");
        sender.sendMessage(msg("&aПодтверждение принято. Обратный отсчёт начался."));
    }

    public void cancel(CommandSender sender) {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        if (countdownRunning) {
            countdownRunning = false;
            wipeExecuting = false;
            Bukkit.broadcastMessage(msg("&aОбратный отсчёт вайпа отменён администратором."));
        } else {
            sender.sendMessage(msg("&7Активного обратного отсчёта нет."));
        }
        confirmationExpiresAt = 0L;
    }

    private void startCountdown(int seconds, String source) {
        if (countdownRunning || wipeExecuting) return;
        countdownRunning = true;
        remainingSeconds = Math.max(0, seconds);
        Bukkit.broadcastMessage(msg("&cВнимание! &fMoonEclips готовится к полному вайпу. &7Источник: " + source));
        if (remainingSeconds == 0) {
            performWipe();
            return;
        }
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!countdownRunning) {
                if (countdownTask != null) countdownTask.cancel();
                return;
            }
            if (shouldAnnounce(remainingSeconds)) {
                Bukkit.broadcastMessage(msg("&eПолный вайп MoonEclips через &c" + formatTime(remainingSeconds) + "&e."));
            }
            remainingSeconds--;
            if (remainingSeconds <= 0) {
                if (countdownTask != null) countdownTask.cancel();
                countdownTask = null;
                performWipe();
            }
        }, 0L, 20L);
    }

    private boolean shouldAnnounce(int seconds) {
        if (seconds <= 5) return true;
        List<Integer> warnings = plugin.getConfig().getIntegerList("schedule.warnings-seconds");
        if (warnings.isEmpty()) warnings = Arrays.asList(300, 60, 30, 10, 5);
        return warnings.contains(seconds);
    }

    private void checkSchedule() {
        if (countdownRunning || wipeExecuting || !plugin.getConfig().getBoolean("schedule.enabled", false)) return;
        try {
            String zoneName = plugin.getConfig().getString("schedule.timezone", "system");
            ZoneId zone = zoneName == null || zoneName.equalsIgnoreCase("system")
                    ? ZoneId.systemDefault() : ZoneId.of(zoneName);
            ZonedDateTime now = ZonedDateTime.now(zone);
            LocalTime time = LocalTime.parse(plugin.getConfig().getString("schedule.time", "14:00"));
            ZonedDateTime target;
            if (plugin.getConfig().getString("schedule.mode", "weekly").equalsIgnoreCase("once")) {
                LocalDate date = LocalDate.parse(plugin.getConfig().getString("schedule.date", LocalDate.now(zone).toString()));
                target = ZonedDateTime.of(date, time, zone);
                if (target.isBefore(now.minusSeconds(1))) return;
            } else {
                DayOfWeek day = DayOfWeek.valueOf(plugin.getConfig().getString("schedule.day", "SATURDAY").toUpperCase(Locale.ROOT));
                target = now.with(TemporalAdjusters.nextOrSame(day)).with(time);
                if (target.toLocalDate().isBefore(now.toLocalDate()) || target.isBefore(now.minusSeconds(1))) target = target.plusWeeks(1);
            }
            int configuredCountdown = Math.max(5, plugin.getConfig().getInt("settings.countdown-seconds", 60));
            ZonedDateTime start = target.minusSeconds(configuredCountdown);
            String lastRun = plugin.getConfig().getString("schedule.last-run", "");
            String runKey = target.toLocalDate().toString() + "@" + target.toLocalTime();
            if (lastRun.equals(runKey)) return;
            if (!now.isBefore(start) && !now.isAfter(target.plusSeconds(1))) {
                plugin.getConfig().set("schedule.last-run", runKey);
                if (plugin.getConfig().getString("schedule.mode", "weekly").equalsIgnoreCase("once")) {
                    plugin.getConfig().set("schedule.enabled", false);
                }
                plugin.saveConfig();
                long seconds = Math.max(0L, Duration.between(now, target).getSeconds());
                startCountdown((int) Math.min(Integer.MAX_VALUE, seconds), "Расписание");
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Не удалось проверить расписание MoonWipe: " + ex.getMessage());
        }
    }

    public void setSchedule(CommandSender sender, String rawDay, String rawTime) {
        try {
            DayOfWeek day = DayOfWeek.valueOf(rawDay.toUpperCase(Locale.ROOT));
            LocalTime time = LocalTime.parse(rawTime);
            plugin.getConfig().set("schedule.day", day.name());
            plugin.getConfig().set("schedule.time", String.format(Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute()));
            plugin.getConfig().set("schedule.enabled", true);
            plugin.getConfig().set("schedule.last-run", "");
            plugin.saveConfig();
            sender.sendMessage(msg("&aРасписание включено: &f" + day.name() + " " + time + "&a. Часовой пояс: &f"
                    + plugin.getConfig().getString("schedule.timezone", "system") + "&a."));
        } catch (Exception ex) {
            sender.sendMessage(msg("&cНеверный день или время. Пример: /mwipe schedule SATURDAY 14:00"));
        }
    }

    public void disableSchedule(CommandSender sender) {
        plugin.getConfig().set("schedule.enabled", false);
        plugin.saveConfig();
        sender.sendMessage(msg("&eАвтоматическое расписание MoonWipe выключено."));
    }

    public LocalDate getSelectedDate() {
        String zoneName = plugin.getConfig().getString("schedule.timezone", "system");
        ZoneId zone = zoneName == null || zoneName.equalsIgnoreCase("system") ? ZoneId.systemDefault() : ZoneId.of(zoneName);
        return LocalDate.parse(plugin.getConfig().getString("schedule.date", LocalDate.now(zone).plusDays(1).toString()));
    }

    public LocalTime getSelectedTime() {
        return LocalTime.parse(plugin.getConfig().getString("schedule.time", "14:00"));
    }

    public void saveOneTimeSchedule(LocalDate date, LocalTime time, CommandSender sender) {
        String zoneName = plugin.getConfig().getString("schedule.timezone", "system");
        ZoneId zone = zoneName == null || zoneName.equalsIgnoreCase("system") ? ZoneId.systemDefault() : ZoneId.of(zoneName);
        ZonedDateTime target = ZonedDateTime.of(date, time, zone);
        if (!target.isAfter(ZonedDateTime.now(zone).plusSeconds(5))) {
            sender.sendMessage(msg("&cВыбери дату и время в будущем."));
            return;
        }
        plugin.getConfig().set("schedule.mode", "once");
        plugin.getConfig().set("schedule.date", date.toString());
        plugin.getConfig().set("schedule.time", String.format(Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute()));
        plugin.getConfig().set("schedule.enabled", true);
        plugin.getConfig().set("schedule.last-run", "");
        plugin.saveConfig();
        sender.sendMessage(msg("&aОдноразовый вайп назначен на &f" + date + " " + time + "&a."));
    }

    public void toggleResetModule(String key) {
        if (!Set.of("reset-worlds", "reset-economy", "reset-auction").contains(key)) return;
        String path = "settings." + key;
        plugin.getConfig().set(path, !plugin.getConfig().getBoolean(path, true));
        plugin.saveConfig();
    }

    public boolean isResetModuleEnabled(String key) {
        return plugin.getConfig().getBoolean("settings." + key, true);
    }

    public String getScheduleDescription() {
        if (!plugin.getConfig().getBoolean("schedule.enabled", false)) return "выключено";
        if (plugin.getConfig().getString("schedule.mode", "weekly").equalsIgnoreCase("once")) {
            return plugin.getConfig().getString("schedule.date", "?") + " " + plugin.getConfig().getString("schedule.time", "14:00");
        }
        return plugin.getConfig().getString("schedule.day", "SATURDAY") + " " + plugin.getConfig().getString("schedule.time", "14:00");
    }

    public void sendStatus(CommandSender sender) {
        boolean enabled = plugin.getConfig().getBoolean("schedule.enabled", false);
        String day = plugin.getConfig().getString("schedule.day", "SATURDAY");
        String time = plugin.getConfig().getString("schedule.time", "14:00");
        sender.sendMessage(msg("&8&m──────── &5Moon&bWipe &8• &fСостояние &8&m────────"));
        sender.sendMessage(msg("&7Обратный отсчёт: " + (countdownRunning ? "&aактивен (" + formatTime(remainingSeconds) + ")" : "&fнет")));
        sender.sendMessage(msg("&7Автоматическое расписание: " + (enabled ? "&aвключено" : "&cвыключено")));
        sender.sendMessage(msg("&7Расписание: &f" + day + " " + time));
        sender.sendMessage(msg("&7Часовой пояс: &f" + plugin.getConfig().getString("schedule.timezone", "system")));
        sender.sendMessage(msg("&7Экономика Vault: " + (economy == null ? "&cне найдена" : "&aподключена")));
    }

    public void createBackup(CommandSender sender) {
        try {
            saveWorldsAndPlayers();
            Path backup = createBackupArchive();
            sender.sendMessage(msg("&aРезервная копия создана: &f" + plugin.getDataFolder().toPath().toAbsolutePath().normalize().relativize(backup.toAbsolutePath().normalize())));
        } catch (Exception ex) {
            sender.sendMessage(msg("&cНе удалось создать резервную копию: &f" + ex.getMessage()));
            plugin.getLogger().severe("MoonWipe backup failed: " + ex.getMessage());
        }
    }

    private void performWipe() {
        countdownRunning = false;
        wipeExecuting = true;
        confirmationExpiresAt = 0L;

        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (Player player : online) {
            player.sendMessage(msg("&cНачинается полный вайп. Сервер будет перезапущен."));
        }

        byte[] oldServerProperties = null;
        AuctionSnapshot auctionSnapshot = null;
        List<BalanceSnapshot> balances = new ArrayList<>();
        boolean balancesChanged = false;
        boolean levelNameChanged = false;

        try {
            boolean resetWorlds = plugin.getConfig().getBoolean("settings.reset-worlds", true);
            String newWorldName = validateWorldName(plugin.getConfig().getString("settings.new-world-name", "world"));
            List<String> worldFolders = resetWorlds ? collectWorldFolderNames(newWorldName) : List.of();
            if (!plugin.getConfig().getBoolean("backup.enabled", true)) {
                throw new IllegalStateException("Резервное копирование отключено; безопасный вайп отменён.");
            }
            if (plugin.getConfig().getBoolean("settings.reset-economy", true)) {
                refreshEconomy();
                if (economy == null) throw new IllegalStateException("Vault Economy provider не найден. Вайп отменён.");
            }
            if (plugin.getConfig().getBoolean("settings.reset-auction", true)) {
                findAuctionDatabasePath();
            }
            Path propertiesPath = serverRoot().resolve("server.properties");
            if (!Files.isRegularFile(propertiesPath)) throw new IOException("Файл server.properties не найден.");
            oldServerProperties = Files.readAllBytes(propertiesPath);

            saveWorldsAndPlayers();
            for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                player.kickPlayer(MoonWipePlugin.color("&5Moon&bEclips &8» &cВыполняется полный вайп. Зайдите после перезапуска."));
            }
            Bukkit.savePlayers();
            Path backup = createBackupArchive();
            plugin.getLogger().info("Резервная копия перед вайпом: " + backup);

            if (resetWorlds) {
                levelNameChanged = true;
                updateLevelName(newWorldName);
            }

            if (plugin.getConfig().getBoolean("settings.reset-economy", true)) {
                balances = resetBalances();
                balancesChanged = true;
            }

            if (plugin.getConfig().getBoolean("settings.reset-auction", true)) {
                auctionSnapshot = clearAuctionDatabase();
            }

            if (resetWorlds) writePendingWipe(newWorldName, worldFolders);
            Bukkit.broadcastMessage(msg("&aДанные вайпа подготовлены. Сервер сейчас штатно остановится."));
            Bukkit.getScheduler().runTaskLater(plugin, Bukkit::shutdown, 20L);
        } catch (Exception ex) {
            plugin.getLogger().severe("MoonWipe не смог завершить подготовку: " + ex.getMessage());
            if (auctionSnapshot != null) restoreAuctionDatabase(auctionSnapshot);
            if (balancesChanged) restoreBalances(balances);
            if (levelNameChanged && oldServerProperties != null) {
                try {
                    Files.write(serverRoot().resolve("server.properties"), oldServerProperties);
                } catch (IOException restoreError) {
                    plugin.getLogger().severe("Не удалось вернуть server.properties: " + restoreError.getMessage());
                }
            }
            wipeExecuting = false;
            Bukkit.broadcastMessage(msg("&cПодготовка вайпа прервана. Миры не удалены. Проверь консоль и резервную копию."));
        }
    }

    private void saveWorldsAndPlayers() {
        Bukkit.savePlayers();
        for (World world : Bukkit.getWorlds()) {
            world.save();
        }
    }

    private Path createBackupArchive() throws IOException {
        if (!plugin.getConfig().getBoolean("backup.enabled", true)) {
            throw new IOException("Резервное копирование отключено; безопасный вайп отменён.");
        }
        Path root = serverRoot();
        Path backupDir = plugin.getDataFolder().toPath()
                .resolve(plugin.getConfig().getString("backup.directory", "backups")).normalize();
        if (!backupDir.startsWith(plugin.getDataFolder().toPath().normalize())) {
            throw new IOException("Некорректная папка резервных копий.");
        }
        Files.createDirectories(backupDir);
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path archive = backupDir.resolve("MoonWipe-" + stamp + ".zip");
        Set<Path> sources = new LinkedHashSet<>();

        if (plugin.getConfig().getBoolean("backup.include-worlds", true)) {
            for (String name : collectWorldFolderNames(validateWorldName(
                    plugin.getConfig().getString("settings.new-world-name", "world")))) {
                Path path = Bukkit.getWorldContainer().toPath().resolve(name).normalize();
                if (path.startsWith(Bukkit.getWorldContainer().toPath().normalize()) && Files.exists(path)) sources.add(path);
            }
        }
        if (plugin.getConfig().getBoolean("backup.include-essentials-userdata", true)) {
            sources.add(root.resolve("plugins").resolve("Essentials").resolve("userdata"));
        }
        if (plugin.getConfig().getBoolean("backup.include-auction-database", true)) {
            Path auction = findAuctionDatabasePath();
            if (auction != null) sources.add(auction);
            Path auctionConfig = root.resolve("plugins").resolve("EconomicMoonEclips").resolve("auction.yml");
            if (Files.exists(auctionConfig)) sources.add(auctionConfig);
        }
        Path serverProperties = root.resolve("server.properties");
        if (Files.exists(serverProperties)) sources.add(serverProperties);

        try (OutputStream fileOut = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(fileOut)) {
            for (Path source : sources) {
                if (Files.exists(source)) addToZip(source, root, zip);
            }
        } catch (Exception ex) {
            Files.deleteIfExists(archive);
            if (ex instanceof IOException io) throw io;
            throw new IOException("Ошибка упаковки резервной копии: " + ex.getMessage(), ex);
        }
        return archive;
    }

    private void addToZip(Path source, Path root, ZipOutputStream zip) throws IOException {
        if (Files.isRegularFile(source)) {
            addFileToZip(source, root, zip);
            return;
        }
        if (!Files.isDirectory(source)) return;
        try (var stream = Files.walk(source)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                addFileToZip(path, root, zip);
            }
        }
    }

    private void addFileToZip(Path file, Path root, ZipOutputStream zip) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(root.toAbsolutePath().normalize())) return;
        String entryName = root.toAbsolutePath().normalize().relativize(normalized).toString().replace('\\', '/');
        zip.putNextEntry(new ZipEntry(entryName));
        Files.copy(normalized, zip);
        zip.closeEntry();
    }

    private List<BalanceSnapshot> resetBalances() throws Exception {
        refreshEconomy();
        if (economy == null) throw new IllegalStateException("Vault Economy provider не найден. Балансы не сброшены.");

        Set<UUID> playerIds = new LinkedHashSet<>();
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) playerIds.add(player.getUniqueId());

        Path userData = serverRoot().resolve("plugins").resolve("Essentials").resolve("userdata");
        if (Files.isDirectory(userData)) {
            try (var files = Files.newDirectoryStream(userData, "*.yml")) {
                for (Path file : files) {
                    String filename = file.getFileName().toString();
                    try {
                        playerIds.add(UUID.fromString(filename.substring(0, filename.length() - 4)));
                    } catch (IllegalArgumentException ignored) {
                        plugin.getLogger().warning("Пропущен файл Essentials с неизвестным UUID: " + filename);
                    }
                }
            }
        }

        List<BalanceSnapshot> changed = new ArrayList<>();
        try {
            for (UUID uuid : playerIds) {
                OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
                double original = economy.getBalance(player);
                if (!Double.isFinite(original)) throw new IllegalStateException("Некорректный баланс у UUID " + uuid);
                if (Math.abs(original) < 0.000001D) continue;

                changed.add(new BalanceSnapshot(player, original));
                EconomyResponse response;
                if (original > 0) response = economy.withdrawPlayer(player, original);
                else response = economy.depositPlayer(player, -original);

                if (!response.transactionSuccess() || Math.abs(economy.getBalance(player)) > 0.01D) {
                    throw new IllegalStateException("Не удалось обнулить баланс UUID " + uuid
                            + (response.errorMessage == null ? "" : ": " + response.errorMessage));
                }
            }
            plugin.getLogger().info("Обнулены балансы игроков: " + changed.size());
            return changed;
        } catch (Exception ex) {
            restoreBalances(changed);
            throw ex;
        }
    }

    private void restoreBalances(List<BalanceSnapshot> snapshots) {
        if (economy == null) refreshEconomy();
        if (economy == null) {
            plugin.getLogger().severe("Откат балансов невозможен: Vault Economy provider отсутствует.");
            return;
        }
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            BalanceSnapshot snapshot = snapshots.get(i);
            try {
                double current = economy.getBalance(snapshot.player());
                double delta = snapshot.balance() - current;
                if (Math.abs(delta) < 0.000001D) continue;
                EconomyResponse response = delta > 0
                        ? economy.depositPlayer(snapshot.player(), delta)
                        : economy.withdrawPlayer(snapshot.player(), -delta);
                if (!response.transactionSuccess()) {
                    plugin.getLogger().severe("Не удалось восстановить баланс " + snapshot.player().getUniqueId()
                            + ": " + response.errorMessage);
                }
            } catch (Exception ex) {
                plugin.getLogger().severe("Ошибка восстановления баланса: " + ex.getMessage());
            }
        }
    }

    private AuctionSnapshot clearAuctionDatabase() throws Exception {
        Plugin auctionPlugin = Bukkit.getPluginManager().getPlugin(
                plugin.getConfig().getString("auction.plugin-folder", "EconomicMoonEclips"));
        Path database = findAuctionDatabasePath();
        if (database == null || !Files.exists(database)) {
            plugin.getLogger().warning("База аукциона не найдена — очистка аукциона пропущена.");
            return null;
        }

        boolean wasEnabled = auctionPlugin != null && auctionPlugin.isEnabled();
        if (wasEnabled) Bukkit.getPluginManager().disablePlugin(auctionPlugin);
        byte[] originalBytes = null;

        try {
            originalBytes = Files.readAllBytes(database);
            Class.forName("org.sqlite.JDBC");
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                Set<String> tables = new LinkedHashSet<>();
                try (ResultSet rs = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                    while (rs.next()) tables.add(rs.getString(1));
                }
                if (!tables.contains("listings") || !tables.contains("item_mail")) {
                    throw new SQLException("Схема auction.db отличается от проверенной: ожидались listings и item_mail. База не изменена.");
                }
                statement.executeUpdate("DELETE FROM listings");
                statement.executeUpdate("DELETE FROM item_mail");
                if (tables.contains("sqlite_sequence")) {
                    statement.executeUpdate("DELETE FROM sqlite_sequence WHERE name IN ('listings','item_mail')");
                }
                connection.commit();
            }
            if (wasEnabled) {
                Bukkit.getPluginManager().enablePlugin(auctionPlugin);
                if (!auctionPlugin.isEnabled()) {
                    throw new IllegalStateException("EconomicMoonEclips не смог повторно включиться после очистки аукциона.");
                }
            }
            plugin.getLogger().info("Аукцион очищен: listings и item_mail удалены, структура базы сохранена.");
            return new AuctionSnapshot(database, originalBytes, wasEnabled, auctionPlugin);
        } catch (Exception ex) {
            if (originalBytes != null) {
                try {
                    Files.write(database, originalBytes);
                } catch (IOException restoreError) {
                    plugin.getLogger().severe("Не удалось восстановить auction.db после ошибки: " + restoreError.getMessage());
                }
            }
            if (wasEnabled && !auctionPlugin.isEnabled()) Bukkit.getPluginManager().enablePlugin(auctionPlugin);
            throw ex;
        }
    }

    private void restoreAuctionDatabase(AuctionSnapshot snapshot) {
        if (snapshot == null) return;
        try {
            if (snapshot.plugin() != null && snapshot.plugin().isEnabled()) {
                Bukkit.getPluginManager().disablePlugin(snapshot.plugin());
            }
            Files.write(snapshot.path(), snapshot.originalBytes());
            if (snapshot.wasEnabled() && snapshot.plugin() != null && !snapshot.plugin().isEnabled()) {
                Bukkit.getPluginManager().enablePlugin(snapshot.plugin());
            }
        } catch (Exception ex) {
            plugin.getLogger().severe("Не удалось откатить auction.db: " + ex.getMessage());
        }
    }

    private Path findAuctionDatabasePath() throws IOException {
        Path root = serverRoot();
        String folderName = plugin.getConfig().getString("auction.plugin-folder", "EconomicMoonEclips");
        if (folderName == null || !folderName.matches("[A-Za-z0-9._-]+")) {
            throw new IOException("Некорректное имя папки экономического плагина.");
        }
        Path folder = root.resolve("plugins").resolve(folderName).normalize();
        if (!folder.startsWith(root.resolve("plugins").normalize())) throw new IOException("Путь к базе аукциона вне plugins.");
        Path configFile = folder.resolve(plugin.getConfig().getString("auction.config-file", "auction.yml"));
        String databaseName = plugin.getConfig().getString("auction.default-database-file", "auction.db");
        if (Files.exists(configFile)) {
            YamlConfiguration auctionConfig = YamlConfiguration.loadConfiguration(configFile.toFile());
            databaseName = auctionConfig.getString("auction.database-file", databaseName);
        }
        Path database = folder.resolve(databaseName).normalize();
        if (!database.startsWith(folder.normalize())) throw new IOException("Путь к auction.db выходит за пределы папки плагина.");
        return database;
    }

    private List<String> collectWorldFolderNames(String newWorldName) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        Path worldContainer = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        for (World world : Bukkit.getWorlds()) {
            Path folder = world.getWorldFolder().toPath().toAbsolutePath().normalize();
            if (folder.getParent() != null && folder.getParent().equals(worldContainer)) {
                names.add(validateWorldName(folder.getFileName().toString()));
            }
        }
        for (String name : plugin.getConfig().getStringList("worlds.additional")) {
            names.add(validateWorldName(name));
        }
        names.add(newWorldName);
        names.add(newWorldName + "_nether");
        names.add(newWorldName + "_the_end");
        return new ArrayList<>(names);
    }

    private void writePendingWipe(String newWorldName, List<String> worldFolders) throws IOException {
        Path file = plugin.getDataFolder().toPath().resolve("pending-wipe.yml");
        YamlConfiguration pending = new YamlConfiguration();
        pending.set("new-world-name", newWorldName);
        pending.set("world-folders", worldFolders);
        pending.set("created-at", System.currentTimeMillis());
        pending.save(file.toFile());
    }

    public static void processPendingWipe(MoonWipePlugin plugin) {
        Path pendingFile = plugin.getDataFolder().toPath().resolve("pending-wipe.yml");
        if (!Files.exists(pendingFile)) return;
        try {
            YamlConfiguration pending = YamlConfiguration.loadConfiguration(pendingFile.toFile());
            List<String> names = pending.getStringList("world-folders");
            Path worldContainer = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
            for (String rawName : names) {
                String name = validateWorldName(rawName);
                Path target = worldContainer.resolve(name).normalize();
                if (!target.startsWith(worldContainer) || target.equals(worldContainer)) {
                    throw new IOException("Небезопасный путь мира: " + rawName);
                }
                deleteRecursively(target);
                plugin.getLogger().info("Старый мир очищен перед загрузкой: " + name);
            }
            Files.deleteIfExists(pendingFile);
            plugin.getLogger().info("План MoonWipe выполнен. Paper создаст новый мир при загрузке.");
        } catch (Exception ex) {
            plugin.getLogger().severe("Не удалось выполнить отложенное удаление миров MoonWipe: " + ex.getMessage());
            plugin.getLogger().severe("Файл pending-wipe.yml сохранён. Не запускайте вайп повторно, сначала проверьте папки миров.");
        }
    }

    private static void deleteRecursively(Path target) throws IOException {
        if (!Files.exists(target)) return;
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void updateLevelName(String worldName) throws IOException {
        Path properties = serverRoot().resolve("server.properties");
        List<String> lines = Files.readAllLines(properties);
        boolean found = false;
        List<String> updated = new ArrayList<>();
        for (String line : lines) {
            if (line.trim().startsWith("level-name=") || line.trim().startsWith("level-name =")) {
                updated.add("level-name=" + worldName);
                found = true;
            } else {
                updated.add(line);
            }
        }
        if (!found) updated.add("level-name=" + worldName);
        Files.write(properties, updated);
    }

    private Path serverRoot() {
        return plugin.getDataFolder().toPath().toAbsolutePath().normalize().getParent().getParent();
    }

    private static String validateWorldName(String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9._-]+") || name.equals(".") || name.equals("..")) {
            throw new IOException("Недопустимое имя папки мира: " + name);
        }
        Set<String> reserved = Set.of("plugins", "logs", "libraries", "cache", "crash-reports",
                "versions", "config", "mods", "resourcepacks", "backups");
        if (reserved.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IOException("Имя папки мира совпадает с системной папкой сервера: " + name);
        }
        return name;
    }

    private String msg(String text) {
        String prefix = plugin.getConfig().getString("messages.prefix", "&8[&5Moon&bWipe&8] ");
        return ChatColor.translateAlternateColorCodes('&', prefix + text);
    }

    private String formatTime(int seconds) {
        if (seconds >= 60) return (seconds / 60) + " мин. " + (seconds % 60) + " сек.";
        return seconds + " сек.";
    }

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (countdownRunning || wipeExecuting) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    MoonWipePlugin.color("&cMoonEclips готовится к полному вайпу. Попробуйте позже."));
        }
    }

    private record BalanceSnapshot(OfflinePlayer player, double balance) {}
    private record AuctionSnapshot(Path path, byte[] originalBytes, boolean wasEnabled, Plugin plugin) {}
}
