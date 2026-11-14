package ru.mooneclips.moonwipe;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class MoonWipePlugin extends JavaPlugin implements CommandExecutor, Listener {
    private static final String MENU_TITLE = ChatColor.DARK_AQUA + "MoonWipe";
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final AtomicBoolean wipeInProgress = new AtomicBoolean(false);
    private String confirmationCode;
    private long confirmationExpiresAt;
    private ZoneId scheduleZone = ZoneId.of("Europe/Berlin");
    private ZonedDateTime nextScheduledWipe;
    private final Set<Integer> warnedMinutes = new HashSet<>();
    private BukkitTask scheduleTask;

    @Override
    public void onLoad() {
        try {
            Files.createDirectories(getDataFolder().toPath());
            processPendingWipeBeforeWorldLoad();
        } catch (Exception exception) {
            getLogger().severe("Could not process pending wipe during startup: " + exception.getMessage());
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("mwipe").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);

        getServer().getScheduler().runTask(this, this::finishPendingWipe);

        loadSchedule();
        scheduleTask = getServer().getScheduler().runTaskTimer(this, this::tickSchedule, 20L, 20L);
        getLogger().info("MoonWipe 1.0.0 enabled. Automatic schedule: "
                + (getConfig().getBoolean("schedule.enabled", false) ? "enabled" : "disabled"));
    }

    @Override
    public void onDisable() {
        if (scheduleTask != null) {
            scheduleTask.cancel();
        }
    }

    private void processPendingWipeBeforeWorldLoad() {
        Path pendingPath = getDataFolder().toPath().resolve("pending-wipe.yml");
        if (!Files.exists(pendingPath)) {
            return;
        }

        YamlConfiguration pending = YamlConfiguration.loadConfiguration(pendingPath.toFile());
        if (pending.getBoolean("filesystem-complete", false)) {
            getLogger().warning("A wipe was already applied to files; finishing remaining tasks after plugins enable.");
            return;
        }

        List<String> errors = new ArrayList<>();
        List<String> completed = new ArrayList<>();
        Path root = serverRoot();

        for (String relative : pending.getStringList("world-folders")) {
            try {
                Path target = resolveSafeWorldPath(root, relative);
                if (Files.exists(target)) {
                    deleteRecursively(target);
                }
                completed.add("world:" + relative);
                getLogger().info("Removed configured world folder: " + relative);
            } catch (Exception exception) {
                errors.add("world " + relative + ": " + exception.getMessage());
            }
        }

        if (pending.getBoolean("reset-auction-database", true)) {
            String relativeDb = pending.getString("auction-database", "plugins/EconomicMoonEclips/auction.db");
            try {
                Path database = resolveSafeAuctionDatabase(root, relativeDb);
                if (Files.exists(database)) {
                    clearSqliteDatabase(database);
                    completed.add("auction-database:" + relativeDb);
                    getLogger().info("Cleared SQLite application tables: " + relativeDb);
                } else {
                    completed.add("auction-database:not-found:" + relativeDb);
                    getLogger().warning("Auction database was not found; no database rows were changed: " + relativeDb);
                }
            } catch (Exception exception) {
                errors.add("auction database: " + exception.getMessage());
            }
        }

        for (String relative : pending.getStringList("extra-data-paths")) {
            try {
                Path target = resolveSafeExtraPath(root, relative);
                if (Files.exists(target)) {
                    deleteRecursively(target);
                }
                completed.add("extra:" + relative);
            } catch (Exception exception) {
                errors.add("extra path " + relative + ": " + exception.getMessage());
            }
        }

        pending.set("filesystem-complete", errors.isEmpty());
        pending.set("completed", completed);
        pending.set("errors", errors);
        pending.set("applied-at", ZonedDateTime.now().toString());
        try {
            pending.save(pendingPath.toFile());
        } catch (IOException exception) {
            getLogger().severe("Could not write wipe state: " + exception.getMessage());
        }

        if (!errors.isEmpty()) {
            getLogger().severe("MoonWipe file reset was incomplete. Check plugins/MoonWipe/pending-wipe.yml: " + errors);
        }
    }

    private void finishPendingWipe() {
        Path pendingPath = getDataFolder().toPath().resolve("pending-wipe.yml");
        if (!Files.exists(pendingPath)) {
            return;
        }

        YamlConfiguration pending = YamlConfiguration.loadConfiguration(pendingPath.toFile());
        List<String> errors = new ArrayList<>(pending.getStringList("errors"));
        List<String> completed = new ArrayList<>(pending.getStringList("completed"));

        if (!pending.getBoolean("filesystem-complete", false)) {
            writeWipeReport(false, completed, errors);
            getLogger().severe("MoonWipe did not finish all file operations. Money was NOT reset to avoid a partial wipe.");
            try {
                Files.deleteIfExists(pendingPath);
            } catch (IOException exception) {
                getLogger().warning("Could not remove failed pending-wipe marker: " + exception.getMessage());
            }
            return;
        }

        if (pending.getBoolean("reset-money", true)) {
            try {
                int changed = resetAllBalancesThroughVault();
                completed.add("money-balances:" + changed);
            } catch (Exception exception) {
                errors.add("money: " + exception.getMessage());
            }
        }

        boolean success = errors.isEmpty();
        writeWipeReport(success, completed, errors);
        try {
            Files.deleteIfExists(pendingPath);
        } catch (IOException exception) {
            getLogger().warning("Could not remove pending-wipe marker: " + exception.getMessage());
        }

        if (success) {
            getLogger().info("MoonWipe completed successfully. Privileges, plugin jars, plugin configs and schematic files were preserved.");
            Bukkit.broadcastMessage(color(prefix() + "&aПолный вайп завершён. Новый сезон готов."));
        } else {
            getLogger().severe("MoonWipe completed with errors. Review plugins/MoonWipe/last-wipe.yml: " + errors);
        }
    }

    private int resetAllBalancesThroughVault() throws Exception {
        RegisteredServiceProvider<Economy> registration =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) {
            throw new IllegalStateException("Vault Economy provider is not registered. Install/enable Vault and EssentialsX Economy.");
        }

        Economy economy = registration.getProvider();
        Path userdata = serverRoot().resolve("plugins/Essentials/userdata").normalize();
        if (!userdata.startsWith(serverRoot()) || !Files.isDirectory(userdata)) {
            throw new IllegalStateException("EssentialsX userdata folder was not found: plugins/Essentials/userdata");
        }

        Set<UUID> playerIds = new LinkedHashSet<>();
        try (var files = Files.list(userdata)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                String name = file.getFileName().toString();
                if (name.toLowerCase(Locale.ROOT).endsWith(".yml")) {
                    try {
                        playerIds.add(UUID.fromString(name.substring(0, name.length() - 4)));
                    } catch (IllegalArgumentException ignored) {
                        // Ignore non-UUID YAML files.
                    }
                }
            });
        }

        if (playerIds.isEmpty()) {
            throw new IllegalStateException("No UUID userdata files were found; refusing to claim that balances were reset.");
        }

        int changed = 0;
        List<String> failures = new ArrayList<>();
        for (UUID uuid : playerIds) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            double balance = economy.getBalance(offlinePlayer);
            if (!Double.isFinite(balance)) {
                failures.add(uuid + " returned an invalid balance: " + balance);
                continue;
            }
            if (balance == 0.0D) {
                continue;
            }
            EconomyResponse response = balance > 0.0D
                    ? economy.withdrawPlayer(offlinePlayer, balance)
                    : economy.depositPlayer(offlinePlayer, -balance);
            if (response.transactionSuccess()) {
                changed++;
            } else {
                failures.add(uuid + ": " + response.errorMessage);
            }
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException("Some balances could not be reset (" + failures.size() + "): "
                    + String.join("; ", failures.subList(0, Math.min(5, failures.size()))));
        }
        return changed;
    }

    private void writeWipeReport(boolean success, List<String> completed, List<String> errors) {
        YamlConfiguration report = new YamlConfiguration();
        report.set("success", success);
        report.set("finished-at", ZonedDateTime.now().toString());
        report.set("completed", completed);
        report.set("errors", errors);
        try {
            report.save(getDataFolder().toPath().resolve("last-wipe.yml").toFile());
        } catch (IOException exception) {
            getLogger().severe("Could not save last-wipe.yml: " + exception.getMessage());
        }
    }

    private void clearSqliteDatabase(Path database) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            try (Statement pragma = connection.createStatement()) {
                pragma.execute("PRAGMA foreign_keys=OFF");
            }
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                List<String> tables = new ArrayList<>();
                try (ResultSet result = statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
                    while (result.next()) {
                        tables.add(result.getString(1));
                    }
                }
                if (tables.isEmpty()) {
                    throw new IllegalStateException("No application tables found; refusing to treat an unknown/empty database as a successful auction reset");
                }
                for (String table : tables) {
                    String quoted = "\"" + table.replace("\"", "\"\"") + "\"";
                    statement.executeUpdate("DELETE FROM " + quoted);
                }
                connection.commit();
                try (ResultSet integrity = statement.executeQuery("PRAGMA integrity_check")) {
                    if (!integrity.next() || !"ok".equalsIgnoreCase(integrity.getString(1))) {
                        throw new IllegalStateException("SQLite integrity_check did not return ok");
                    }
                }
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
            try (Statement vacuum = connection.createStatement()) {
                vacuum.execute("VACUUM");
            }
        }
    }

    private void loadSchedule() {
        try {
            scheduleZone = ZoneId.of(getConfig().getString("timezone", "Europe/Berlin"));
            nextScheduledWipe = calculateNextWipe(ZonedDateTime.now(scheduleZone));
            warnedMinutes.clear();
        } catch (Exception exception) {
            nextScheduledWipe = null;
            getLogger().severe("Invalid wipe schedule configuration: " + exception.getMessage());
        }
    }

    private ZonedDateTime calculateNextWipe(ZonedDateTime now) {
        DayOfWeek day = DayOfWeek.valueOf(getConfig().getString("schedule.day", "SATURDAY").toUpperCase(Locale.ROOT));
        LocalTime time = LocalTime.parse(getConfig().getString("schedule.time", "14:00"));
        LocalDate date = now.toLocalDate();
        ZonedDateTime candidate = LocalDateTime.of(date, time).atZone(scheduleZone);
        while (candidate.getDayOfWeek() != day || !candidate.isAfter(now)) {
            date = date.plusDays(1);
            candidate = LocalDateTime.of(date, time).atZone(scheduleZone);
        }
        return candidate;
    }

    private void tickSchedule() {
        if (!getConfig().getBoolean("schedule.enabled", false) || nextScheduledWipe == null || wipeInProgress.get()) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(scheduleZone);
        long seconds = Duration.between(now, nextScheduledWipe).getSeconds();

        for (int minute : getConfig().getIntegerList("messages.warn-minutes")) {
            if (minute > 0 && seconds <= minute * 60L && seconds > (minute - 1) * 60L && warnedMinutes.add(minute)) {
                Bukkit.broadcastMessage(color(prefix() + "&eПолный вайп MoonEclips через " + minute + " мин."));
            }
        }

        if (seconds <= 0) {
            ZonedDateTime due = nextScheduledWipe;
            nextScheduledWipe = calculateNextWipe(due.plusSeconds(1));
            warnedMinutes.clear();
            getLogger().info("Scheduled wipe started for " + due);
            beginWipe(null, true);
        }
    }

    private void beginWipe(CommandSender sender, boolean automatic) {
        if (!wipeInProgress.compareAndSet(false, true)) {
            send(sender, "&cОперация вайпа уже выполняется.");
            return;
        }

        List<String> worldFolders = getConfig().getStringList("wipe.world-folders");
        if (worldFolders.isEmpty()) {
            wipeInProgress.set(false);
            send(sender, "&cВ конфигурации не заданы wipe.world-folders.");
            return;
        }

        String primaryWorld = readPrimaryWorldName();
        if (primaryWorld != null && !worldFolders.contains(primaryWorld)) {
            wipeInProgress.set(false);
            send(sender, "&cВ wipe.world-folders отсутствует основной мир level-name=" + primaryWorld
                    + ". Добавь его в config.yml и перезагрузи MoonWipe.");
            return;
        }

        for (String folder : worldFolders) {
            try {
                resolveSafeWorldPath(serverRoot(), folder);
            } catch (Exception exception) {
                wipeInProgress.set(false);
                send(sender, "&cНебезопасный путь мира '" + folder + "': " + exception.getMessage());
                return;
            }
        }

        if (!getConfig().getBoolean("backup.enabled", true)) {
            wipeInProgress.set(false);
            send(sender, "&cВайп запрещён: резервное копирование отключено. Включи backup.enabled.");
            return;
        }
        if (getConfig().getBoolean("wipe.reset-money", true)) {
            Path essentialsUserdata = serverRoot().resolve("plugins/Essentials/userdata");
            if (!Files.isDirectory(essentialsUserdata)) {
                wipeInProgress.set(false);
                send(sender, "&cНе найдена папка plugins/Essentials/userdata. Сброс Money отменён.");
                return;
            }
            if (getServer().getServicesManager().getRegistration(Economy.class) == null) {
                wipeInProgress.set(false);
                send(sender, "&cНе найден Vault Economy provider. Проверь Vault и EssentialsX Economy.");
                return;
            }
        }
        if (getConfig().getBoolean("wipe.reset-auction-database", true)) {
            try {
                Path database = resolveSafeAuctionDatabase(serverRoot(),
                        getConfig().getString("wipe.auction-database", "plugins/EconomicMoonEclips/auction.db"));
                if (!Files.isRegularFile(database)) {
                    throw new IOException("database file does not exist: " + database);
                }
            } catch (Exception exception) {
                wipeInProgress.set(false);
                send(sender, "&cБаза аукциона не готова к сбросу: " + exception.getMessage());
                return;
            }
        }
        for (String extra : getConfig().getStringList("wipe.extra-data-paths")) {
            try {
                resolveSafeExtraPath(serverRoot(), extra);
            } catch (Exception exception) {
                wipeInProgress.set(false);
                send(sender, "&cНебезопасный дополнительный путь '" + extra + "': " + exception.getMessage());
                return;
            }
        }

        send(sender, "&eПодготавливаю вайп: сохраняю миры и отключаю игроков...");
        Bukkit.broadcastMessage(color(prefix() + "&eНачалась подготовка полного вайпа. Игроки будут отключены."));

        for (org.bukkit.World world : Bukkit.getWorlds()) {
            world.save();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.kickPlayer(color("&cНа сервере начинается полный вайп. Зайдите позже."));
        }

        getServer().getScheduler().runTaskLater(this, () -> CompletableFuture.runAsync(() -> {
            try {
                if (getConfig().getBoolean("backup.enabled", true)) {
                    createBackupArchive();
                }
                YamlConfiguration pending = new YamlConfiguration();
                pending.set("created-at", ZonedDateTime.now().toString());
                pending.set("automatic", automatic);
                pending.set("filesystem-complete", false);
                pending.set("world-folders", worldFolders);
                pending.set("reset-money", getConfig().getBoolean("wipe.reset-money", true));
                pending.set("reset-auction-database", getConfig().getBoolean("wipe.reset-auction-database", true));
                pending.set("auction-database", getConfig().getString("wipe.auction-database",
                        "plugins/EconomicMoonEclips/auction.db"));
                pending.set("extra-data-paths", getConfig().getStringList("wipe.extra-data-paths"));
                pending.save(getDataFolder().toPath().resolve("pending-wipe.yml").toFile());

                Bukkit.getScheduler().runTask(this, () -> {
                    send(sender, "&aРезервная копия готова. Сервер завершает работу для безопасного вайпа.");
                    getLogger().warning("Wipe manifest created. Shutting down; host panel must restart this server process.");
                    Bukkit.shutdown();
                });
            } catch (Exception exception) {
                wipeInProgress.set(false);
                getLogger().severe("Wipe preparation failed; no wipe manifest was written: " + exception.getMessage());
                Bukkit.getScheduler().runTask(this, () -> send(sender, "&cПодготовка вайпа отменена: " + exception.getMessage()));
            }
        }), 40L);
    }

    private void createBackupArchive() throws IOException {
        Path root = serverRoot();
        Path backupDirectory = resolveSafeBackupDirectory(root,
                getConfig().getString("backup.folder", "plugins/MoonWipe/backups"));
        Files.createDirectories(backupDirectory);
        String archiveName = "moonwipe-" + ZonedDateTime.now().format(FILE_TIME) + ".zip";
        Path archive = backupDirectory.resolve(archiveName);

        List<Path> sources = new ArrayList<>();
        for (String world : getConfig().getStringList("wipe.world-folders")) {
            Path source = resolveSafeWorldPath(root, world);
            if (Files.exists(source)) {
                sources.add(source);
            }
        }
        Path essentialsUserdata = root.resolve("plugins/Essentials/userdata");
        if (Files.exists(essentialsUserdata)) {
            sources.add(essentialsUserdata);
        }
        if (getConfig().getBoolean("wipe.reset-auction-database", true)) {
            Path database = resolveSafeAuctionDatabase(root,
                    getConfig().getString("wipe.auction-database", "plugins/EconomicMoonEclips/auction.db"));
            if (Files.exists(database)) {
                sources.add(database);
            }
            Path wal = database.resolveSibling(database.getFileName() + "-wal");
            Path shm = database.resolveSibling(database.getFileName() + "-shm");
            if (Files.exists(wal)) {
                sources.add(wal);
            }
            if (Files.exists(shm)) {
                sources.add(shm);
            }
        }
        Path schematic = root.resolve("plugins/WorldEdit/schematics/hub-spawn.schem");
        if (Files.exists(schematic)) {
            sources.add(schematic);
        }
        for (String extra : getConfig().getStringList("wipe.extra-data-paths")) {
            Path extraPath = resolveSafeExtraPath(root, extra);
            if (Files.exists(extraPath)) {
                sources.add(extraPath);
            }
        }
        Path configFile = getDataFolder().toPath().resolve("config.yml");
        if (Files.exists(configFile)) {
            sources.add(configFile);
        }

        try (OutputStream fileOut = Files.newOutputStream(archive);
             BufferedOutputStream buffered = new BufferedOutputStream(fileOut);
             ZipOutputStream zip = new ZipOutputStream(buffered)) {
            for (Path source : sources) {
                addPathToZip(root, source, zip);
            }
        } catch (Exception exception) {
            Files.deleteIfExists(archive);
            throw exception;
        }
        if (!Files.isRegularFile(archive) || Files.size(archive) == 0L) {
            Files.deleteIfExists(archive);
            throw new IOException("backup archive was not created or is empty");
        }
        pruneBackups(backupDirectory);
        getLogger().info("Backup created: " + archive);
    }

    private void addPathToZip(Path root, Path source, ZipOutputStream zip) throws IOException {
        if (Files.isDirectory(source)) {
            try (var paths = Files.walk(source)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    addFileEntry(root, path, zip);
                }
            }
        } else if (Files.isRegularFile(source)) {
            addFileEntry(root, source, zip);
        }
    }

    private void addFileEntry(Path root, Path file, ZipOutputStream zip) throws IOException {
        String entryName = root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        zip.putNextEntry(new ZipEntry(entryName));
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            input.transferTo(zip);
        }
        zip.closeEntry();
    }

    private void pruneBackups(Path directory) throws IOException {
        int keep = getConfig().getInt("backup.keep-newest", 5);
        if (keep <= 0) {
            return;
        }
        try (var files = Files.list(directory)) {
            List<Path> archives = files.filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparingLong(this::lastModified).reversed()).toList();
            for (int i = keep; i < archives.size(); i++) {
                Files.deleteIfExists(archives.get(i));
            }
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private void createBackupOnly(CommandSender sender) {
        if (!getConfig().getBoolean("backup.enabled", true)) {
            send(sender, "&cРезервное копирование отключено в config.yml.");
            return;
        }
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            send(sender, "&cДля отдельной резервной копии сначала отключи всех игроков. Это защищает их данные от неполного сохранения.");
            return;
        }
        if (!wipeInProgress.compareAndSet(false, true)) {
            send(sender, "&cДругая операция MoonWipe уже выполняется.");
            return;
        }
        send(sender, "&eСоздаю резервную копию. Это может занять некоторое время...");
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            world.save();
        }
        CompletableFuture.runAsync(() -> {
            try {
                createBackupArchive();
                Bukkit.getScheduler().runTask(this, () -> {
                    wipeInProgress.set(false);
                    send(sender, "&aРезервная копия создана.");
                });
            } catch (Exception exception) {
                Bukkit.getScheduler().runTask(this, () -> {
                    wipeInProgress.set(false);
                    send(sender, "&cОшибка резервного копирования: " + exception.getMessage());
                });
            }
        });
    }

    private Path serverRoot() {
        return getDataFolder().toPath().toAbsolutePath().normalize().getParent().getParent();
    }

    private Path resolveSafeWorldPath(Path root, String relative) throws IOException {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || target.equals(root) || target.startsWith(root.resolve("plugins"))) {
            throw new IOException("world path must be a direct server world folder, not a plugin path or server root");
        }
        if (relative.contains("..") || relative.isBlank()) {
            throw new IOException("invalid relative path");
        }
        return target;
    }

    private Path resolveSafeAuctionDatabase(Path root, String relative) throws IOException {
        Path target = root.resolve(relative).normalize();
        Path allowed = root.resolve("plugins/EconomicMoonEclips").normalize();
        if (!target.startsWith(allowed) || !target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".db")
                || relative.contains("..")) {
            throw new IOException("auction database must be a .db file inside plugins/EconomicMoonEclips/");
        }
        return target;
    }

    private Path resolveSafeExtraPath(Path root, String relative) throws IOException {
        Path target = root.resolve(relative).normalize();
        Path plugins = root.resolve("plugins").normalize();
        if (!target.startsWith(plugins) || target.equals(plugins) || relative.contains("..")) {
            throw new IOException("extra data path must stay inside a plugin data folder");
        }
        Path relativeToPlugins = plugins.relativize(target);
        if (relativeToPlugins.getNameCount() < 2) {
            throw new IOException("extra path must target a file/subfolder inside a plugin data folder, not the plugin root");
        }
        String pluginFolder = relativeToPlugins.getName(0).toString().toLowerCase(Locale.ROOT);
        if (Set.of("luckperms", "moonwipe", "essentials", "economicmooneclips", "worldedit").contains(pluginFolder)) {
            throw new IOException("protected plugin data folder cannot be added to extra-data-paths: " + pluginFolder);
        }
        String filename = target.getFileName().toString().toLowerCase(Locale.ROOT);
        if (Set.of("config.yml", "settings.yml", "plugin.yml").contains(filename) || filename.endsWith(".jar")) {
            throw new IOException("configuration and plugin jar files are protected");
        }
        return target;
    }

    private Path resolveSafeBackupDirectory(Path root, String relative) throws IOException {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root.resolve("plugins/MoonWipe")) || relative.contains("..")) {
            throw new IOException("backup folder must be inside plugins/MoonWipe/");
        }
        return target;
    }

    private void deleteRecursively(Path target) throws IOException {
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private String readPrimaryWorldName() {
        Path propertiesFile = serverRoot().resolve("server.properties");
        if (!Files.isRegularFile(propertiesFile)) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(propertiesFile)) {
            properties.load(input);
            return properties.getProperty("level-name", "world").trim();
        } catch (IOException exception) {
            getLogger().warning("Could not read server.properties: " + exception.getMessage());
            return null;
        }
    }

    private void prepareConfirmation(CommandSender sender) {
        confirmationCode = String.format(Locale.ROOT, "%06d", new java.security.SecureRandom().nextInt(1_000_000));
        confirmationExpiresAt = System.currentTimeMillis() + 120_000L;
        send(sender, "&eПодготовка полного вайпа. Будут удалены миры: &f" + String.join(", ", getConfig().getStringList("wipe.world-folders")));
        send(sender, "&eMoney: &f" + getConfig().getBoolean("wipe.reset-money", true)
                + " &e| Аукцион: &f" + getConfig().getBoolean("wipe.reset-auction-database", true));
        send(sender, "&eПроверь настройки и резервную копию. Для подтверждения в течение 2 минут введи:");
        send(sender, "&b/mwipe confirm " + confirmationCode);
        send(sender, "&cКоманда остановит сервер после создания резервной копии.");
    }

    private void sendStatus(CommandSender sender) {
        send(sender, "&7Автоматический вайп: " + (getConfig().getBoolean("schedule.enabled", false) ? "&aвключён" : "&cвыключен"));
        send(sender, "&7Основной мир: &f" + readPrimaryWorldName());
        send(sender, "&7Миры для удаления: &f" + String.join(", ", getConfig().getStringList("wipe.world-folders")));
        send(sender, "&7Сброс Money: &f" + getConfig().getBoolean("wipe.reset-money", true));
        send(sender, "&7Сброс аукциона: &f" + getConfig().getBoolean("wipe.reset-auction-database", true));
        if (nextScheduledWipe != null && getConfig().getBoolean("schedule.enabled", false)) {
            send(sender, "&7Следующий вайп: &f" + nextScheduledWipe);
        }
    }

    private void setSchedule(CommandSender sender, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("off")) {
            getConfig().set("schedule.enabled", false);
            saveConfig();
            loadSchedule();
            send(sender, "&aАвтоматический вайп отключён.");
            return;
        }
        if (args.length != 4) {
            send(sender, "&eИспользование: /mwipe schedule <DAY> <HH:mm> <ZoneId>");
            send(sender, "&eПример: /mwipe schedule SATURDAY 14:00 Europe/Berlin");
            return;
        }
        try {
            DayOfWeek day = DayOfWeek.valueOf(args[1].toUpperCase(Locale.ROOT));
            LocalTime.parse(args[2]);
            ZoneId zone = ZoneId.of(args[3]);
            getConfig().set("schedule.day", day.name());
            getConfig().set("schedule.time", args[2]);
            getConfig().set("timezone", zone.getId());
            getConfig().set("schedule.enabled", true);
            saveConfig();
            loadSchedule();
            send(sender, "&aРасписание включено: " + day + " " + args[2] + " (" + zone + ").");
        } catch (Exception exception) {
            send(sender, "&cНеверный день, время или часовой пояс: " + exception.getMessage());
        }
    }

    private void openMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, MENU_TITLE);
        inventory.setItem(10, item(Material.TNT, "&cПодготовить вайп", "&7Создать код подтверждения", "&7Сделать резервную копию и остановить сервер"));
        inventory.setItem(12, item(Material.CHEST, "&aСоздать резервную копию", "&7Без запуска вайпа"));
        inventory.setItem(14, item(Material.CLOCK, "&bРасписание", "&7" + (getConfig().getBoolean("schedule.enabled", false) ? "Включено" : "Выключено"),
                "&7Настройка: /mwipe schedule <DAY> <HH:mm> <ZoneId>"));
        inventory.setItem(16, item(Material.PAPER, "&eСтатус", "&7Мир: " + readPrimaryWorldName(),
                "&7Миры: " + String.join(", ", getConfig().getStringList("wipe.world-folders"))));
        player.openInventory(inventory);
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(color(name));
        meta.setLore(java.util.Arrays.stream(lore).map(this::color).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!MENU_TITLE.equals(event.getView().getTitle())) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getCurrentItem() == null
                || !event.getCurrentItem().hasItemMeta()) {
            return;
        }
        String name = ChatColor.stripColor(event.getCurrentItem().getItemMeta().getDisplayName());
        switch (name) {
            case "Подготовить вайп" -> prepareConfirmation(player);
            case "Создать резервную копию" -> createBackupOnly(player);
            case "Расписание" -> send(player, "&eНастрой расписание командой /mwipe schedule <DAY> <HH:mm> <ZoneId>.");
            case "Статус" -> sendStatus(player);
            default -> { }
        }
        player.closeInventory();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("moonwipe.admin")) {
            send(sender, "&cНедостаточно прав.");
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                openMenu(player);
            } else {
                sendStatus(sender);
            }
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> prepareConfirmation(sender);
            case "confirm" -> {
                if (args.length != 2) {
                    send(sender, "&eИспользование: /mwipe confirm <код>");
                    return true;
                }
                if (confirmationCode == null || System.currentTimeMillis() > confirmationExpiresAt
                        || !confirmationCode.equals(args[1])) {
                    send(sender, "&cКод неверный или срок подтверждения истёк. Выполни /mwipe start заново.");
                    return true;
                }
                confirmationCode = null;
                beginWipe(sender, false);
            }
            case "cancel" -> {
                confirmationCode = null;
                confirmationExpiresAt = 0L;
                send(sender, "&aПодтверждение отменено. Вайп не запущен.");
            }
            case "backup" -> createBackupOnly(sender);
            case "reload" -> {
                reloadConfig();
                loadSchedule();
                send(sender, "&aКонфигурация MoonWipe перезагружена.");
            }
            case "schedule" -> setSchedule(sender, args);
            case "status" -> sendStatus(sender);
            default -> send(sender, "&eКоманды: /mwipe, start, confirm <код>, cancel, backup, schedule, status, reload");
        }
        return true;
    }

    private String prefix() {
        return getConfig().getString("messages.prefix", "&8[&bMoonWipe&8] ");
    }

    private void send(CommandSender sender, String message) {
        if (sender != null) {
            sender.sendMessage(color(prefix() + message));
        }
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
