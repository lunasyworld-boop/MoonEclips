package ru.mooneclips.moonmeteor;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class MoonMeteorPlugin extends JavaPlugin implements Listener {
    private static final String COMMON = "common";
    private static final String MOLTEN = "molten";
    private static final String BURNING = "burning";
    private final Random random = new Random();
    private final Map<UUID, Long> liveDrops = new HashMap<>();
    private final List<Location> meteorBlocks = new ArrayList<>();
    private NamespacedKey dropKey;
    private boolean active;
    private Location meteorCenter;
    private int baseY;
    private BukkitTask dropTask;
    private BukkitTask visualTask;
    private BukkitTask endTask;
    private BukkitTask scheduledStartTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dropKey = new NamespacedKey(this, "meteor_drop");
        Bukkit.getPluginManager().registerEvents(this, this);
        cleanupPersistedRuntime();
        active = false;
        getConfig().set("runtime.active", false);
        saveConfig();
        scheduleNextAutomaticStart();
        getLogger().info("MoonMeteor 1.0.0 включён.");
    }

    @Override
    public void onDisable() {
        cancelTasks();
        removeTaggedDropsFromLoadedWorlds();
        removeMeteorBlocks();
        active = false;
        getConfig().set("runtime.active", false);
        getConfig().set("runtime.blocks", new ArrayList<>());
        saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cЭту команду может использовать только игрок.");
            return true;
        }
        if (!player.hasPermission("moonmeteor.admin")) {
            player.sendMessage("§cНедостаточно прав.");
            return true;
        }
        if (args.length == 0) {
            openMainMenu(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "menu" -> openMainMenu(player);
            case "start" -> startEvent(player);
            case "stop" -> stopEvent("§cСобытие метеорита остановлено администратором.");
            case "reload" -> {
                reloadConfig();
                player.sendMessage("§aКонфигурация MoonMeteor перезагружена.");
                scheduleNextAutomaticStart();
            }
            default -> player.sendMessage("§eИспользование: /moonmeteor [menu|start|stop|reload]");
        }
        return true;
    }

    private void startEvent(Player admin) {
        if (active) {
            if (admin != null) admin.sendMessage("§cСобытие уже запущено.");
            return;
        }
        Location anchor = configuredSpawn();
        if (anchor == null || anchor.getWorld() == null) {
            if (admin != null) admin.sendMessage("§cМир для метеорита не найден. Укажи точку спавна в меню.");
            getLogger().warning("Мир для автоматического/ручного спавна метеорита не найден.");
            return;
        }
        Location target = chooseMeteorLocation(anchor);
        if (target == null) {
            if (admin != null) admin.sendMessage("§cНе удалось выбрать место для метеорита.");
            return;
        }
        meteorCenter = target;
        baseY = target.getBlockY();
        buildMeteorite();
        active = true;
        getConfig().set("runtime.active", true);
        saveConfig();
        long duration = Math.max(30, getConfig().getLong("event.duration-seconds", 600));
        long interval = Math.max(1, getConfig().getLong("event.drop-interval-seconds", 3));
        broadcastMeteorAnnouncement(target);
        target.getWorld().playSound(target, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.65f);
        target.getWorld().spawnParticle(Particle.EXPLOSION, target.clone().add(0, 3, 0), 1);
        dropTask = Bukkit.getScheduler().runTaskTimer(this, this::dropLoot, 0L, interval * 20L);
        visualTask = Bukkit.getScheduler().runTaskTimer(this, this::tickDrops, 10L, 10L);
        endTask = Bukkit.getScheduler().runTaskLater(this,
                () -> stopEvent("§6§lМЕТЕОРИТ §8» §eСобытие завершено."), duration * 20L);
        if (admin != null) admin.sendMessage("§aСобытие метеорита запущено.");
    }

    private void broadcastMeteorAnnouncement(Location target) {
        int x = target.getBlockX();
        int y = target.getBlockY();
        int z = target.getBlockZ();

        Bukkit.broadcastMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        Bukkit.broadcastMessage("§6✦ §c§lМЕТЕОРИТНЫЙ ДОЖДЬ §6✦");
        Bukkit.broadcastMessage("§7С неба упал метеорит! В его кратере уже появляется добыча.");
        Bukkit.broadcastMessage("§fКоординаты: §eX: " + x + " §7| §eY: " + y + " §7| §eZ: " + z);
        Bukkit.broadcastMessage("§7Предметы вылетают из кратера каждые §e3 секунды§7.");
        Bukkit.broadcastMessage("§eУспейте добраться до метеорита и забрать редкие ресурсы!");
        Bukkit.broadcastMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        for (Player online : Bukkit.getOnlinePlayers()) {
            online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        }
    }

    private Location configuredSpawn() {
        String worldName = getConfig().getString("spawn.world", "world");
        World world = Bukkit.getWorld(worldName == null ? "world" : worldName);
        if (world == null) return null;
        return new Location(world, getConfig().getDouble("spawn.x", 0.5),
                getConfig().getDouble("spawn.y", 80), getConfig().getDouble("spawn.z", 0.5));
    }

    private Location chooseMeteorLocation(Location anchor) {
        World world = anchor.getWorld();
        if (world == null) return null;
        int x = anchor.getBlockX();
        int z = anchor.getBlockZ();
        if (getConfig().getBoolean("spawn.random", true)) {
            int radius = Math.max(1, getConfig().getInt("spawn.random-radius", 500));
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = Math.sqrt(random.nextDouble()) * radius;
            x += (int) Math.round(Math.cos(angle) * distance);
            z += (int) Math.round(Math.sin(angle) * distance);
        }
        int y = world.getHighestBlockYAt(x, z) + 1;
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    private void buildMeteorite() {
        meteorBlocks.clear();
        World world = meteorCenter.getWorld();
        // Диаметр 13 блоков: максимальный радиус 6.
        int[] radii = {6, 6, 5, 4, 3, 2, 1};
        Material[] palette = {Material.BLACKSTONE, Material.BASALT, Material.MAGMA_BLOCK,
                Material.ANCIENT_DEBRIS, Material.POLISHED_BASALT};

        // Формируем корпус метеорита с центральным отверстием 3x3.
        for (int layer = 0; layer < radii.length; layer++) {
            int radius = radii[layer];
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > radius * radius) continue;
                    if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) continue;
                    Block block = world.getBlockAt(meteorCenter.getBlockX() + dx, baseY + layer,
                            meteorCenter.getBlockZ() + dz);
                    if (!block.getType().isAir()) continue;
                    block.setType(palette[random.nextInt(palette.length)], false);
                    meteorBlocks.add(block.getLocation());
                }
            }
        }

        // Сплошное дно ямы из блоков метеорита на уровне baseY + 1.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block floor = world.getBlockAt(meteorCenter.getBlockX() + dx, baseY + 1,
                        meteorCenter.getBlockZ() + dz);
                if (!floor.getType().isAir()) continue;
                floor.setType(Material.ANCIENT_DEBRIS, false);
                meteorBlocks.add(floor.getLocation());
            }
        }
        persistMeteorBlocks();
    }

    private void dropLoot() {
        if (!active || meteorCenter == null || meteorCenter.getWorld() == null) return;
        String rarity = chooseRarity();
        List<ItemStack> loot = getLoot(rarity);
        if (loot.isEmpty()) return;
        ItemStack stack = loot.get(random.nextInt(loot.size())).clone();
        int min = Math.max(1, getConfig().getInt("random-loot.min-amount", 1));
        int max = Math.max(min, getConfig().getInt("random-loot.max-amount", 3));
        stack.setAmount(min == max ? min : min + random.nextInt(max - min + 1));
        // Предмет появляется внутри ямы и выстреливает вверх, затем разлетается в стороны.
        Location spawn = meteorCenter.clone().add((random.nextDouble() - 0.5) * 0.45, 2.0,
                (random.nextDouble() - 0.5) * 0.45);
        Item item = meteorCenter.getWorld().dropItem(spawn, stack);
        item.setPickupDelay(0);
        item.setVelocity(new Vector((random.nextDouble() - 0.5) * 0.55,
                0.48 + random.nextDouble() * 0.28,
                (random.nextDouble() - 0.5) * 0.55));
        item.getPersistentDataContainer().set(dropKey, PersistentDataType.BYTE, (byte) 1);
        String itemName = stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()
                ? stack.getItemMeta().getDisplayName() : prettify(stack.getType());
        item.setCustomName(rarityName(rarity) + " §8• §f" + itemName);
        item.setCustomNameVisible(true);
        long despawn = Math.max(1, getConfig().getLong("event.item-despawn-seconds", 15));
        liveDrops.put(item.getUniqueId(), System.currentTimeMillis() + despawn * 1000L);
    }

    private void tickDrops() {
        if (!active) return;
        long now = System.currentTimeMillis();
        var iterator = liveDrops.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof Item item) || !item.isValid()) {
                iterator.remove();
                continue;
            }
            if (now >= entry.getValue()) {
                item.remove();
                iterator.remove();
                continue;
            }
            item.getWorld().spawnParticle(Particle.FLAME, item.getLocation().add(0, 0.12, 0),
                    2, 0.08, 0.08, 0.08, 0.005);
        }
    }

    private String chooseRarity() {
        int common = Math.max(0, getConfig().getInt("rarity-chances.common", 70));
        int molten = Math.max(0, getConfig().getInt("rarity-chances.molten", 25));
        int burning = Math.max(0, getConfig().getInt("rarity-chances.burning", 5));
        int total = common + molten + burning;
        if (total <= 0) return COMMON;
        int roll = random.nextInt(total);
        if (roll < common) return COMMON;
        if (roll < common + molten) return MOLTEN;
        return BURNING;
    }

    private List<ItemStack> getLoot(String rarity) {
        List<?> raw = getConfig().getList("loot." + rarity, List.of());
        List<ItemStack> result = new ArrayList<>();
        for (Object value : raw) {
            if (value instanceof ItemStack stack && !stack.getType().isAir()) result.add(stack);
        }
        return result;
    }

    private void stopEvent(String message) {
        if (!active && meteorBlocks.isEmpty() && liveDrops.isEmpty()) return;
        active = false;
        cancelTask(dropTask); dropTask = null;
        cancelTask(visualTask); visualTask = null;
        cancelTask(endTask); endTask = null;
        for (UUID id : new HashSet<>(liveDrops.keySet())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) entity.remove();
        }
        liveDrops.clear();
        removeTaggedDropsFromLoadedWorlds();
        removeMeteorBlocks();
        meteorCenter = null;
        getConfig().set("runtime.active", false);
        getConfig().set("runtime.blocks", new ArrayList<>());
        saveConfig();
        if (message != null && !message.isBlank()) Bukkit.broadcastMessage(message);
    }

    private void cancelTasks() {
        cancelTask(dropTask); cancelTask(visualTask); cancelTask(endTask); cancelTask(scheduledStartTask);
        dropTask = visualTask = endTask = scheduledStartTask = null;
    }

    private void cancelTask(BukkitTask task) {
        if (task != null) task.cancel();
    }

    private void removeTaggedDropsFromLoadedWorlds() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Item item
                        && item.getPersistentDataContainer().has(dropKey, PersistentDataType.BYTE)) {
                    item.remove();
                }
            }
        }
        liveDrops.clear();
    }

    private void removeMeteorBlocks() {
        for (Location location : new ArrayList<>(meteorBlocks)) {
            if (location.getWorld() == null) continue;
            Block block = location.getBlock();
            if (isMeteorMaterial(block.getType())) block.setType(Material.AIR, false);
        }
        meteorBlocks.clear();
    }

    private boolean isMeteorMaterial(Material material) {
        return material == Material.BLACKSTONE || material == Material.BASALT || material == Material.MAGMA_BLOCK
                || material == Material.ANCIENT_DEBRIS || material == Material.POLISHED_BASALT;
    }

    private void persistMeteorBlocks() {
        List<String> locations = new ArrayList<>();
        for (Location loc : meteorBlocks) {
            locations.add(loc.getWorld().getName() + ";" + loc.getBlockX() + ";" + loc.getBlockY() + ";" + loc.getBlockZ());
        }
        getConfig().set("runtime.blocks", locations);
        saveConfig();
    }

    private void cleanupPersistedRuntime() {
        List<String> saved = getConfig().getStringList("runtime.blocks");
        for (String value : saved) {
            String[] parts = value.split(";");
            if (parts.length != 4) continue;
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) continue;
            try {
                Block block = world.getBlockAt(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]));
                if (isMeteorMaterial(block.getType())) block.setType(Material.AIR, false);
            } catch (NumberFormatException ignored) {
            }
        }
        removeTaggedDropsFromLoadedWorlds();
        getConfig().set("runtime.blocks", new ArrayList<>());
    }

    private boolean insideProtectedZone(Location location) {
        if (!active || meteorCenter == null || location.getWorld() == null
                || !location.getWorld().equals(meteorCenter.getWorld())) return false;
        int radius = Math.max(2, getConfig().getInt("event.protection-radius", 8));
        int height = Math.max(3, getConfig().getInt("event.protection-height", 8));
        return Math.abs(location.getBlockX() - meteorCenter.getBlockX()) <= radius
                && Math.abs(location.getBlockZ() - meteorCenter.getBlockZ()) <= radius
                && location.getBlockY() >= baseY - 1 && location.getBlockY() <= baseY + height;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (insideProtectedZone(event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cВо время события территорию метеорита нельзя разрушать.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (insideProtectedZone(event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cВо время события на территории метеорита нельзя строить.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (insideProtectedZone(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (insideProtectedZone(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (insideProtectedZone(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> insideProtectedZone(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> insideProtectedZone(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (insideProtectedZone(event.getBlock().getLocation()) || event.getBlocks().stream()
                .anyMatch(block -> insideProtectedZone(block.getRelative(event.getDirection()).getLocation()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (insideProtectedZone(event.getBlock().getLocation()) || event.getBlocks().stream()
                .anyMatch(block -> insideProtectedZone(block.getRelative(event.getDirection().getOppositeFace()).getLocation()))) {
            event.setCancelled(true);
        }
    }

    private void scheduleNextAutomaticStart() {
        cancelTask(scheduledStartTask);
        scheduledStartTask = null;
        if (!getConfig().getBoolean("schedule.enabled", false)) return;
        LocalTime time;
        try {
            time = LocalTime.parse(getConfig().getString("schedule.time", "18:00"));
        } catch (Exception ex) {
            getLogger().warning("Некорректное schedule.time; используйте HH:mm.");
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        LocalDateTime next = LocalDate.now(ZoneId.systemDefault()).atTime(time);
        if (!next.isAfter(now)) next = next.plusDays(1);
        long ticks = Math.max(1, Duration.between(now, next).toMillis() / 50L);
        scheduledStartTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!active) startEvent(null);
            scheduleNextAutomaticStart();
        }, ticks);
    }

    private static final class MenuHolder implements InventoryHolder {
        private final String type;
        private final String rarity;
        private Inventory inventory;

        private MenuHolder(String type, String rarity) {
            this.type = type;
            this.rarity = rarity;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private Inventory createMenu(Player player, String type, String rarity, int size, String title) {
        MenuHolder holder = new MenuHolder(type, rarity);
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.inventory = inventory;
        player.openInventory(inventory);
        return inventory;
    }

    private ItemStack button(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName("§e" + name);
        if (lore.length > 0) meta.setLore(List.of(lore));
        stack.setItemMeta(meta);
        return stack;
    }

    private void openMainMenu(Player player) {
        Inventory inv = createMenu(player, "main", null, 27, "§8MoonMeteor — управление");
        inv.setItem(10, button(active ? Material.REDSTONE_BLOCK : Material.EMERALD_BLOCK,
                active ? "Остановить событие" : "Запустить событие",
                "§7Состояние: " + (active ? "§aактивно" : "§cостановлено")));
        inv.setItem(12, button(Material.COMPASS, "Установить точку спавна",
                "§7Использовать ваше текущее положение"));
        inv.setItem(14, button(Material.ENDER_PEARL,
                "Случайное место: " + (getConfig().getBoolean("spawn.random", true) ? "§aвключено" : "§cвыключено"),
                "§7Нажмите для переключения"));
        inv.setItem(16, button(Material.REPEATER, "Настройки события",
                "§7Время, интервалы, шансы и радиус"));
        inv.setItem(20, button(Material.IRON_INGOT, "Лут: Обычный", "§7Настроить обычную редкость"));
        inv.setItem(22, button(Material.MAGMA_BLOCK, "Лут: Плавленый", "§7Настроить плавленую редкость"));
        inv.setItem(24, button(Material.BLAZE_POWDER, "Лут: Горящий", "§7Настроить горящую редкость"));
        inv.setItem(26, button(Material.CHEST, "Пул случайной генерации",
                "§7Предметы, из которых генерируется лут"));
    }

    private void openSettingsMenu(Player player) {
        Inventory inv = createMenu(player, "settings", null, 36, "§8MoonMeteor — настройки");
        inv.setItem(10, button(Material.CLOCK, "Длительность: " + getConfig().getInt("event.duration-seconds", 600) + " сек.",
                "§7ЛКМ +30 сек. | ПКМ -30 сек."));
        inv.setItem(11, button(Material.HOPPER, "Интервал выпадения: " + getConfig().getInt("event.drop-interval-seconds", 3) + " сек.",
                "§7ЛКМ +1 сек. | ПКМ -1 сек."));
        inv.setItem(12, button(Material.FIRE_CHARGE, "Исчезновение предмета: " + getConfig().getInt("event.item-despawn-seconds", 15) + " сек.",
                "§7ЛКМ +1 сек. | ПКМ -1 сек."));
        inv.setItem(13, button(Material.BARRIER, "Радиус защиты: " + getConfig().getInt("event.protection-radius", 8),
                "§7ЛКМ +1 | ПКМ -1"));
        inv.setItem(14, button(Material.MAP, "Радиус случайного спавна: " + getConfig().getInt("spawn.random-radius", 500),
                "§7ЛКМ +50 | ПКМ -50"));
        inv.setItem(15, button(Material.SLIME_BALL, "Мин. количество: " + getConfig().getInt("random-loot.min-amount", 1),
                "§7ЛКМ +1 | ПКМ -1"));
        inv.setItem(16, button(Material.MAGMA_CREAM, "Макс. количество: " + getConfig().getInt("random-loot.max-amount", 3),
                "§7ЛКМ +1 | ПКМ -1"));
        inv.setItem(20, button(Material.IRON_INGOT, "Шанс обычного: " + getConfig().getInt("rarity-chances.common", 70) + "%",
                "§7ЛКМ +5 | ПКМ -5"));
        inv.setItem(21, button(Material.GOLD_INGOT, "Шанс плавленого: " + getConfig().getInt("rarity-chances.molten", 25) + "%",
                "§7ЛКМ +5 | ПКМ -5"));
        inv.setItem(22, button(Material.NETHERITE_INGOT, "Шанс горящего: " + getConfig().getInt("rarity-chances.burning", 5) + "%",
                "§7ЛКМ +5 | ПКМ -5"));
        inv.setItem(24, button(Material.DAYLIGHT_DETECTOR,
                "Автозапуск: " + (getConfig().getBoolean("schedule.enabled") ? "§aвключён" : "§cвыключен"),
                "§7Нажмите для переключения"));
        inv.setItem(25, button(Material.CLOCK, "Время автозапуска: " + getConfig().getString("schedule.time", "18:00"),
                "§7ЛКМ +15 мин. | ПКМ -15 мин."));
        inv.setItem(31, button(Material.ARROW, "Назад"));
    }

    private void openLootMenu(Player player, String rarity) {
        Inventory inv = createMenu(player, "loot", rarity, 54, "§8Лут — " + rarityName(rarity));
        List<ItemStack> items = getLoot(rarity);
        for (int i = 0; i < Math.min(45, items.size()); i++) inv.setItem(i, items.get(i).clone());
        inv.setItem(45, button(Material.LIME_DYE, "Добавить предмет из руки",
                "§7Возьмите предмет в основную руку"));
        inv.setItem(49, button(Material.SUNFLOWER, "Сгенерировать 20 случайных предметов",
                "§7Используется пул случайной генерации"));
        inv.setItem(53, button(Material.ARROW, "Назад"));
    }

    private void openPoolMenu(Player player) {
        Inventory inv = createMenu(player, "pool", null, 54, "§8MoonMeteor — пул предметов");
        List<?> pool = getConfig().getList("random-pool", List.of());
        for (int i = 0; i < Math.min(45, pool.size()); i++) {
            Object value = pool.get(i);
            if (value instanceof ItemStack stack) inv.setItem(i, stack.clone());
        }
        inv.setItem(45, button(Material.LIME_DYE, "Добавить предмет из руки", "§7Добавить предмет в пул"));
        inv.setItem(49, button(Material.BARRIER, "Очистить пул",
                "§7Пустой пул = все разрешённые предметы"));
        inv.setItem(53, button(Material.ARROW, "Назад"));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!player.hasPermission("moonmeteor.admin")) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;
        switch (holder.type) {
            case "main" -> {
                switch (slot) {
                    case 10 -> {
                        if (active) stopEvent("§cСобытие метеорита остановлено администратором.");
                        else startEvent(player);
                        openMainMenu(player);
                    }
                    case 12 -> {
                        Location loc = player.getLocation();
                        getConfig().set("spawn.world", loc.getWorld().getName());
                        getConfig().set("spawn.x", loc.getX());
                        getConfig().set("spawn.y", loc.getY());
                        getConfig().set("spawn.z", loc.getZ());
                        saveConfig();
                        player.sendMessage("§aТочка спавна метеорита сохранена.");
                        openMainMenu(player);
                    }
                    case 14 -> {
                        getConfig().set("spawn.random", !getConfig().getBoolean("spawn.random", true));
                        saveConfig();
                        openMainMenu(player);
                    }
                    case 16 -> openSettingsMenu(player);
                    case 20 -> openLootMenu(player, COMMON);
                    case 22 -> openLootMenu(player, MOLTEN);
                    case 24 -> openLootMenu(player, BURNING);
                    case 26 -> openPoolMenu(player);
                    default -> { }
                }
            }
            case "settings" -> handleSettingsClick(player, slot, event.isRightClick());
            case "loot" -> handleLootClick(player, holder.rarity, slot);
            case "pool" -> handlePoolClick(player, slot);
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
    }

    private void handleSettingsClick(Player player, int slot, boolean right) {
        String[] setting = switch (slot) {
            case 10 -> new String[]{"event.duration-seconds", "30"};
            case 11 -> new String[]{"event.drop-interval-seconds", "1"};
            case 12 -> new String[]{"event.item-despawn-seconds", "1"};
            case 13 -> new String[]{"event.protection-radius", "1"};
            case 14 -> new String[]{"spawn.random-radius", "50"};
            case 15 -> new String[]{"random-loot.min-amount", "1"};
            case 16 -> new String[]{"random-loot.max-amount", "1"};
            case 20 -> new String[]{"rarity-chances.common", "5"};
            case 21 -> new String[]{"rarity-chances.molten", "5"};
            case 22 -> new String[]{"rarity-chances.burning", "5"};
            default -> null;
        };
        if (slot == 24) {
            getConfig().set("schedule.enabled", !getConfig().getBoolean("schedule.enabled", false));
            saveConfig();
            scheduleNextAutomaticStart();
            openSettingsMenu(player);
            return;
        }
        if (slot == 25) {
            LocalTime time;
            try {
                time = LocalTime.parse(getConfig().getString("schedule.time", "18:00"));
            } catch (Exception ex) {
                time = LocalTime.of(18, 0);
            }
            time = right ? time.minusMinutes(15) : time.plusMinutes(15);
            getConfig().set("schedule.time", time.toString().substring(0, 5));
            saveConfig();
            scheduleNextAutomaticStart();
            openSettingsMenu(player);
            return;
        }
        if (slot == 31) {
            openMainMenu(player);
            return;
        }
        if (setting == null) return;
        String path = setting[0];
        int step = Integer.parseInt(setting[1]);
        int current = getConfig().getInt(path);
        int minimum = path.equals("event.drop-interval-seconds") || path.equals("event.item-despawn-seconds")
                || path.contains("amount") ? 1 : 0;
        int value = Math.max(minimum, current + (right ? -step : step));
        if (path.startsWith("rarity-chances.")) value = Math.min(100, value);
        if (path.equals("random-loot.min-amount")) {
            value = Math.min(value, getConfig().getInt("random-loot.max-amount", 3));
        }
        if (path.equals("random-loot.max-amount")) {
            value = Math.max(value, getConfig().getInt("random-loot.min-amount", 1));
        }
        getConfig().set(path, value);
        saveConfig();
        openSettingsMenu(player);
    }

    private void handleLootClick(Player player, String rarity, int slot) {
        List<ItemStack> items = getLoot(rarity);
        if (slot == 45) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                player.sendMessage("§cВозьмите предмет в основную руку.");
                return;
            }
            if (items.size() >= 45) {
                player.sendMessage("§cВ списке уже 45 предметов.");
                return;
            }
            ItemStack copy = hand.clone();
            copy.setAmount(1);
            items.add(copy);
            saveLoot(rarity, items);
            openLootMenu(player, rarity);
            return;
        }
        if (slot == 49) {
            List<Material> pool = getRandomPoolMaterials();
            if (pool.isEmpty()) {
                player.sendMessage("§cВ пуле нет разрешённых предметов.");
                return;
            }
            items.clear();
            for (int i = 0; i < 20; i++) items.add(new ItemStack(pool.get(random.nextInt(pool.size())), 1));
            saveLoot(rarity, items);
            openLootMenu(player, rarity);
            return;
        }
        if (slot == 53) {
            openMainMenu(player);
            return;
        }
        if (slot >= 0 && slot < 45 && slot < items.size()) {
            items.remove(slot);
            saveLoot(rarity, items);
            openLootMenu(player, rarity);
        }
    }

    private void saveLoot(String rarity, List<ItemStack> items) {
        getConfig().set("loot." + rarity, items);
        saveConfig();
    }

    private void handlePoolClick(Player player, int slot) {
        List<ItemStack> pool = new ArrayList<>();
        for (Object value : getConfig().getList("random-pool", List.of())) {
            if (value instanceof ItemStack stack) pool.add(stack.clone());
        }
        if (slot == 45) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                player.sendMessage("§cВозьмите предмет в основную руку.");
                return;
            }
            if (pool.size() >= 45) {
                player.sendMessage("§cВ пуле уже 45 предметов.");
                return;
            }
            ItemStack copy = hand.clone();
            copy.setAmount(1);
            pool.add(copy);
            getConfig().set("random-pool", pool);
            saveConfig();
            openPoolMenu(player);
            return;
        }
        if (slot == 49) {
            getConfig().set("random-pool", new ArrayList<>());
            saveConfig();
            openPoolMenu(player);
            return;
        }
        if (slot == 53) {
            openMainMenu(player);
            return;
        }
        if (slot >= 0 && slot < 45 && slot < pool.size()) {
            pool.remove(slot);
            getConfig().set("random-pool", pool);
            saveConfig();
            openPoolMenu(player);
        }
    }

    private List<Material> getRandomPoolMaterials() {
        List<Material> result = new ArrayList<>();
        Collection<?> configured = getConfig().getList("random-pool", List.of());
        if (!configured.isEmpty()) {
            for (Object value : configured) {
                if (value instanceof ItemStack stack && isAllowedRandomItem(stack.getType())) {
                    result.add(stack.getType());
                }
            }
        } else {
            for (Material material : Material.values()) {
                if (isAllowedRandomItem(material)) result.add(material);
            }
        }
        return result;
    }

    private boolean isAllowedRandomItem(Material material) {
        if (material == null || !material.isItem() || material.isAir()) return false;
        String name = material.name();
        return material != Material.BEDROCK && material != Material.BARRIER && material != Material.COMMAND_BLOCK
                && material != Material.CHAIN_COMMAND_BLOCK && material != Material.REPEATING_COMMAND_BLOCK
                && material != Material.STRUCTURE_BLOCK && material != Material.STRUCTURE_VOID
                && material != Material.JIGSAW && material != Material.DEBUG_STICK
                && material != Material.KNOWLEDGE_BOOK && material != Material.SPAWNER
                && material != Material.END_PORTAL_FRAME && material != Material.LIGHT
                && !name.contains("COMMAND_BLOCK") && !name.contains("SPAWN_EGG");
    }

    private String rarityName(String rarity) {
        return switch (rarity) {
            case MOLTEN -> "§6Плавленый";
            case BURNING -> "§cГорящий";
            default -> "§fОбычный";
        };
    }

    private String prettify(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
