package ru.mooneclips.moonhub;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Random;

/**
 * Winter fantasy hub generator. Uses only vanilla Paper 1.21.4 blocks.
 * A separate world is used so the previous hub is preserved.
 */
public final class HubWorldBuilder {
    private static final String WORLD_NAME = "MoonHubWinter";
    private static final int Y = 100;
    private final MoonHub plugin;

    public HubWorldBuilder(MoonHub plugin) {
        this.plugin = plugin;
    }

    public void build(Player builder) {
        World world = Bukkit.getWorld(WORLD_NAME);
        if (world == null) {
            world = new WorldCreator(WORLD_NAME).environment(World.Environment.NORMAL)
                    .type(WorldType.NORMAL).createWorld();
        }
        if (world == null) {
            builder.sendMessage("§bMoonHub §8» §cНе удалось создать зимний мир. Проверь консоль.");
            return;
        }

        world.getWorldBorder().setCenter(0, 0);
        world.getWorldBorder().setSize(200);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setStorm(false);
        world.setThundering(false);
        world.setTime(18000);

        // Snow-covered terrain and a clean building layer.
        for (int x = -94; x <= 94; x++) {
            for (int z = -94; z <= 94; z++) {
                set(world, x, Y, z, Material.SNOW_BLOCK);
                set(world, x, Y + 1, z, Material.SNOW);
                if (Math.abs(x) > 88 || Math.abs(z) > 88) {
                    set(world, x, Y + 1, z, Material.SPRUCE_LEAVES);
                }
            }
        }

        buildPlaza(world);
        buildCastle(world, 0, Y + 1, -19);
        buildTopHall(world, 0, Y + 1, -58);
        buildCaseHall(world, -54, Y + 1, -4);
        buildFreeCase(world, -54, Y + 1, -39);
        buildMine(world);
        buildArena(world);
        buildFrozenLake(world, 55, Y + 1, 54);
        buildPineForest(world);
        buildDecorations(world);

        // Regions keep their original purpose; mine and arena override safe hub.
        saveRegion("hub_safe", world, -94, Y - 2, -94, 94, Y + 40, 94, "safe");
        saveRegion("hub_mine", world, 38, Y - 2, -17, 76, Y + 16, 17, "mine");
        saveRegion("hub_pvp", world, -23, Y - 2, 35, 23, Y + 25, 81, "pvp");

        savePoint("spawn", world, 0.5, Y + 2, 25.5, 180);
        savePoint("top", world, 0.5, Y + 2, -57.5, 0);
        savePoint("avto", world, 56.5, Y + 2, -1.5, 90);
        savePoint("case", world, -54.5, Y + 2, -3.5, -90);
        savePoint("freecase", world, -54.5, Y + 2, -39.5, -90);
        plugin.saveConfig();

        world.setSpawnLocation(0, Y + 2, 25);
        builder.teleport(new Location(world, 0.5, Y + 2, 25.5, 180, 0));
        builder.sendMessage("§bMoonHub §8» §aЗимний мир создан: §f" + WORLD_NAME);
        builder.sendMessage("§7Старый мир MoonHub сохранён. Точки /spawn, /top, /avto, /case и /freecase перенесены.");
        plugin.getLogger().info("Winter hub generated in " + WORLD_NAME + " by " + builder.getName());
    }

    private void buildPlaza(World w) {
        disk(w, 0, Y + 1, 14, 28, Material.PACKED_ICE);
        disk(w, 0, Y + 2, 14, 22, Material.SNOW_BLOCK);
        ring(w, 0, Y + 2, 14, 24, Material.BLUE_ICE);
        ring(w, 0, Y + 2, 14, 18, Material.QUARTZ_BLOCK);
        disk(w, 0, Y + 2, 14, 8, Material.BLUE_ICE);
        disk(w, 0, Y + 3, 14, 4, Material.SEA_LANTERN);
        for (int a = 0; a < 360; a += 45) {
            double r = Math.toRadians(a);
            int x = (int) Math.round(Math.cos(r) * 34);
            int z = 14 + (int) Math.round(Math.sin(r) * 34);
            for (int n = 0; n < 25; n++) {
                int px = (int) Math.round(x * n / 24.0);
                int pz = 14 + (int) Math.round((z - 14) * n / 24.0);
                set(w, px, Y + 1, pz, Material.SPRUCE_PLANKS);
                if (n % 4 == 0) set(w, px, Y + 2, pz, Material.SEA_LANTERN);
            }
        }
        // Warm central lantern monument.
        pillar(w, 0, Y + 3, 14, 7, Material.QUARTZ_PILLAR, Material.LANTERN);
        disk(w, 0, Y + 10, 14, 3, Material.BLUE_ICE);
        set(w, 0, Y + 11, 14, Material.SEA_LANTERN);
    }

