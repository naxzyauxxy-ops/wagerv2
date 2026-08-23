package com.example.wagers;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Arrays;
import java.util.stream.Collectors;

public enum WagerMode {

    /** Fight with whatever you're carrying right now. */
    CLASSIC("Classic", "Fight with your own gear"),
    /** Full diamond kit, no potions. */
    DIAMOND("Diamond", "Full diamond kit + gapples"),
    /** Iron gear + healing potions, classic potpvp. */
    NODEBUFF("NoDebuff", "Iron kit + splash healing pots"),
    /** Knockback sticks only, no damage kit. */
    SUMO("Sumo", "Knockback sticks only"),
    /** Diamond kit but no regen and no food. */
    HARDCORE("Hardcore", "Diamond kit, no healing");

    private final String display;
    private final String description;

    WagerMode(String display, String description) {
        this.display = display;
        this.description = description;
    }

    public String getDisplay() {
        return display;
    }

    public String getDescription() {
        return description;
    }

    public static WagerMode match(String input) {
        for (WagerMode m : values()) {
            if (m.name().equalsIgnoreCase(input) || m.display.equalsIgnoreCase(input)) return m;
        }
        return null;
    }

    public static String list() {
        return Arrays.stream(values()).map(WagerMode::getDisplay).collect(Collectors.joining(", "));
    }

    /** True if this mode replaces the player's inventory with a kit. */
    public boolean usesKit() {
        return this != CLASSIC;
    }

    public void applyKit(Player p) {
        if (!usesKit()) return;
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setArmorContents(null);

        switch (this) {
            case DIAMOND -> {
                inv.setHelmet(new ItemStack(Material.DIAMOND_HELMET));
                inv.setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
                inv.setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS));
                inv.setBoots(new ItemStack(Material.DIAMOND_BOOTS));
                inv.setItem(0, new ItemStack(Material.DIAMOND_SWORD));
                inv.setItem(1, new ItemStack(Material.GOLDEN_APPLE, 8));
                inv.setItem(8, new ItemStack(Material.COOKED_BEEF, 16));
            }
            case NODEBUFF -> {
                inv.setHelmet(new ItemStack(Material.IRON_HELMET));
                inv.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
                inv.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
                inv.setBoots(new ItemStack(Material.IRON_BOOTS));
                inv.setItem(0, new ItemStack(Material.IRON_SWORD));
                for (int i = 1; i < 36; i++) {
                    if (inv.getItem(i) == null) inv.setItem(i, new ItemStack(Material.SPLASH_POTION));
                }
                inv.setItem(8, new ItemStack(Material.COOKED_BEEF, 16));
            }
            case SUMO -> {
                ItemStack stick = new ItemStack(Material.STICK);
                stick.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.KNOCKBACK, 2);
                inv.setItem(0, stick);
            }
            case HARDCORE -> {
                inv.setHelmet(new ItemStack(Material.DIAMOND_HELMET));
                inv.setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
                inv.setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS));
                inv.setBoots(new ItemStack(Material.DIAMOND_BOOTS));
                inv.setItem(0, new ItemStack(Material.DIAMOND_SWORD));
            }
            default -> { }
        }

        p.setHealth(p.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        for (PotionEffect effect : p.getActivePotionEffects()) {
            p.removePotionEffect(effect.getType());
        }
        if (this == HARDCORE) {
            // suppress natural regen via a long weakness-style marker handled in listener
            p.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, Integer.MAX_VALUE, 0, false, false));
        }
    }
}
