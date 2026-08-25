package com.example.wagers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.data.type.Bed;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The four event minigames. Every map builds itself the first time it runs -
 * no schematics, no manual setup. Building happens in small batches so it
 * never spikes TPS.
 *
 *   SKYWARS - floating islands with loot chests, last player standing
 *   SUMO    - knockback sticks on a platform, ring-outs
 *   BEDWARS - solo bed wars: your bed is your extra life
 *   PARKOUR - randomly generated course, first to the finish wins
 */
public class MinigameManager {

    public enum Game {
        NONE("FFA", "Last player standing"),
        SKYWARS("Skywars", "Loot your island, bridge, be the last alive"),
        SUMO("Sumo", "Knock everyone off the platform"),
        BEDWARS("Bed Wars", "Protect your bed - lose it and you're mortal"),
        PARKOUR("Parkour", "First to the finish wins");

        private final String display, description;

        Game(String display, String description) {
            this.display = display;
            this.description = description;
        }

        public String getDisplay() { return display; }
        public String getDescription() { return description; }

        public static Game match(String s) {
            if (s == null) return NONE;
            String clean = s.replace(" ", "").replace("_", "");
            for (Game g : values()) {
                if (g.name().equalsIgnoreCase(clean)
                        || g.display.replace(" ", "").equalsIgnoreCase(clean)) return g;
            }
            return NONE;
        }

        /** Games where falling means elimination rather than a respawn. */
        public boolean fallIsFatal() {
            return this == SKYWARS || this == SUMO;
        }
    }

    /** A built map. */
    public static class Map3D {
        public final World world;
        public final int x, y, z, radius;

        Map3D(World world, int x, int y, int z, int radius) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
        }

        public Location center() {
            return new Location(world, x + 0.5, y + 1, z + 0.5);
        }