    private void buildCastle(World w, int cx, int base, int cz) {
        // Main keep with a tall, open entrance facing the spawn plaza.
        box(w, cx - 15, base, cz - 11, cx + 15, base + 15, cz + 11, Material.STONE_BRICKS);
        hollow(w, cx - 13, base + 1, cz - 9, cx + 13, base + 14, cz + 9);
        box(w, cx - 11, base + 1, cz - 9, cx + 11, base + 1, cz + 9, Material.SPRUCE_PLANKS);
        for (int x = cx - 3; x <= cx + 3; x++) {
            for (int y = base + 1; y <= base + 8; y++) set(w, x, y, cz + 11, Material.AIR);
        }
        // Interior floor and icy throne dais.
        disk(w, cx, base + 2, cz, 8, Material.POLISHED_DIORITE);
        disk(w, cx, base + 3, cz, 4, Material.BLUE_ICE);
        pillar(w, cx, base + 4, cz - 5, 5, Material.QUARTZ_BLOCK, Material.SEA_LANTERN);
        // Four corner towers.
        tower(w, cx - 15, base, cz - 11, 23);
        tower(w, cx + 15, base, cz - 11, 23);
        tower(w, cx - 15, base, cz + 11, 23);
        tower(w, cx + 15, base, cz + 11, 23);
        // Stepped snowy roof over the central keep.
        for (int layer = 0; layer < 8; layer++) {
            int halfX = 14 - layer * 2;
            int halfZ = 10 - layer;
            int y = base + 15 + layer;
            for (int x = cx - halfX; x <= cx + halfX; x++) {
                set(w, x, y, cz - halfZ, Material.SPRUCE_STAIRS);
                set(w, x, y, cz + halfZ, Material.SPRUCE_STAIRS);
            }
            for (int z = cz - halfZ; z <= cz + halfZ; z++) {
                set(w, cx - halfX, y, z, Material.SPRUCE_STAIRS);
                set(w, cx + halfX, y, z, Material.SPRUCE_STAIRS);
            }
            if (layer < 4) disk(w, cx, y, cz, 4 - layer, Material.SNOW_BLOCK);
        }
        // Frosted windows and warm interior lights.
        for (int x : new int[]{cx - 8, cx - 4, cx + 4, cx + 8}) {
            for (int y = base + 5; y <= base + 10; y++) {
                set(w, x, y, cz + 11, Material.LIGHT_BLUE_STAINED_GLASS);
            }
            set(w, x, base + 4, cz + 10, Material.LANTERN);
        }
        signPillar(w, cx, base + 1, cz + 12);
    }

    private void tower(World w, int x, int base, int z, int height) {
        for (int y = base; y <= base + height; y++) {
            ringAtY(w, x, y, z, 4, Material.STONE_BRICKS);
        }
        disk(w, x, base + 1, z, 3, Material.SPRUCE_PLANKS);
        for (int y = base + 2; y < base + height; y++) {
            set(w, x, y, z, Material.AIR);
        }
        for (int layer = 0; layer < 5; layer++) {
            int r = 4 - layer;
            for (int dx = -r; dx <= r; dx++) {
                set(w, x + dx, base + height + layer, z - r, Material.SPRUCE_STAIRS);
                set(w, x + dx, base + height + layer, z + r, Material.SPRUCE_STAIRS);
            }
            for (int dz = -r; dz <= r; dz++) {
                set(w, x - r, base + height + layer, z + dz, Material.SPRUCE_STAIRS);
                set(w, x + r, base + height + layer, z + dz, Material.SPRUCE_STAIRS);
            }
        }
        pillar(w, x, base + height + 5, z, 3, Material.QUARTZ_PILLAR, Material.LANTERN);
    }

    private void buildTopHall(World w, int cx, int base, int cz) {
        building(w, cx, base, cz, 22, 14, 13);
        tower(w, cx - 10, base, cz - 6, 12);
        tower(w, cx + 10, base, cz - 6, 12);
        for (int x = cx - 8; x <= cx + 8; x += 8) {
            pedestal(w, x, base + 2, cz + 2, Material.GOLD_BLOCK);
        }
        bridge(w, 0, Y + 2, -42, 0, base + 2, cz + 12, Material.PACKED_ICE);
    }

    private void buildCaseHall(World w, int cx, int base, int cz) {
        building(w, cx, base, cz, 18, 12, 11);
        tower(w, cx - 8, base, cz - 5, 10);
        tower(w, cx + 8, base, cz - 5, 10);
        pedestal(w, cx - 5, base + 2, cz, Material.AMETHYST_BLOCK);
        pedestal(w, cx + 5, base + 2, cz, Material.CRYING_OBSIDIAN);
        bridge(w, -30, Y + 2, 14, cx + 17, base + 2, cz, Material.SPRUCE_PLANKS);
    }

