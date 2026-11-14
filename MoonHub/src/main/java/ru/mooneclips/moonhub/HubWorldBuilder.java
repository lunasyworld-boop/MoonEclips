package ru.mooneclips.moonhub;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Generates the first playable MoonEclips hub in a dedicated normal world.
 * The build is intentionally made from vanilla blocks and needs no schematic plugin.
 */
public final class HubWorldBuilder {
    private final MoonHub plugin;
    private static final int Y = 100;

    public HubWorldBuilder(MoonHub plugin) {
        this.plugin = plugin;
    }

    public void build(Player builder) {
        World world = Bukkit.getWorld("MoonHub");
        if (world == null) {
            world = new WorldCreator("MoonHub").environment(World.Environment.NORMAL)
                    .type(WorldType.NORMAL).createWorld();
        }
        if (world == null) {
            builder.sendMessage("§5MoonHub §8» §cНе удалось создать мир MoonHub. Проверь консоль.");
            return;
        }

        world.getWorldBorder().setCenter(0, 0);
        world.getWorldBorder().setSize(200);
        world.setSpawnLocation(0, Y + 2, 0);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setStorm(false);
        world.setThundering(false);
        world.setTime(18000);

        // Main 200x200 hub platform.
        for (int x = -98; x <= 98; x++) {
            for (int z = -98; z <= 98; z++) {
                Material floor = ((x + z) & 7) == 0 ? Material.POLISHED_BLACKSTONE
                        : (((x - z) & 15) == 0 ? Material.PURPLE_TERRACOTTA : Material.DEEPSLATE_TILES);
                set(world, x, Y, z, floor);
                if (Math.abs(x) > 91 || Math.abs(z) > 91) {
                    set(world, x, Y + 1, z, Material.PURPLE_STAINED_GLASS);
                }
            }
        }

        // Central plaza and radial paths.
        disk(world, 0, Y + 1, 0, 22, Material.POLISHED_DEEPSLATE);
        disk(world, 0, Y + 2, 0, 17, Material.PURPLE_CONCRETE);
        disk(world, 0, Y + 3, 0, 12, Material.POLISHED_BLACKSTONE);
        disk(world, 0, Y + 4, 0, 8, Material.AMETHYST_BLOCK);
        disk(world, 0, Y + 5, 0, 4, Material.CRYING_OBSIDIAN);
        for (int angle = 0; angle < 360; angle += 45) {
            double r = Math.toRadians(angle);
            for (int n = 20; n <= 82; n++) {
                int x = (int) Math.round(Math.cos(r) * n);
                int z = (int) Math.round(Math.sin(r) * n);
                set(world, x, Y + 1, z, Material.PURPLE_CONCRETE);
                set(world, x, Y + 2, z, Material.MAGENTA_GLAZED_TERRACOTTA);
            }
        }

        // Tall eclipse ring, held by four crystal pylons.
        for (int angle = 0; angle < 360; angle += 2) {
            double r = Math.toRadians(angle);
            int x = (int) Math.round(Math.cos(r) * 25);
            int y = Y + 29 + (int) Math.round(Math.sin(r) * 25);
            set(world, x, y, 0, Material.PURPLE_STAINED_GLASS);
            if (angle % 12 == 0) set(world, x, y, 0, Material.AMETHYST_BLOCK);
        }
        for (int[] p : new int[][]{{-25,-25},{25,-25},{-25,25},{25,25}}) {
            pillar(world, p[0], Y + 2, p[1], 18, Material.POLISHED_BLACKSTONE, Material.AMETHYST_BLOCK);
            set(world, p[0], Y + 20, p[1], Material.END_ROD);
        }
        // Floating crystal islands around the plaza.
        island(world, -68, Y + 13, -62);
        island(world, 68, Y + 15, -62);
        island(world, -68, Y + 12, 62);
        island(world, 68, Y + 14, 62);

        // Decorative fountain at the centre.
        disk(world, 0, Y + 6, 0, 5, Material.QUARTZ_BLOCK);
        disk(world, 0, Y + 7, 0, 3, Material.WATER);
        pillar(world, 0, Y + 7, 0, 5, Material.AMETHYST_BLOCK, Material.SEA_LANTERN);
        set(world, 0, Y + 12, 0, Material.WATER);

        // Leaderboard pavilion: three marked pedestals ready for hologram integration.
        pavilion(world, 0, Y + 1, -52);
        pedestal(world, -8, Y + 2, -52, Material.GOLD_BLOCK);
        pedestal(world, 0, Y + 2, -52, Material.DIAMOND_BLOCK);
        pedestal(world, 8, Y + 2, -52, Material.EMERALD_BLOCK);

        // Case area with two physical display plinths.
        pavilion(world, -52, Y + 1, -42);
        pedestal(world, -58, Y + 2, -42, Material.OBSIDIAN);
        pedestal(world, -46, Y + 2, -42, Material.CRYING_OBSIDIAN);
        pillar(world, -58, Y + 3, -42, 5, Material.PURPLE_STAINED_GLASS, Material.AMETHYST_BLOCK);
        pillar(world, -46, Y + 3, -42, 5, Material.MAGENTA_STAINED_GLASS, Material.AMETHYST_BLOCK);

        // Free case monument.
        pavilion(world, -52, Y + 1, -65);
        pedestal(world, -52, Y + 2, -65, Material.GOLD_BLOCK);
        pillar(world, -52, Y + 3, -65, 7, Material.GOLD_BLOCK, Material.LANTERN);

        // Auto-mine: enclosed 29x29 room, filled with mineable blocks.
        mineRoom(world);
        // PvP arena with a visible perimeter.
        arena(world);

        // Clear a doorway in the north wall of the mine.\n        for (int x = 52; x <= 58; x++) for (int y = Y + 2; y <= Y + 4; y++)\n            set(world, x, y, -16, Material.AIR);\n\n        // Floating approach bridges.
        bridge(world, -67, Y + 11, -62, -52, Y + 2, -42);
        bridge(world, 67, Y + 12, 62, 30, Y + 2, 50);

        // Region definitions; mine and pvp are checked before the general safe zone.
        saveRegion("hub_safe", world, -98, Y - 3, -98, 98, Y + 45, 98, "safe");
        saveRegion("hub_mine", world, 40, Y - 2, -16, 70, Y + 12, 16, "mine");
        saveRegion("hub_pvp", world, -55, Y - 2, 35, -15, Y + 25, 75, "pvp");

        savePoint("spawn", world, 0.5, Y + 2, 0.5, 180);
        savePoint("top", world, 0.5, Y + 2, -52.5, 0);
        savePoint("avto", world, 55.5, Y + 2, -13.5, 0);
        savePoint("case", world, -52.5, Y + 2, -34.5, 180);
        savePoint("freecase", world, -52.5, Y + 2, -58.5, 0);
        plugin.saveConfig();

        builder.teleport(new Location(world, 0.5, Y + 2, 0.5, 180, 0));
        builder.sendMessage("§5MoonHub §8» §aМир MoonHub создан! Спавн, автошахта, кейс-зоны, топ-площадка и PvP-арена построены.");
        builder.sendMessage("§7Мир: §dMoonHub §8| §7Размер: §d200×200 §8| §7Автошахта: §d/avto");
        plugin.getLogger().info("MoonHub world build completed by " + builder.getName());
    }

