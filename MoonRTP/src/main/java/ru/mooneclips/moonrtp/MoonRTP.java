package ru.mooneclips.moonrtp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class MoonRTP extends JavaPlugin {
    private final Random random = new Random();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("MoonRTP 1.21.0b enabled.");
    }

    @Override
    public void onDisable() {
        cooldowns.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Эту команду может использовать только игрок.");
            return true;
        }
        if (!player.hasPermission("moonrtp.use")) {
            player.sendMessage(ChatColor.RED + "У тебя нет прав на использование RTP.");
            return true;
        }

        long now = System.currentTimeMillis();
        long readyAt = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (readyAt > now) {
            long seconds = (readyAt - now + 999L) / 1000L;
            player.sendMessage(ChatColor.YELLOW + "Повтори через " + seconds + " сек.");
            return true;
        }

        World world = Bukkit.getWorld(getConfig().getString("world", "world"));
        if (world == null) {
            player.sendMessage(ChatColor.RED + "Мир для RTP не найден. Сообщи администрации.");
            return true;
        }

        int min = Math.max(1, getConfig().getInt("min-radius", 500));
        int max = Math.max(min, getConfig().getInt("max-radius", 5000));
        int attempts = Math.max(1, Math.min(100, getConfig().getInt("max-attempts", 24)));

        player.sendMessage(ChatColor.GRAY + "Ищем безопасное место...");
        for (int i = 0; i < attempts; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = Math.sqrt(random.nextDouble() * (max * (double) max - min * (double) min)
                    + min * (double) min);
            int x = world.getSpawnLocation().getBlockX() + (int) Math.round(Math.cos(angle) * radius);
            int z = world.getSpawnLocation().getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
            Location target = findSafeLocation(world, x, z);
            if (target == null || !world.getWorldBorder().isInside(target)) {
                continue;
            }

            player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation().add(0, 1, 0),
                    45, 0.45, 0.8, 0.45, 0.15);
            player.playSound(player.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 0.7f, 1.2f);
            if (player.teleport(target)) {
                world.spawnParticle(Particle.REVERSE_PORTAL, target.clone().add(0, 1, 0),
                        65, 0.55, 0.9, 0.55, 0.08);
                player.playSound(target, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.35f);
                cooldowns.put(player.getUniqueId(), now + Math.max(0L,
                        getConfig().getLong("cooldown-seconds", 30)) * 1000L);
                player.sendMessage(ChatColor.GREEN + "Ты телепортирован в безопасное место.");
            }
            return true;
        }

        player.sendMessage(ChatColor.RED + "Не удалось найти безопасное место. Попробуй ещё раз.");
        return true;
    }

    private Location findSafeLocation(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        if (y <= world.getMinHeight() || y + 3 >= world.getMaxHeight()) return null;

        Block floor = world.getBlockAt(x, y, z);
        Block feet = world.getBlockAt(x, y + 1, z);
        Block head = world.getBlockAt(x, y + 2, z);

        if (!floor.getType().isSolid() || floor.isLiquid() || isDangerous(floor.getType())
                || floor.getType().name().endsWith("_LEAVES")) return null;
        if (!feet.isPassable() || !head.isPassable()) return null;
        if (feet.isLiquid() || head.isLiquid()) return null;

        return new Location(world, x + 0.5, y + 1.0, z + 0.5,
                random.nextFloat() * 360.0f, 0.0f);
    }

    private boolean isDangerous(Material material) {
        return switch (material) {
            case LAVA, MAGMA_BLOCK, CAMPFIRE, SOUL_CAMPFIRE, FIRE, SOUL_FIRE,
                    CACTUS, SWEET_BERRY_BUSH, POWDER_SNOW, WITHER_ROSE,
                    POINTED_DRIPSTONE, VOID_AIR -> true;
            default -> false;
        };
    }
}