    private void buildFreeCase(World w, int cx, int base, int cz) {
        building(w, cx, base, cz, 14, 10, 9);
        pedestal(w, cx, base + 2, cz, Material.GOLD_BLOCK);
        pillar(w, cx, base + 5, cz, 7, Material.QUARTZ_PILLAR, Material.LANTERN);
    }

    private void building(World w, int cx, int base, int cz, int half, int height, int depth) {
        box(w, cx - half, base, cz - depth, cx + half, base + height, cz + depth, Material.SPRUCE_PLANKS);
        hollow(w, cx - half + 1, base + 1, cz - depth + 1, cx + half - 1, base + height - 1, cz + depth - 1);
        for (int x = cx - half + 2; x <= cx + half - 2; x++) {
            set(w, x, base + height, cz - depth, Material.SPRUCE_STAIRS);
            set(w, x, base + height, cz + depth, Material.SPRUCE_STAIRS);
        }
        for (int z = cz - depth + 2; z <= cz + depth - 2; z++) {
            set(w, cx - half, base + height, z, Material.SPRUCE_STAIRS);
            set(w, cx + half, base + height, z, Material.SPRUCE_STAIRS);
        }
        for (int x = cx - 3; x <= cx + 3; x++) {
            for (int y = base + 1; y <= base + 5; y++) set(w, x, y, cz + depth, Material.AIR);
        }
        for (int x : new int[]{cx - half + 3, cx + half - 3}) {
            for (int y = base + 3; y <= base + 6; y++) set(w, x, y, cz + depth, Material.LIGHT_BLUE_STAINED_GLASS);
            set(w, x, base + 2, cz + depth - 1, Material.LANTERN);
        }
        disk(w, cx, base + 1, cz, Math.min(half - 2, 8), Material.POLISHED_DIORITE);
    }

    private void buildMine(World w) {
        box(w, 38, Y + 1, -17, 76, Y + 12, 17, Material.SPRUCE_PLANKS);
        hollow(w, 39, Y + 2, -16, 75, Y + 11, 16);
        box(w, 40, Y + 1, -15, 74, Y + 1, 15, Material.DEEPSLATE_TILES);
        box(w, 40, Y + 11, -15, 74, Y + 11, 15, Material.BLUE_ICE);
        for (int x = 42; x <= 72; x++) {
            for (int z = -13; z <= 13; z++) {
                Material[] ores = {Material.STONE, Material.DEEPSLATE, Material.COAL_ORE, Material.IRON_ORE,
                        Material.COPPER_ORE, Material.GOLD_ORE, Material.REDSTONE_ORE, Material.LAPIS_ORE,
                        Material.DIAMOND_ORE, Material.EMERALD_ORE, Material.DEEPSLATE_DIAMOND_ORE};
                set(w, x, Y + 2, z, ores[(Math.abs(x * 31 + z * 17)) % ores.length]);
            }
        }
        for (int x = 42; x <= 72; x += 6) pillar(w, x, Y + 2, 0, 7, Material.SPRUCE_LOG, Material.LANTERN);
        bridge(w, 30, Y + 2, 14, 38, Y + 2, 0, Material.PACKED_ICE);
    }

    private void buildArena(World w) {
        disk(w, 0, Y + 1, 58, 22, Material.PACKED_ICE);
        disk(w, 0, Y + 2, 58, 18, Material.SNOW_BLOCK);
        ring(w, 0, Y + 3, 58, 20, Material.BLUE_ICE);
        ring(w, 0, Y + 3, 58, 17, Material.SPRUCE_FENCE);
        for (int a = 0; a < 360; a += 45) {
            double r = Math.toRadians(a);
            int x = (int) Math.round(Math.cos(r) * 19);
            int z = 58 + (int) Math.round(Math.sin(r) * 19);
            pillar(w, x, Y + 3, z, 6, Material.STONE_BRICKS, Material.SOUL_LANTERN);
        }
    }

    private void buildFrozenLake(World w, int cx, int base, int cz) {
        disk(w, cx, base, cz, 17, Material.BLUE_ICE);
        disk(w, cx, base + 1, cz, 13, Material.ICE);
        ring(w, cx, base + 1, cz, 18, Material.SNOW_BLOCK);
        for (int i = -8; i <= 8; i += 4) {
            set(w, cx + i, base + 2, cz, Material.SNOW);
            set(w, cx, base + 2, cz + i, Material.SNOW);
        }
    }