    private void mineRoom(World w) {
        for (int x = 40; x <= 70; x++) for (int z = -16; z <= 16; z++) {
            set(w, x, Y + 1, z, Material.DEEPSLATE_TILES);
            set(w, x, Y + 9, z, Material.PURPLE_STAINED_GLASS);
        }
        for (int y = Y + 1; y <= Y + 9; y++) for (int n = 40; n <= 70; n++) {
            set(w, n, y, -16, Material.POLISHED_BLACKSTONE_BRICKS);
            set(w, n, y, 16, Material.POLISHED_BLACKSTONE_BRICKS);
            set(w, 40, y, n - 54, Material.POLISHED_BLACKSTONE_BRICKS);
            set(w, 70, y, n - 54, Material.POLISHED_BLACKSTONE_BRICKS);
        }
        Material[] ores = {Material.STONE, Material.DEEPSLATE, Material.COAL_ORE, Material.IRON_ORE,
                Material.COPPER_ORE, Material.GOLD_ORE, Material.REDSTONE_ORE, Material.LAPIS_ORE,
                Material.DIAMOND_ORE, Material.EMERALD_ORE, Material.DEEPSLATE_DIAMOND_ORE};
        Random random = new Random(2104);
        for (int x = 42; x <= 68; x++) for (int z = -14; z <= 14; z++) {
            set(w, x, Y + 2, z, ores[random.nextInt(ores.length)]);
        }
        pillar(w, 55, Y + 2, -14, 6, Material.AMETHYST_BLOCK, Material.SEA_LANTERN);
        pillar(w, 55, Y + 2, 14, 6, Material.AMETHYST_BLOCK, Material.SEA_LANTERN);
    }

