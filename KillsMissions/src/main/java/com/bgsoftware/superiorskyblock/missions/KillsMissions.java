package com.bgsoftware.superiorskyblock.missions;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblock;
import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.missions.MissionLoadException;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("unused")
public final class KillsMissions extends Mission<KillsMissions.KillsTracker> implements Listener {

    private static final SuperiorSkyblock superiorSkyblock = SuperiorSkyblockAPI.getSuperiorSkyblock();

    private static final Pattern percentagePattern = Pattern.compile("(.*)\\{percentage_(.+?)}(.*)"),
            valuePattern = Pattern.compile("(.*)\\{value_(.+?)}(.*)");

    private JavaPlugin plugin;
    private final Map<List<String>, Integer> requiredEntities = new HashMap<>();
    private final Map<EntityType, String> entityBossBar = new HashMap<>();
    private boolean resetAfterFinish;

    @Override
    public void load(JavaPlugin plugin, ConfigurationSection section) throws MissionLoadException {
        this.plugin = plugin;

        if (!section.contains("required-entities"))
            throw new MissionLoadException("You must have the \"required-entities\" section in the config.");

        for (String key : section.getConfigurationSection("required-entities").getKeys(false)) {
            List<String> entityTypes = section.getStringList("required-entities." + key + ".types");
            int requiredAmount = section.getInt("required-entities." + key + ".amount");
            String bossBar = section.getString("required-entities." + key + ".boss-bar", "?");

            requiredEntities.put(entityTypes, requiredAmount);
            for (String type : entityTypes) {
                try {
                    EntityType entityType = EntityType.valueOf(type);
                    entityBossBar.put(entityType, bossBar);
                } catch (Exception ignored) {}
            }
        }

        resetAfterFinish = section.getBoolean("reset-after-finish", false);

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public double getProgress(SuperiorPlayer superiorPlayer) {
        KillsTracker killsTracker = get(superiorPlayer);

        if (killsTracker == null)
            return 0.0;

        int requiredEntities = 0;
        int kills = 0;

        for (Map.Entry<List<String>, Integer> entry : this.requiredEntities.entrySet()) {
            requiredEntities += entry.getValue();
            kills += Math.min(killsTracker.getKills(entry.getKey()), entry.getValue());
        }

        return (double) kills / requiredEntities;
    }

    @Override
    public int getProgressValue(SuperiorPlayer superiorPlayer) {
        KillsTracker killsTracker = get(superiorPlayer);

        if (killsTracker == null)
            return 0;

        int kills = 0;

        for (Map.Entry<List<String>, Integer> entry : this.requiredEntities.entrySet())
            kills += Math.min(killsTracker.getKills(entry.getKey()), entry.getValue());

        return kills;
    }

    public int getRequired(String type) {
        for (Map.Entry<List<String>, Integer> entry : requiredEntities.entrySet()) {
            if (entry.getKey().contains(type))
                return entry.getValue();
        }

        return 0;
    }

    public int getProgress(SuperiorPlayer superiorPlayer, EntityType type) {
        KillsTracker killsTracker = get(superiorPlayer);
        if (killsTracker == null)
            return 0;

        for (Map.Entry<List<String>, Integer> entry : this.requiredEntities.entrySet()) {
            if (entry.getKey().contains(type.name()))
                return killsTracker.getKills(entry.getKey());
        }

        return 0;
    }

    @Override
    public void onComplete(SuperiorPlayer superiorPlayer) {
        if (resetAfterFinish)
            clearData(superiorPlayer);
    }

    @Override
    public void onCompleteFail(SuperiorPlayer superiorPlayer) {

    }

    @Override
    public void saveProgress(ConfigurationSection section) {
        for (Map.Entry<SuperiorPlayer, KillsTracker> entry : entrySet()) {
            String uuid = entry.getKey().getUniqueId().toString();
            for (Map.Entry<String, Integer> brokenEntry : entry.getValue().killsTracker.entrySet()) {
                section.set(uuid + "." + brokenEntry.getKey(), brokenEntry.getValue());
            }
        }
    }

    @Override
    public void loadProgress(ConfigurationSection section) {
        for (String uuid : section.getKeys(false)) {
            KillsTracker killsTracker = new KillsTracker();
            UUID playerUUID = UUID.fromString(uuid);
            SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(playerUUID);

            insertData(superiorPlayer, killsTracker);

            for (String key : section.getConfigurationSection(uuid).getKeys(false)) {
                killsTracker.killsTracker.put(key, section.getInt(uuid + "." + key));
            }
        }
    }

    @Override
    public void formatItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        KillsTracker killsTracker = getOrCreate(superiorPlayer, s -> new KillsTracker());

        if(killsTracker == null)
            return;

        ItemMeta itemMeta = itemStack.getItemMeta();

        if (itemMeta.hasDisplayName())
            itemMeta.setDisplayName(parsePlaceholders(killsTracker, itemMeta.getDisplayName()));

        if (itemMeta.hasLore()) {
            List<String> lore = new ArrayList<>();
            for (String line : itemMeta.getLore())
                lore.add(parsePlaceholders(killsTracker, line));
            itemMeta.setLore(lore);
        }

        itemStack.setItemMeta(itemMeta);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityKill(EntityDeathEvent e) {
        if (!isMissionEntity(e.getEntity()))
            return;

        EntityDamageEvent damageCause = e.getEntity().getLastDamageCause();

        if (!(damageCause instanceof EntityDamageByEntityEvent))
            return;

        EntityDamageByEntityEvent entityDamageByEntityEvent = (EntityDamageByEntityEvent) damageCause;

        Player damager = null;

        if (entityDamageByEntityEvent.getDamager() instanceof Player) {
            damager = (Player) entityDamageByEntityEvent.getDamager();
        } else if (entityDamageByEntityEvent.getDamager() instanceof Projectile) {
            ProjectileSource shooter = ((Projectile) entityDamageByEntityEvent.getDamager()).getShooter();
            if (shooter instanceof Player)
                damager = (Player) shooter;
        }

        if (damager == null)
            return;

        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(damager);

        if (!superiorSkyblock.getMissions().canCompleteNoProgress(superiorPlayer, this))
            return;

        KillsTracker killsTracker = getOrCreate(superiorPlayer, s -> new KillsTracker());

        if(killsTracker == null)
            return;

        killsTracker.track(e.getEntityType().name(), getEntityAmount(e.getEntity()));
        if (entityBossBar.containsKey(e.getEntityType()))
            sendBossBar(superiorPlayer, entityBossBar.get(e.getEntityType()), getProgress(superiorPlayer, e.getEntityType()), getRequired(e.getEntityType().name()), getProgress(superiorPlayer));

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> superiorPlayer.runIfOnline(player -> {
            if (canComplete(superiorPlayer))
                SuperiorSkyblockAPI.getSuperiorSkyblock().getMissions().rewardMission(this, superiorPlayer, true);
        }), 2L);
    }

