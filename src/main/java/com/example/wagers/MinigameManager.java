package com.example.wagers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minigames that events can run instead of a plain FFA fight. Each one builds
 * its own arena automatically the first time it's used - no manual setup, no
 * schematics. Arenas are built in small batches so building never spikes TPS.
 */
public class MinigameManager {

    public enum Game {
        NONE("FFA", "Last player standing"),
        SPLEEF("Spleef", "Break the floor, don't fall"),
        KOTH("King of the Hill", "Hold the centre to win");

        private final String display, description;

        Game(String display, String description) {
            this.display = display;
            this.description = description;
        }

        public String getDisplay() { return display; }
        public String getDescription() { return description; }

        public static Game match(String s) {
            if (s == null) return NONE;
            for (Game g : values()) {
                if (g.name().equalsIgnoreCase(s) || g.display.equalsIgnoreCase(s)) return g;
            }
            return NONE;
        }
    }

    /** A built minigame map. */
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

        /** Fall below this and you're eliminated. */
        public double lossY() {
            return y - 4.0;
        }

        public boolean insideHill(Location loc, int hillRadius) {
            if (loc.getWorld() == null || !loc.getWorld().equals(world)) return false;
            double dx = loc.getX() - (x + 0.5);
            double dz = loc.getZ() - (z + 0.5);
            return dx * dx + dz * dz <= hillRadius * hillRadius;
        }
    }

    private final WagersPlugin plugin;
    private final java.util.Map<Game, Map3D> maps = new EnumMap<>(Game.class);
    private final Set<Game> built = new HashSet<>();

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
                    sec.getInt("y", 100),
                    sec.getInt("z", 0),
                    Math.max(5, Math.min(48, sec.getInt("radius", 12)))));
        }
    }

    public Map3D getMap(Game game) {
        return maps.get(game);
    }

    public int hillRadius() {
        return Math.max(1, plugin.getConfig().getInt("minigames.KOTH.hill-radius", 3));
    }

    public int kothCaptureSeconds() {
        return Math.max(3, plugin.getConfig().getInt("minigames.KOTH.capture-seconds", 20));
    }

    /** Build the map if it hasn't been built yet this server session. */
    public void ensureBuilt(Game game) {
        Map3D map = maps.get(game);
        if (map == null || built.contains(game)) return;
        built.add(game);
        if (!plugin.getConfig().getBoolean("minigames.auto-build", true)) return;

        Material floor = switch (game) {
            case SPLEEF -> Material.SNOW_BLOCK;
            case KOTH -> Material.SMOOTH_STONE;
            default -> Material.STONE;
        };

        List<Block> place = new ArrayList<>();
        List<Block> clear = new ArrayList<>();
        int r = map.radius;
        int hill = hillRadius();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double dist2 = dx * dx + dz * dz;
                if (dist2 > r * r) continue;
                int bx = map.x + dx, bz = map.z + dz;

                Block block = map.world.getBlockAt(bx, map.y, bz);
                // KOTH gets a gold hill marker in the middle
                Material want = (game == Game.KOTH && dist2 <= hill * hill)
                        ? Material.GOLD_BLOCK : floor;
                if (block.getType() != want) place.add(block);

                for (int dy = 1; dy <= 5; dy++) {
                    Block above = map.world.getBlockAt(bx, map.y + dy, bz);
                    if (above.getType() != Material.AIR) clear.add(above);
                }
            }
        }
        if (place.isEmpty() && clear.isEmpty()) return;

        final int perTick = Math.max(20, plugin.getConfig().getInt("minigames.blocks-per-tick", 150));
        plugin.getLogger().info("Building " + game.getDisplay() + " map ("
                + (place.size() + clear.size()) + " blocks)...");

        final Material floorMat = floor;
        final int hillR = hill;
        final Game g = game;
        new BukkitRunnable() {
            int pi = 0, ci = 0;

            @Override
            public void run() {
                int budget = perTick;
                while (budget > 0 && ci < clear.size()) {
                    clear.get(ci++).setType(Material.AIR, false);
                    budget--;
                }
                while (budget > 0 && pi < place.size()) {
                    Block b = place.get(pi++);
                    double dx = b.getX() - map.x, dz = b.getZ() - map.z;
                    boolean isHill = g == Game.KOTH && (dx * dx + dz * dz) <= hillR * hillR;
                    b.setType(isHill ? Material.GOLD_BLOCK : floorMat, false);
                    budget--;
                }
                if (pi >= place.size() && ci >= clear.size()) {
                    plugin.getLogger().info(g.getDisplay() + " map ready.");
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Rebuild a map mid-rotation (Spleef floors get eaten every round). */
    public void reset(Game game) {
        built.remove(game);
        ensureBuilt(game);
    }

    /** Spawn points spread evenly around the map edge, facing the middle. */
    public List<Location> spawns(Map3D map, int count) {
        List<Location> out = new ArrayList<>();
        double ring = Math.max(2, map.radius - 2);
        Location center = map.center();
        for (int i = 0; i < count; i++) {
            double angle = (2 * Math.PI / Math.max(1, count)) * i;
            Location spot = new Location(map.world,
                    map.x + 0.5 + Math.cos(angle) * ring,
                    map.y + 1,
                    map.z + 0.5 + Math.sin(angle) * ring);
            spot.setDirection(center.toVector().subtract(spot.toVector()));
            out.add(spot);
        }
        return out;
    }

    /** Give the player whatever this game needs. */
    public void applyKit(Player p, Game game) {
        if (game == Game.SPLEEF) {
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
            ItemStack shovel = new ItemStack(Material.DIAMOND_SHOVEL);
            shovel.addUnsafeEnchantment(Enchantment.DIG_SPEED, 5);
            p.getInventory().setItem(0, shovel);
        }
        // KOTH just uses the event's own WagerMode kit
    }

    /** Only snow may be broken, only in Spleef, only inside the map. */
    public boolean canBreak(Player p, Block block, Game game) {
        if (game != Game.SPLEEF) return false;
        Map3D map = maps.get(Game.SPLEEF);
        if (map == null) return false;
        if (!block.getWorld().equals(map.world)) return false;
        if (block.getType() != Material.SNOW_BLOCK) return false;
        if (block.getY() != map.y) return false;
        double dx = block.getX() - map.x, dz = block.getZ() - map.z;
        return dx * dx + dz * dz <= (double) map.radius * map.radius;
    }
}