    private void arena(World w) {
        disk(w, -35, Y + 1, 55, 18, Material.POLISHED_BLACKSTONE);
        disk(w, -35, Y + 2, 55, 15, Material.RED_NETHER_BRICKS);
        for (int a = 0; a < 360; a += 3) {
            double r = Math.toRadians(a);
            int x = -35 + (int) Math.round(Math.cos(r) * 18);
            int z = 55 + (int) Math.round(Math.sin(r) * 18);
            pillar(w, x, Y + 2, z, 4, Material.POLISHED_BLACKSTONE_BRICKS, Material.REDSTONE_LAMP);
        }
        for (int x = -43; x <= -27; x += 8) for (int z = 47; z <= 63; z += 8)
            pillar(w, x, Y + 2, z, 5, Material.NETHER_BRICKS, Material.SOUL_LANTERN);
    }

    private void pavilion(World w, int x, int y, int z) {
        disk(w, x, y, z, 9, Material.PURPLE_TERRACOTTA);
        for (int dx : new int[]{-8, 8}) for (int dz : new int[]{-8, 8})
            pillar(w, x + dx, y + 1, z + dz, 8, Material.POLISHED_BLACKSTONE, Material.AMETHYST_BLOCK);
        for (int dx = -8; dx <= 8; dx++) {
            set(w, x + dx, y + 9, z - 8, Material.PURPLE_STAINED_GLASS);
            set(w, x + dx, y + 9, z + 8, Material.PURPLE_STAINED_GLASS);
        }
    }

    private void pedestal(World w, int x, int y, int z, Material material) {
        disk(w, x, y, z, 3, Material.POLISHED_BLACKSTONE);
        set(w, x, y + 1, z, material);
        set(w, x, y + 2, z, Material.AMETHYST_BLOCK);
        set(w, x, y + 3, z, Material.END_ROD);
    }

    private void island(World w, int x, int y, int z) {
        disk(w, x, y, z, 8, Material.PURPLE_CONCRETE);
        disk(w, x, y - 1, z, 6, Material.CRYING_OBSIDIAN);
        for (int a = 0; a < 360; a += 30) {
            double r = Math.toRadians(a);
            int px = x + (int) Math.round(Math.cos(r) * 6);
            int pz = z + (int) Math.round(Math.sin(r) * 6);
            pillar(w, px, y + 1, pz, 5, Material.AMETHYST_BLOCK, Material.SEA_LANTERN);
        }
    }

    private void bridge(World w, int x1, int y1, int z1, int x2, int y2, int z2) {
        int steps = 35;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int x = (int) Math.round(x1 + (x2 - x1) * t);
            int z = (int) Math.round(z1 + (z2 - z1) * t);
            int y = (int) Math.round(y1 + (y2 - y1) * t);
            for (int width = -2; width <= 2; width++) {
                set(w, x + width, y, z, Material.PURPUR_BLOCK);
                if (Math.abs(width) == 2) set(w, x + width, y + 1, z, Material.PURPLE_STAINED_GLASS);
            }
        }
    }

    private void pillar(World w, int x, int y, int z, int height, Material body, Material cap) {
        for (int dy = 0; dy < height; dy++) set(w, x, y + dy, z, body);
        set(w, x, y + height, z, cap);
    }

    private void disk(World w, int cx, int y, int cz, int radius, Material material) {
        int r2 = radius * radius;
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++)
            if (x * x + z * z <= r2) set(w, cx + x, y, cz + z, material);
    }

    private void set(World w, int x, int y, int z, Material material) {
        Block block = w.getBlockAt(x, y, z);
        block.setType(material, false);
    }

    private void savePoint(String id, World world, double x, double y, double z, float yaw) {
        plugin.getConfig().set("points." + id, new Location(world, x, y, z, yaw, 0));
    }

    private void saveRegion(String id, World world, int minX, int minY, int minZ,
                            int maxX, int maxY, int maxZ, String mode) {
        String path = "regions." + id;
        plugin.getConfig().set(path + ".world", world.getName());
        plugin.getConfig().set(path + ".min-x", minX);
        plugin.getConfig().set(path + ".min-y", minY);
        plugin.getConfig().set(path + ".min-z", minZ);
        plugin.getConfig().set(path + ".max-x", maxX);
        plugin.getConfig().set(path + ".max-y", maxY);
        plugin.getConfig().set(path + ".max-z", maxZ);
        plugin.getConfig().set(path + ".mode", mode);
    }
}
