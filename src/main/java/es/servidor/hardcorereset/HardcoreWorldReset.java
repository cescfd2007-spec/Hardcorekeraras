package es.servidor.hardcorereset;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

public final class HardcoreWorldReset extends JavaPlugin implements Listener {
    private boolean resetting;
    private World currentOverworld;
    private World currentNether;
    private World currentEnd;
    private World holdingWorld;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        currentOverworld = Bukkit.getWorlds().get(0);
        currentNether = Bukkit.getWorld(currentOverworld.getName() + "_nether");
        currentEnd = Bukkit.getWorld(currentOverworld.getName() + "_the_end");
        setupScoreboards();
        for (Player p : Bukkit.getOnlinePlayers()) forceNormalState(p);
        getLogger().info("HardcoreWorldReset activado. Base de mundos: " + currentOverworld.getName());
    }

    private void setupScoreboards() {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();

        Objective deaths = board.getObjective("muertes");
        if (deaths == null) {
            deaths = board.registerNewObjective("muertes", Criteria.DEATH_COUNT, "☠ MUERTES");
        }
        deaths.setDisplaySlot(DisplaySlot.SIDEBAR);

        Objective tabHealth = board.getObjective("tab_health");
        if (tabHealth == null) {
            tabHealth = board.registerNewObjective("tab_health", Criteria.HEALTH, "Vida");
        }
        tabHealth.setRenderType(RenderType.HEARTS);
        tabHealth.setDisplaySlot(DisplaySlot.PLAYER_LIST);

        Objective belowName = board.getObjective("below_health");
        if (belowName == null) {
            belowName = board.registerNewObjective("below_health", Criteria.HEALTH, "❤");
        }
        belowName.setRenderType(RenderType.HEARTS);
        belowName.setDisplaySlot(DisplaySlot.BELOW_NAME);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (resetting) return;

        Player dead = event.getPlayer();
        event.setKeepInventory(false);
        event.setKeepLevel(false);

        resetAdvancementsForEveryone();

        resetting = true;
        World oldOverworld = currentOverworld;
        World oldNether = currentNether;
        World oldEnd = currentEnd;

        Bukkit.broadcastMessage("§c§l¡" + dead.getName() + " HA MUERTO!");
        Bukkit.broadcastMessage("§7Reiniciando Overworld, Nether y End...");

        // Mundo temporal: permite sacar a todos de las dimensiones antiguas antes de descargarlas.
        String holdName = "hardcore_reset_hold_" + System.currentTimeMillis();
        holdingWorld = Bukkit.createWorld(new WorldCreator(holdName));
        if (holdingWorld == null) {
            getLogger().severe("No se pudo crear el mundo temporal.");
            resetting = false;
            return;
        }
        holdingWorld.setDifficulty(Difficulty.HARD);

        dead.spigot().respawn();

        Bukkit.getScheduler().runTaskLater(this, () -> resetAllWorlds(oldOverworld, oldNether, oldEnd, dead), 2L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (resetting && holdingWorld != null) {
            event.setRespawnLocation(holdingWorld.getSpawnLocation());
        } else if (currentOverworld != null) {
            event.setRespawnLocation(currentOverworld.getSpawnLocation());
        }

        Bukkit.getScheduler().runTask(this, () -> {
            Player p = event.getPlayer();
            if (!p.isOnline()) return;
            forceNormalState(p);
        });
    }

    private void resetAllWorlds(World oldOverworld, World oldNether, World oldEnd, Player dead) {
        try {
            // Aseguramos que todos estén fuera de los mundos viejos.
            Location holdSpawn = holdingWorld.getSpawnLocation();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p != dead || !p.isDead()) {
                    p.teleport(holdSpawn);
                }
            }

            // Descargar y borrar las tres dimensiones antiguas.
            unloadAndDelete(oldNether);
            unloadAndDelete(oldEnd);
            unloadAndDelete(oldOverworld);

            // IMPORTANTE: reutilizamos exactamente los nombres base, _nether y _the_end.
            // Así Minecraft/Paper vuelve a hacer el enlace nativo de portales correctamente.
            String baseName = oldOverworld.getName();
            currentOverworld = createDimension(baseName, World.Environment.NORMAL);
            currentNether = createDimension(baseName + "_nether", World.Environment.NETHER);
            currentEnd = createDimension(baseName + "_the_end", World.Environment.THE_END);

            if (currentOverworld == null || currentNether == null || currentEnd == null) {
                throw new IllegalStateException("No se pudieron crear las tres dimensiones nuevas.");
            }

            currentOverworld.setDifficulty(Difficulty.HARD);
            currentNether.setDifficulty(Difficulty.HARD);
            currentEnd.setDifficulty(Difficulty.HARD);
            Bukkit.getServer().setRespawnWorld(currentOverworld);

            Location spawn = currentOverworld.getSpawnLocation();
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.closeInventory();
                p.getInventory().clear();
                p.getEnderChest().clear();
                p.setExp(0);
                p.setLevel(0);
                p.setTotalExperience(0);
                p.getActivePotionEffects().forEach(effect -> p.removePotionEffect(effect.getType()));
                p.setHealth(p.getMaxHealth());
                p.setFoodLevel(20);
                p.setSaturation(5);
                p.setFireTicks(0);
                p.setFallDistance(0);
                p.teleport(spawn);
                forceNormalState(p);
            }

            Bukkit.broadcastMessage("§a§l¡NUEVO MUNDO!");
            Bukkit.broadcastMessage("§7Overworld, Nether y End han sido reiniciados.");

            // El temporal ya no hace falta.
            World temp = holdingWorld;
            holdingWorld = null;
            if (temp != null) {
                Bukkit.unloadWorld(temp, false);
                deleteWorldFolder(temp.getWorldFolder().toPath());
            }
        } catch (Exception ex) {
            getLogger().severe("No se pudo reiniciar el mundo: " + ex.getMessage());
            ex.printStackTrace();
        } finally {
            resetting = false;
        }
    }

    private World createDimension(String name, World.Environment environment) {
        return Bukkit.createWorld(new WorldCreator(name).environment(environment));
    }

    private void unloadAndDelete(World world) throws IOException {
        if (world == null) return;
        Path folder = world.getWorldFolder().toPath();
        String name = world.getName();
        if (Bukkit.unloadWorld(world, false)) {
            deleteWorldFolder(folder);
            getLogger().info("Mundo eliminado: " + name);
        } else {
            throw new IllegalStateException("No se pudo descargar el mundo " + name);
        }
    }

    private void resetAdvancementsForEveryone() {
        Iterator<Advancement> iterator = Bukkit.getServer().advancementIterator();
        while (iterator.hasNext()) {
            Advancement advancement = iterator.next();
            for (Player player : Bukkit.getOnlinePlayers()) {
                AdvancementProgress progress = player.getAdvancementProgress(advancement);
                for (String criterion : progress.getAwardedCriteria()) {
                    progress.revokeCriteria(criterion);
                }
            }
        }
    }

    private void forceNormalState(Player p) {
        p.setGameMode(GameMode.SURVIVAL);
        p.setInvulnerable(false);
        p.setAllowFlight(false);
        p.setFlying(false);
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
