package tc.oc.pgm.spawner;

import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.metadata.MetadataValue;
import tc.oc.pgm.api.PGM;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.match.Tickable;
import tc.oc.pgm.api.match.event.MatchFinishEvent;
import tc.oc.pgm.api.player.MatchPlayer;
import tc.oc.pgm.api.time.Tick;
import tc.oc.pgm.util.TimeUtils;
import tc.oc.pgm.util.bukkit.OnlinePlayerMapAdapter;
import tc.oc.pgm.util.event.PlayerCoarseMoveEvent;

public class Spawner implements Listener, Tickable {

  public static final String METADATA_KEY = "spawner";

  private final Match match;
  private final SpawnerDefinition definition;
  private final OnlinePlayerMapAdapter<MatchPlayer> players;

  private long lastTick;
  private long currentDelay;
  private long spawnedEntities;

  public Spawner(SpawnerDefinition definition, Match match) {
    this.definition = definition;
    this.match = match;

    this.lastTick = match.getTick().tick;
    this.players = new OnlinePlayerMapAdapter<>(PGM.get());
    calculateDelay();
  }

  @Override
  public void tick(Match match, Tick tick) {
    if (!canSpawn()) return;
    if (match.getTick().tick - lastTick >= currentDelay) {
      for (Spawnable spawnable : definition.objects) {
        final Location location =
            definition.spawnRegion.getRandom(match.getRandom()).toLocation(match.getWorld());
        spawnable.spawn(location, match);
        match.getWorld().spigot().playEffect(location, Effect.FLAME, 0, 0, 0, 0.15f, 0, 0, 40, 64);

        spawnedEntities = spawnedEntities + spawnable.getSpawnCount();
      }
      calculateDelay();
    }
  }

  private void calculateDelay() {
    if (definition.minDelay == definition.maxDelay) {
      currentDelay = TimeUtils.toTicks(definition.delay);
    } else {
      long maxDelay = TimeUtils.toTicks(definition.maxDelay);
      long minDelay = TimeUtils.toTicks(definition.minDelay);
      currentDelay =
          (long)
              (match.getRandom().nextDouble() * (maxDelay - minDelay)
                  + minDelay); // Picks a random tick duration between minDelay and maxDelay
    }
    lastTick = match.getTick().tick;
  }

  private boolean canSpawn() {
    if (spawnedEntities >= definition.maxEntities || players.isEmpty()) return false;
    for (MatchPlayer player : players.values()) {
      if (definition.playerFilter.query(player.getQuery()).isAllowed()) return true;
    }
    return false;
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onItemMerge(ItemMergeEvent event) {
    boolean entityTracked = false;
    boolean targetTracked = false;
    if (event.getEntity().hasMetadata(METADATA_KEY)) {
      entityTracked = true;
    }
    if (event.getTarget().hasMetadata(METADATA_KEY)) {
      targetTracked = true;
    }

    // Do nothing if neither item is from a PGM Spawner
    if (!entityTracked && !targetTracked) {
      return;
    }

    // Cancel the merge if only 1 of the items is from a PGM Spawner
    if ((entityTracked && !targetTracked) || (!entityTracked && targetTracked)) {
      event.setCancelled(true);
      return;
    }

    int entitySpawnerID = -1;
    int targetSpawnerID = -1;
    for (MetadataValue mv : event.getEntity().getMetadata(METADATA_KEY)) {
      if (mv.getOwningPlugin().equals(PGM.get())) {
        entitySpawnerID = mv.asInt();
        break;
      }
    }
    for (MetadataValue mv : event.getTarget().getMetadata(METADATA_KEY)) {
      if (mv.getOwningPlugin().equals(PGM.get())) {
        targetSpawnerID = mv.asInt();
        break;
      }
    }
    // Cancel the merge if the items are from different PGM spawners
    if (entitySpawnerID != targetSpawnerID) {
      event.setCancelled(true);
      return;
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onEntityDeath(EntityDeathEvent event) {
    if (event.getEntity().hasMetadata(METADATA_KEY)) {
      for (MetadataValue mv : event.getEntity().getMetadata(METADATA_KEY)) {
        if (mv.getOwningPlugin().equals(PGM.get()) && mv.asInt() == definition.numericID) {
          spawnedEntities--;
          spawnedEntities = Math.max(0, spawnedEntities);
          return;
        }
      }
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onItemDespawn(ItemDespawnEvent event) {
    if (event.getEntity().hasMetadata(METADATA_KEY)) {
      for (MetadataValue mv : event.getEntity().getMetadata(METADATA_KEY)) {
        if (mv.getOwningPlugin().equals(PGM.get()) && mv.asInt() == definition.numericID) {
          spawnedEntities -= event.getEntity().getItemStack().getAmount();
          spawnedEntities = Math.max(0, spawnedEntities);
          return;
        }
      }
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPlayerPickup(PlayerPickupItemEvent event) {
    if (!event.isCancelled() && event.getItem().hasMetadata(METADATA_KEY)) {
      for (MetadataValue mv : event.getItem().getMetadata(METADATA_KEY)) {
        if (mv.getOwningPlugin().equals(PGM.get()) && mv.asInt() == definition.numericID) {
          event.getItem().removeMetadata(METADATA_KEY, PGM.get());
          spawnedEntities -= event.getItem().getItemStack().getAmount();
          spawnedEntities = Math.max(0, spawnedEntities);
          return;
        }
      }
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPlayerMove(PlayerCoarseMoveEvent event) {
    final MatchPlayer player = match.getParticipant(event.getPlayer());
    if (player == null) return;
    if (definition.playerRegion.contains(event.getPlayer())) {
      players.putIfAbsent(event.getPlayer(), player);
    } else {
      players.remove(event.getPlayer());
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onMatchEnd(MatchFinishEvent event) {
    this.players.clear();
  }
}
