package ru.mooneclips.moonwipe;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

public final class WipeMenu implements Listener {
    private final MoonWipePlugin plugin;
    private final WipeManager manager;

    public WipeMenu(MoonWipePlugin plugin, WipeManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void openMain(Player player) {
        MainHolder holder = new MainHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, color("&5Moon&bWipe &8• &fУправление"));
        holder.inventory = inv;
        fill(inv);
        inv.setItem(11, item(Material.TNT, "&cНачать полный вайп", "&7Сбросить выбранные данные.", "&eПотребуется подтверждение."));
        inv.setItem(13, item(Material.CLOCK, "&eНастроить дату и время", "&7Следующий вайп: &f" + manager.getScheduleDescription(), "&7Нажми, чтобы открыть календарь."));
        inv.setItem(15, item(Material.CHEST, "&bВыбор данных для сброса", "&7Настроить, какие системы очищать.", "&7Привилегии LuckPerms не затрагиваются."));
        inv.setItem(22, item(Material.BOOK, "&fСостояние MoonWipe", "&7/mwipe status"));
        player.openInventory(inv);
    }

    public void openConfirm(Player player) {
        ConfirmHolder holder = new ConfirmHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, color("&4Подтверждение вайпа"));
        holder.inventory = inv;
        fill(inv);
        inv.setItem(11, item(Material.RED_CONCRETE, "&cОтмена", "&7Вернуться без запуска."));
        inv.setItem(13, item(Material.PAPER, "&eПеред запуском", "&7Будут сброшены только выбранные", "&7модули в настройках MoonWipe.", "&aLuckPerms и привилегии сохраняются.", "&cПеред очисткой создаётся ZIP-копия."));
        inv.setItem(15, item(Material.LIME_CONCRETE, "&aПОДТВЕРДИТЬ", "&7Запустить обратный отсчёт."));
        player.openInventory(inv);
    }

    private void openSchedule(Player player) {
        ScheduleHolder holder = new ScheduleHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, color("&5MoonWipe &8• &fДата вайпа"));
        holder.inventory = inv;
        fill(inv);
        LocalDate date = manager.getSelectedDate();
        LocalTime time = manager.getSelectedTime();
        inv.setItem(10, item(Material.RED_DYE, "&c- 7 дней", "&7Перенести дату на неделю назад."));
        inv.setItem(11, item(Material.RED_DYE, "&c- 1 день", "&7Предыдущий день."));
        inv.setItem(13, item(Material.WRITABLE_BOOK, "&eДата вайпа", "&f" + date.format(DateTimeFormatter.ofPattern("dd.MM.uuuu")), "&7Выбирай дату кнопками по сторонам."));
        inv.setItem(15, item(Material.LIME_DYE, "&a+ 1 день", "&7Следующий день."));
        inv.setItem(16, item(Material.LIME_DYE, "&a+ 7 дней", "&7Перенести дату на неделю вперёд."));
        inv.setItem(19, item(Material.REDSTONE, "&c- 1 час", "&7Уменьшить час."));
        inv.setItem(20, item(Material.REDSTONE, "&c- 5 минут", "&7Уменьшить минуты."));
        inv.setItem(22, item(Material.CLOCK, "&eВремя вайпа", "&f" + time.format(DateTimeFormatter.ofPattern("HH:mm")), "&7Часовой пояс: " + plugin.getConfig().getString("schedule.timezone", "system")));
        inv.setItem(24, item(Material.EMERALD, "&a+ 5 минут", "&7Увеличить минуты."));
        inv.setItem(25, item(Material.EMERALD, "&a+ 1 час", "&7Увеличить час."));
        inv.setItem(38, item(Material.BARRIER, "&cВыключить расписание", "&7Автоматический вайп будет отключён."));
        inv.setItem(42, item(Material.CHEST, "&bВыбрать данные", "&7Открыть настройки очистки."));
        inv.setItem(49, item(Material.LIME_CONCRETE, "&aСОХРАНИТЬ ДАТУ", "&7Назначить одноразовый вайп", "&7на выбранные дату и время."));
        inv.setItem(53, item(Material.ARROW, "&fНазад"));
        player.openInventory(inv);
    }

    private void openModules(Player player) {
        ModulesHolder holder = new ModulesHolder();
        Inventory inv = Bukkit.createInventory(holder, 45, color("&5MoonWipe &8• &fЧто очищать"));
        holder.inventory = inv;
        fill(inv);
        inv.setItem(11, toggleItem(Material.GRASS_BLOCK, "&aИгровые миры и прогресс", "reset-worlds", "&7Миры, инвентари, эндер-сундуки,", "&7достижения и статистика."));
        inv.setItem(13, toggleItem(Material.GOLD_INGOT, "&6Баланс Money", "reset-economy", "&7Личные балансы через Vault", "&7(поддерживаемый Economy-провайдер)."));
        inv.setItem(15, toggleItem(Material.CHEST, "&bАукцион EconomicMoonEclips", "reset-auction", "&7Лоты и почта аукциона.", "&7Требуется проверенная SQLite-схема."));
        inv.setItem(29, item(Material.BOOK, "&fДругие плагины", "&7Их данные пока не очищаются.", "&7Для каждого нужен отдельный безопасный", "&7адаптер под формат его хранения."));
        inv.setItem(40, item(Material.ARROW, "&fНазад"));
        player.openInventory(inv);
    }

    private ItemStack toggleItem(Material material, String title, String key, String... lore) {
        boolean enabled = manager.isResetModuleEnabled(key);
        String[] lines = Arrays.copyOf(lore, lore.length + 1);
        lines[lore.length] = enabled ? "&aВКЛЮЧЕНО — нажми, чтобы выключить" : "&cВЫКЛЮЧЕНО — нажми, чтобы включить";
        return item(material, title, lines);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof MainHolder || holder instanceof ConfirmHolder || holder instanceof ScheduleHolder || holder instanceof ModulesHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!player.hasPermission("moonwipe.admin")) {
            player.closeInventory();
            player.sendMessage(color("&cНедостаточно прав."));
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;

        if (holder instanceof MainHolder) {
            if (slot == 11) { manager.armConfirmation(); openConfirm(player); }
            else if (slot == 13) openSchedule(player);
            else if (slot == 15) openModules(player);
            else if (slot == 22) { player.closeInventory(); manager.sendStatus(player); }
        } else if (holder instanceof ConfirmHolder) {
            if (slot == 11) { player.closeInventory(); manager.cancel(player); }
            else if (slot == 15) { player.closeInventory(); manager.confirm(player); }
        } else if (holder instanceof ScheduleHolder) {
            LocalDate date = manager.getSelectedDate();
            LocalTime time = manager.getSelectedTime();
            switch (slot) {
                case 10 -> changeDate(player, date.minusDays(7), time);
                case 11 -> changeDate(player, date.minusDays(1), time);
                case 15 -> changeDate(player, date.plusDays(1), time);
                case 16 -> changeDate(player, date.plusDays(7), time);
                case 19 -> changeTime(player, date, time.minusHours(1));
                case 20 -> changeTime(player, date, time.minusMinutes(5));
                case 24 -> changeTime(player, date, time.plusMinutes(5));
                case 25 -> changeTime(player, date, time.plusHours(1));
                case 38 -> { manager.disableSchedule(player); openSchedule(player); }
                case 42 -> openModules(player);
                case 49 -> { manager.saveOneTimeSchedule(date, time, player); openSchedule(player); }
                case 53 -> openMain(player);
                default -> { }
            }
        } else if (holder instanceof ModulesHolder) {
            if (slot == 11) manager.toggleResetModule("reset-worlds");
            else if (slot == 13) manager.toggleResetModule("reset-economy");
            else if (slot == 15) manager.toggleResetModule("reset-auction");
            else if (slot == 40) { openMain(player); return; }
            else return;
            openModules(player);
        }
    }

    private void changeDate(Player player, LocalDate date, LocalTime time) {
        plugin.getConfig().set("schedule.date", date.toString());
        plugin.getConfig().set("schedule.time", time.format(DateTimeFormatter.ofPattern("HH:mm")));
        openSchedule(player);
    }

    private void changeTime(Player player, LocalDate date, LocalTime time) {
        plugin.getConfig().set("schedule.date", date.toString());
        plugin.getConfig().set("schedule.time", time.format(DateTimeFormatter.ofPattern("HH:mm")));
        openSchedule(player);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof MainHolder || holder instanceof ConfirmHolder || holder instanceof ScheduleHolder || holder instanceof ModulesHolder) event.setCancelled(true);
    }

    private void fill(Inventory inventory) {
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, pane);
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            meta.setLore(Arrays.stream(lore).map(WipeMenu::color).toList());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }

    private static final class MainHolder implements InventoryHolder { private Inventory inventory; public Inventory getInventory() { return inventory; } }
    private static final class ConfirmHolder implements InventoryHolder { private Inventory inventory; public Inventory getInventory() { return inventory; } }
    private static final class ScheduleHolder implements InventoryHolder { private Inventory inventory; public Inventory getInventory() { return inventory; } }
    private static final class ModulesHolder implements InventoryHolder { private Inventory inventory; public Inventory getInventory() { return inventory; } }
}