    private int getEntityAmount(LivingEntity entity) {
        if (Bukkit.getPluginManager().isPluginEnabled("WildStacker")) {
            try {
                return com.bgsoftware.wildstacker.api.WildStackerAPI.getEntityAmount(entity);
            } catch (Exception ignored) {
            }
        }

        return 1;
    }

    private boolean isMissionEntity(Entity entity) {
        if (entity == null || entity instanceof ArmorStand)
            return false;

        for (List<String> requiredEntity : requiredEntities.keySet()) {
            if (requiredEntity.contains("ALL") || requiredEntity.contains("all") || requiredEntity.contains(entity.getType().name()))
                return true;
        }

        return false;
    }

    private String parsePlaceholders(KillsTracker killsTracker, String line) {
        Matcher matcher = percentagePattern.matcher(line);

        if (matcher.matches()) {
            String requiredEntity = matcher.group(2).toUpperCase();
            Optional<Map.Entry<List<String>, Integer>> entry = requiredEntities.entrySet().stream().filter(e -> e.getKey().contains(requiredEntity)).findAny();
            if (entry.isPresent()) {
                line = line.replace("{percentage_" + matcher.group(2) + "}",
                        "" + (killsTracker.getKills(Collections.singletonList(requiredEntity)) * 100) / entry.get().getValue());
            }
        }

        if ((matcher = valuePattern.matcher(line)).matches()) {
            String requiredEntity = matcher.group(2).toUpperCase();
            Optional<Map.Entry<List<String>, Integer>> entry = requiredEntities.entrySet().stream().filter(e -> e.getKey().contains(requiredEntity)).findFirst();
            if (entry.isPresent()) {
                line = line.replace("{value_" + matcher.group(2) + "}",
                        "" + killsTracker.getKills(entry.get().getKey()));
            }
        }

        return ChatColor.translateAlternateColorCodes('&', line);
    }

    public static class KillsTracker {

        private final Map<String, Integer> killsTracker = new HashMap<>();

        void track(String entity, int amount) {
            int newAmount = amount + killsTracker.getOrDefault(entity, 0);
            killsTracker.put(entity, newAmount);
        }

        int getKills(List<String> entities) {
            int amount = 0;
            boolean all = entities.contains("ALL") || entities.contains("all");

            for (String entity : killsTracker.keySet()) {
                if (all || entities.contains(entity))
                    amount += killsTracker.get(entity);
            }

            return amount;
        }

    }

}
