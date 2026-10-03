package com.example.boomerang;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.List;

public class BoomerangTridentPlugin extends JavaPlugin implements Listener {

    private NamespacedKey itemKey;
    private NamespacedKey projectileKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "boomerang_item");
        projectileKey = new NamespacedKey(this, "boomerang_projectile");
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("BoomerangTrident enabled. Use /boomerang give");
    }

    // ------------------------------------------------------------------ item

    private ItemStack createItem() {
        ItemStack item = new ItemStack(Material.TRIDENT);
        ItemMeta meta = item.getItemMeta();
        String name = getConfig().getString("item-name", "&b&lBoomerang Trident");
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(name)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Left-click: hurl 3 tridents at your target", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("They fly back to you after striking.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.addEnchant(Enchantment.LOYALTY, 3, true);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isBoomerang(ItemStack item) {
        if (item == null || item.getType() != Material.TRIDENT || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    // --------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "give";
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                sender.sendMessage(Component.text("BoomerangTrident config reloaded.", NamedTextColor.GREEN));
            }
            case "give" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can receive the item.");
                    return true;
                }
                player.getInventory().addItem(createItem());
                player.sendMessage(Component.text("You received the Boomerang Trident!", NamedTextColor.AQUA));
            }
            default -> sender.sendMessage(Component.text("Usage: /boomerang [give|reload]", NamedTextColor.RED));
        }
        return true;
    }

    // --------------------------------------------------------------- ability

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        if (!isBoomerang(player.getInventory().getItemInMainHand())) return;
        if (!player.hasPermission("boomerang.use")) return;

        if (player.hasCooldown(Material.TRIDENT)) return;

        double range = getConfig().getDouble("range", 40.0);
        RayTraceResult hit = player.getWorld().rayTraceEntities(
                player.getEyeLocation(), player.getEyeLocation().getDirection(), range, 0.6,
                e -> e instanceof LivingEntity && !e.equals(player) && !e.isDead());

        if (hit == null || !(hit.getHitEntity() instanceof LivingEntity target)) {
            player.sendActionBar(Component.text("No target in sight!", NamedTextColor.RED));
            return;
        }

        player.setCooldown(Material.TRIDENT, getConfig().getInt("cooldown-ticks", 100));
        launchVolley(player, target);
    }

    private void launchVolley(Player player, LivingEntity target) {
        int stagger = getConfig().getInt("launch-stagger-ticks", 4);
        // side offsets: left, centre, right of the player
        double[] sideOffsets = {-0.9, 0.0, 0.9};
        for (int i = 0; i < 3; i++) {
            final double side = sideOffsets[i];
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline() && !player.isDead()) {
                        spawnBoomerang(player, target, side);
                    }
                }
            }.runTaskLater(this, (long) i * stagger);
        }
    }

    private void spawnBoomerang(Player player, LivingEntity target, double sideOffset) {
        Location eye = player.getEyeLocation();
        Vector forward = eye.getDirection().normalize();
        Vector right = forward.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0E-6) right = new Vector(1, 0, 0);
        right.normalize();

        Location spawn = eye.clone().add(forward.multiply(0.8)).add(right.multiply(sideOffset)).add(0, -0.3, 0);
        World world = spawn.getWorld();

        Trident trident = world.spawn(spawn, Trident.class, t -> {
            t.setShooter(player);
            t.setGravity(false);
            t.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
            t.setInvulnerable(true);
            t.setPersistent(false);
            t.getPersistentDataContainer().set(projectileKey, PersistentDataType.BYTE, (byte) 1);
        });

        world.playSound(spawn, Sound.ITEM_TRIDENT_THROW, SoundCategory.PLAYERS, 1.0f, 1.0f);

        double damage = getConfig().getDouble("damage", 6.0);
        double speedOut = getConfig().getDouble("speed-out", 1.6);
        double speedBack = getConfig().getDouble("speed-return", 1.9);
        int maxOut = getConfig().getInt("max-outbound-ticks", 60);

        new BukkitRunnable() {
            boolean returning = false;
            int ticks = 0;

            @Override
            public void run() {
                if (!trident.isValid() || !player.isOnline() || player.isDead()
                        || !player.getWorld().equals(trident.getWorld())) {
                    trident.remove();
                    cancel();
                    return;
                }

                Location tLoc = trident.getLocation();
                ticks++;

                if (!returning) {
                    if (!target.isValid() || target.isDead()
                            || !target.getWorld().equals(trident.getWorld()) || ticks > maxOut) {
                        returning = true;
                    } else {
                        Location aim = target.getLocation().add(0, target.getHeight() / 2.0, 0);
                        Vector toTarget = aim.toVector().subtract(tLoc.toVector());
                        if (toTarget.length() < 1.4) {
                            target.damage(damage, player);
                            tLoc.getWorld().playSound(tLoc, Sound.ITEM_TRIDENT_HIT, SoundCategory.PLAYERS, 1.0f, 1.0f);
                            tLoc.getWorld().spawnParticle(Particle.CRIT, tLoc, 15, 0.3, 0.3, 0.3, 0.2);
                            returning = true;
                        } else {
                            fly(trident, toTarget, speedOut);
                        }
                    }
                }

                if (returning) {
                    Location home = player.getLocation().add(0, 1.0, 0);
                    Vector toPlayer = home.toVector().subtract(tLoc.toVector());
                    if (toPlayer.length() < 1.6) {
                        tLoc.getWorld().playSound(tLoc, Sound.ITEM_TRIDENT_RETURN, SoundCategory.PLAYERS, 1.0f, 1.0f);
                        trident.remove();
                        cancel();
                        return;
                    }
                    fly(trident, toPlayer, speedBack);
                }

                tLoc.getWorld().spawnParticle(Particle.BUBBLE_POP, tLoc, 2, 0.1, 0.1, 0.1, 0.0);
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void fly(Trident trident, Vector direction, double speed) {
        Vector vel = direction.clone().normalize().multiply(speed);
        trident.setVelocity(vel);
    }

    // Our tridents are driven manually: stop vanilla hit behaviour (sticking in blocks, double damage).
    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(projectileKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }
}
