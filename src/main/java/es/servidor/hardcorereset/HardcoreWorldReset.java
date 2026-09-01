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
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class HardcoreWorldReset extends JavaPlugin implements Listener {
    private boolean resetting = false;
    private World currentWorld;
    private Scoreboard scoreboard;
    private Objective deaths;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        currentWorld = Bukkit.getWorlds().get(0);
        setupScoreboard();
        for (Player p : Bukkit.getOnlinePlayers()) forceNormalState(p);
        getLogger().info("HardcoreWorldReset activado.");
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
        try {
            String newName = "world_" + System.currentTimeMillis();
            World newWorld = Bukkit.createWorld(new WorldCreator(newName));
            if (newWorld == null) throw new IllegalStateException("No se pudo crear el mundo nuevo.");

            newWorld.setDifficulty(Difficulty.HARD);
            currentWorld = newWorld;
            Location spawn = newWorld.getSpawnLocation();
            getServer().setRespawnWorld(newWorld);

            if (dead.isDead()) dead.spigot().respawn();

            for (Player p : Bukkit.getOnlinePlayers()) {
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
                if (p != dead) p.teleport(spawn);
            }

            Bukkit.getScheduler().runTask(this, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.setGameMode(GameMode.SURVIVAL);
                    p.setInvulnerable(false);
                    p.setAllowFlight(false);
                    p.setFlying(false);
                    p.teleport(spawn);
                }
                Bukkit.broadcastMessage("§a§l¡NUEVO MUNDO!");
                Bukkit.broadcastMessage("§7Todos empezáis desde cero.");
                resetOldWorld(oldWorld);
            });
        } catch (Exception ex) {
            getLogger().severe("No se pudo reiniciar el mundo: " + ex.getMessage());
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
