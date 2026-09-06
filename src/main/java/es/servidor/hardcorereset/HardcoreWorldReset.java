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
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

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
        currentWorld = loadConfiguredWorld("world", World.Environment.NORMAL);
        currentNether = loadConfiguredWorld("nether", World.Environment.NETHER);
        currentEnd = loadConfiguredWorld("end", World.Environment.THE_END);

        // Primera instalación: reutilizar los mundos que ya existan.
        if (currentWorld == null) {
            currentWorld = Bukkit.getWorlds().stream()
                    .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
                    .findFirst().orElse(Bukkit.getWorlds().get(0));
        }
        if (currentNether == null) {
            currentNether = Bukkit.getWorlds().stream()
                    .filter(w -> w.getEnvironment() == World.Environment.NETHER)
                    .findFirst().orElse(null);
        }
        if (currentEnd == null) {
            currentEnd = Bukkit.getWorlds().stream()
                    .filter(w -> w.getEnvironment() == World.Environment.THE_END)
                    .findFirst().orElse(null);
        }
        saveConfiguredWorlds();
        setupScoreboard();
        for (Player p : Bukkit.getOnlinePlayers()) forceNormalState(p);
        getLogger().info("HardcoreWorldReset activado.");
    }

    private World loadConfiguredWorld(String key, World.Environment environment) {
        String name = getConfig().getString("current-worlds." + key);
        if (name == null || name.isBlank()) return null;
        World existing = Bukkit.getWorld(name);
        if (existing != null) return existing;
        return Bukkit.createWorld(new WorldCreator(name).environment(environment));
    }

    private void saveConfiguredWorlds() {
        if (currentWorld != null) getConfig().set("current-worlds.world", currentWorld.getName());
        if (currentNether != null) getConfig().set("current-worlds.nether", currentNether.getName());
        if (currentEnd != null) getConfig().set("current-worlds.end", currentEnd.getName());
        saveConfig();
    }

    private void setupScoreboard() {
        scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();

        deaths = scoreboard.getObjective("muertes");
        if (deaths == null) {
            deaths = scoreboard.registerNewObjective("muertes", Criteria.DEATH_COUNT, "☠ MUERTES");
        }
        deaths.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Barra de vida en la lista de jugadores (TAB).
        Objective health = scoreboard.getObjective("tab_health");
        if (health == null) {
            health = scoreboard.registerNewObjective("tab_health", Criteria.HEALTH, "Vida");
        }
        health.setRenderType(RenderType.HEARTS);
        health.setDisplaySlot(DisplaySlot.PLAYER_LIST);

        Objective healthBelow = scoreboard.getObjective("health_below_name");
        if (healthBelow == null) {
            healthBelow = scoreboard.registerNewObjective("health_below_name", Criteria.HEALTH, "❤");
        }
        healthBelow.setRenderType(RenderType.HEARTS);
        healthBelow.setDisplaySlot(DisplaySlot.BELOW_NAME);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (resetting) return;
        Player dead = event.getPlayer();

        event.setKeepInventory(false);
        event.setKeepLevel(false);

        resetting = true;
        World oldWorld = currentWorld;

        Bukkit.broadcastMessage("§c§l¡" + dead.getName() + " HA MUERTO!");
        Bukkit.broadcastMessage("§7Reiniciando el mundo...");

        Bukkit.getScheduler().runTask(this, () -> resetWorld(oldWorld, dead));
    }

    private void resetWorld(World oldWorld, Player dead) {
        World oldNether = currentNether;
        World oldEnd = currentEnd;
        try {
            String id = String.valueOf(System.currentTimeMillis());
            String base = "hardcore_" + id;
            long seed = new Random().nextLong();

            World newWorld = new WorldCreator(base)
                    .environment(World.Environment.NORMAL)
                    .seed(seed)
                    .hardcore(false)
                    .createWorld();
            World newNether = new WorldCreator(base + "_nether")
                    .environment(World.Environment.NETHER)
                    .seed(seed)
                    .hardcore(false)
                    .createWorld();
            World newEnd = new WorldCreator(base + "_the_end")
                    .environment(World.Environment.THE_END)
                    .seed(seed)
                    .hardcore(false)
                    .createWorld();

            if (newWorld == null || newNether == null || newEnd == null) {
                throw new IllegalStateException("No se pudieron crear las tres dimensiones nuevas.");
            }

            newWorld.setDifficulty(Difficulty.HARD);
            newNether.setDifficulty(Difficulty.HARD);
            newEnd.setDifficulty(Difficulty.HARD);

            currentWorld = newWorld;
            currentNether = newNether;
            currentEnd = newEnd;
            getServer().setRespawnWorld(newWorld);

            resetAdvancements();

            if (dead.isDead()) dead.spigot().respawn();

            Location spawn = newWorld.getSpawnLocation();
            for (Player p : Bukkit.getOnlinePlayers()) {
                resetPlayer(p);
                if (p != dead) p.teleport(spawn);
            }

            Bukkit.getScheduler().runTask(this, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    resetPlayer(p);
                    p.teleport(newWorld.getSpawnLocation());
                }
                Bukkit.broadcastMessage("§a§l¡NUEVO MUNDO!");
                Bukkit.broadcastMessage("§7Overworld, Nether y End han sido reiniciados.");

                resetOldWorld(oldWorld);
                resetOldWorld(oldNether);
                resetOldWorld(oldEnd);
            });
        } catch (Exception ex) {
            getLogger().severe("No se pudo reiniciar las dimensiones: " + ex.getMessage());
            ex.printStackTrace();
        } finally {
            resetting = false;
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

    private void resetAdvancements() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Bukkit.getServer().advancementIterator().forEachRemaining(advancement -> {
                var progress = p.getAdvancementProgress(advancement);
                for (String criterion : progress.getAwardedCriteria()) {
                    progress.revokeCriteria(criterion);
                }
            });
        }
    }

    @EventHandler
    public void onPortal(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (currentWorld == null || currentNether == null || currentEnd == null) return;

        Player player = event.getPlayer();
        World from = event.getFrom().getWorld();
        if (from == null) return;

        World target = null;
        if (event.getCause() == org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            if (from.getUID().equals(currentWorld.getUID())) target = currentNether;
            else if (from.getUID().equals(currentNether.getUID())) target = currentWorld;
        } else if (event.getCause() == org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            if (from.getUID().equals(currentWorld.getUID())) target = currentEnd;
            else if (from.getUID().equals(currentEnd.getUID())) target = currentWorld;
        }

        if (target == null) return;

        // Cancelamos la resolución vanilla del portal y hacemos el viaje manualmente.
        // Así nunca puede escoger un Nether/End antiguo.
        event.setCancelled(true);
        player.setPortalCooldown(20);
        Location destination = target.getSpawnLocation().clone();
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                player.teleport(destination);
                player.setPortalCooldown(20);
            }
        });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (currentWorld == null) return;
        event.setRespawnLocation(currentWorld.getSpawnLocation());

        Bukkit.getScheduler().runTask(this, () -> {
            Player p = event.getPlayer();
            if (!p.isOnline()) return;
            p.setGameMode(GameMode.SURVIVAL);
            p.setInvulnerable(false);
            p.setAllowFlight(false);
            p.setFlying(false);
            p.setHealth(p.getMaxHealth());
            p.setFoodLevel(20);
            p.setSaturation(5);
            p.getInventory().clear();
            p.setExp(0);
            p.setLevel(0);
            p.setTotalExperience(0);
        });
    }

    private void forceNormalState(Player p) {
        p.setGameMode(GameMode.SURVIVAL);
        p.setInvulnerable(false);
        p.setAllowFlight(false);
        p.setFlying(false);
    }

    private void resetOldWorld(World oldWorld) {
        if (oldWorld == null || oldWorld == currentWorld) return;
        String oldName = oldWorld.getName();
        boolean unloaded = Bukkit.unloadWorld(oldWorld, false);
        if (unloaded) {
            try {
                deleteWorldFolder(oldWorld.getWorldFolder().toPath());
                getLogger().info("Mundo eliminado: " + oldName);
            } catch (IOException e) {
                getLogger().warning("No se pudo borrar " + oldName + ": " + e.getMessage());
            }
        }
    }

    private void deleteWorldFolder(Path folder) throws IOException {
        if (!Files.exists(folder)) return;
        try (var stream = Files.walk(folder)) {
            stream.sorted((a,b) -> b.compareTo(a)).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException e) { getLogger().warning("No se pudo borrar " + path); }
            });
        }
    }
}
