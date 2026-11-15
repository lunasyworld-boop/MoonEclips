package ru.mooneclips.mooncases;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.Node;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public final class MoonCasesPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private static final String MENU = "§0Кейсы MoonEclips";
    private static final String DONATE = "§0Кейс привилегий";
    private static final String TITUL = "§0Кейс титулов";
    private final Map<String, Location> cases = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> counts = new HashMap<>();
    private final Map<UUID, Set<String>> titles = new HashMap<>();
    private final Map<UUID, String> selectedTitles = new HashMap<>();
    private final Map<String, UUID> opening = new HashMap<>();
    private LuckPerms luckPerms;
    private NamespacedKey caseKey;

    @Override public void onEnable() {
        saveDefaultConfig();
        caseKey = new NamespacedKey(this, "mooncases_type");
        try { luckPerms = LuckPermsProvider.get(); }
        catch (IllegalStateException ex) {
            getLogger().severe("LuckPerms API недоступен. MoonCases отключён.");
            getServer().getPluginManager().disablePlugin(this); return;
        }
        loadData();
        Objects.requireNonNull(getCommand("mcase")).setExecutor(this);
        Objects.requireNonNull(getCommand("titul")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override public void onDisable() { saveData(); }

    private void loadData() {
        ConfigurationSection sec = getConfig().getConfigurationSection("physical-cases");
        if (sec != null) for (String type : sec.getKeys(false)) {
            String world = sec.getString(type + ".world");
            World w = world == null ? null : Bukkit.getWorld(world);
            if (w == null) continue;
            Location loc = new Location(w, sec.getInt(type+".x"), sec.getInt(type+".y"), sec.getInt(type+".z"));
            if (isOurBarrel(loc, type)) cases.put(type, loc);
            else getLogger().warning("Кейс " + type + " не загружен: блок заменён или метка отсутствует.");
        }
        ConfigurationSection players = getConfig().getConfigurationSection("players");
        if (players != null) for (String id : players.getKeys(false)) try {
            UUID uuid = UUID.fromString(id);
            Map<String,Integer> playerCounts = new HashMap<>();
            for (String type : List.of("donate","titul")) {
                int n = players.getInt(id+".cases-"+type, 0);
                if (n > 0) playerCounts.put(type,n);
            }
            // Совместимость со старым beta-форматом: общий баланс переносится в donate-кейсы.
            int legacy = players.getInt(id+".cases", 0);
            if (legacy > 0 && playerCounts.values().stream().mapToInt(Integer::intValue).sum() == 0)
                playerCounts.put("donate", legacy);
            counts.put(uuid, playerCounts);
            titles.put(uuid, new HashSet<>(players.getStringList(id+".titles")));
            String selected = players.getString(id+".selected");
            if (selected != null && !selected.isBlank()) selectedTitles.put(uuid, selected);
        } catch (IllegalArgumentException ignored) { }
    }

    private void saveData() {
        getConfig().set("physical-cases", null);
        getConfig().set("players", null);
        cases.forEach((type,loc) -> {
            String path = "physical-cases."+type+".";
            getConfig().set(path+"world", loc.getWorld().getName());
            getConfig().set(path+"x",loc.getBlockX()); getConfig().set(path+"y",loc.getBlockY()); getConfig().set(path+"z",loc.getBlockZ());
        });
        Set<UUID> ids = new HashSet<>(counts.keySet()); ids.addAll(titles.keySet()); ids.addAll(selectedTitles.keySet());
        for (UUID id : ids) {
            String path = "players."+id;
            Map<String,Integer> map = counts.getOrDefault(id, Map.of());
            getConfig().set(path+".cases-donate", map.getOrDefault("donate",0));
            getConfig().set(path+".cases-titul", map.getOrDefault("titul",0));
            getConfig().set(path+".titles", new ArrayList<>(titles.getOrDefault(id,Set.of())));
            getConfig().set(path+".selected", selectedTitles.get(id));
        }
        saveConfig();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (command.getName().equalsIgnoreCase("titul")) {
            if (sender instanceof Player p) openTitles(p); else sender.sendMessage("Команда доступна игроку.");
            return true;
        }
        if (a.length == 0 || a[0].equalsIgnoreCase("menu")) {
            if (sender instanceof Player p) openCaseMenu(p); else sender.sendMessage("Команда доступна игроку.");
            return true;
        }
        if (!sender.hasPermission("mooncases.admin")) { sender.sendMessage("§cНедостаточно прав."); return true; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "spawncase" -> {
                if (!(sender instanceof Player p) || a.length < 2 || !validType(a[1])) {
                    sender.sendMessage("Использование: /mcase spawncase donate|titul"); return true;
                }
                String type = a[1].toLowerCase(Locale.ROOT);
                Block block = p.getLocation().getBlock();
                if (!block.getType().isAir()) { p.sendMessage("§cНа позиции ног должен быть воздух. Существующий блок не заменён."); return true; }
                Location previous = cases.get(type);
                if (previous != null && isOurBarrel(previous,type)) previous.getBlock().setType(Material.AIR);
                block.setType(Material.BARREL);
                TileState state = (TileState) block.getState();
                state.getPersistentDataContainer().set(caseKey, PersistentDataType.STRING, type);
                state.update(true, false);
                cases.put(type, block.getLocation());
                saveData(); p.sendMessage("§aКейс установлен на свободной позиции.");
            }
            case "delete" -> {
                if (a.length < 2 || !validType(a[1])) { sender.sendMessage("Использование: /mcase delete donate|titul"); return true; }
                String type = a[1].toLowerCase(Locale.ROOT);
                Location loc = cases.remove(type);
                if (loc != null && isOurBarrel(loc,type)) loc.getBlock().setType(Material.AIR);
                saveData();
                sender.sendMessage(loc == null ? "§cКейс не найден." : "§aРегистрация кейса удалена. Чужой или заменённый блок не затронут.");
            }
            case "give", "clear" -> {
                if (a.length < 3 || (a.length > 3 && !validType(a[3]))) {
                    sender.sendMessage("Использование: /mcase "+a[0]+" <ник> <количество> [donate|titul]"); return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(a[1]);
                int amount;
                try { amount = Integer.parseInt(a[2]); } catch (NumberFormatException ex) { amount = -1; }
                if (amount < 1 || amount > 100000 || (!target.hasPlayedBefore() && !target.isOnline())) {
                    sender.sendMessage("§cПроверь ник и количество (1–100000)."); return true;
                }
                String type = a.length == 4 ? a[3].toLowerCase(Locale.ROOT) : "donate";
                Map<String,Integer> map = counts.computeIfAbsent(target.getUniqueId(), k -> new HashMap<>());
                int old = map.getOrDefault(type,0);
                map.put(type, a[0].equalsIgnoreCase("give") ? Math.addExact(old,amount) : Math.max(0,old-amount));
                saveData();
                sender.sendMessage("§aБаланс "+type+"-кейсов игрока "+a[1]+": "+map.get(type));
            }
            default -> sender.sendMessage("§e/mcase menu | spawncase | delete | give | clear");
        }
        return true;
    }

    private boolean validType(String s) { return s.equalsIgnoreCase("donate") || s.equalsIgnoreCase("titul"); }

    private boolean isOurBarrel(Location loc, String type) {
        if (loc.getWorld() == null || loc.getBlock().getType() != Material.BARREL) return false;
        if (!(loc.getBlock().getState() instanceof TileState state)) return false;
        return type.equals(state.getPersistentDataContainer().get(caseKey,PersistentDataType.STRING));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBarrelClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        for (var entry : cases.entrySet()) {
            if (!entry.getValue().getBlock().equals(e.getClickedBlock())) continue;
            if (!isOurBarrel(entry.getValue(),entry.getKey())) {
                e.setCancelled(true);
                e.getPlayer().sendMessage("§cЭтот блок больше не зарегистрирован как кейс.");
                return;
            }
            // Разрешаем взаимодействие только с зарегистрированной бочкой, в том числе в safe-зоне MoonHub.
            e.setCancelled(true);
            openPhysical(e.getPlayer(),entry.getKey());
            return;
        }
    }

    private void openPhysical(Player p,String type) {
        Inventory inv = Bukkit.createInventory(null,27,type.equals("donate")?DONATE:TITUL);
        ConfigurationSection sec = getConfig().getConfigurationSection("rewards."+type);
        if (sec != null) {
            int slot=0;
            for (String key:sec.getKeys(false)) {
                if (slot>=18) break;
                String display=sec.getString(key+".display",key);
                double weight=Math.max(0,sec.getDouble(key+".weight",0));
                double total=0;
                for(String k:sec.getKeys(false)) total+=Math.max(0,sec.getDouble(k+".weight",0));
                double chance=total<=0?0:weight/total*100.0;
                inv.setItem(slot++,item(type.equals("donate")?Material.NETHER_STAR:Material.NAME_TAG,
                    ChatColor.translateAlternateColorCodes('&',display),
                    List.of("§7Шанс: §f"+String.format(Locale.US,"%.2f",chance)+"%")));
            }
        }
        inv.setItem(18,item(Material.RED_STAINED_GLASS_PANE,"§cНе открывать"));
        inv.setItem(26,item(Material.LIME_STAINED_GLASS_PANE,"§aОткрыть"));
        inv.setItem(22,item(Material.PAPER,"§7Твои кейсы: §e"+count(p.getUniqueId(),type)));
        p.openInventory(inv);
    }

    private void openCaseMenu(Player p) {
        Inventory inv=Bukkit.createInventory(null,27,MENU);
        inv.setItem(11,item(Material.BARREL,"§bКейс привилегий",List.of("§7Количество: §e"+count(p.getUniqueId(),"donate"))));
        inv.setItem(15,item(Material.BARREL,"§dКейс титулов",List.of("§7Количество: §e"+count(p.getUniqueId(),"titul"))));
        p.openInventory(inv);
    }

    @EventHandler public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title=e.getView().getTitle();
        if (!Set.of(MENU,DONATE,TITUL,"§0Твои титулы").contains(title)) return;
        e.setCancelled(true);
        if (e.getClickedInventory()==null || e.getClickedInventory()!=e.getView().getTopInventory()) return;
        if (title.equals(MENU)) {
            if (e.getRawSlot()==11) openPhysical(p,"donate");
            else if (e.getRawSlot()==15) openPhysical(p,"titul");
            return;
        }
        if (title.equals("§0Твои титулы")) {
            if (e.getCurrentItem()==null || !e.getCurrentItem().hasItemMeta()) return;
            String chosen=ChatColor.stripColor(e.getCurrentItem().getItemMeta().getDisplayName());
            if (chosen.equalsIgnoreCase("Снять титул")) selectedTitles.remove(p.getUniqueId());
            else if (titles.getOrDefault(p.getUniqueId(),Set.of()).contains(chosen)) selectedTitles.put(p.getUniqueId(),chosen);
            saveData(); p.sendMessage("§aВыбор титула обновлён."); p.closeInventory(); return;
        }
        if (e.getRawSlot()==18) { p.closeInventory(); return; }
        if (e.getRawSlot()!=26) return;
        String type=title.equals(DONATE)?"donate":"titul";
        int available=count(p.getUniqueId(),type);
        if (available<1) { p.sendMessage("§cУ тебя нет кейса этого типа."); return; }
        String lockKey=cases.entrySet().stream().filter(x->x.getKey().equals(type)).map(x->locKey(x.getValue())).findFirst().orElse("virtual-"+type);
        if (opening.containsKey(lockKey)) { p.sendMessage("§cЭтот кейс уже открывает другой игрок."); return; }
        String reward=rollKey(type);
        if (reward==null) { p.sendMessage("§cНаграды не настроены или их суммарный вес равен нулю."); return; }
        opening.put(lockKey,p.getUniqueId());
        p.closeInventory();
        animate(p,type,reward,success -> {
            if (!success) { opening.remove(lockKey); p.sendMessage("§cНе удалось выдать награду. Кейс не списан."); return; }
            setCount(p.getUniqueId(),type,available-1);
            saveData();
            opening.remove(lockKey);
        });
    }

    private String rollKey(String type) {
        ConfigurationSection sec=getConfig().getConfigurationSection("rewards."+type);
        if(sec==null) return null;
        List<String> keys=new ArrayList<>(sec.getKeys(false));
        double total=keys.stream().mapToDouble(k->Math.max(0,sec.getDouble(k+".weight",0))).sum();
        if(keys.isEmpty()||total<=0) return null;
        double roll=ThreadLocalRandom.current().nextDouble(total);
        for(String key:keys) { roll-=Math.max(0,sec.getDouble(key+".weight",0)); if(roll<0) return key; }
        return keys.get(keys.size()-1);
    }

    private void animate(Player p,String type,String reward,Consumer<Boolean> completion) {
        ConfigurationSection sec=getConfig().getConfigurationSection("rewards."+type+"."+reward);
        if(sec==null) { completion.accept(false); return; }
        Material material=type.equals("donate")?Material.NETHER_STAR:Material.NAME_TAG;
        World world=p.getWorld();
        List<ItemDisplay> displays=new ArrayList<>();
        Location center=p.getLocation().add(0,2.0,0);
        Material[] visuals={Material.AMETHYST_SHARD,Material.DIAMOND,Material.EMERALD,Material.GOLD_INGOT,material};
        for(int i=0;i<visuals.length;i++) {
            Entity entity=world.spawnEntity(center.clone().add(Math.cos(i*2*Math.PI/5)*1.4,0.4,Math.sin(i*2*Math.PI/5)*1.4),EntityType.ITEM_DISPLAY);
            if(entity instanceof ItemDisplay display) {
                display.setItemStack(new ItemStack(visuals[i]));
                display.setInvulnerable(true);
                displays.add(display);
            }
        }
        new BukkitRunnable() {
            int step=0;
            @Override public void run() {
                if(!p.isOnline() || !p.getWorld().equals(world)) {
                    displays.forEach(Entity::remove); completion.accept(false); cancel(); return;
                }
                for(ItemDisplay d:displays) if(d.isValid()) d.teleport(d.getLocation().subtract(0,0.12,0));
                if(step<displays.size()-1) {
                    if(displays.get(step).isValid()) displays.get(step).remove();
                    step++; return;
                }
                displays.forEach(Entity::remove);
                giveReward(p,type,reward,completion);
                cancel();
            }
        }.runTaskTimer(this,2L,8L);
    }

    private void giveReward(Player p,String type,String key,Consumer<Boolean> completion) {
        ConfigurationSection sec=getConfig().getConfigurationSection("rewards."+type+"."+key);
        if(sec==null) { completion.accept(false); return; }
        if(type.equals("titul")) {
            String display=ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&',sec.getString("display",key)));
            titles.computeIfAbsent(p.getUniqueId(),k->new HashSet<>()).add(display);
            p.sendMessage("§aТы получил титул: "+display);
            completion.accept(true); return;
        }
        String group=sec.getString("group");
        int days=sec.getInt("days");
        Set<String> managed=new HashSet<>();
        for(String g:getConfig().getStringList("donate-groups")) managed.add(g.toLowerCase(Locale.ROOT));
        if(group==null || !managed.contains(group.toLowerCase(Locale.ROOT)) || days<0 || days>36500) {
            p.sendMessage("§cНеверно настроена группа или срок награды.");
            completion.accept(false); return;
        }
        luckPerms.getUserManager().loadUser(p.getUniqueId()).thenCompose(user -> {
            List<Node> previous=user.getNodes().stream().filter(n->isManagedGroup(n,managed)).toList();
            user.data().clear(n->isManagedGroup(n,managed));
            var builder=Node.builder("group."+group);
            if(days>0) builder.expiry(Duration.ofDays(days));
            user.data().add(builder.build());
            return luckPerms.getUserManager().saveUser(user).handle((ignored,error)->{
                if(error==null) return true;
                user.data().clear(n->isManagedGroup(n,managed));
                previous.forEach(user.data()::add);
                luckPerms.getUserManager().saveUser(user);
                throw new java.util.concurrent.CompletionException(error);
            });
        }).whenComplete((ok,error)->Bukkit.getScheduler().runTask(this,()->{
            if(error!=null || !Boolean.TRUE.equals(ok)) {
                getLogger().severe("Ошибка выдачи группы LuckPerms: "+(error==null?"неизвестная ошибка":error.getMessage()));
                p.sendMessage("§cНе удалось выдать привилегию. Кейс не списан.");
                completion.accept(false);
            } else {
                p.sendMessage("§aПолучена привилегия "+group+(days==0?" навсегда":" на "+days+" дн.")+".");
                completion.accept(true);
            }
        }));
    }

    private boolean isManagedGroup(Node node,Set<String> managed) {
        String key=node.getKey();
        return key.startsWith("group.") && managed.contains(key.substring("group.".length()).toLowerCase(Locale.ROOT));
    }

    private int count(UUID id,String type) { return counts.getOrDefault(id,Map.of()).getOrDefault(type,0); }
    private void setCount(UUID id,String type,int value) {
        Map<String,Integer> map=counts.computeIfAbsent(id,k->new HashMap<>());
        if(value<=0) map.remove(type); else map.put(type,value);
    }
    private String locKey(Location l) { return l.getWorld().getName()+":"+l.getBlockX()+":"+l.getBlockY()+":"+l.getBlockZ(); }

    private void openTitles(Player p) {
        Inventory inv=Bukkit.createInventory(null,54,"§0Твои титулы");
        int slot=0;
        for(String t:titles.getOrDefault(p.getUniqueId(),Set.of())) if(slot<45) inv.setItem(slot++,item(Material.NAME_TAG,t));
        inv.setItem(49,item(Material.BARRIER,"§cСнять титул"));
        p.openInventory(inv);
    }

    private ItemStack item(Material type,String name) { return item(type,name,List.of()); }
    private ItemStack item(Material type,String name,List<String> lore) {
        ItemStack stack=new ItemStack(type); ItemMeta meta=stack.getItemMeta();
        meta.setDisplayName(name); meta.setLore(lore); stack.setItemMeta(meta); return stack;
    }
}