        public double lossY() {
            return y - 4.0;
        }
    }

    private final WagersPlugin plugin;
    private final java.util.Map<Game, Map3D> maps = new EnumMap<>(Game.class);
    private final Set<Game> built = new HashSet<>();
    /** Blocks players placed this round, wiped when the round ends. */
    private final List<Block> placedBlocks = new ArrayList<>();
    /** The generated parkour course, start first, finish last. */
    private final List<Location> parkourCourse = new ArrayList<>();

    public MinigameManager(WagersPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        maps.clear();
        built.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("minigames");
        if (root == null) return;

        for (Game game : Game.values()) {
            if (game == Game.NONE) continue;
            ConfigurationSection sec = root.getConfigurationSection(game.name());
            if (sec == null) continue;

            World world = Bukkit.getWorld(sec.getString("world", "world"));
            if (world == null) {
                plugin.getLogger().warning("Minigame " + game.name() + ": world not found, skipping.");
                continue;
            }
            maps.put(game, new Map3D(world,
                    sec.getInt("x", 0),
                    sec.getInt("y", 120),
                    sec.getInt("z", 0),
                    Math.max(6, Math.min(64, sec.getInt("radius", 24)))));
        }
    }

    public Map3D getMap(Game game) {
        return maps.get(game);
    }

    private ConfigurationSection section(Game game) {
        return plugin.getConfig().getConfigurationSection("minigames." + game.name());
    }

    private int cfg(Game game, String key, int def) {
        ConfigurationSection sec = section(game);
        return sec == null ? def : sec.getInt(key, def);
    }

    private Material cfgMat(Game game, String key, Material def) {
        ConfigurationSection sec = section(game);
        if (sec == null) return def;
        Material m = Material.matchMaterial(sec.getString(key, def.name()));
        return (m == null || !m.isBlock()) ? def : m;
    }

    /* ------------------------------------------------------------------ */
    /* Building                                                            */
    /* ------------------------------------------------------------------ */

    public void ensureBuilt(Game game) {
        if (built.contains(game)) return;
        built.add(game);
        rebuild(game);
    }

    /** Force a rebuild - maps get chewed up during a round. */
    public void reset(Game game) {
        built.add(game);
        rebuild(game);
    }

    private void rebuild(Game game) {
        Map3D map = maps.get(game);
        if (map == null) return;
        if (!plugin.getConfig().getBoolean("minigames.auto-build", true)) return;

        switch (game) {
            case SUMO -> buildDisc(map, cfgMat(game, "material", Material.OBSIDIAN));
            case SKYWARS -> buildIslands(map, game, true);
            case BEDWARS -> buildIslands(map, game, false);
            case PARKOUR -> buildParkour(map);
            default -> { }
        }
    }

    /** Queue a batch of block edits that runs a few per tick. */
    private void runBatch(String label, List<Block> clear, List<Block> place, Material material,
                          Runnable onDone) {
        if (clear.isEmpty() && place.isEmpty()) {
            if (onDone != null) onDone.run();
            return;
        }
        final int perTick = Math.max(20, plugin.getConfig().getInt("minigames.blocks-per-tick", 150));
        plugin.getLogger().info("Building " + label + " (" + (clear.size() + place.size()) + " blocks)...");

        new BukkitRunnable() {
            int ci = 0, pi = 0;

            @Override
            public void run() {
                int budget = perTick;
                while (budget > 0 && ci < clear.size()) {
                    clear.get(ci++).setType(Material.AIR, false);
                    budget--;
                }
                while (budget > 0 && pi < place.size()) {
                    place.get(pi++).setType(material, false);
                    budget--;
                }
                if (ci >= clear.size() && pi >= place.size()) {
                    plugin.getLogger().info(label + " ready.");
                    if (onDone != null) onDone.run();
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Flat disc, used by Sumo. */
    private void buildDisc(Map3D map, Material material) {
        List<Block> place = new ArrayList<>(), clear = new ArrayList<>();
        int r = map.radius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                int bx = map.x + dx, bz = map.z + dz;
                place.add(map.world.getBlockAt(bx, map.y, bz));
                for (int dy = 1; dy <= 5; dy++) {
                    Block above = map.world.getBlockAt(bx, map.y + dy, bz);
                    if (above.getType() != Material.AIR) clear.add(above);
                }
            }
        }
        runBatch("Sumo platform", clear, place, material, null);
    }

    /**
     * Ring of floating islands. Skywars gets loot chests, Bed Wars gets a
     * generator block on each island (beds are placed per round).
     */
    private void buildIslands(Map3D map, Game game, boolean chests) {
        int count = Math.max(2, Math.min(16, cfg(game, "island-count", 8)));
        int iRadius = Math.max(2, cfg(game, "island-radius", 4));
        Material floor = cfgMat(game, "island-material",
                game == Game.SKYWARS ? Material.GRASS_BLOCK : Material.SANDSTONE);

        List<Block> place = new ArrayList<>(), clear = new ArrayList<>();
        List<Location> centers = islandSpots(map, count);

        for (Location spot : centers) {
            int cx = spot.getBlockX(), cz = spot.getBlockZ();
            for (int dx = -iRadius; dx <= iRadius; dx++) {
                for (int dz = -iRadius; dz <= iRadius; dz++) {
                    if (dx * dx + dz * dz > iRadius * iRadius) continue;
                    place.add(map.world.getBlockAt(cx + dx, map.y, cz + dz));
                    for (int dy = 1; dy <= 6; dy++) {
                        Block above = map.world.getBlockAt(cx + dx, map.y + dy, cz + dz);
                        if (above.getType() != Material.AIR) clear.add(above);
                    }
                }
            }
        }
        // Middle island - contested ground
        int mid = iRadius + 1;
        for (int dx = -mid; dx <= mid; dx++) {
            for (int dz = -mid; dz <= mid; dz++) {
                if (dx * dx + dz * dz > mid * mid) continue;
                place.add(map.world.getBlockAt(map.x + dx, map.y, map.z + dz));
                for (int dy = 1; dy <= 6; dy++) {
                    Block above = map.world.getBlockAt(map.x + dx, map.y + dy, map.z + dz);
                    if (above.getType() != Material.AIR) clear.add(above);
                }
            }
        }

        final Game g = game;
        final boolean withChests = chests;
        runBatch(game.getDisplay() + " islands", clear, place, floor, () -> {
            for (Location spot : centers) {
                if (withChests) {
                    Block chest = map.world.getBlockAt(spot.getBlockX(), map.y + 1, spot.getBlockZ());
                    chest.setType(Material.CHEST, false);
                    fillChest(chest, g);
                } else {
                    Block gen = map.world.getBlockAt(spot.getBlockX() + 2, map.y, spot.getBlockZ());
                    gen.setType(cfgMat(g, "generator-material", Material.IRON_BLOCK), false);
                }
            }
            if (withChests) {
                Block midChest = map.world.getBlockAt(map.x, map.y + 1, map.z);
                midChest.setType(Material.CHEST, false);
                fillChest(midChest, g);
            }
        });
    }

    /** A randomly generated jump course, start to finish. */
    private void buildParkour(Map3D map) {
        parkourCourse.clear();
        int jumps = Math.max(5, Math.min(60, cfg(Game.PARKOUR, "jumps", 20)));
        Material padMat = cfgMat(Game.PARKOUR, "material", Material.QUARTZ_BLOCK);
        Material finishMat = cfgMat(Game.PARKOUR, "finish-material", Material.GOLD_BLOCK);

        List<Block> place = new ArrayList<>(), clear = new ArrayList<>();
        List<Block> finishBlocks = new ArrayList<>();

        int cx = map.x, cy = map.y, cz = map.z;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                place.add(map.world.getBlockAt(cx + dx, cy, cz + dz));
            }
        }
        parkourCourse.add(new Location(map.world, cx + 0.5, cy + 1, cz + 0.5));

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double angle = rng.nextDouble() * Math.PI * 2;

        for (int i = 0; i < jumps; i++) {
            // Wander outward with a gentle turn so the course doesn't cross itself
            angle += rng.nextDouble(-0.7, 0.7);
            int dist = rng.nextInt(3, 5);            // 3-4 blocks: a normal jump
            int dy = rng.nextInt(-1, 2);             // -1, 0 or +1
            cx += (int) Math.round(Math.cos(angle) * dist);
            cz += (int) Math.round(Math.sin(angle) * dist);
            cy = Math.max(map.y - 10, Math.min(map.y + 25, cy + dy));

            boolean last = (i == jumps - 1);
            if (last) {
                for (int ddx = -1; ddx <= 1; ddx++) {
                    for (int ddz = -1; ddz <= 1; ddz++) {
                        finishBlocks.add(map.world.getBlockAt(cx + ddx, cy, cz + ddz));
                    }
                }
            } else {
                place.add(map.world.getBlockAt(cx, cy, cz));
            }
            for (int dyy = 1; dyy <= 4; dyy++) {
                Block above = map.world.getBlockAt(cx, cy + dyy, cz);
                if (above.getType() != Material.AIR) clear.add(above);
            }
            parkourCourse.add(new Location(map.world, cx + 0.5, cy + 1, cz + 0.5));
        }

        final List<Block> finish = finishBlocks;
        final Material fMat = finishMat;
        runBatch("Parkour course", clear, place, padMat,
                () -> finish.forEach(b -> b.setType(fMat, false)));
    }

    private void fillChest(Block block, Game game) {
        BlockState state = block.getState();
        if (!(state instanceof Chest chest)) return;
        List<ItemStack> loot = new ArrayList<>(lootTable(game));
        Collections.shuffle(loot);
        int picks = Math.min(loot.size(), Math.max(1, cfg(game, "chest-items", 5)));
        for (int i = 0; i < picks; i++) {
            chest.getInventory().addItem(loot.get(i).clone());
        }
        chest.update();
    }

    /** Items from config, written as MATERIAL:amount. */
    public List<ItemStack> itemList(Game game, String key, List<String> fallback) {
        ConfigurationSection sec = section(game);
        List<String> raw = sec == null ? new ArrayList<>() : sec.getStringList(key);
        if (raw.isEmpty()) raw = fallback;

        List<ItemStack> out = new ArrayList<>();
        for (String entry : raw) {
            String[] parts = entry.split(":");
            Material mat = Material.matchMaterial(parts[0].trim());
            if (mat == null) continue;
            int amount = 1;
            if (parts.length > 1) {
                try {
                    amount = Math.max(1, Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException ignored) { }
            }
            out.add(new ItemStack(mat, amount));
        }
        return out;
    }

    public List<ItemStack> lootTable(Game game) {
        return itemList(game, "loot", List.of(
                "STONE_SWORD:1", "IRON_SWORD:1", "LEATHER_CHESTPLATE:1", "IRON_HELMET:1",
                "GOLDEN_APPLE:2", "COOKED_BEEF:8", "OAK_PLANKS:32", "BOW:1", "ARROW:8",
                "ENDER_PEARL:1", "IRON_PICKAXE:1"));
    }

    public List<ItemStack> generatorItems() {
        return itemList(Game.BEDWARS, "items", List.of("WHITE_WOOL:16", "IRON_INGOT:3", "GOLDEN_APPLE:1"));
    }

    public int generatorSeconds() {
        return Math.max(1, cfg(Game.BEDWARS, "generator-seconds", 10));
    }

    public void runGenerator(Location at) {
        if (at == null || at.getWorld() == null) return;
        Location spawnAt = at.clone().add(0, 1, 0);
        for (ItemStack item : generatorItems()) {
            at.getWorld().dropItem(spawnAt, item.clone()).setPickupDelay(10);
        }
        at.getWorld().playSound(spawnAt, org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.6f);
    }

    /* ------------------------------------------------------------------ */
    /* Spawns, beds, course                                                */
    /* ------------------------------------------------------------------ */

    public List<Location> islandSpots(Map3D map, int count) {
        List<Location> out = new ArrayList<>();
        double ring = Math.max(5, map.radius - 4);
        for (int i = 0; i < count; i++) {
            double angle = (2 * Math.PI / Math.max(1, count)) * i;
            out.add(new Location(map.world,
                    map.x + Math.round(Math.cos(angle) * ring),
                    map.y,
                    map.z + Math.round(Math.sin(angle) * ring)));
        }
        return out;
    }

    /** Where players stand at the start of each game. */
    public List<Location> spawns(Game game, Map3D map, int count) {
        List<Location> out = new ArrayList<>();
        Location center = map.center();

        if (game == Game.PARKOUR) {
            Location start = parkourStart(map);
            for (int i = 0; i < count; i++) out.add(start.clone());
            return out;
        }
        if (game == Game.SUMO) {
            double ring = Math.max(2, map.radius - 2);
            for (int i = 0; i < count; i++) {
                double angle = (2 * Math.PI / Math.max(1, count)) * i;
                Location spot = new Location(map.world,
                        map.x + 0.5 + Math.cos(angle) * ring, map.y + 1,
                        map.z + 0.5 + Math.sin(angle) * ring);
                spot.setDirection(center.toVector().subtract(spot.toVector()));
                out.add(spot);
            }
            return out;
        }

        // Skywars / Bed Wars: one island each
        int islands = Math.max(count, Math.min(16, cfg(game, "island-count", 8)));
        List<Location> spots = islandSpots(map, islands);
        for (int i = 0; i < count; i++) {
            Location base = spots.get(i % spots.size());
            Location spawn = new Location(map.world,
                    base.getX() + 0.5, map.y + 1, base.getZ() + 0.5);
            spawn.setDirection(center.toVector().subtract(spawn.toVector()));
            out.add(spawn);
        }
        return out;
    }

    public Location parkourStart(Map3D map) {
        if (!parkourCourse.isEmpty()) return parkourCourse.get(0).clone();
        return map.center();
    }

    public Location parkourFinish(Map3D map) {
        if (!parkourCourse.isEmpty()) return parkourCourse.get(parkourCourse.size() - 1).clone();
        return map.center();
    }

    /**
     * Place a bed beside an island spawn and return its foot block.
     * Beds are two blocks, so both halves get matching block data.
     */
    public Block placeBed(Map3D map, Location islandSpawn) {
        Material bedMat = cfgMat(Game.BEDWARS, "bed-material", Material.RED_BED);
        Block foot = map.world.getBlockAt(
                islandSpawn.getBlockX() - 2, map.y + 1, islandSpawn.getBlockZ());
        Block head = foot.getRelative(BlockFace.EAST);

        foot.setType(bedMat, false);
        head.setType(bedMat, false);

        if (foot.getBlockData() instanceof Bed footData) {
            footData.setPart(Bed.Part.FOOT);
            footData.setFacing(BlockFace.EAST);
            foot.setBlockData(footData, false);
        }
        if (head.getBlockData() instanceof Bed headData) {
            headData.setPart(Bed.Part.HEAD);
            headData.setFacing(BlockFace.EAST);
            head.setBlockData(headData, false);
        }
        return foot;
    }

    /** Remove both halves of a bed. */
    public void destroyBed(Block foot) {
        if (foot == null) return;
        BlockFace[] faces = {BlockFace.SELF, BlockFace.EAST, BlockFace.WEST,
                BlockFace.NORTH, BlockFace.SOUTH};
        for (BlockFace face : faces) {
            Block b = foot.getRelative(face);
            if (b.getBlockData() instanceof Bed) b.setType(Material.AIR, false);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Kits and build rules                                                */
    /* ------------------------------------------------------------------ */

    public void applyKit(Player p, Game game) {
        switch (game) {
            case SUMO -> {
                p.getInventory().clear();
                p.getInventory().setArmorContents(null);
                ItemStack stick = new ItemStack(Material.STICK);
                stick.addUnsafeEnchantment(Enchantment.KNOCKBACK, 2);
                p.getInventory().setItem(0, stick);
            }
            case BEDWARS -> {
                p.getInventory().clear();
                p.getInventory().setArmorContents(null);
                p.getInventory().setItem(0, new ItemStack(Material.WOODEN_SWORD));
                p.getInventory().setItem(1, new ItemStack(Material.WHITE_WOOL, 16));
                p.getInventory().setItem(8, new ItemStack(Material.COOKED_BEEF, 8));
                p.getInventory().setHelmet(new ItemStack(Material.LEATHER_HELMET));
                p.getInventory().setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
            }
            case PARKOUR, SKYWARS -> {
                // Parkour needs empty hands; Skywars gear comes from the chests
                p.getInventory().clear();
                p.getInventory().setArmorContents(null);
            }
            default -> { }
        }
    }

    /** Skywars and Bed Wars let players bridge; Sumo and Parkour don't. */
    public boolean canBuild(Block block, Game game) {
        if (game != Game.SKYWARS && game != Game.BEDWARS) return false;
        Map3D map = maps.get(game);
        if (map == null || !block.getWorld().equals(map.world)) return false;
        if (block.getY() < map.y - 20 || block.getY() > map.y + 40) return false;
        double dx = block.getX() - map.x, dz = block.getZ() - map.z;
        return dx * dx + dz * dz <= (double) map.radius * map.radius;
    }

    public void recordPlaced(Block block) {
        placedBlocks.add(block);
    }

    /** Wipe everything players built this round. */
    public void clearPlaced() {
        for (Block b : placedBlocks) {
            if (b.getType() != Material.AIR && !(b.getBlockData() instanceof Bed)) {
                b.setType(Material.AIR, false);
            }
        }
        placedBlocks.clear();
    }
}
