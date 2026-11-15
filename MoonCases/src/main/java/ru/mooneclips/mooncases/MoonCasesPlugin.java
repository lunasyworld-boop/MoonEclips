package ru.mooneclips.mooncases;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Barrel;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class MoonCasesPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private final Map<String, Location> cases = new HashMap<>();
    private final Map<UUID, Integer> counts = new HashMap<>();
    private final Map<UUID, Set<String>> titles = new HashMap<>();
    private final Map<UUID, String> selectedTitles = new HashMap<>();
    private LuckPerms luckPerms;
    private NamespacedKey caseKey;

    @Override public void onEnable() {
        saveDefaultConfig();
        caseKey = new NamespacedKey(this, "case_type");
        try { luckPerms = LuckPermsProvider.get(); }
        catch (IllegalStateException ex) { getLogger().severe("LuckPerms API недоступен. Плагин отключён."); getServer().getPluginManager().disablePlugin(this); return; }
        loadData();
        Objects.requireNonNull(getCommand("mcase")).setExecutor(this);
        Objects.requireNonNull(getCommand("titul")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("MoonCases включён. Сборка beta; игровые сценарии требуют серверного тестирования.");
    }

    @Override public void onDisable() { saveData(); }

    private void loadData() {
        var sec = getConfig().getConfigurationSection("physical-cases");
        if (sec != null) for (String type : sec.getKeys(false)) {
            String world = sec.getString(type + ".world");
            World w = world == null ? null : Bukkit.getWorld(world);
            if (w != null) cases.put(type, new Location(w, sec.getInt(type+".x"), sec.getInt(type+".y"), sec.getInt(type+".z")));
        }
        var players = getConfig().getConfigurationSection("players");
        if (players != null) for (String id : players.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(id);
                counts.put(uuid, players.getInt(id+".cases", 0));
                titles.put(uuid, new HashSet<>(players.getStringList(id+".titles")));
                String selected = players.getString(id+".selected");
                if (selected != null && !selected.isBlank()) selectedTitles.put(uuid, selected);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void saveData() {
        for (String key : List.of("physical-cases", "players")) getConfig().set(key, null);
        cases.forEach((type, loc) -> {
            String p = "physical-cases."+type+".";
            getConfig().set(p+"world", loc.getWorld().getName()); getConfig().set(p+"x", loc.getBlockX());
            getConfig().set(p+"y", loc.getBlockY()); getConfig().set(p+"z", loc.getBlockZ());
        });
        Set<UUID> ids = new HashSet<>(counts.keySet()); ids.addAll(titles.keySet()); ids.addAll(selectedTitles.keySet());
        for (UUID id : ids) {
            String p = "players."+id;
            getConfig().set(p+".cases", counts.getOrDefault(id, 0));
            getConfig().set(p+".titles", new ArrayList<>(titles.getOrDefault(id, Set.of())));
            getConfig().set(p+".selected", selectedTitles.get(id));
        }
        saveConfig();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (command.getName().equalsIgnoreCase("titul")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Команда доступна игроку."); return true; }
            openTitles(p); return true;
        }
        if (a.length == 0 || a[0].equalsIgnoreCase("menu")) {
            if (sender instanceof Player p) openCaseMenu(p); else sender.sendMessage("Команда доступна игроку.");
            return true;
        }
        if (!sender.hasPermission("mooncases.admin")) { sender.sendMessage("§cНедостаточно прав."); return true; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "spawncase" -> {
                if (!(sender instanceof Player p) || a.length < 2 || !validType(a[1])) { sender.sendMessage("Использование: /mcase spawncase donate|titul"); return true; }
                Block b = p.getLocation().getBlock();
                b.setType(Material.BARREL);
                cases.put(a[1].toLowerCase(Locale.ROOT), b.getLocation());
                saveData(); p.sendMessage("§aФизический кейс установлен.");
            }
            case "delete" -> {
                if (a.length < 2 || !validType(a[1])) { sender.sendMessage("Использование: /mcase delete donate|titul"); return true; }
                Location loc = cases.remove(a[1].toLowerCase(Locale.ROOT));
                if (loc != null && loc.getBlock().getType() == Material.BARREL) loc.getBlock().setType(Material.AIR);
                saveData(); sender.sendMessage(loc == null ? "§cКейс не найден." : "§aКейс удалён.");
            }
            case "give", "clear" -> {
                if (a.length < 3) { sender.sendMessage("Использование: /mcase "+a[0]+" ник количество"); return true; }
                Player target = Bukkit.getPlayerExact(a[1]);
                int amount; try { amount = Integer.parseInt(a[2]); } catch (NumberFormatException e) { amount = -1; }
                if (target == null || amount < 1 || amount > 100000) { sender.sendMessage("§cИгрок должен быть онлайн, количество 1–100000."); return true; }
                int old = counts.getOrDefault(target.getUniqueId(), 0);
                counts.put(target.getUniqueId(), a[0].equalsIgnoreCase("give") ? old + amount : Math.max(0, old - amount));
                saveData(); sender.sendMessage("§aКоличество кейсов обновлено для "+target.getName()+".");
            }
            default -> sender.sendMessage("§e/mcase menu | spawncase | delete | give | clear");
        }
        return true;
    }

    private boolean validType(String s) { return s.equalsIgnoreCase("donate") || s.equalsIgnoreCase("titul"); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBarrelClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        for (var entry : cases.entrySet()) if (entry.getValue().getBlock().equals(e.getClickedBlock())) {
            e.setCancelled(true);
            openPhysical(e.getPlayer(), entry.getKey());
            return;
        }
    }

    private void openPhysical(Player p, String type) {
        Inventory inv = Bukkit.createInventory(null, 27, type.equals("donate") ? "§0Кейс привилегий" : "§0Кейс титулов");
        fillRewards(inv, type);
        inv.setItem(18, item(Material.RED_STAINED_GLASS_PANE, "§cНе открывать"));
        inv.setItem(26, item(Material.LIME_STAINED_GLASS_PANE, "§aОткрыть"));
        inv.setItem(22, item(Material.BARREL, "§eПросмотр наград"));
        p.openInventory(inv);
        p.setMetadata("mooncases_open_type", new org.bukkit.metadata.FixedMetadataValue(this, type));
    }

    private void openCaseMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, "§0Кейсы MoonEclips");
        inv.setItem(11, item(Material.BARREL, "§bКейс привилегий"));
        inv.setItem(15, item(Material.BARREL, "§dКейс титулов"));
        inv.setItem(22, item(Material.PAPER, "§fУ тебя кейсов: §e"+counts.getOrDefault(p.getUniqueId(),0)));
        p.openInventory(inv);
    }

    private void fillRewards(Inventory inv, String type) {
        ConfigurationSection sec = getConfig().getConfigurationSection("rewards."+type);
        if (sec == null) return;
        int slot = 0;
        for (String key : sec.getKeys(false)) {
            if (slot >= 18) break;
            String display = sec.getString(key+".display", key);
            inv.setItem(slot++, item(type.equals("donate") ? Material.NETHER_STAR : Material.NAME_TAG, ChatColor.translateAlternateColorCodes('&', display)));
        }
    }

    @EventHandler public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        if (!title.equals("§0Кейсы MoonEclips") && !title.equals("§0Кейс привилегий") && !title.equals("§0Кейс титулов") && !title.equals("§0Твои титулы")) return;
        e.setCancelled(true);
        if (e.getCurrentItem() == null || !e.getCurrentItem().hasItemMeta()) return;
        String name = e.getCurrentItem().getItemMeta().getDisplayName();
        if (title.equals("§0Кейсы MoonEclips")) {
            if (e.getRawSlot() == 11) openPhysical(p, "donate");
            if (e.getRawSlot() == 15) openPhysical(p, "titul");
        } else if (title.equals("§0Твои титулы")) {
            String chosen = ChatColor.stripColor(name);
            if (chosen.equalsIgnoreCase("Снять титул")) selectedTitles.remove(p.getUniqueId());
            else if (titles.getOrDefault(p.getUniqueId(), Set.of()).contains(chosen)) selectedTitles.put(p.getUniqueId(), chosen);
            saveData(); p.sendMessage("§aВыбор титула обновлён."); p.closeInventory();
        } else if (e.getRawSlot() == 18) p.closeInventory();
        else if (e.getRawSlot() == 26) {
            String type = title.contains("привилегий") ? "donate" : "titul";
            UUID id = p.getUniqueId();
            if (counts.getOrDefault(id, 0) < 1) { p.sendMessage("§cУ тебя нет такого кейса."); return; }
            counts.put(id, counts.get(id)-1);
            rollReward(p, type); saveData(); p.closeInventory();
        }
    }

    private void rollReward(Player p, String type) {
        ConfigurationSection sec = getConfig().getConfigurationSection("rewards."+type);
        if (sec == null || sec.getKeys(false).isEmpty()) { p.sendMessage("§cНаграды не настроены."); return; }
        List<String> keys = new ArrayList<>(sec.getKeys(false));
        double total = keys.stream().mapToDouble(k -> Math.max(0, sec.getDouble(k+".weight", 0))).sum();
        if (total <= 0) { p.sendMessage("§cСумма весов наград равна нулю."); return; }
        double roll = ThreadLocalRandom.current().nextDouble(total);
        String win = keys.get(keys.size()-1);
        for (String key : keys) { roll -= Math.max(0, sec.getDouble(key+".weight",0)); if (roll < 0) { win = key; break; } }
        if (type.equals("donate")) grantRank(p, sec.getString(win+".group"), sec.getInt(win+".days"));
        else {
            String display = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', sec.getString(win+".display", win)));
            titles.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>()).add(display);
            p.sendMessage("§aТы получил титул: "+sec.getString(win+".display", win));
        }
    }

    private void grantRank(Player p, String group, int days) {
        if (group == null || !getConfig().getStringList("donate-groups").contains(group)) {
            p.sendMessage("§cГруппа награды не входит в список управляемых групп. Обратись к администратору."); return;
        }
        luckPerms.getUserManager().loadUser(p.getUniqueId()).thenAccept(user -> {
            Set<String> managed = new HashSet<>(getConfig().getStringList("donate-groups"));
            user.data().clear(node -> node.getKey().startsWith("group.") && managed.contains(node.getKey().substring(6)));
            var builder = Node.builder("group."+group);
            if (days > 0) builder.expiry(Duration.ofDays(days));
            user.data().add(builder.build());
            luckPerms.getUserManager().saveUser(user).thenRun(() -> p.getScheduler().run(this, task -> p.sendMessage("§aПолучена привилегия "+group+"."), null));
        }).exceptionally(ex -> { getLogger().severe("Не удалось выдать группу: "+ex.getMessage()); return null; });
    }

    private void openTitles(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, "§0Твои титулы");
        int slot = 0;
        for (String t : titles.getOrDefault(p.getUniqueId(), Set.of())) {
            if (slot >= 45) break;
            inv.setItem(slot++, item(Material.NAME_TAG, t));
        }
        inv.setItem(49, item(Material.BARRIER, "§cСнять титул"));
        p.openInventory(inv);
    }

    private ItemStack item(Material type, String name) {
        ItemStack item = new ItemStack(type); ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name); item.setItemMeta(meta); return item;
    }
}