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
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class HardcoreWorldReset extends JavaPlugin implements Listener {
    private boolean resetting;
    private World currentWorld;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        currentWorld = Bukkit.getWorlds().getFirst();
        getLogger().info("HardcoreWorldReset activado.");
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (resetting) return;
        resetting = true;

        World oldWorld = currentWorld;
        Bukkit.broadcastMessage("§c§l¡ALGUIEN HA MUERTO!");
        Bukkit.broadcastMessage("§7La partida se reinicia...");

        Bukkit.getScheduler().runTask(this, () -> resetWorld(oldWorld));
    }

    private void resetWorld(World oldWorld) {
        try {
            if (oldWorld == null) throw new IllegalStateException("No se encontró el mundo actual.");

            String newName = oldWorld.getName() + "_new_" + System.currentTimeMillis();
            World newWorld = new WorldCreator(newName).hardcore(true).createWorld();
            if (newWorld == null) throw new IllegalStateException("No se pudo crear el mundo nuevo.");

            // En Paper 26.2 la dificultad se establece en el mundo ya creado.
            newWorld.setDifficulty(Difficulty.HARD);
            Bukkit.setRespawnWorld(newWorld);
            currentWorld = newWorld;
            Location spawn = newWorld.getSpawnLocation();

            for (Player player : Bukkit.getOnlinePlayers()) {
                player.closeInventory();
                player.getInventory().clear();
                player.getEnderChest().clear();
                player.setExp(0);
                player.setLevel(0);
                player.setTotalExperience(0);
                player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
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

            Path oldFolder = oldWorld.getWorldFolder().toPath();
            String oldName = oldWorld.getName();
            if (oldWorld != newWorld && Bukkit.unloadWorld(oldWorld, false)) {
                deleteWorldFolder(oldFolder);
                getLogger().info("Mundo eliminado: " + oldName);
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
                try { Files.deleteIfExists(path); }
                catch (IOException e) { getLogger().warning("No se pudo borrar " + path + ": " + e.getMessage()); }
            });
        }
    }
}
