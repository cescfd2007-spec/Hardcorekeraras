package es.servidor.hardcorereset;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

public final class HardcoreWorldReset extends JavaPlugin implements Listener {
    private boolean resetting = false;
    private World currentWorld;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        currentWorld = Bukkit.getWorlds().get(0);
        getLogger().info("HardcoreWorldReset activado.");
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (resetting) return;
        resetting = true;

        Player dead = event.getPlayer();
        World oldWorld = currentWorld;

        Bukkit.broadcastMessage("§c§l¡ALGUIEN HA MUERTO!");
        Bukkit.broadcastMessage("§7La partida se reinicia...");

        // Dejar que termine el evento de muerte y hacer el cambio en el siguiente tick.
        Bukkit.getScheduler().runTask(this, () -> resetWorld(oldWorld, dead.getName()));
    }

    private void resetWorld(World oldWorld, String deadName) {
        try {
            // Crear un mundo completamente nuevo con semilla aleatoria.
            String newName = "world_" + System.currentTimeMillis();
            World newWorld = Bukkit.createWorld(new WorldCreator(newName));
            if (newWorld == null) throw new IllegalStateException("No se pudo crear el mundo nuevo.");

            currentWorld = newWorld;
            Location spawn = newWorld.getSpawnLocation();

            // Todos empiezan de cero.
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.closeInventory();
                player.getInventory().clear();
                player.getEnderChest().clear();
                player.setExp(0);
                player.setLevel(0);
                player.setTotalExperience(0);
                player.getActivePotionEffects().forEach(effect ->
                        player.removePotionEffect(effect.getType()));
                player.setGameMode(GameMode.SURVIVAL);
                player.setHealth(player.getMaxHealth());
                player.setFoodLevel(20);
                player.setSaturation(5);
                player.setFireTicks(0);
                player.setFallDistance(0);
                player.teleport(spawn);
            }

            Bukkit.broadcastMessage("§a§l¡NUEVO MUNDO!");
            Bukkit.broadcastMessage("§7Todos empezáis desde cero.");

            // El mundo anterior ya no tiene jugadores: se descarga y se elimina.
            if (oldWorld != null && oldWorld != newWorld) {
                String oldName = oldWorld.getName();
                boolean unloaded = Bukkit.unloadWorld(oldWorld, false);
                if (unloaded) {
                    deleteWorldFolder(oldWorld.getWorldFolder());
                    getLogger().info("Mundo eliminado: " + oldName);
                } else {
                    getLogger().warning("No se pudo descargar el mundo antiguo: " + oldName);
                }
            }
        } catch (Exception ex) {
            getLogger().severe("No se pudo reiniciar el mundo: " + ex.getMessage());
            ex.printStackTrace();
        } finally {
            resetting = false;
        }
    }

    private void deleteWorldFolder(Path folder) throws IOException {
        if (!Files.exists(folder)) return;
        try (var stream = Files.walk(folder)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    getLogger().warning("No se pudo borrar " + path + ": " + e.getMessage());
                }
            });
        }
    }
}
