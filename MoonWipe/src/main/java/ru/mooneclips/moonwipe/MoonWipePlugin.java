package ru.mooneclips.moonwipe;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class MoonWipePlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private WipeManager wipeManager;
    private WipeMenu wipeMenu;

    @Override
    public void onLoad() {
        WipeManager.processPendingWipe(this);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        wipeManager = new WipeManager(this);
        wipeMenu = new WipeMenu(this, wipeManager);
        Bukkit.getPluginManager().registerEvents(wipeManager, this);
        Bukkit.getPluginManager().registerEvents(wipeMenu, this);

        Objects.requireNonNull(getCommand("mwipe"), "mwipe command missing")
                .setExecutor(this);
        Objects.requireNonNull(getCommand("mwipe"), "mwipe command missing")
                .setTabCompleter(this);

        wipeManager.startScheduler();
        getLogger().info("MoonWipe 1.0.0 включён. Ручной и плановый режимы готовы.");
    }

    @Override
    public void onDisable() {
        if (wipeManager != null) {
            wipeManager.shutdown();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("moonwipe.admin")) {
            sender.sendMessage(color("&cНедостаточно прав."));
            return true;
        }

        if (args.length == 0) {
            if (sender instanceof Player player) {
                wipeMenu.openMain(player);
            } else {
                sendHelp(sender);
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu" -> {
                if (sender instanceof Player player) wipeMenu.openMain(player);
                else sendHelp(sender);
            }
            case "start" -> {
                wipeManager.armConfirmation();
                if (sender instanceof Player player) {
                    wipeMenu.openConfirm(player);
                } else {
                    sender.sendMessage(color("&eПодтверждение подготовлено. Выполни &f/mwipe confirm&e."));
                }
            }
            case "confirm" -> wipeManager.confirm(sender);
            case "cancel" -> wipeManager.cancel(sender);
            case "backup" -> wipeManager.createBackup(sender);
            case "status" -> wipeManager.sendStatus(sender);
            case "reload" -> {
                reloadConfig();
                wipeManager.reloadSettings();
                sender.sendMessage(color("&aКонфигурация MoonWipe перезагружена."));
            }
            case "schedule" -> {
                if (args.length == 2 && args[1].equalsIgnoreCase("off")) {
                    wipeManager.disableSchedule(sender);
                } else if (args.length == 3) {
                    wipeManager.setSchedule(sender, args[1], args[2]);
                } else {
                    sender.sendMessage(color("&eИспользование: /mwipe schedule <MONDAY..SUNDAY> <HH:mm>"));
                    sender.sendMessage(color("&eИли: /mwipe schedule off"));
                }
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("menu", "start", "confirm", "cancel", "backup", "schedule", "status", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("schedule")) {
            return filter(List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY", "off"), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) result.add(value);
        }
        return result;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(color("&8&m──────── &5Moon&bWipe &8• &fКоманды &8&m────────"));
        sender.sendMessage(color("&b/mwipe menu &7— открыть меню управления"));
        sender.sendMessage(color("&b/mwipe start &7— подготовить ручной вайп"));
        sender.sendMessage(color("&b/mwipe confirm &7— подтвердить вайп"));
        sender.sendMessage(color("&b/mwipe cancel &7— отменить обратный отсчёт"));
        sender.sendMessage(color("&b/mwipe backup &7— создать резервную копию"));
        sender.sendMessage(color("&b/mwipe schedule <день> <HH:mm> &7— настроить расписание"));
        sender.sendMessage(color("&b/mwipe schedule off &7— выключить расписание"));
        sender.sendMessage(color("&b/mwipe status &7— состояние плагина"));
    }

    static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
