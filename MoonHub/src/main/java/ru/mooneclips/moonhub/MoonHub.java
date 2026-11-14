package ru.mooneclips.moonhub;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class MoonHub extends JavaPlugin implements Listener, CommandExecutor {
    private static final String MENU_TITLE = "§5§lMOONECLIPS §8• §dMoonHub";
    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String name : List.of("moonhub", "spawn", "top", "avto", "case", "freecase")) {
            if (getCommand(name) != null) getCommand(name).setExecutor(this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("MoonHub enabled. Paper 1.21.4 / Java 21");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (!cmd.equals("moonhub")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command can only be used by a player.");
                return true;
            }
            teleport(player, cmd);
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("gui")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Use this command in game.");
                return true;
            }
            if (!player.hasPermission("moonhub.admin")) {
                player.sendMessage(msg("Недостаточно прав."));
                return true;
            }
            openMenu(player);
            return true;
        }
        if (!sender.hasPermission("moonhub.admin")) {
            sender.sendMessage(msg("Недостаточно прав."));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "set" -> {
                if (!(sender instanceof Player player) || args.length < 2) {
                    sender.sendMessage(msg("Использование: /moonhub set <spawn|top|avto|case|freecase>"));
                    return true;
                }
                String point = normalizePoint(args[1]);
                if (point == null) {
                    player.sendMessage(msg("Неизвестная точка. Доступно: spawn, top, avto, case, freecase."));
                    return true;
                }
                getConfig().set("points." + point, player.getLocation());
                saveConfig();
                player.sendMessage(msg("Точка " + point + " сохранена."));
            }
            case "pos1", "pos2" -> {
                if (!(sender instanceof Player player)) return true;
                if (args.length < 2) {
                    player.sendMessage(msg("Использование: /moonhub " + args[0] + " <название-региона>"));
                    return true;
                }
                String id = safeId(args[1]);
                (args[0].equalsIgnoreCase("pos1") ? pos1 : pos2).put(player.getUniqueId(), player.getLocation().getBlock().getLocation());
                player.sendMessage(msg((args[0].equalsIgnoreCase("pos1") ? "Первая" : "Вторая") +
                        " точка выделения записана. Название региона: " + id));
            }
            case "region" -> createRegion(sender, args);
            case "reload" -> {
                reloadConfig();
                sender.sendMessage(msg("Настройки перезагружены."));
            }
            case "help" -> sender.sendMessage("§dMoonHub §7— /moonhub gui, /moonhub set <точка>, /moonhub pos1 <регион>, /moonhub pos2 <регион>, /moonhub region <регион> <safe|mine|pvp>, /moonhub reload");
            default -> sender.sendMessage(msg("Неизвестная команда. Используй /moonhub help."));
        }
        return true;
    }

    private void createRegion(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length < 3) {
            sender.sendMessage(msg("Использование: /moonhub region <название> <safe|mine|pvp>"));
            return;
        }
        String id = safeId(args[1]);
        Location a = pos1.get(player.getUniqueId());
        Location b = pos2.get(player.getUniqueId());
        if (id.isBlank() || a == null || b == null || !Objects.equals(a.getWorld(), b.getWorld())) {
            player.sendMessage(msg("Сначала задай две точки в одном мире: /moonhub pos1 " + id + " и /moonhub pos2 " + id));
            return;
        }
        String mode = args[2].toLowerCase(Locale.ROOT);
        if (!Set.of("safe", "mine", "pvp").contains(mode)) {
            player.sendMessage(msg("Режимы: safe, mine, pvp."));
            return;
        }
        String path = "regions." + id;
        getConfig().set(path + ".world", a.getWorld().getName());
        getConfig().set(path + ".min-x", Math.min(a.getBlockX(), b.getBlockX()));
        getConfig().set(path + ".min-y", Math.min(a.getBlockY(), b.getBlockY()));
        getConfig().set(path + ".min-z", Math.min(a.getBlockZ(), b.getBlockZ()));
        getConfig().set(path + ".max-x", Math.max(a.getBlockX(), b.getBlockX()));
        getConfig().set(path + ".max-y", Math.max(a.getBlockY(), b.getBlockY()));
        getConfig().set(path + ".max-z", Math.max(a.getBlockZ(), b.getBlockZ()));
        getConfig().set(path + ".mode", mode);
        saveConfig();
        player.sendMessage(msg("Регион " + id + " создан. Режим: " + mode + "."));
    }

    private void openMenu(Player player) {
        HubMenuHolder holder = new HubMenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 36, Component.text(MENU_TITLE));
        holder.inventory = inv;
        inv.setItem(10, item(Material.RESPAWN_ANCHOR, "Главный спавн", "ЛКМ — сохранить текущую точку", "Команда игроков: /spawn"));
        inv.setItem(12, item(Material.WRITABLE_BOOK, "Таблицы топа", "ЛКМ — сохранить точку топов", "Команда игроков: /top"));
        inv.setItem(14, item(Material.DIAMOND_PICKAXE, "Автошахта", "ЛКМ — сохранить вход в шахту", "Команда игроков: /avto"));
        inv.setItem(16, item(Material.ENDER_CHEST, "Обычные кейсы", "ЛКМ — сохранить точку кейсов", "Команда игроков: /case"));
        inv.setItem(22, item(Material.CHEST, "Бесплатный кейс", "ЛКМ — сохранить точку", "Команда игроков: /freecase"));
        inv.setItem(30, item(Material.SHIELD, "Защита регионов", "Выдели две точки командами:", "/moonhub pos1 <имя>", "/moonhub pos2 <имя>", "Создай регион:", "/moonhub region <имя> <safe|mine|pvp>"));
        inv.setItem(32, item(Material.REDSTONE, "Перезагрузить настройки", "Перечитать config.yml"));
        player.openInventory(inv);
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) lines.add(Component.text(line));
        meta.lore(lines);
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof HubMenuHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission("moonhub.admin")) return;
        int slot = event.getRawSlot();
        String point = switch (slot) {
            case 10 -> "spawn";
            case 12 -> "top";
            case 14 -> "avto";
            case 16 -> "case";
            case 22 -> "freecase";
            default -> null;
        };
        if (point != null) {
            getConfig().set("points." + point, player.getLocation());
            saveConfig();
            player.sendMessage(msg("Точка " + point + " сохранена в config.yml."));
            player.closeInventory();
        } else if (slot == 32) {
            reloadConfig();
            player.sendMessage(msg("Настройки перечитаны."));
            player.closeInventory();
        }
    }

    private void teleport(Player player, String point) {
        Location target = getConfig().getLocation("points." + point);
        if (target == null) {
            player.sendMessage(msg("Точка " + point + " ещё не настроена. Обратись к администрации."));
            return;
        }
        if (target.getWorld() == null) {
            player.sendMessage(msg("Мир для точки " + point + " не загружен."));
            return;
        }
        player.teleportAsync(target).thenAccept(ok -> {
            if (ok) player.sendMessage(msg("Телепортация выполнена."));
        });
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Region region = findRegion(event.getBlock().getLocation());
        if (region == null || player.hasPermission("moonhub.bypass")) return;
        if (!region.mode.equals("mine")) {
            event.setCancelled(true);
            player.sendMessage(msg("Здесь нельзя ломать блоки."));
            return;
        }
        if (getConfig().getBoolean("settings.mine-regeneration.enabled", true)) {
            Material original = event.getBlock().getType();
            Location location = event.getBlock().getLocation();
            long delay = Math.max(1, getConfig().getLong("settings.mine-regeneration.delay-seconds", 8));
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Block block = location.getBlock();
                if (block.getType().isAir()) block.setType(original, false);
            }, delay * 20L);
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        Region region = findRegion(event.getBlock().getLocation());
        if (region != null && !event.getPlayer().hasPermission("moonhub.bypass")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(msg(region.mode.equals("mine")
                    ? "В автошахте нельзя устанавливать блоки."
                    : "Строительство в этой зоне запрещено."));
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Location at = event.getClickedBlock() == null ? event.getPlayer().getLocation() : event.getClickedBlock().getLocation();
        Region region = findRegion(at);
        if (region != null && region.mode.equals("safe") && !event.getPlayer().hasPermission("moonhub.bypass")) {
            if (event.getClickedBlock() != null && event.getClickedBlock().getType().isInteractable()) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        Region victimRegion = findRegion(victim.getLocation());
        Region attackerRegion = findRegion(attacker.getLocation());
        if (victimRegion == null && attackerRegion == null) return;
        boolean allowed = victimRegion != null && attackerRegion != null
                && victimRegion.id.equals(attackerRegion.id) && victimRegion.mode.equals("pvp");
        if (!allowed && !attacker.hasPermission("moonhub.bypass") && !victim.hasPermission("moonhub.bypass")) {
            event.setCancelled(true);
            attacker.sendMessage(msg("PvP разрешено только на арене."));
        }
    }

    @EventHandler
    public void onExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> findRegion(block.getLocation()) != null);
    }

    @EventHandler
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> findRegion(block.getLocation()) != null);
    }

    @EventHandler
    public void onBurn(BlockBurnEvent event) {
        if (findRegion(event.getBlock().getLocation()) != null) event.setCancelled(true);
    }

    private Region findRegion(Location location) {
        if (location.getWorld() == null) return null;
        ConfigurationSection section = getConfig().getConfigurationSection("regions");
        if (section == null) return null;
        for (String id : section.getKeys(false)) {
            String base = "regions." + id;
            if (!location.getWorld().getName().equals(getConfig().getString(base + ".world"))) continue;
            if (location.getBlockX() >= getConfig().getInt(base + ".min-x")
                    && location.getBlockX() <= getConfig().getInt(base + ".max-x")
                    && location.getBlockY() >= getConfig().getInt(base + ".min-y")
                    && location.getBlockY() <= getConfig().getInt(base + ".max-y")
                    && location.getBlockZ() >= getConfig().getInt(base + ".min-z")
                    && location.getBlockZ() <= getConfig().getInt(base + ".max-z")) {
                return new Region(id, getConfig().getString(base + ".mode", "safe"));
            }
        }
        return null;
    }

    private String normalizePoint(String raw) {
        String point = raw.toLowerCase(Locale.ROOT);
        if (point.equals("mine")) point = "avto";
        return Set.of("spawn", "top", "avto", "case", "freecase").contains(point) ? point : null;
    }

    private String safeId(String raw) {
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    }

    private String msg(String text) {
        return "§5MoonHub §8» §d" + text;
    }

    private record Region(String id, String mode) {}

    private static final class HubMenuHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }
}
