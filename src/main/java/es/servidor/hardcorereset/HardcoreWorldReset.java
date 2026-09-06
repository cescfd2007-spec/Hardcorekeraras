package es.servidor.hardcorereset;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
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

public final class HardcoreWorldReset extends JavaPlugin implements Listener {
    private boolean resetting = false;
    private World currentWorld;
    private World currentNether;
    private World currentEnd;
    private Scoreboard scoreboard;
    private Objective deaths;

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

    // Dejamos que Minecraft/Paper gestione los portales de forma VANILLA.
    // Solo cambiamos el mundo de destino para que siempre sea el de la partida actual.
    // No construimos portales manualmente ni fijamos una altura (por ejemplo Y=80).
    @EventHandler
    public void onPortal(PlayerPortalEvent event) {
        if (currentWorld == null) return;

        World from = event.getFrom().getWorld();
        if (from == null) return;

        if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            World target;
            if (from == currentWorld) {
                target = currentNether;
            } else if (from == currentNether) {
                target = currentWorld;
            } else {
                return;
            }

            if (target == null) return;

            // Conservamos las coordenadas que Paper calculó para el portal vanilla,
            // cambiando únicamente el mundo. Así se mantiene la escala 1:8 y la
            // búsqueda/creación natural del portal, incluida su altura apropiada.
            Location vanillaDestination = event.getTo();
            if (vanillaDestination != null) {
                Location destination = vanillaDestination.clone();
                destination.setWorld(target);
                event.setTo(destination);
            }
            event.setCanCreatePortal(true);
            event.setSearchRadius(128);
            event.setCreationRadius(16);
            return;
        }

        if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            if (from.getEnvironment() == World.Environment.NORMAL && currentEnd != null) {
                event.setTo(currentEnd.getSpawnLocation());
            } else if (from.getEnvironment() == World.Environment.THE_END) {
                event.setTo(currentWorld.getSpawnLocation());
            }
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