    private void buildPineForest(World w) {
        Random random = new Random(2104);
        for (int i = 0; i < 65; i++) {
            int x = random.nextInt(165) - 82;
            int z = random.nextInt(165) - 82;
            if (Math.abs(x) < 32 && Math.abs(z) < 32) continue;
            if (x > 35 && x < 78 && z > -20 && z < 20) continue;
            if (z > 34 && z < 82 && x > -25 && x < 25) continue;
            if (x < -34 && x > -76 && z > -55 && z < 10) continue;
            tree(w, x, Y + 2, z, 5 + random.nextInt(4));
        }
    }

    private void tree(World w, int x, int base, int z, int height) {
        for (int y = 0; y < height; y++) set(w, x, base + y, z, Material.SPRUCE_LOG);
        for (int layer = 0; layer < 4; layer++) {
            int radius = 3 - layer / 2;
            disk(w, x, base + height - 2 + layer, z, radius, Material.SPRUCE_LEAVES);
        }
        set(w, x, base + height + 2, z, Material.SNOW);
    }

    private void buildDecorations(World w) {
        // Snowy lamps along paths.
        for (int z = -45; z <= 45; z += 9) {
            lamp(w, -31, Y + 2, z);
            lamp(w, 31, Y + 2, z);
        }
        for (int x = -45; x <= 45; x += 9) {
            lamp(w, x, Y + 2, -31);
            lamp(w, x, Y + 2, 31);
        }
        // Snow banks and crystal clusters.
        for (int[] p : new int[][]{{-31,-31},{31,-31},{-31,31},{31,31},{-78,0},{78,0}}) {
            disk(w, p[0], Y + 2, p[1], 4, Material.SNOW_BLOCK);
            pillar(w, p[0], Y + 3, p[1], 4, Material.PACKED_ICE, Material.SEA_LANTERN);
            set(w, p[0] + 1, Y + 3, p[1], Material.BLUE_ICE);
        }
    }

    private void signPillar(World w, int x, int y, int z) {
        pillar(w, x, y, z, 6, Material.QUARTZ_PILLAR, Material.SEA_LANTERN);
        for (int dx = -5; dx <= 5; dx++) set(w, x + dx, y + 5, z, Material.BLUE_ICE);
    }

    private void lamp(World w, int x, int y, int z) {
        pillar(w, x, y, z, 4, Material.SPRUCE_FENCE, Material.LANTERN);
        set(w, x, y + 5, z, Material.SNOW);
    }

    private void pedestal(World w, int x, int y, int z, Material material) {
        disk(w, x, y, z, 3, Material.POLISHED_DIORITE);
        set(w, x, y + 1, z, material);
        set(w, x, y + 2, z, Material.SEA_LANTERN);
    }

    private void bridge(World w, int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) Math.max(1, steps);
            int x = (int) Math.round(x1 + (x2 - x1) * t);
            int z = (int) Math.round(z1 + (z2 - z1) * t);
            int y = (int) Math.round(y1 + (y2 - y1) * t);
            for (int dx = -2; dx <= 2; dx++) {
                set(w, x + dx, y, z, material);
                if (Math.abs(dx) == 2) set(w, x + dx, y + 1, z, Material.SPRUCE_FENCE);
            }
            if (i % 5 == 0) set(w, x, y + 1, z, Material.SEA_LANTERN);
        }
    }

    private void towerDecoration(World w, int x, int y, int z) {
        pillar(w, x, y, z, 8, Material.PACKED_ICE, Material.SEA_LANTERN);
    }

    private void disk(World w, int cx, int y, int cz, int radius, Material material) {
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (dx * dx + dz * dz <= r2) set(w, cx + dx, y, cz + dz, material);
        }
    }

    private void ring(World w, int cx, int y, int cz, int radius, Material material) {
        for (int a = 0; a < 360; a += 3) {
            double r = Math.toRadians(a);
            set(w, cx + (int) Math.round(Math.cos(r) * radius), y,
                    cz + (int) Math.round(Math.sin(r) * radius), material);
        }
    }

    private void ringAtY(World w, int cx, int y, int cz, int radius, Material material) {
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (Math.abs(dx) == radius || Math.abs(dz) == radius) set(w, cx + dx, y, cz + dz, material);
        }
    }

    private void box(World w, int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
        for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++)
            set(w, x, y, z, material);
    }

    private void hollow(World w, int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) {
            if (x == x1 || x == x2 || y == y1 || y == y2 || z == z1 || z == z2) continue;
            set(w, x, y, z, Material.AIR);
        }
    }

    private void pillar(World w, int x, int y, int z, int height, Material body, Material cap) {
        for (int dy = 0; dy < height; dy++) set(w, x, y + dy, z, body);
        set(w, x, y + height, z, cap);
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
