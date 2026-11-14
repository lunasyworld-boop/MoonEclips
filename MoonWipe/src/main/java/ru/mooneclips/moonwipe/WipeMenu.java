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

import java.util.Arrays;
import java.util.List;

public final class WipeMenu implements Listener {
    private final MoonWipePlugin plugin;
    private final WipeManager manager;

    public WipeMenu(MoonWipePlugin plugin, WipeManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void openMain(Player player) {
        MainHolder holder = new MainHolder();
        Inventory inventory = Bukkit.createInventory(holder, 27, color("&5Moon&bWipe &8• &fУправление"));
        holder.inventory = inventory;
        fill(inventory);

        inventory.setItem(11, item(Material.TNT, "&cНачать полный вайп",
                "&7Сбросить игровой мир, прогресс,", "&7балансы и аукцион.", "&eПотребуется второе подтверждение."));
        boolean scheduleEnabled = plugin.getConfig().getBoolean("schedule.enabled", false);
        String day = plugin.getConfig().getString("schedule.day", "SATURDAY");
        String time = plugin.getConfig().getString("schedule.time", "14:00");
        inventory.setItem(13, item(Material.CLOCK, "&eАвтоматическое расписание",
                "&7Состояние: " + (scheduleEnabled ? "&aвключено" : "&cвыключено"),
                "&7Текущее: &f" + day + " " + time,
                "&7Часовой пояс: &f" + plugin.getConfig().getString("schedule.timezone", "system"),
                "&eНастройка: /mwipe schedule <день> <HH:mm>",
                "&eВыключить: /mwipe schedule off"));
        inventory.setItem(15, item(Material.BOOK, "&bСостояние MoonWipe",
                "&7Посмотреть экономику, таймер", "&7и расписание: &f/mwipe status"));
        inventory.setItem(22, item(Material.BARRIER, "&cЗакрыть меню", "&7Нажми, чтобы закрыть."));
        player.openInventory(inventory);
    }

    public void openConfirm(Player player) {
        ConfirmHolder holder = new ConfirmHolder();
        Inventory inventory = Bukkit.createInventory(holder, 27, color("&4Подтверждение полного вайпа"));
        holder.inventory = inventory;
        fill(inventory);
        inventory.setItem(11, item(Material.RED_CONCRETE, "&cОтмена",
                "&7Вернуться без запуска вайпа."));
        inventory.setItem(13, item(Material.PAPER, "&eЧто будет сброшено?",
                "&7Миры, инвентари, достижения, статистика,",
                "&7Money и лоты аукциона.",
                "&aПривилегии LuckPerms сохраняются.",
                "&aКонфигурации и JAR-файлы плагинов сохраняются.",
                "&cПеред очисткой создаётся ZIP-копия."));
        inventory.setItem(15, item(Material.LIME_CONCRETE, "&aПОДТВЕРДИТЬ ВАЙП",
                "&7Запустить обратный отсчёт.",
                "&cЭто действие изменит данные сервера."));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof MainHolder) && !(holder instanceof ConfirmHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!player.hasPermission("moonwipe.admin")) {
            player.closeInventory();
            player.sendMessage(color("&cНедостаточно прав."));
            return;
        }

        int slot = event.getRawSlot();
        if (holder instanceof MainHolder) {
            if (slot == 11) {
                manager.armConfirmation();
                openConfirm(player);
            } else if (slot == 13) {
                boolean enabled = plugin.getConfig().getBoolean("schedule.enabled", false);
                player.sendMessage(color("&8[&5Moon&bWipe&8] &7Расписание: "
                        + (enabled ? "&aвключено" : "&cвыключено") + "&7. Для настройки используй &f/mwipe schedule <день> <HH:mm>"));
            } else if (slot == 15) {
                player.closeInventory();
                manager.sendStatus(player);
            } else if (slot == 22) {
                player.closeInventory();
            }
        } else if (holder instanceof ConfirmHolder) {
            if (slot == 11) {
                player.closeInventory();
                manager.cancel(player);
                player.sendMessage(color("&aПодтверждение вайпа отменено."));
            } else if (slot == 15) {
                player.closeInventory();
                manager.confirm(player);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof MainHolder || holder instanceof ConfirmHolder) {
            event.setCancelled(true);
        }
    }

    private void fill(Inventory inventory) {
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, pane);
        }
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

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static final class MainHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class ConfirmHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }
}
