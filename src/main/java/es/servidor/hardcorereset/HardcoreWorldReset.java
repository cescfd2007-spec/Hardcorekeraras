package es.servidor.hardcorereset;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class HardcoreWorldReset extends JavaPlugin implements Listener {
    private boolean resetting = false;
    private World currentWorld;
    private World currentNether;
    private World currentEnd;
    private Scoreboard scoreboard;
    private Objective deaths;
    private final Map<UUID, Long> portalCooldown = new HashMap<>();
    private final Map<UUID, Boolean> insidePortal = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        currentWorld = findWorld(org.bukkit.World.Environment.NORMAL);
        if (currentWorld == null) currentWorld = Bukkit.getWorlds().get(0);

        currentNether = findWorld(org.bukkit.World.Environment.NETHER);
        if (currentNether == null) {
            currentNether = new WorldCreator(currentWorld.getName() + "_nether")
                    .environment(World.Environment.NETHER)
                    .createWorld();
        }

        currentEnd = findWorld(org.bukkit.World.Environment.THE_END);
        if (currentEnd == null) {
            currentEnd = new WorldCreator(currentWorld.getName() + "_the_end")
                    .environment(World.Environment.THE_END)
                    .createWorld();
        }

        if (currentNether == null || currentEnd == null) {
            getLogger().severe("No se pudieron cargar/crear Nether y End iniciales.");
        }

        setupScoreboard();
        for (Player p : Bukkit.getOnlinePlayers()) forceNormalState(p);
        getLogger().info("HardcoreWorldReset activado.");
    }

    private World findWorld(World.Environment environment) {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == environment) return world;
        }
        return null;
    }

    private void setupScoreboard() {
        scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();

        deaths = scoreboard.getObjective("muertes");
        if (deaths == null) {
            deaths = scoreboard.registerNewObjective("muertes", Criteria.DEATH_COUNT, "☠ MUERTES");
        }
        deaths.setDisplaySlot(DisplaySlot.SIDEBAR);

        Objective health = scoreboard.getObjective("tab_health");
        if (health == null) {
            health = scoreboard.registerNewObjective("tab_health", Criteria.HEALTH, "Vida");
        }
        health.setRenderType(RenderType.HEARTS);
        health.setDisplaySlot(DisplaySlot.PLAYER_LIST);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (resetting) return;

        Player dead = event.getPlayer();
        event.setKeepInventory(false);
        event.setKeepLevel(false);

        resetting = true;
        World oldOverworld = currentWorld;
        World oldNether = currentNether;
        World oldEnd = currentEnd;

        Bukkit.broadcastMessage("§c§l¡" + dead.getName() + " HA MUERTO!");
        Bukkit.broadcastMessage("§7Reiniciando Overworld, Nether y End...");

        Bukkit.getScheduler().runTask(this, () -> resetAllDimensions(oldOverworld, oldNether, oldEnd, dead));
    }

    private void resetAllDimensions(World oldOverworld, World oldNether, World oldEnd, Player dead) {
        try {
            String id = "world_" + System.currentTimeMillis();

            World newOverworld = new WorldCreator(id)
                    .environment(World.Environment.NORMAL)
                    .createWorld();
            World newNether = new WorldCreator(id + "_nether")
                    .environment(World.Environment.NETHER)
                    .createWorld();
            World newEnd = new WorldCreator(id + "_the_end")
                    .environment(World.Environment.THE_END)
                    .createWorld();

            if (newOverworld == null || newNether == null || newEnd == null) {
                throw new IllegalStateException("No se pudieron crear las tres dimensiones.");
            }

            newOverworld.setDifficulty(Difficulty.HARD);
            newNether.setDifficulty(Difficulty.HARD);
            newEnd.setDifficulty(Difficulty.HARD);

            currentWorld = newOverworld;
            currentNether = newNether;
            currentEnd = newEnd;

            getServer().setRespawnWorld(newOverworld);

            if (dead.isDead()) {
                dead.spigot().respawn();
            }

            Location spawn = newOverworld.getSpawnLocation();

            for (Player p : Bukkit.getOnlinePlayers()) {
                resetPlayer(p);
                if (p != dead) {
                    p.teleport(spawn);
                }
            }

            Bukkit.getScheduler().runTask(this, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    resetPlayer(p);
                    p.teleport(spawn);
                }

                Bukkit.broadcastMessage("§a§l¡NUEVO MUNDO!");
                Bukkit.broadcastMessage("§7También se han creado un Nether y un End nuevos.");
                Bukkit.broadcastMessage("§7Todos empezáis desde cero.");

                unloadAndDelete(oldOverworld);
                unloadAndDelete(oldNether);
                unloadAndDelete(oldEnd);
            });
        } catch (Exception ex) {
            getLogger().severe("No se pudo reiniciar las dimensiones: " + ex.getMessage());
            ex.printStackTrace();
        } finally {
            resetting = false;
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (currentWorld == null) return;
        event.setRespawnLocation(currentWorld.getSpawnLocation());

        Bukkit.getScheduler().runTask(this, () -> {
            Player p = event.getPlayer();
            if (!p.isOnline()) return;
            resetPlayer(p);
            p.teleport(currentWorld.getSpawnLocation());
        });
    }

    // Paper 26.2 puede no activar el flujo vanilla de portales para dimensiones creadas
    // dinámicamente. Por eso detectamos directamente el bloque NETHER_PORTAL y
    // hacemos nosotros el viaje entre las dimensiones de la partida actual.
    @EventHandler
    public void onPortalBlock(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (currentWorld == null || currentNether == null) return;

        Location to = event.getTo();
        if (to == null) return;

        UUID uuid = player.getUniqueId();
        boolean inPortal = to.getBlock().getType() == Material.NETHER_PORTAL;

        // Debe salir del portal antes de poder usarlo otra vez.
        // Esto evita el bucle infinito de teletransportes.
        if (!inPortal) {
            insidePortal.put(uuid, false);
            return;
        }
        if (Boolean.TRUE.equals(insidePortal.get(uuid))) return;

        long now = System.currentTimeMillis();
        long last = portalCooldown.getOrDefault(uuid, 0L);
        if (now - last < 3000L) return;

        World from = player.getWorld();
        World targetWorld;
        Location desired;

        if (from == currentWorld) {
            targetWorld = currentNether;
            desired = new Location(targetWorld,
                    player.getLocation().getX() / 8.0, 80,
                    player.getLocation().getZ() / 8.0);
        } else if (from == currentNether) {
            targetWorld = currentWorld;
            desired = new Location(targetWorld,
                    player.getLocation().getX() * 8.0, 80,
                    player.getLocation().getZ() * 8.0);
        } else {
            return;
        }

        // Reutiliza un portal existente. Solo crea uno si no hay ninguno cerca.
        Location targetPortal = findExistingPortal(targetWorld, desired, 16);
        if (targetPortal == null) {
            targetPortal = createSimplePortal(findSafePortalLocation(desired));
        }
        if (targetPortal == null) return;

        portalCooldown.put(uuid, now);
        insidePortal.put(uuid, true);

        Location destination = targetPortal.clone().add(0.5, 0.0, 0.5);
        destination.setYaw(player.getLocation().getYaw());
        destination.setPitch(player.getLocation().getPitch());
        player.teleport(destination);
    }

    private Location findExistingPortal(World world, Location center, int radius) {
        int cx = center.getBlockX();
        int cy = Math.max(world.getMinHeight() + 1,
                Math.min(world.getMaxHeight() - 2, center.getBlockY()));
        int cz = center.getBlockZ();

        Location best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int y = Math.max(world.getMinHeight() + 1, cy - 16);
                 y <= Math.min(world.getMaxHeight() - 2, cy + 16); y++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    if (world.getBlockAt(x, y, z).getType() != Material.NETHER_PORTAL) continue;

                    double dx = x + 0.5 - center.getX();
                    double dy = y + 0.5 - center.getY();
                    double dz = z + 0.5 - center.getZ();
                    double distance = dx * dx + dy * dy + dz * dz;

                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new Location(world, x, y, z);
                    }
                }
            }
        }
        return best;
    }

    private Location findSafePortalLocation(Location target) {
        World world = target.getWorld();
        if (world == null) return target;

        int x = target.getBlockX();
        int z = target.getBlockZ();
        int y = Math.max(world.getMinHeight() + 2,
                Math.min(world.getMaxHeight() - 6, 80));

        return new Location(world, x, y, z);
    }

    private Location createSimplePortal(Location center) {
        World world = center.getWorld();
        if (world == null) return null;

        int x = center.getBlockX();
        int y = center.getBlockY() - 1;
        int z = center.getBlockZ();

        for (int dy = 0; dy < 5; dy++) {
            world.getBlockAt(x - 1, y + dy, z).setType(Material.OBSIDIAN);
            world.getBlockAt(x + 2, y + dy, z).setType(Material.OBSIDIAN);
        }
        for (int dx = -1; dx <= 2; dx++) {
            world.getBlockAt(x + dx, y, z).setType(Material.OBSIDIAN);
            world.getBlockAt(x + dx, y + 4, z).setType(Material.OBSIDIAN);
        }
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 1; dy <= 3; dy++) {
                world.getBlockAt(x + dx, y + dy, z).setType(Material.NETHER_PORTAL);
            }
        }
        return new Location(world, x, y + 1, z);
    }

    @EventHandler
    public void onPortal(PlayerPortalEvent event) {
        if (currentWorld == null || currentEnd == null) return;

        World from = event.getFrom().getWorld();
        if (from == null) return;

        // El Nether lo gestiona onPortalBlock. Cancelamos el evento vanilla
        // para impedir que ambos sistemas se ejecuten a la vez.
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            event.setCancelled(true);
            return;
        }

        if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL
                && from.getEnvironment() == World.Environment.NORMAL) {
            event.setTo(currentEnd.getSpawnLocation());
            event.setCanCreatePortal(true);
            event.setCreationRadius(16);
            return;
        }

        if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL
                && from.getEnvironment() == World.Environment.THE_END) {
            event.setTo(currentWorld.getSpawnLocation());
            event.setCanCreatePortal(true);
            event.setCreationRadius(16);
        }
    }

    private void resetPlayer(Player p) {
        p.closeInventory();
        p.getInventory().clear();
        p.getEnderChest().clear();
        p.setExp(0);
        p.setLevel(0);
        p.setTotalExperience(0);
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(5);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setGameMode(GameMode.SURVIVAL);
        p.setInvulnerable(false);
        p.setAllowFlight(false);
        p.setFlying(false);
    }

    private void forceNormalState(Player p) {
        p.setGameMode(GameMode.SURVIVAL);
        p.setInvulnerable(false);
        p.setAllowFlight(false);
        p.setFlying(false);
    }

    private void unloadAndDelete(World world) {
        if (world == null || world == currentWorld || world == currentNether || world == currentEnd) return;

        String name = world.getName();
        Path folder = world.getWorldFolder().toPath();

        if (Bukkit.unloadWorld(world, false)) {
            try {
                deleteWorldFolder(folder);
                getLogger().info("Mundo eliminado: " + name);
            } catch (IOException e) {
                getLogger().warning("No se pudo borrar " + name + ": " + e.getMessage());
            }
        }
    }

    private void deleteWorldFolder(Path folder) throws IOException {
        if (!Files.exists(folder)) return;
        try (var stream = Files.walk(folder)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    getLogger().warning("No se pudo borrar " + path);
                }
            });
        }
    }
}