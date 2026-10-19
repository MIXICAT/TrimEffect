package dev.example.trimeffects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TrimEffectsPlugin extends JavaPlugin implements TabExecutor, Listener {

    /** Overall state of the SMP season. Effects only run while ACTIVE. */
    private enum SmpState { INACTIVE, ACTIVE, PAUSED }

    /** One configured pattern -> effect mapping. */
    private record EffectSpec(PotionEffectType type, int amplifier, int minPieces) {}

    /** A restriction on one player. expiresAt == -1 means permanent. */
    private record Restriction(String name, long expiresAt) {
        boolean permanent() {
            return expiresAt < 0;
        }

        boolean expired(long now) {
            return !permanent() && now >= expiresAt;
        }
    }

    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)([smhdw])");
    private static final String ADMIN = "trimeffects.admin";

    private final MiniMessage mm = MiniMessage.miniMessage();

    // Config
    private final Map<String, EffectSpec> specs = new LinkedHashMap<>(); // key = "namespace:value"
    private final Set<String> disabledWorlds = new HashSet<>();
    private int durationTicks;
    private boolean particles;
    private boolean icon;
    private int perExtraPiece;
    private int maxAmplifier;

    // Persistent state (data.yml)
    private SmpState state = SmpState.INACTIVE;
    private int season = 1;
    private final Map<UUID, Restriction> restrictions = new HashMap<>();
    private final Set<UUID> optedOut = new HashSet<>();
    private final Map<UUID, Boolean> forcedToggles = new HashMap<>();
    private final List<String> seasonHistory = new ArrayList<>();
    private final List<String> auditLog = new ArrayList<>();
    private final Map<UUID, List<String>> playerHistory = new HashMap<>();
    private boolean maintenance;
    private boolean broadcastsEnabled = true;
    // Permanent killer:victim pairs. No automatic reset is applied.
    private final Set<String> rewardedKillPairs = new HashSet<>();
    private final Set<String> issuedRemnantIds = new HashSet<>();

    private NamespacedKey remnantKey;
    private NamespacedKey remnantIdKey;
    private NamespacedKey ascendedKey;
    private NamespacedKey armorIdKey;
    private NamespacedKey loreLinesKey;
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();

    // Runtime only
    private final Map<UUID, Set<PotionEffectType>> applied = new HashMap<>();
    private File dataFile;
    private BukkitTask task;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        remnantKey = new NamespacedKey(this, "trim_remnant");
        remnantIdKey = new NamespacedKey(this, "trim_remnant_id");
        ascendedKey = new NamespacedKey(this, "ascended_armor");
        armorIdKey = new NamespacedKey(this, "armor_piece_id");
        loreLinesKey = new NamespacedKey(this, "managed_lore_lines");
        getServer().getPluginManager().registerEvents(this, this);

        var command = getCommand("trimeffect");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        loadConfig();
        loadData();
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
        clearAll();
        saveData();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        applied.remove(event.getPlayer().getUniqueId());
    }

    private void loadConfig() {
        reloadConfig();
        if (task != null) {
            task.cancel();
        }

        var cfg = getConfig();
        durationTicks = Math.max(20, cfg.getInt("effect-duration-ticks", 60));
        particles = cfg.getBoolean("particles", false);
        icon = cfg.getBoolean("icon", true);
        perExtraPiece = Math.max(0, cfg.getInt("amplifier-per-extra-piece", 1));
        maxAmplifier = Math.max(0, cfg.getInt("max-amplifier", 2));

        disabledWorlds.clear();
        disabledWorlds.addAll(cfg.getStringList("disabled-worlds"));

        specs.clear();
        ConfigurationSection patterns = cfg.getConfigurationSection("patterns");
        if (patterns != null) {
            for (String key : patterns.getKeys(false)) {
                ConfigurationSection section = patterns.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                NamespacedKey patternKey = parseKey(key);
                String effectName = section.getString("effect", "");
                NamespacedKey effectKey = parseKey(effectName);
                PotionEffectType type = effectKey == null ? null : Registry.EFFECT.get(effectKey);
                if (patternKey == null || type == null) {
                    getLogger().warning("Skipping pattern '" + key + "': unknown pattern or effect '" + effectName + "'.");
                    continue;
                }
                specs.put(patternKey.toString(), new EffectSpec(
                        type,
                        Math.max(0, section.getInt("amplifier", 0)),
                        Math.max(1, section.getInt("min-pieces", 1))));
            }
        }

        long interval = Math.max(5, cfg.getInt("check-interval-ticks", 20));
        task = getServer().getScheduler().runTaskTimer(this, this::tick, interval, interval);
        getLogger().info("Loaded " + specs.size() + " trim effects.");
    }

    private static NamespacedKey parseKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains(":") ? NamespacedKey.fromString(lower) : NamespacedKey.minecraft(lower);
    }

    // ------------------------------------------------------------------ persistence

    private void loadData() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);

        season = Math.max(1, yaml.getInt("season", 1));

        String fallback = getConfig().getString("smp.default-state", "INACTIVE");
        state = parseState(yaml.getString("state"), parseState(fallback, SmpState.INACTIVE));

        restrictions.clear();
        ConfigurationSection section = yaml.getConfigurationSection("restrictions");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(id);
                    restrictions.put(uuid, new Restriction(
                            section.getString(id + ".name", id),
                            section.getLong(id + ".expires", -1L)));
                } catch (IllegalArgumentException ignored) {
                    getLogger().warning("Ignoring bad UUID in data.yml: " + id);
                }
            }
        }

        rewardedKillPairs.clear();
        rewardedKillPairs.addAll(yaml.getStringList("pvp-rewarded-kill-pairs"));
        issuedRemnantIds.clear();
        issuedRemnantIds.addAll(yaml.getStringList("issued-remnant-ids"));

        maintenance = yaml.getBoolean("maintenance", false);
        broadcastsEnabled = yaml.getBoolean("broadcasts-enabled", true);
        forcedToggles.clear();
        ConfigurationSection forced = yaml.getConfigurationSection("forced-toggles");
        if (forced != null) {
            for (String id : forced.getKeys(false)) {
                try { forcedToggles.put(UUID.fromString(id), forced.getBoolean(id)); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        seasonHistory.clear(); seasonHistory.addAll(yaml.getStringList("season-history"));
        auditLog.clear(); auditLog.addAll(yaml.getStringList("audit-log"));
        playerHistory.clear();
        ConfigurationSection historySection = yaml.getConfigurationSection("player-history");
        if (historySection != null) for (String id : historySection.getKeys(false)) {
            try { playerHistory.put(UUID.fromString(id), new ArrayList<>(historySection.getStringList(id))); }
            catch (IllegalArgumentException ignored) { }
        }
        optedOut.clear();
        for (String id : yaml.getStringList("opted-out")) {
            try {
                optedOut.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
                // skip
            }
        }
    }

    private static SmpState parseState(String raw, SmpState fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return SmpState.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private void saveData() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("season", season);
        yaml.set("state", state.name());
        restrictions.forEach((uuid, r) -> {
            yaml.set("restrictions." + uuid + ".name", r.name());
            yaml.set("restrictions." + uuid + ".expires", r.expiresAt());
        });
        yaml.set("opted-out", optedOut.stream().map(UUID::toString).toList());
        yaml.set("maintenance", maintenance);
        yaml.set("broadcasts-enabled", broadcastsEnabled);
        forcedToggles.forEach((uuid, enabled) -> yaml.set("forced-toggles." + uuid, enabled));
        yaml.set("season-history", seasonHistory);
        yaml.set("audit-log", auditLog);
        playerHistory.forEach((uuid, entries) -> yaml.set("player-history." + uuid, entries));
        yaml.set("pvp-rewarded-kill-pairs", new ArrayList<>(rewardedKillPairs));
        yaml.set("issued-remnant-ids", new ArrayList<>(issuedRemnantIds));
        try {
            getDataFolder().mkdirs();
            yaml.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save data.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ effect engine

    private void tick() {
        purgeExpiredRestrictions();
        for (Player player : getServer().getOnlinePlayers()) {
            refreshPlayerItemLore(player);
            if (ineligibleReason(player) != null) {
                clear(player);
            } else {
                apply(player);
            }
        }
    }

    /** Returns why this player gets no effects right now, or null if they are eligible. */
    private String ineligibleReason(Player player) {
        if (maintenance) {
            return "TrimEffects maintenance mode is enabled";
        }
        if (state == SmpState.INACTIVE) {
            return "the SMP isn't active";
        }
        if (state == SmpState.PAUSED) {
            return "the SMP is paused";
        }
        if (player.hasPermission("trimeffects.bypass")) {
            return "bypass permission";
        }
        Boolean forced = forcedToggles.get(player.getUniqueId());
        if (forced != null) {
            if (!forced) return "an administrator forced effects off";
            // A forced-on preference does not bypass season state, restrictions, or bypass permission.
        }
        Restriction restriction = restrictions.get(player.getUniqueId());
        if (restriction != null && !restriction.expired(System.currentTimeMillis())) {
            return restriction.permanent()
                    ? "restricted permanently"
                    : "restricted for another " + formatDuration(restriction.expiresAt() - System.currentTimeMillis());
        }
        if (optedOut.contains(player.getUniqueId()) && !Boolean.TRUE.equals(forcedToggles.get(player.getUniqueId()))) {
            return "turned off with /trimeffect toggle";
        }
        if (disabledWorlds.contains(player.getWorld().getName())) {
            return "effects are disabled in this world";
        }
        if (player.isDead()) {
            return "dead";
        }
        return null;
    }

    private void purgeExpiredRestrictions() {
        long now = System.currentTimeMillis();
        List<UUID> expired = restrictions.entrySet().stream()
                .filter(e -> e.getValue().expired(now))
                .map(Map.Entry::getKey)
                .toList();
        if (expired.isEmpty()) {
            return;
        }
        for (UUID uuid : expired) {
            restrictions.remove(uuid);
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                online.sendMessage(msg("you-unrestricted"));
            }
        }
        saveData();
    }

    /** Effects are specialized by pattern: 2-3 pieces = I, 4 = II, ascended full set = III. */
    private Map<PotionEffectType, Integer> computeEffects(Player player) {
        Map<String, Integer> counts = new HashMap<>();
        ItemStack[] armor = player.getInventory().getArmorContents();
        for (ItemStack piece : armor) {
            String pattern = patternOf(piece);
            if (pattern != null) counts.merge(pattern, 1, Integer::sum);
        }

        Map<PotionEffectType, Integer> best = new HashMap<>();
        counts.forEach((pattern, pieces) -> {
            EffectSpec spec = specs.get(pattern);
            if (spec == null || pieces < 2) return;
            int amplifier = spec.amplifier() + (pieces == 4 ? 1 : 0);
            if (pieces == 4 && isFullyAscendedSet(armor, pattern)) amplifier++;
            best.merge(spec.type(), amplifier, Math::max);
        });

        int trimmedPieces = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (trimmedPieces == 4 && counts.size() > 1) {
            int largestGroup = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            if (largestGroup == 1) {
                best.merge(PotionEffectType.WEAKNESS, 1, Math::max); // Weakness II
                best.merge(PotionEffectType.SLOWNESS, 0, Math::max); // Slowness I
            } else if (counts.size() >= 3) {
                best.merge(PotionEffectType.WEAKNESS, 0, Math::max);
                best.merge(PotionEffectType.SLOWNESS, 0, Math::max);
            } else {
                best.merge(PotionEffectType.WEAKNESS, 0, Math::max);
            }
        }
        return best;
    }

    private String patternOf(ItemStack piece) {
        if (piece == null || !(piece.getItemMeta() instanceof ArmorMeta meta)) return null;
        ArmorTrim trim = meta.getTrim();
        return trim == null ? null : trim.getPattern().key().asString();
    }

    private boolean isFullyAscendedSet(ItemStack[] armor, String pattern) {
        if (armor.length != 4) return false;
        Set<String> identities = new HashSet<>();
        for (ItemStack piece : armor) {
            if (!pattern.equals(patternOf(piece)) || !isAscended(piece)) return false;
            String id = piece.getItemMeta().getPersistentDataContainer().get(armorIdKey, PersistentDataType.STRING);
            // Copied metadata must not let one duplicated armor identity count as four pieces.
            if (id == null || !identities.add(id)) return false;
        }
        return identities.size() == 4;
    }

    private boolean isAscended(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(ascendedKey, PersistentDataType.BYTE);
    }

    private void setAscended(ItemStack item, boolean ascended) {
        if (item == null || !(item.getItemMeta() instanceof ArmorMeta meta)) return;
        if (ascended) {
            meta.getPersistentDataContainer().set(ascendedKey, PersistentDataType.BYTE, (byte) 1);
            if (!meta.getPersistentDataContainer().has(armorIdKey, PersistentDataType.STRING)) {
                meta.getPersistentDataContainer().set(armorIdKey, PersistentDataType.STRING, UUID.randomUUID().toString());
            }
        } else {
            meta.getPersistentDataContainer().remove(ascendedKey);
            // Keep piece ID for stable identity if the item is upgraded again later.
        }
        item.setItemMeta(meta);
    }

    private boolean isTrimRemnant(ItemStack item) {
        if (item == null || item.getType() != org.bukkit.Material.NETHER_STAR || !item.hasItemMeta()) return false;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(remnantIdKey, PersistentDataType.STRING);
        return pdc.has(remnantKey, PersistentDataType.BYTE) && id != null && issuedRemnantIds.contains(id);
    }

    private ItemStack createTrimRemnant() {
        ItemStack item = new ItemStack(org.bukkit.Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Trim Remnant", NamedTextColor.LIGHT_PURPLE));
        meta.lore(List.of(
                Component.text("A remnant of defeated trim power.", NamedTextColor.GRAY),
                Component.text("Use while wearing a matching full armor set", NamedTextColor.DARK_PURPLE),
                Component.text("to Ascend it.", NamedTextColor.DARK_PURPLE)));
        String id = UUID.randomUUID().toString();
        meta.getPersistentDataContainer().set(remnantKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(remnantIdKey, PersistentDataType.STRING, id);
        issuedRemnantIds.add(id);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onTrimRemnantUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack held = event.getItem();
        if (!isTrimRemnant(held)) return;

        ItemStack[] armor = player.getInventory().getArmorContents();
        String pattern = null;
        for (ItemStack piece : armor) {
            String current = patternOf(piece);
            if (current == null || (pattern != null && !pattern.equals(current))) {
                player.sendMessage(Component.text("Wear four armor pieces with the same trim pattern first.", NamedTextColor.RED));
                return;
            }
            pattern = current;
        }
        if (pattern == null || armor.length != 4) {
            player.sendMessage(Component.text("Wear a complete trimmed armor set first.", NamedTextColor.RED));
            return;
        }
        for (ItemStack piece : armor) {
            if (isAscended(piece)) {
                player.sendMessage(Component.text("This set already contains Ascended armor. The Remnant was not consumed.", NamedTextColor.RED));
                return;
            }
        }

        for (ItemStack piece : armor) setAscended(piece, true);
        player.getInventory().setArmorContents(armor);
        refreshPlayerItemLore(player);
        String remnantId = held.getItemMeta().getPersistentDataContainer().get(remnantIdKey, PersistentDataType.STRING);
        if (remnantId == null || !issuedRemnantIds.remove(remnantId)) {
            // Defensive rollback: do not grant Ascension if the remnant identity was already spent.
            for (ItemStack piece : armor) setAscended(piece, false);
            player.getInventory().setArmorContents(armor);
            refreshPlayerItemLore(player);
            player.sendMessage(Component.text("This Trim Remnant is invalid or has already been used.", NamedTextColor.RED));
            return;
        }
        player.getInventory().setItemInMainHand(null);
        player.sendMessage(Component.text("Your matching armor set has Ascended!", NamedTextColor.LIGHT_PURPLE));
        recordPlayerEvent(player.getUniqueId(), "Ascended a full " + pretty(pattern.substring(pattern.indexOf(':') + 1)) + " armor set");
        event.setCancelled(true);
        saveData();
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();

        // A player's own death resets only the kill-history pairs where they were the killer.
        // Pair format is "killerUUID:victimUUID"; victim-side history remains untouched.
        String killerPrefix = victim.getUniqueId() + ":";
        if (rewardedKillPairs.removeIf(pair -> pair.startsWith(killerPrefix))) {
            saveData();
        }

        Player killer = victim.getKiller();
        // Only the final lethal damage being caused by a player (or their projectile) qualifies.
        // A previous player hit followed by fire, lava, a fall, etc. must not count.
        if (!(victim.getLastDamageCause() instanceof EntityDamageByEntityEvent damage)) return;
        Object damager = damage.getDamager();
        Player finalAttacker = null;
        if (damager instanceof Player directPlayer) finalAttacker = directPlayer;
        else if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) finalAttacker = shooter;
        if (killer == null || finalAttacker == null || !killer.getUniqueId().equals(finalAttacker.getUniqueId())
                || killer.getUniqueId().equals(victim.getUniqueId())) return;

        // Downgrade worn Ascended pieces before the death drops are finalized.
        PlayerInventory inventory = victim.getInventory();
        ItemStack[] armor = inventory.getArmorContents();
        Set<String> downgradedIds = new HashSet<>();
        for (ItemStack piece : armor) {
            if (!isAscended(piece)) continue;
            if (piece.getItemMeta() != null) {
                String id = piece.getItemMeta().getPersistentDataContainer().get(armorIdKey, PersistentDataType.STRING);
                if (id != null) downgradedIds.add(id);
            }
            setAscended(piece, false);
        }
        inventory.setArmorContents(armor);
        refreshPlayerItemLore(victim);

        // Event drops can be clones of inventory items; downgrade matching identities too.
        for (ItemStack drop : event.getDrops()) {
            if (!isAscended(drop)) continue;
            String id = drop.getItemMeta().getPersistentDataContainer().get(armorIdKey, PersistentDataType.STRING);
            if (id == null || downgradedIds.contains(id)) {
                setAscended(drop, false);
                refreshItemLore(drop);
            }
        }

        String pair = killer.getUniqueId() + ":" + victim.getUniqueId();
        if (rewardedKillPairs.add(pair)) {
            ItemStack remnant = createTrimRemnant();
            Map<Integer, ItemStack> leftovers = killer.getInventory().addItem(remnant);
            leftovers.values().forEach(item -> killer.getWorld().dropItemNaturally(killer.getLocation(), item));
            killer.sendMessage(Component.text("You received a Trim Remnant for an eligible PvP kill.", NamedTextColor.LIGHT_PURPLE));
            recordPlayerEvent(killer.getUniqueId(), "Earned a Trim Remnant for defeating " + victim.getName());
            saveData();
        }
    }

    // ------------------------------------------------------------------ dynamic item lore

    /** Refreshes supported templates and trimmed armor in a player's inventory without replacing unrelated lore. */
    private void refreshPlayerItemLore(Player player) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        boolean storageChanged = false;
        for (int i = 0; i < storage.length; i++) {
            ItemStack item = storage[i];
            if (refreshItemLore(item)) {
                storageChanged = true;
            }
        }
        if (storageChanged) player.getInventory().setStorageContents(storage);

        ItemStack[] armor = player.getInventory().getArmorContents();
        boolean armorChanged = false;
        for (int i = 0; i < armor.length; i++) {
            if (refreshItemLore(armor[i])) armorChanged = true;
        }
        if (armorChanged) player.getInventory().setArmorContents(armor);

        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (refreshItemLore(offhand)) player.getInventory().setItemInOffHand(offhand);
    }

    /** Returns true only when this item metadata was changed. */
    private boolean refreshItemLore(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        String materialName = item.getType().name();
        String templateSuffix = "_ARMOR_TRIM_SMITHING_TEMPLATE";
        boolean template = materialName.endsWith(templateSuffix);
        // Some vanilla templates can report no custom metadata before this plugin adds lore.
        // Do not skip templates just because hasItemMeta() is initially false.
        if (!template && !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        String pattern = patternOf(item);
        if (template) {
            // Vanilla template material names are the trim pattern name plus this suffix.
            // Resolve through the same configured specs map used by the effect engine.
            String patternName = materialName.substring(0, materialName.length() - templateSuffix.length())
                    .toLowerCase(Locale.ROOT);
            pattern = "minecraft:" + patternName;
        }

        EffectSpec spec = pattern == null ? null : specs.get(pattern);
        if (spec == null || (!template && patternOf(item) == null)) {
            return removeManagedLore(item, meta);
        }

        String patternName = pretty(shortKey(pattern));
        String effectName = pretty(spec.type().key().value());
        int baseLevel = spec.amplifier() + 1;
        List<String> desired = new ArrayList<>();
        desired.add("TRIM PATTERN");
        desired.add("Pattern: " + patternName);
        desired.add("Effect: " + effectName);
        desired.add(" ");
        desired.add("SET PROGRESSION");
        desired.add("1 piece: No effect");
        desired.add("2-3 pieces: " + effectName + " " + roman(baseLevel));
        desired.add("4 matching pieces: " + effectName + " " + roman(baseLevel + 1));
        desired.add(" ");
        desired.add("ASCENSION");
        if (template) {
            desired.add("4 Ascended pieces: " + effectName + " " + roman(baseLevel + 2));
            desired.add("Apply this vanilla template to armor to add its pattern.");
        } else {
            desired.add("Status: " + (isAscended(item) ? "Ascended" : "Unascended"));
            desired.add("4 Ascended matching pieces: " + effectName + " " + roman(baseLevel + 2));
            desired.add(" ");
            desired.add("SET INFORMATION");
            desired.add("The full-set bonus needs 4 matching patterns.");
            desired.add("Mixed patterns can weaken effects or add penalties.");
        }

        List<Component> oldLore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        String previous = meta.getPersistentDataContainer().get(loreLinesKey, PersistentDataType.STRING);
        if (previous != null && !previous.isEmpty()) removeManagedLoreBlock(oldLore, previous);
        List<Component> newLore = new ArrayList<>(oldLore);
        for (String line : desired) newLore.add(formatLoreLine(line));
        String newStored = String.join("\n", desired);
        boolean same = previous != null && previous.equals(newStored)
                && meta.lore() != null && meta.lore().size() == newLore.size();
        if (same) {
            for (int i = 0; i < newLore.size(); i++) {
                if (!plainText.serialize(meta.lore().get(i)).equals(plainText.serialize(newLore.get(i)))) {
                    same = false;
                    break;
                }
            }
        }
        if (same) return false;
        meta.lore(newLore);
        meta.getPersistentDataContainer().set(loreLinesKey, PersistentDataType.STRING, newStored);
        item.setItemMeta(meta);
        return true;
    }

    private Component formatLoreLine(String line) {
        if (line.equals(" ")) return Component.text(" ", NamedTextColor.DARK_GRAY);
        return switch (line) {
            case "TRIM PATTERN", "SET PROGRESSION", "ASCENSION", "SET INFORMATION" ->
                    Component.text(line, NamedTextColor.AQUA);
            default -> Component.text(line, NamedTextColor.GRAY);
        };
    }

    /** Removes only a contiguous block that exactly matches the plugin-owned lore. */
    private boolean removeManagedLoreBlock(List<Component> lore, String storedLines) {
        String[] lines = storedLines.split("\n", -1);
        if (lines.length == 0 || lines.length > lore.size()) return false;
        for (int start = 0; start <= lore.size() - lines.length; start++) {
            boolean matches = true;
            for (int offset = 0; offset < lines.length; offset++) {
                if (!plainText.serialize(lore.get(start + offset)).equals(lines[offset])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                lore.subList(start, start + lines.length).clear();
                return true;
            }
        }
        return false;
    }

    private boolean removeManagedLore(ItemStack item, ItemMeta meta) {
        String previous = meta.getPersistentDataContainer().get(loreLinesKey, PersistentDataType.STRING);
        if (previous == null) return false;
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        removeManagedLoreBlock(lore, previous);
        if (lore.isEmpty()) meta.lore(null); else meta.lore(lore);
        meta.getPersistentDataContainer().remove(loreLinesKey);
        item.setItemMeta(meta);
        return true;
    }

    private void apply(Player player) {
        Map<PotionEffectType, Integer> effects = computeEffects(player);
        Set<PotionEffectType> tracked = applied.computeIfAbsent(player.getUniqueId(), id -> new HashSet<>());
        for (PotionEffectType old : new HashSet<>(tracked)) {
            if (!effects.containsKey(old)) {
                PotionEffect current = player.getPotionEffect(old);
                if (current != null && current.getDuration() <= durationTicks) player.removePotionEffect(old);
                tracked.remove(old);
            }
        }
        effects.forEach((type, amplifier) -> {
            player.addPotionEffect(new PotionEffect(type, durationTicks, amplifier, true, particles, icon));
            tracked.add(type);
        });
    }

    /** Immediately removes effects this plugin applied (leaves longer potions/beacons alone). */
    private void clear(Player player) {
        Set<PotionEffectType> types = applied.remove(player.getUniqueId());
        if (types == null) {
            return;
        }
        for (PotionEffectType type : types) {
            PotionEffect current = player.getPotionEffect(type);
            if (current != null && current.getDuration() <= durationTicks) {
                player.removePotionEffect(type);
            }
        }
    }

    private void clearAll() {
        for (Player player : getServer().getOnlinePlayers()) {
            clear(player);
        }
    }

    // ------------------------------------------------------------------ messages

    private Component msg(String path, TagResolver... resolvers) {
        Component prefix = mm.deserialize(getConfig().getString("messages.prefix", ""));
        Component body = mm.deserialize(getConfig().getString("messages." + path, path), TagResolver.resolver(resolvers));
        return prefix.append(body);
    }

    private TagResolver seasonTag() {
        return Placeholder.unparsed("season", String.valueOf(season));
    }

    private void announce(String path) {
        Component chat = msg(path, seasonTag());
        if (broadcastsEnabled) Bukkit.broadcast(chat);
        if (broadcastsEnabled && getConfig().getBoolean("smp.announce-title", true)) {
            // Keep announcements in the subtitle line; the main title stays empty.
            Component subtitleLine = mm.deserialize(
                    getConfig().getString("messages." + path, ""), seasonTag());
            Title subtitle = Title.title(Component.empty(), subtitleLine);
            for (Player player : getServer().getOnlinePlayers()) {
                player.showTitle(subtitle);
            }
        }
    }

    private void line(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text, NamedTextColor.GRAY));
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission(ADMIN)) {
            return true;
        }
        sender.sendMessage(msg("no-permission"));
        return false;
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { help(sender, label, 1); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(sender, label, args.length > 1 ? parsePage(args[1]) : 1);
            case "list" -> list(sender, args.length > 1 ? parsePage(args[1]) : 1);
            case "info", "inspect", "debug" -> {
                if (sub.equals("debug")) {
                    if (!requirePermission(sender, "trimeffects.admin.debug")) return true;
                } else if (args.length >= 2 && !sender.hasPermission("trimeffects.inspect") && !sender.hasPermission(ADMIN)) {
                    sender.sendMessage(msg("no-permission")); return true;
                }
                info(sender, args);
                if (sub.equals("debug") && args.length >= 2) line(sender, "Diagnostic: state=" + state + ", maintenance=" + maintenance + ", optedOut=" + optedOut.contains(Bukkit.getOfflinePlayer(args[1]).getUniqueId()));
            }
            case "status" -> status(sender);
            case "toggle" -> toggle(sender);
            case "preview", "compare" -> preview(sender, args, sub.equals("compare"));
            case "history" -> showHistory(sender, args.length > 1 ? parsePage(args[1]) : 1, false);
            case "version" -> line(sender, "TrimEffects " + getDescription().getVersion() + " | server " + Bukkit.getMinecraftVersion());
            case "reload" -> { if (requirePermission(sender, "trimeffects.admin.reload")) safeReload(sender); }
            case "restrict" -> { if (requirePermission(sender, "trimeffects.admin.restrict")) restrict(sender, args); }
            case "unrestrict" -> { if (requirePermission(sender, "trimeffects.admin.restrict")) unrestrict(sender, args); }
            case "restrictions" -> { if (requirePermission(sender, "trimeffects.admin.restrict")) listRestrictions(sender, args.length > 1 ? parsePage(args[1]) : 1); }
            case "give" -> { if (requirePermission(sender, "trimeffects.admin.items")) remnantCommand(sender, args, true); }
            case "take" -> { if (requirePermission(sender, "trimeffects.admin.items")) remnantCommand(sender, args, false); }
            case "force" -> { if (requirePermission(sender, "trimeffects.admin.player")) forceCommand(sender, args); }
            case "reset" -> { if (requirePermission(sender, "trimeffects.admin.player")) resetCommand(sender, args); }
            case "audit" -> { if (requirePermission(sender, "trimeffects.admin.audit")) showAudit(sender, args.length > 1 ? parsePage(args[1]) : 1); }
            case "diagnose" -> { if (requirePermission(sender, "trimeffects.admin.diagnose")) diagnose(sender); }
            case "admin" -> { if (requirePermission(sender, "trimeffects.admin.help")) help(sender, label, 2); }
            case "smp" -> smp(sender, args);
            default -> help(sender, label, 1);
        }
        return true;
    }

    private void help(CommandSender sender, String label, int page) {
        if (page <= 1) {
            sender.sendMessage(Component.text("TrimEffects — Player Commands", NamedTextColor.GOLD));
            line(sender, "/" + label + " help [page]");
            line(sender, "Show command help.");
            sender.sendMessage(Component.text(" "));
            line(sender, "/" + label + " list [page]");
            line(sender, "List trim patterns and their configured effects.");
            sender.sendMessage(Component.text(" "));
            line(sender, "/" + label + " info [player]");
            line(sender, "View trim effects and eligibility details.");
            sender.sendMessage(Component.text(" "));
            line(sender, "/" + label + " status");
            line(sender, "Show season state and your effect status.");
            sender.sendMessage(Component.text(" "));
            line(sender, "/" + label + " toggle");
            line(sender, "Toggle your personal effects when not overridden.");
            sender.sendMessage(Component.text(" "));
            line(sender, "/" + label + " preview <pattern>");
            line(sender, "Preview a pattern's effect progression.");
            line(sender, "/" + label + " compare <pattern>");
            line(sender, "Compare full-set and Ascended effects.");
            line(sender, "/" + label + " history [page]");
            line(sender, "View your recorded rewards and Ascension events.");
            line(sender, "/" + label + " version");
            line(sender, "Show plugin and server versions.");
            if (sender.hasPermission(ADMIN) || sender.hasPermission("trimeffects.admin.help")) {
                sender.sendMessage(Component.text(" "));
                line(sender, "Use /" + label + " help 2 for administrative commands.");
            }
        } else {
            if (!(sender.hasPermission(ADMIN) || sender.hasPermission("trimeffects.admin.help"))) {
                sender.sendMessage(msg("no-permission"));
                return;
            }
            sender.sendMessage(Component.text("TrimEffects — Administrative Commands", NamedTextColor.GOLD));
            line(sender, "/" + label + " reload");
            line(sender, "Validate and reload supported configuration and data.");
            sender.sendMessage(Component.text(" "));
            line(sender, "/" + label + " restrict <player> [duration|perm]");
            line(sender, "/" + label + " unrestrict <player>");
            line(sender, "/" + label + " restrictions [page]");
            sender.sendMessage(Component.text(" "));
            line(sender, "/" + label + " give|take <player> <amount>");
            line(sender, "/" + label + " force <player> <on|off|clear>");
            line(sender, "/" + label + " reset <player> <settings|all>");
            sender.sendMessage(Component.text(" "));
            line(sender, "/" + label + " audit [page] | diagnose | debug <player>");
            sender.sendMessage(Component.text(" "));
            line(sender, "/" + label + " smp <subcommand>");
            line(sender, "Use /" + label + " smp for lifecycle, records, and server controls.");
        }
    }

    private boolean requirePermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission) || sender.hasPermission(ADMIN)) return true;
        sender.sendMessage(msg("no-permission")); return false;
    }

    private int parsePage(String raw) {
        try { return Math.max(1, Integer.parseInt(raw)); } catch (NumberFormatException ignored) { return 1; }
    }

    /** Records a concise player-facing progression event in data.yml. */
    private void recordPlayerEvent(UUID playerId, String event) {
        List<String> entries = playerHistory.computeIfAbsent(playerId, ignored -> new ArrayList<>());
        entries.add("[" + java.time.Instant.now() + "] " + event);
        while (entries.size() > 100) entries.remove(0);
        saveData();
    }

    private void preview(CommandSender sender, String[] args, boolean compare) {
        if (args.length < 2) {
            line(sender, "Usage: /trimeffect " + (compare ? "compare" : "preview") + " <pattern>");
            line(sender, "Example: /trimeffect " + (compare ? "compare" : "preview") + " rib");
            return;
        }
        NamespacedKey key = parseKey(args[1]);
        EffectSpec spec = key == null ? null : specs.get(key.toString());
        if (spec == null) {
            line(sender, "Unknown or unsupported trim pattern: " + args[1]);
            line(sender, "Use /trimeffect list to view configured patterns.");
            return;
        }
        String name = pretty(shortKey(key.toString()));
        String effect = pretty(spec.type().key().value());
        int base = spec.amplifier();
        sender.sendMessage(Component.text("TrimEffects — " + (compare ? "Pattern Comparison" : "Pattern Preview"), NamedTextColor.GOLD));
        line(sender, "Pattern: " + name);
        line(sender, "Effect: " + effect);
        sender.sendMessage(Component.text(" "));
        line(sender, "2–3 matching pieces: " + effect + " " + roman(base + 1));
        line(sender, "4 matching pieces: " + effect + " " + roman(base + 2));
        line(sender, "4 matching Ascended pieces: " + effect + " " + roman(base + 3));
        if (compare) {
            sender.sendMessage(Component.text(" "));
            line(sender, "Ascension improves a complete matching set by one additional level.");
        }
    }

    private void showHistory(CommandSender sender, int page, boolean administrative) {
        UUID id;
        if (administrative) {
            if (sender instanceof Player player) id = player.getUniqueId();
            else { line(sender, "Specify a player history from in-game to view personal events."); return; }
        } else {
            if (!(sender instanceof Player player)) { sender.sendMessage(msg("player-only")); return; }
            id = player.getUniqueId();
        }
        List<String> entries = new ArrayList<>(playerHistory.getOrDefault(id, List.of()));
        java.util.Collections.reverse(entries);
        showPaged(sender, entries, page, "Your TrimEffects History");
        if (entries.isEmpty()) line(sender, "No TrimEffects events have been recorded yet.");
    }

    private void showAudit(CommandSender sender, int page) {
        List<String> entries = new ArrayList<>(auditLog);
        java.util.Collections.reverse(entries);
        showPaged(sender, entries, page, "Administrative Audit Log");
        if (entries.isEmpty()) line(sender, "No administrative actions have been recorded yet.");
    }

    private void diagnose(CommandSender sender) {
        sender.sendMessage(Component.text("TrimEffects — Diagnostics", NamedTextColor.GOLD));
        line(sender, "Plugin version: " + getDescription().getVersion());
        line(sender, "Server version: " + Bukkit.getVersion());
        line(sender, "Configured trim effects: " + specs.size());
        line(sender, "Season: " + season + " (" + state.name() + ")");
        line(sender, "Maintenance: " + (maintenance ? "enabled" : "disabled"));
        line(sender, "Data file: " + (dataFile != null && dataFile.isFile() && dataFile.canRead() ? "present/readable" : "missing or unreadable"));
        line(sender, "Persistent records: restrictions=" + restrictions.size()
                + ", season history=" + seasonHistory.size()
                + ", audit entries=" + auditLog.size()
                + ", player histories=" + playerHistory.size()
                + ", issued Remnants=" + issuedRemnantIds.size());
        if (specs.isEmpty()) line(sender, "Warning: no trim effects are configured successfully.");
    }

    private void showPaged(CommandSender sender, List<String> rows, int page, String heading) {
        final int pageSize = 8;
        int pages = Math.max(1, (rows.size() + pageSize - 1) / pageSize);
        int actual = Math.min(Math.max(1, page), pages);
        sender.sendMessage(Component.text("TrimEffects — " + heading, NamedTextColor.GOLD));
        line(sender, "Page " + actual + "/" + pages + " | Entries: " + rows.size());
        sender.sendMessage(Component.text(" "));
        if (rows.isEmpty()) {
            line(sender, "Nothing to display.");
            return;
        }
        int start = (actual - 1) * pageSize;
        for (int i = start; i < Math.min(start + pageSize, rows.size()); i++) {
            line(sender, "• " + rows.get(i));
        }
    }

    private void audit(CommandSender sender, String action) {
        String who = sender instanceof Player p ? p.getName() : "CONSOLE";
        auditLog.add("[" + java.time.Instant.now() + "] " + who + ": " + action);
        while (auditLog.size() > 200) auditLog.remove(0);
        saveData();
    }

    private void safeReload(CommandSender sender) {
        try {
            YamlConfiguration check = new YamlConfiguration();
            check.load(new File(getDataFolder(), "config.yml"));
            if (dataFile.isFile()) {
                YamlConfiguration dataCheck = new YamlConfiguration();
                dataCheck.load(dataFile);
            }
            ConfigurationSection patternSection = check.getConfigurationSection("patterns");
            if (patternSection == null || patternSection.getKeys(false).isEmpty()) {
                line(sender, "Reload rejected: config.yml has no configured patterns; current settings were kept."); return;
            }
            for (String pattern : patternSection.getKeys(false)) {
                ConfigurationSection row = patternSection.getConfigurationSection(pattern);
                if (row == null || parseKey(pattern) == null || parseKey(row.getString("effect", "")) == null
                        || Registry.EFFECT.get(parseKey(row.getString("effect", ""))) == null) {
                    line(sender, "Reload rejected: invalid pattern/effect entry '" + pattern + "'; current settings were kept."); return;
                }
            }
            loadConfig(); loadData(); audit(sender, "reloaded configuration and data");
            sender.sendMessage(msg("reloaded"));
        } catch (Exception ex) {
            getLogger().warning("Configuration reload rejected: " + ex.getMessage());
            line(sender, "Reload rejected; current running settings were kept. Check config.yml syntax.");
        }
    }

    private void status(CommandSender sender) {
        sender.sendMessage(Component.text("TrimEffects Status", NamedTextColor.GOLD));
        line(sender, "Season: " + season);
        line(sender, "State: " + state.name());
        line(sender, "Maintenance: " + (maintenance ? "Enabled" : "Disabled"));
        if (sender instanceof Player p) {
            String reason = ineligibleReason(p);
            line(sender, "Your effects: " + (reason == null ? "Enabled" : "Disabled — " + reason));
            line(sender, "Your preference: " + (Boolean.TRUE.equals(forcedToggles.get(p.getUniqueId())) ? "Forced on" : Boolean.FALSE.equals(forcedToggles.get(p.getUniqueId())) ? "Forced off" : optedOut.contains(p.getUniqueId()) ? "Opted out" : "Default"));
        } else {
            line(sender, "Restricted players: " + restrictions.size());
            line(sender, "Opted-out players: " + optedOut.size());
        }
    }


    private void remnantCommand(CommandSender sender, String[] args, boolean give) {
        if (args.length < 3) { line(sender, "Usage: /trimeffect " + (give ? "give" : "take") + " <online-player> <amount>"); return; }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { line(sender, "Target must be online so the item transfer cannot be lost."); return; }
        int amount;
        try { amount = Integer.parseInt(args[2]); } catch (NumberFormatException ex) { amount = -1; }
        if (amount < 1 || amount > 64) { line(sender, "Amount must be between 1 and 64."); return; }
        if (give) {
            int left = amount;
            // Each Remnant gets its own issued identity; never clone a single identity into a stack.
            while (left-- > 0) { ItemStack item = createTrimRemnant(); Map<Integer, ItemStack> overflow = target.getInventory().addItem(item); overflow.values().forEach(it -> target.getWorld().dropItemNaturally(target.getLocation(), it)); }
            audit(sender, "gave " + amount + " genuine Trim Remnant(s) to " + target.getName());
            line(sender, "Gave " + amount + " genuine Trim Remnant(s) to " + target.getName() + ".");
        } else {
            int remaining = amount;
            PlayerInventory inv = target.getInventory();
            for (int slot = 0; slot < inv.getSize() && remaining > 0; slot++) {
                ItemStack item = inv.getItem(slot);
                if (!isTrimRemnant(item)) continue;
                int take = Math.min(remaining, item.getAmount()); remaining -= take;
                if (item.hasItemMeta()) {
                    String remnantId = item.getItemMeta().getPersistentDataContainer().get(remnantIdKey, PersistentDataType.STRING);
                    if (remnantId != null && take == item.getAmount()) issuedRemnantIds.remove(remnantId);
                }
                if (take == item.getAmount()) inv.setItem(slot, null); else item.setAmount(item.getAmount() - take);
            }
            int removed = amount - remaining;
            audit(sender, "took " + removed + " genuine Trim Remnant(s) from " + target.getName());
            line(sender, "Removed " + removed + " genuine Trim Remnant(s) from " + target.getName() + ".");
        }
        saveData();
    }

    private void forceCommand(CommandSender sender, String[] args) {
        if (args.length < 3) { line(sender, "Usage: /trimeffect force <player> <on|off|clear>"); return; }
        OfflinePlayer target = findPlayer(args[1]); if (target == null) { line(sender, "Player must have joined this server before."); return; }
        UUID id = target.getUniqueId(); String value = args[2].toLowerCase(Locale.ROOT);
        if (value.equals("clear")) forcedToggles.remove(id);
        else if (value.equals("on")) forcedToggles.put(id, true);
        else if (value.equals("off")) forcedToggles.put(id, false);
        else { line(sender, "Expected on, off, or clear."); return; }
        Player online = target.getPlayer(); if (online != null) { if (Boolean.FALSE.equals(forcedToggles.get(id))) clear(online); else if (state == SmpState.ACTIVE && !maintenance) apply(online); }
        audit(sender, "set forced toggle for " + target.getName() + " to " + value); saveData(); line(sender, "Forced toggle for " + target.getName() + ": " + value + ".");
    }

    private void resetCommand(CommandSender sender, String[] args) {
        if (args.length < 3) { line(sender, "Usage: /trimeffect reset <player> <settings|all>"); return; }
        OfflinePlayer target = findPlayer(args[1]); if (target == null) { line(sender, "Player must have joined this server before."); return; }
        String setting = args[2].toLowerCase(Locale.ROOT); UUID id = target.getUniqueId();
        if (!setting.equals("settings") && !setting.equals("all")) { line(sender, "Supported reset target: settings (does not touch inventory, armor, or other player data)."); return; }
        optedOut.remove(id); forcedToggles.remove(id);
        audit(sender, "reset TrimEffects settings for " + target.getName()); saveData(); line(sender, "Reset TrimEffects preferences for " + target.getName() + "; inventory and Ascended armor were not changed.");
    }

    // ---- list

    private void list(CommandSender sender, int page) {
        Set<String> shown = new HashSet<>();
        Map<String, String> rows = new TreeMap<>();
        for (var pattern : Registry.TRIM_PATTERN) {
            String key = pattern.key().asString();
            shown.add(key);
            rows.put(shortKey(key), describe(specs.get(key)));
        }
        for (var entry : specs.entrySet()) { // mapped patterns not in the registry
            if (!shown.contains(entry.getKey())) {
                rows.put(shortKey(entry.getKey()), describe(entry.getValue()));
            }
        }
        List<String> output = rows.entrySet().stream().map(e -> e.getKey() + " -> " + e.getValue()).toList();
        int pageSize = 8, pages = Math.max(1, (output.size() + pageSize - 1) / pageSize), actual = Math.min(page, pages);
        sender.sendMessage(Component.text("TrimEffects — Trim Patterns", NamedTextColor.GOLD));
        line(sender, "State: " + stateLabel() + " | Page " + actual + "/" + pages);
        sender.sendMessage(Component.text(" "));
        for (int i = (actual - 1) * pageSize; i < Math.min(actual * pageSize, output.size()); i++) {
            String row = output.get(i); int split = row.indexOf(" -> ");
            sender.sendMessage(Component.text(" - ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(row.substring(0, split), NamedTextColor.AQUA))
                    .append(Component.text(" -> ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(row.substring(split + 4), row.substring(split + 4).startsWith("(") ? NamedTextColor.DARK_GRAY : NamedTextColor.WHITE)));
        }
    }

    private static String shortKey(String key) {
        return key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
    }

    private String describe(EffectSpec spec) {
        if (spec == null) {
            return "(no effect)";
        }
        String text = pretty(spec.type().key().value());
        if (spec.amplifier() > 0) {
            text += " " + roman(spec.amplifier() + 1);
        }
        text += " (2-3 matching: " + roman(spec.amplifier() + 1)
                + ", 4 matching: " + roman(spec.amplifier() + 2)
                + ", Ascended: " + roman(spec.amplifier() + 3) + ")";
        return text;
    }

    private static String pretty(String key) {
        StringBuilder out = new StringBuilder();
        for (String word : key.split("_")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(n);
        };
    }

    // ---- info / toggle

    private String matchingPieceSummary(Player player) {
        Map<String, Integer> counts = new TreeMap<>();
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            String pattern = patternOf(piece);
            if (pattern != null) counts.merge(shortKey(pattern), 1, Integer::sum);
        }
        if (counts.isEmpty()) return "0/4";
        return counts.entrySet().stream().map(e -> pretty(e.getKey()) + " " + e.getValue() + "/4")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private boolean isAscendedFullSet(Player player) {
        ItemStack[] armor = player.getInventory().getArmorContents();
        String pattern = null;
        int count = 0;
        for (ItemStack piece : armor) {
            String current = patternOf(piece);
            if (current == null) return false;
            if (pattern == null) pattern = current;
            if (!pattern.equals(current)) return false;
            count++;
        }
        return count == 4 && pattern != null && isFullyAscendedSet(armor, pattern);
    }

    private void info(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission("trimeffects.inspect") && !sender.hasPermission(ADMIN)
                    && !(args.length > 0 && args[0].equalsIgnoreCase("debug") && sender.hasPermission("trimeffects.admin.debug"))) {
                sender.sendMessage(msg("no-permission")); return;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(msg("player-not-found", Placeholder.unparsed("player", args[1])));
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage(msg("player-only"));
            return;
        }

        sender.sendMessage(Component.text("TrimEffects — " + target.getName(), NamedTextColor.GOLD));
        line(sender, "Season: " + season);
        line(sender, "State: " + state.name());
        line(sender, "Matching armor pieces: " + matchingPieceSummary(target));
        line(sender, "Ascended full set: " + (isAscendedFullSet(target) ? "Yes" : "No"));
        String reason = ineligibleReason(target);
        if (reason != null) {
            line(sender, "Currently receiving no effects: " + reason + ".");
        }
        Map<PotionEffectType, Integer> effects = computeEffects(target);
        sender.sendMessage(Component.text(" "));
        sender.sendMessage(Component.text("Calculated Effects", NamedTextColor.AQUA));
        if (effects.isEmpty()) {
            line(sender, "No trim effects are currently calculated for this armor.");
            return;
        }
        effects.forEach((type, amplifier) -> line(sender, "- " + pretty(type.key().value()) + " " + roman(amplifier + 1)));
    }

    private void toggle(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("player-only"));
            return;
        }
        if (forcedToggles.containsKey(player.getUniqueId())) {
            line(sender, "Your effect toggle is controlled by an administrator. Ask an operator to clear the override.");
            return;
        }
        if (optedOut.remove(player.getUniqueId())) {
            player.sendMessage(msg("toggle-on"));
        } else {
            optedOut.add(player.getUniqueId());
            clear(player);
            player.sendMessage(msg("toggle-off"));
        }
        saveData();
    }

    // ---- restrictions

    private void restrict(CommandSender sender, String[] args) {
        if (args.length < 2) {
            line(sender, "Usage: /trimeffect restrict <player> [duration|perm]");
            return;
        }
        OfflinePlayer target = findPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(msg("player-not-found", Placeholder.unparsed("player", args[1])));
            return;
        }

        long expiresAt = -1L; // permanent unless a duration is given
        String durationText = "permanently";
        if (args.length >= 3 && !isPermWord(args[2])) {
            long millis = parseDuration(args[2]);
            if (millis <= 0) {
                line(sender, "Bad duration '" + args[2] + "'. Use e.g. 30m, 2h, 1d12h, or 'perm'.");
                return;
            }
            expiresAt = System.currentTimeMillis() + millis;
            durationText = "for " + formatDuration(millis);
        }

        String name = target.getName() != null ? target.getName() : args[1];
        restrictions.put(target.getUniqueId(), new Restriction(name, expiresAt));
        saveData();

        audit(sender, "restricted " + name + " " + durationText);
        sender.sendMessage(msg("restricted",
                Placeholder.unparsed("player", name),
                Placeholder.unparsed("duration", durationText)));

        Player online = target.getPlayer();
        if (online != null) {
            clear(online);
            online.sendMessage(msg("you-restricted", Placeholder.unparsed("duration", durationText)));
        }
    }

    private void unrestrict(CommandSender sender, String[] args) {
        if (args.length < 2) {
            line(sender, "Usage: /trimeffect unrestrict <player>");
            return;
        }
        OfflinePlayer target = findPlayer(args[1]);
        UUID id = target != null ? target.getUniqueId() : null;
        if (id == null || restrictions.remove(id) == null) {
            sender.sendMessage(msg("not-restricted", Placeholder.unparsed("player", args[1])));
            return;
        }
        saveData();
        audit(sender, "unrestricted " + args[1]);
        sender.sendMessage(msg("unrestricted", Placeholder.unparsed("player", args[1])));
        Player online = target.getPlayer();
        if (online != null) {
            online.sendMessage(msg("you-unrestricted"));
        }
    }

    private void listRestrictions(CommandSender sender, int page) {
        purgeExpiredRestrictions();
        if (restrictions.isEmpty()) {
            line(sender, "No players are restricted.");
            return;
        }
        sender.sendMessage(Component.text("Restricted players", NamedTextColor.YELLOW));
        long now = System.currentTimeMillis();
        List<String> rows = restrictions.values().stream().map(r -> r.name() + ": " + (r.permanent() ? "permanent" : formatDuration(r.expiresAt() - now) + " left")).sorted().toList();
        showPaged(sender, rows, page, "Restricted players");
    }

    private static OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online : Bukkit.getOfflinePlayerIfCached(name);
    }

    private static boolean isPermWord(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.equals("perm") || lower.equals("permanent") || lower.equals("forever");
    }

    /** Parses "30m", "2h", "1d12h" etc. Returns milliseconds, or -1 if invalid. */
    private static long parseDuration(String raw) {
        String text = raw.toLowerCase(Locale.ROOT);
        Matcher matcher = DURATION_PART.matcher(text);
        long total = 0;
        int consumed = 0;
        while (matcher.find()) {
            if (matcher.start() != consumed) {
                return -1;
            }
            consumed = matcher.end();
            long amount = Long.parseLong(matcher.group(1));
            long unit = switch (matcher.group(2)) {
                case "s" -> 1_000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                default -> 604_800_000L; // w
            };
            total += amount * unit;
        }
        return consumed == text.length() && total > 0 ? total : -1;
    }

    private static String formatDuration(long millis) {
        long seconds = Math.max(1, millis / 1000);
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long secs = seconds % 60;
        List<String> parts = new ArrayList<>();
        if (days > 0) parts.add(days + "d");
        if (hours > 0) parts.add(hours + "h");
        if (minutes > 0) parts.add(minutes + "m");
        if (secs > 0 && days == 0 && hours == 0) parts.add(secs + "s");
        return String.join(" ", parts);
    }

    // ---- smp

    private String stateLabel() {
        return switch (state) {
            case ACTIVE -> "SMP active, season " + season;
            case PAUSED -> "SMP paused, season " + season;
            case INACTIVE -> "SMP inactive, season " + season;
        };
    }

    private void smp(CommandSender sender, String[] args) {
        if (args.length < 2) { line(sender, "Usage: /trimeffect smp <start|end|restart|pause|resume|status|season|history|announce|broadcast|rules|players|restricted|optedout|settings|maintenance|config>"); return; }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "start" -> {
                if (!requirePermission(sender, "trimeffects.admin.smp.lifecycle")) return;
                if (maintenance) { line(sender, "Cannot start a season while maintenance mode is enabled."); return; }
                if (state == SmpState.ACTIVE) { sender.sendMessage(msg("smp-already-active", seasonTag())); return; }
                if (state == SmpState.PAUSED) { resume(sender); return; }
                state = SmpState.ACTIVE; recordSeason("START season " + season); saveData(); audit(sender, "started season " + season); announce("smp-started");
            }
            case "end" -> {
                if (!requirePermission(sender, "trimeffects.admin.smp.lifecycle")) return;
                if (state == SmpState.INACTIVE) { sender.sendMessage(msg("smp-not-active")); return; }
                recordSeason("END season " + season); state = SmpState.INACTIVE; saveData(); clearAll(); audit(sender, "ended season " + season); announce("smp-ended");
            }
            case "restart" -> {
                if (!requirePermission(sender, "trimeffects.admin.smp.lifecycle")) return;
                if (state != SmpState.INACTIVE) recordSeason("END (restart) season " + season);
                int next = season + 1;
                if (next < 1) { line(sender, "Season number overflow; restart cancelled."); return; }
                season = next;
                if (getConfig().getBoolean("smp.clear-restrictions-on-restart", true)) restrictions.clear();
                state = maintenance ? SmpState.INACTIVE : SmpState.ACTIVE;
                recordSeason("START season " + season + (maintenance ? " (maintenance; remains inactive)" : ""));
                saveData(); clearAll(); audit(sender, "restarted into season " + season);
                if (!maintenance) announce("smp-restarted"); else line(sender, "Season advanced to " + season + "; it remains inactive because maintenance mode is enabled.");
            }
            case "pause" -> {
                if (!requirePermission(sender, "trimeffects.admin.smp.lifecycle")) return;
                if (state != SmpState.ACTIVE) { sender.sendMessage(msg("smp-not-active")); return; }
                state = SmpState.PAUSED; recordSeason("PAUSE season " + season); saveData(); clearAll(); audit(sender, "paused season " + season); announce("smp-paused");
            }
            case "resume" -> { if (requirePermission(sender, "trimeffects.admin.smp.lifecycle")) resume(sender); }
            case "status" -> {
                sender.sendMessage(Component.text("TrimEffects — SMP Status", NamedTextColor.GOLD));
                line(sender, "Season: " + season);
                line(sender, "State: " + state.name());
                line(sender, "Maintenance: " + (maintenance ? "Enabled" : "Disabled"));
                line(sender, "Online players: " + Bukkit.getOnlinePlayers().size());
                line(sender, "Restricted players: " + restrictions.size());
                line(sender, "Opted-out players: " + optedOut.size());
            }
            case "season" -> {
                if (!requirePermission(sender, "trimeffects.admin.smp.season")) return;
                if (args.length < 3) { line(sender, "Current season: " + season + ". Usage: /trimeffect smp season <number>"); return; }
                try { int value = Integer.parseInt(args[2]); if (value < 1) throw new NumberFormatException(); season = value; }
                catch (NumberFormatException e) { line(sender, "Season must be a positive whole number."); return; }
                recordSeason("SET season number to " + season); saveData(); audit(sender, "set season number to " + season); sender.sendMessage(msg("smp-season-set", seasonTag()));
            }
            case "history" -> { if (!requirePermission(sender, "trimeffects.admin.smp.players")) return; showPaged(sender, seasonHistory, args.length > 2 ? parsePage(args[2]) : 1, "SMP season history"); }
            case "announce" -> { if (!requirePermission(sender, "trimeffects.admin.smp.announce")) return; if (args.length < 3) { line(sender, "Usage: /trimeffect smp announce <message>"); return; } String message = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)); Bukkit.broadcast(Component.text("[TrimEffects] " + message, NamedTextColor.GOLD)); audit(sender, "broadcast SMP announcement"); }
            case "broadcast" -> { if (!requirePermission(sender, "trimeffects.admin.smp.announce")) return; if (args.length < 3 || !(args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("off"))) { line(sender, "Usage: /trimeffect smp broadcast <on|off>"); return; } broadcastsEnabled = args[2].equalsIgnoreCase("on"); saveData(); audit(sender, "set automatic broadcasts " + (broadcastsEnabled ? "on" : "off")); line(sender, "Automatic TrimEffects broadcasts: " + (broadcastsEnabled ? "on" : "off")); }
            case "rules" -> {
                sender.sendMessage(Component.text("TrimEffects — SMP Rules", NamedTextColor.GOLD));
                line(sender, "Effects only apply during an ACTIVE season.");
                line(sender, "2–3 matching trims grant the base tier.");
                line(sender, "4 matching trims improve the effect.");
                line(sender, "A fully Ascended matching set improves it further.");
                line(sender, "Mixed patterns can cause penalties.");
                line(sender, "Restrictions and maintenance can disable effects.");
            }
            case "players" -> { if (!requirePermission(sender, "trimeffects.admin.smp.players")) return; List<String> rows = Bukkit.getOnlinePlayers().stream().map(p -> p.getName() + " — " + (ineligibleReason(p) == null ? "eligible" : ineligibleReason(p))).sorted().toList(); showPaged(sender, rows, args.length > 2 ? parsePage(args[2]) : 1, "Online TrimEffects players"); }
            case "restricted" -> { if (!requirePermission(sender, "trimeffects.admin.smp.players")) return; listRestrictions(sender, args.length > 2 ? parsePage(args[2]) : 1); }
            case "optedout" -> { if (!requirePermission(sender, "trimeffects.admin.smp.players")) return; List<String> rows = optedOut.stream().map(id -> { OfflinePlayer op = Bukkit.getOfflinePlayer(id); return op.getName() == null ? id.toString() : op.getName(); }).sorted().toList(); showPaged(sender, rows, args.length > 2 ? parsePage(args[2]) : 1, "Opted-out players"); }
            case "settings" -> {
                if (!requirePermission(sender, "trimeffects.admin.smp.settings")) return;
                sender.sendMessage(Component.text("TrimEffects — SMP Settings", NamedTextColor.GOLD));
                line(sender, "Season: " + season);
                line(sender, "State: " + state.name());
                line(sender, "Maintenance: " + (maintenance ? "Enabled" : "Disabled"));
                line(sender, "Automatic broadcasts: " + (broadcastsEnabled ? "Enabled" : "Disabled"));
                line(sender, "Clear restrictions on restart: " + getConfig().getBoolean("smp.clear-restrictions-on-restart", true));
            }
            case "maintenance" -> { if (!requirePermission(sender, "trimeffects.admin.smp.maintenance")) return; if (args.length < 3 || !(args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("off"))) { line(sender, "Usage: /trimeffect smp maintenance <on|off>"); return; } maintenance = args[2].equalsIgnoreCase("on"); if (maintenance) clearAll(); saveData(); audit(sender, "set maintenance " + (maintenance ? "on" : "off")); line(sender, "Maintenance mode " + (maintenance ? "enabled" : "disabled") + ". Season state remains " + state + "."); }
            case "config" -> { if (!requirePermission(sender, "trimeffects.admin.smp.settings")) return; if (args.length < 4) { line(sender, "Usage: /trimeffect smp config <setting> <value>. Supported: broadcasts, clear-restrictions-on-restart"); return; } String key = args[2].toLowerCase(Locale.ROOT), value = args[3].toLowerCase(Locale.ROOT); if (!value.equals("on") && !value.equals("off") && !value.equals("true") && !value.equals("false")) { line(sender, "Value must be on/off or true/false."); return; } boolean enabled = value.equals("on") || value.equals("true"); if (key.equals("broadcasts")) broadcastsEnabled = enabled; else if (key.equals("clear-restrictions-on-restart")) getConfig().set("smp.clear-restrictions-on-restart", enabled); else { line(sender, "Unsupported live setting: " + key); return; } if (key.equals("clear-restrictions-on-restart")) saveConfig(); saveData(); audit(sender, "changed SMP setting " + key + "=" + enabled); line(sender, "Updated " + key + " to " + enabled + "."); }
            default -> line(sender, "Unknown SMP command. Use /trimeffect smp status or /trimeffect help 2.");
        }
    }

    private void recordSeason(String event) {
        seasonHistory.add("[" + java.time.Instant.now() + "] " + event);
        while (seasonHistory.size() > 300) seasonHistory.remove(0);
    }

    private void resume(CommandSender sender) {
        if (maintenance) { line(sender, "Cannot resume while maintenance mode is enabled."); return; }
        if (state != SmpState.PAUSED) { sender.sendMessage(msg("smp-not-paused")); return; }
        state = SmpState.ACTIVE; recordSeason("RESUME season " + season); saveData(); audit(sender, "resumed season " + season); announce("smp-resumed");
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission(ADMIN) || sender.hasPermission("trimeffects.admin.help")
                || sender.hasPermission("trimeffects.admin.reload") || sender.hasPermission("trimeffects.admin.debug")
                || sender.hasPermission("trimeffects.admin.restrict") || sender.hasPermission("trimeffects.admin.items")
                || sender.hasPermission("trimeffects.admin.player") || sender.hasPermission("trimeffects.admin.audit")
                || sender.hasPermission("trimeffects.admin.diagnose") || sender.hasPermission("trimeffects.admin.smp.lifecycle")
                || sender.hasPermission("trimeffects.admin.smp.season") || sender.hasPermission("trimeffects.admin.smp.announce")
                || sender.hasPermission("trimeffects.admin.smp.players") || sender.hasPermission("trimeffects.admin.smp.settings")
                || sender.hasPermission("trimeffects.admin.smp.maintenance");
        if (args.length == 1) return filter(List.of("help", "list", "info", "inspect", "status", "toggle", "preview", "compare", "history", "version", "admin", "reload", "restrict", "unrestrict", "restrictions", "give", "take", "force", "reset", "audit", "diagnose", "debug", "smp").stream().filter(s -> admin || List.of("help", "list", "info", "status", "toggle", "preview", "compare", "history", "version").contains(s)).toList(), args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) return switch (sub) {
            case "info", "inspect", "debug", "restrict", "unrestrict", "give", "take", "force", "reset" -> filter(onlineNames(), args[1]);
            case "preview", "compare" -> filter(specs.keySet().stream().map(TrimEffectsPlugin::shortKey).toList(), args[1]);
            case "smp" -> filter(List.of("start", "end", "restart", "pause", "resume", "status", "season", "history", "announce", "broadcast", "rules", "players", "restricted", "optedout", "settings", "maintenance", "config"), args[1]);
            case "help", "list", "history", "audit", "restrictions" -> filter(List.of("1", "2", "3"), args[1]);
            default -> List.of();
        };
        if (args.length == 3) return switch (sub) {
            case "restrict" -> filter(List.of("perm", "30m", "1h", "1d", "7d"), args[2]);
            case "give", "take" -> filter(List.of("1", "2", "4", "8", "16", "32", "64"), args[2]);
            case "force" -> filter(List.of("on", "off", "clear"), args[2]);
            case "reset" -> filter(List.of("settings", "all"), args[2]);
            case "smp" -> filter(List.of("on", "off", "1", "2"), args[2]);
            default -> List.of();
        };
        if (args.length == 4 && sub.equals("smp") && args[1].equalsIgnoreCase("config")) return filter(List.of("broadcasts", "clear-restrictions-on-restart"), args[2]);
        if (args.length == 5 && sub.equals("smp") && args[1].equalsIgnoreCase("config")) return filter(List.of("on", "off", "true", "false"), args[4]);
        return List.of();
    }

    private static List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private static List<String> filter(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
