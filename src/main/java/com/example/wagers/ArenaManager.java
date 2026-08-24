package com.example.wagers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-mode arenas. Two kinds:
 *   RTP      - random safe spot in the wild (the old behaviour)
 *   PLATFORM - a fixed disc the plugin builds itself (e.g. Sumo in the End).
 *              Falling off a platform arena is an instant loss.
 *
 * Platforms are built once, in small batches, so building never spikes TPS.
 */
public class ArenaManager {

    public enum Type { RTP, PLATFORM }

    public static class Arena {
        public final Type type;
        public final World world;
        public final int x, y, z, radius;
        public final Material material;
        public final boolean voidDeath;

        Arena(Type type, World world, int x, int y, int z, int radius, Material material, boolean voidDeath) {
            this.type = type;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
            this.material = material;
            this.voidDeath = voidDeath;
        }

        public Location center() {
            return new Location(world, x + 0.5, y + 1, z + 0.5);
        }

        /** Y below which a fighter counts as knocked out. */
        public double lossY() {
            return voidDeath ? y - 3.0 : Double.NEGATIVE_INFINITY;
        }
    }

    private final WagersPlugin plugin;
    private final Map<WagerMode, Arena> arenas = new EnumMap<>(WagerMode.class);
    private final Set<WagerMode> built = new HashSet<>();

    public ArenaManager(WagersPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        arenas.clear();
        built.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("arenas");
        if (root == null) return;

        for (WagerMode mode : WagerMode.values()) {
            ConfigurationSection sec = root.getConfigurationSection(mode.name());
            if (sec == null) continue;

            Type type;
            try {
                type = Type.valueOf(sec.getString("type", "RTP").toUpperCase());
            } catch (IllegalArgumentException e) {
                type = Type.RTP;
            }
            if (type == Type.RTP) continue;

            String worldName = sec.getString("world", "world");
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                plugin.getLogger().warning("Arena for " + mode.name() + ": world '" + worldName + "' not found, using RTP.");
                continue;
            }
            Material mat = Material.matchMaterial(sec.getString("material", "OBSIDIAN"));
            if (mat == null || !mat.isBlock()) mat = Material.OBSIDIAN;

            arenas.put(mode, new Arena(
                    Type.PLATFORM,
                    world,
                    sec.getInt("x", 0),
                    sec.getInt("y", 64),
                    sec.getInt("z", 0),
                    Math.max(3, Math.min(64, sec.getInt("radius", 8))),
                    mat,
                    sec.getBoolean("void-death", true)));
        }
    }

    /** @return the platform arena for this mode, or null if the mode uses RTP. */
    public Arena getArena(WagerMode mode) {
        return arenas.get(mode);
    }

    /**
     * Make sure the platform exists. Builds only once per mode, and only if
     * auto-build is on. Blocks are placed in small batches across ticks.
     */
    public void ensureBuilt(WagerMode mode) {
        Arena arena = arenas.get(mode);
        if (arena == null || built.contains(mode)) return;
        if (!plugin.getConfig().getBoolean("arenas.auto-build", true)) {
            built.add(mode);
            return;
        }
        built.add(mode); // mark immediately so we never queue two builds

        List<Block> toPlace = new ArrayList<>();
        List<Block> toClear = new ArrayList<>();
        int r = arena.radius;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                int bx = arena.x + dx, bz = arena.z + dz;
                Block floor = arena.world.getBlockAt(bx, arena.y, bz);
                if (floor.getType() != arena.material) toPlace.add(floor);
                // Clear 4 blocks of headroom so nobody spawns inside terrain
                for (int dy = 1; dy <= 4; dy++) {
                    Block above = arena.world.getBlockAt(bx, arena.y + dy, bz);
                    if (above.getType() != Material.AIR) toClear.add(above);
                }
            }
        }
        if (toPlace.isEmpty() && toClear.isEmpty()) return;

        final int perTick = Math.max(20, plugin.getConfig().getInt("arenas.blocks-per-tick", 150));
        plugin.getLogger().info("Building " + mode.getDisplay() + " arena ("
                + (toPlace.size() + toClear.size()) + " blocks, " + perTick + "/tick)...");

        new BukkitRunnable() {
            int placeIndex = 0, clearIndex = 0;

            @Override
            public void run() {
                int budget = perTick;
                while (budget > 0 && clearIndex < toClear.size()) {
                    toClear.get(clearIndex++).setType(Material.AIR, false);
                    budget--;
                }
                while (budget > 0 && placeIndex < toPlace.size()) {
                    toPlace.get(placeIndex++).setType(arena.material, false);
                    budget--;
                }
                if (placeIndex >= toPlace.size() && clearIndex >= toClear.size()) {
                    plugin.getLogger().info("Arena for " + mode.getDisplay() + " ready.");
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Two spawn points on opposite sides of the platform, facing each other. */
    public Location[] duelSpawns(Arena arena) {
        int offset = Math.max(2, arena.radius - 2);
        Location a = new Location(arena.world, arena.x + 0.5 - offset, arena.y + 1, arena.z + 0.5);
        Location b = new Location(arena.world, arena.x + 0.5 + offset, arena.y + 1, arena.z + 0.5);
        a.setDirection(b.toVector().subtract(a.toVector()));
        b.setDirection(a.toVector().subtract(b.toVector()));
        return new Location[]{a, b};
    }

    /** Evenly spread spawn points around the platform edge, all facing the middle. */
    public List<Location> spreadSpawns(Arena arena, int count) {
        List<Location> out = new ArrayList<>();
        double ring = Math.max(2, arena.radius - 2);
        Location center = arena.center();
        for (int i = 0; i < count; i++) {
            double angle = (2 * Math.PI / Math.max(1, count)) * i;
            Location spot = new Location(arena.world,
                    arena.x + 0.5 + Math.cos(angle) * ring,
                    arena.y + 1,
                    arena.z + 0.5 + Math.sin(angle) * ring);
            spot.setDirection(center.toVector().subtract(spot.toVector()));
            out.add(spot);
        }
        return out;
    }
}
