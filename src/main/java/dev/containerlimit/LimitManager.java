package dev.containerlimit;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Holds the configured limits per container type and counts restricted items, including items
 * nested inside shulker boxes / bundles / other container items.
 * <p>
 * A limit ("rule") can cover one material or a group of materials (a Minecraft tag like WOOL,
 * or a wildcard like *_WOOL). A group limit is the combined total of all its materials.
 */
public final class LimitManager {

    public static final String BYPASS_PERMISSION = "containerlimit.bypass";

    /** Guards against absurdly deep nesting (bundle in bundle in bundle...). */
    private static final int MAX_DEPTH = 8;

    /** The player's own inventory screens are never containers that can be limited. */
    private static final Set<InventoryType> UNSUPPORTED = Set.of(
            InventoryType.PLAYER, InventoryType.CRAFTING, InventoryType.CREATIVE);

    private final ContainerLimit plugin;
    /** Config key for limits on what can go inside a bundle. */
    public static final String BUNDLE = "BUNDLE";

    private final Map<InventoryType, LimitTable> limits = new HashMap<>();
    private LimitTable bundleLimits;
    /**
     * Every custom item defined anywhere in the config. An item matching one of these is a custom
     * item everywhere, so it's never counted as its base material (a heart is not a NETHER_STAR).
     */
    private final List<CustomItemMatcher> allCustomMatchers = new ArrayList<>();
    private boolean countContainerContents;
    private boolean ignorePluginInventories;

    public LimitManager(ContainerLimit plugin) {
        this.plugin = plugin;
    }

    /**
     * Loads the limits from the config.
     *
     * @return problems found in the config (unknown materials etc.), empty if everything loaded
     */
    public List<String> load() {
        List<String> problems = new ArrayList<>();
        limits.clear();
        bundleLimits = null;
        allCustomMatchers.clear();
        FileConfiguration config = plugin.getConfig();
        countContainerContents = config.getBoolean("count-container-contents", true);
        ignorePluginInventories = config.getBoolean("ignore-plugin-inventories", true);

        // contains(path, true) ignores the defaults inside the jar, so limits only ever
        // come from the server's own config.yml
        if (config.contains("containers", true) && config.isConfigurationSection("containers")) {
            ConfigurationSection containers = config.getConfigurationSection("containers");
            for (String typeKey : containers.getKeys(false)) {
                ConfigurationSection section = containers.getConfigurationSection(typeKey);
                if (section == null) {
                    problems.add("containers." + typeKey + " must be a list of item: amount");
                    continue;
                }
                // Bundles are items, not an InventoryType, so they get their own table
                if (typeKey.equalsIgnoreCase(BUNDLE)) {
                    bundleLimits = loadSection(BUNDLE, section, problems);
                    continue;
                }
                InventoryType type = parseType(typeKey, problems);
                if (type == null) continue;
                LimitTable table = loadSection(type.name(), section, problems);
                if (table != null) limits.put(type, table);
            }
        }

        // Configs from version 1.0.0 had a single "limits" section for ender chests
        if (config.contains("limits", true) && config.isConfigurationSection("limits")
                && !limits.containsKey(InventoryType.ENDER_CHEST)) {
            LimitTable table = loadSection(InventoryType.ENDER_CHEST.name(), config.getConfigurationSection("limits"), problems);
            if (table != null) limits.put(InventoryType.ENDER_CHEST, table);
        }

        registerBypassPermissions();
        problems.forEach(problem -> plugin.getLogger().warning(problem));
        return problems;
    }

    private InventoryType parseType(String key, List<String> problems) {
        InventoryType type;
        try {
            type = InventoryType.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.add("Unknown container type: " + key);
            return null;
        }
        if (UNSUPPORTED.contains(type)) {
            problems.add("Container type " + key + " can't be limited");
            return null;
        }
        return type;
    }

    /** @return the loaded limits, or null if the section has none */
    private LimitTable loadSection(String sectionName, ConfigurationSection section, List<String> problems) {
        List<Rule> rules = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            if (!section.isInt(key) || section.getInt(key) < 0) {
                problems.add(sectionName + "." + key + ": limit must be a whole number, 0 or higher");
                continue;
            }
            if (CustomItemMatcher.isCustomKey(key)) {
                CustomItemMatcher matcher = CustomItemMatcher.parse(key);
                if (matcher == null) {
                    problems.add(sectionName + ": invalid custom item '" + key
                            + "', use custom:<plugin>:<tag>=<value> (hold the item and run /climit hand)");
                    continue;
                }
                allCustomMatchers.add(matcher);
                rules.add(new Rule(key, matcher.displayName(), Set.of(), matcher, section.getInt(key)));
                continue;
            }
            Set<Material> materials = resolveMaterials(key);
            if (materials.isEmpty()) {
                problems.add(sectionName + ": unknown item, tag or pattern '" + key + "'");
                continue;
            }
            rules.add(new Rule(key, displayName(key, materials), Set.copyOf(materials), null, section.getInt(key)));
        }
        return rules.isEmpty() ? null : new LimitTable(rules);
    }

    /**
     * Turns a config key into materials:
     * <ul>
     *     <li>{@code ELYTRA} - a single material</li>
     *     <li>{@code *_WOOL}, {@code *SHULKER_BOX} - wildcard over material names</li>
     *     <li>{@code WOOL}, {@code #minecraft:beds} - a Minecraft item/block tag</li>
     * </ul>
     */
    private static Set<Material> resolveMaterials(String key) {
        String upper = key.trim().toUpperCase(Locale.ROOT);

        if (upper.contains("*")) {
            Pattern pattern = Pattern.compile(
                    ("\\Q" + upper + "\\E").replace("*", "\\E.*\\Q"));
            Set<Material> result = new HashSet<>();
            for (Material material : Material.values()) {
                if (!material.name().startsWith("LEGACY_") && material.isItem()
                        && pattern.matcher(material.name()).matches()) {
                    result.add(material);
                }
            }
            return result;
        }

        if (!upper.startsWith("#")) {
            Material material = Material.matchMaterial(upper);
            if (material != null) return Set.of(material);
        }

        String tagName = key.trim().replaceFirst("^#", "").toLowerCase(Locale.ROOT);
        NamespacedKey tagKey = NamespacedKey.fromString(tagName);
        if (tagKey == null) return Set.of();
        Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, tagKey, Material.class);
        if (tag == null) tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, tagKey, Material.class);
        if (tag == null) return Set.of();

        Set<Material> result = new HashSet<>();
        for (Material material : tag.getValues()) {
            if (material.isItem()) result.add(material);
        }
        return result;
    }

    private static String displayName(String key, Set<Material> materials) {
        if (materials.size() == 1) {
            return prettyName(materials.iterator().next());
        }
        String cleaned = key.replace("*", "").replaceFirst("^#", "").replaceFirst("(?i)^minecraft:", "");
        return capitalize(cleaned.replaceAll("^_+|_+$", ""));
    }

    /**
     * Unregistered permissions default to true for ops, so the per-container bypass
     * permissions are registered here to make them default to false.
     */
    private void registerBypassPermissions() {
        List<String> sections = new ArrayList<>();
        limits.keySet().forEach(type -> sections.add(type.name()));
        if (bundleLimits != null) sections.add(BUNDLE);

        PluginManager pluginManager = plugin.getServer().getPluginManager();
        for (String section : sections) {
            String name = bypassPermission(section);
            if (pluginManager.getPermission(name) == null) {
                pluginManager.addPermission(new Permission(name,
                        "Ignore limits in " + containerName(section), PermissionDefault.FALSE));
            }
        }
    }

    /** @param section a container type name or {@link #BUNDLE} */
    public static String bypassPermission(String section) {
        return BYPASS_PERMISSION + "." + section.toLowerCase(Locale.ROOT);
    }

    public boolean canBypass(Permissible permissible, InventoryType type) {
        return canBypass(permissible, type.name());
    }

    /** @param section a container type name or {@link #BUNDLE} */
    public boolean canBypass(Permissible permissible, String section) {
        return permissible.hasPermission(BYPASS_PERMISSION) || permissible.hasPermission(bypassPermission(section));
    }

    /** Limits on what can go inside a bundle, or null if there are none. */
    public LimitTable bundleLimits() {
        return bundleLimits;
    }

    /** Limits for this container type, or null if the type has none. */
    public LimitTable limitsFor(InventoryType type) {
        return limits.get(type);
    }

    /**
     * Limits for an opened inventory, or null if it has none. Custom menus made by other plugins
     * (which have no real block or entity behind them) are skipped when ignore-plugin-inventories is on.
     */
    public LimitTable limitsFor(Inventory inventory) {
        if (inventory == null) return null;
        LimitTable table = limits.get(inventory.getType());
        if (table == null || !ignorePluginInventories || inventory.getType() == InventoryType.ENDER_CHEST) {
            return table;
        }
        InventoryHolder holder = inventory.getHolder();
        boolean real = holder instanceof BlockState || holder instanceof DoubleChest || holder instanceof Entity;
        return real ? table : null;
    }

    /** Section name (container type or BUNDLE) -> (config key -> limit), sorted, for /climit list. */
    public Map<String, Map<String, Integer>> getAllLimits() {
        Map<String, LimitTable> tables = new java.util.TreeMap<>();
        limits.forEach((type, table) -> tables.put(type.name(), table));
        if (bundleLimits != null) tables.put(BUNDLE, bundleLimits);

        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        tables.forEach((section, table) -> {
            Map<String, Integer> rules = new LinkedHashMap<>();
            for (Rule rule : table.rules()) {
                String suffix = rule.custom() != null ? " (custom item)"
                        : rule.materials().size() > 1 ? " (" + rule.materials().size() + " items)" : "";
                rules.put(rule.key() + suffix, rule.limit());
            }
            result.put(section, Collections.unmodifiableMap(rules));
        });
        return Collections.unmodifiableMap(result);
    }

    public int totalLimitCount() {
        int bundleCount = bundleLimits == null ? 0 : bundleLimits.rules().size();
        return bundleCount + limits.values().stream().mapToInt(table -> table.rules().size()).sum();
    }

    /** Rule counts for only the top-level material of a stack, ignoring nested contents. */
    public Map<Rule, Integer> countTopLevel(LimitTable table, ItemStack item) {
        Map<Rule, Integer> out = new HashMap<>();
        if (!isAir(item)) {
            for (Rule rule : rulesFor(table, item)) {
                out.merge(rule, item.getAmount(), Integer::sum);
            }
        }
        return out;
    }

    public Map<Rule, Integer> countItems(LimitTable table, ItemStack[] items) {
        Map<Rule, Integer> out = new HashMap<>();
        for (ItemStack item : items) {
            if (!isAir(item)) {
                addCounts(table, item, item.getAmount(), out, 0);
            }
        }
        return out;
    }

    private void addCounts(LimitTable table, ItemStack item, int multiplier, Map<Rule, Integer> out, int depth) {
        for (Rule rule : rulesFor(table, item)) {
            out.merge(rule, multiplier, Integer::sum);
        }
        if (!countContainerContents || depth >= MAX_DEPTH || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta blockStateMeta && blockStateMeta.hasBlockState()) {
            BlockState state = blockStateMeta.getBlockState();
            if (state instanceof Container container) {
                for (ItemStack child : container.getInventory().getContents()) {
                    if (!isAir(child)) {
                        addCounts(table, child, multiplier * child.getAmount(), out, depth + 1);
                    }
                }
            }
        }
        if (meta instanceof BundleMeta bundleMeta && bundleMeta.hasItems()) {
            for (ItemStack child : bundleMeta.getItems()) {
                if (!isAir(child)) {
                    addCounts(table, child, multiplier * child.getAmount(), out, depth + 1);
                }
            }
        }
    }

    /**
     * Works out how many units (items) of {@code moving} may be put into {@code target}.
     *
     * @param units   how many items of the stack the action wants to move in
     * @param leaving rule counts that leave the target in the same action (e.g. a swap)
     */
    public Result allowedUnits(LimitTable table, Inventory target, ItemStack moving, int units,
                               Map<Rule, Integer> leaving) {
        return allowedUnits(table, target.getContents(), moving, units, leaving);
    }

    /** Same as above, for a container that isn't an Inventory (e.g. the items inside a bundle). */
    public Result allowedUnits(LimitTable table, ItemStack[] targetContents, ItemStack moving, int units,
                               Map<Rule, Integer> leaving) {
        if (isAir(moving) || units <= 0) {
            return new Result(Math.max(units, 0), null);
        }
        Map<Rule, Integer> perUnit = new HashMap<>();
        addCounts(table, moving, 1, perUnit, 0);
        if (perUnit.isEmpty()) {
            return new Result(units, null);
        }

        Map<Rule, Integer> current = countItems(table, targetContents);
        int allowed = units;
        Rule limiting = null;
        for (Map.Entry<Rule, Integer> entry : perUnit.entrySet()) {
            Rule rule = entry.getKey();
            int perItem = entry.getValue();
            int leavingAmount = leaving.getOrDefault(rule, 0);
            if ((long) perItem * units <= leavingAmount) {
                continue; // net change is not an increase, never block that
            }
            int available = rule.limit() - (current.getOrDefault(rule, 0) - leavingAmount);
            int fits = available <= 0 ? 0 : available / perItem;
            if (fits < allowed) {
                allowed = fits;
                limiting = rule;
            }
        }
        return new Result(allowed, limiting);
    }

    /** First limit broken by a whole set of items (e.g. a pre-filled shulker box), or null if none. */
    public Result firstViolation(LimitTable table, ItemStack[] items) {
        for (Map.Entry<Rule, Integer> entry : countItems(table, items).entrySet()) {
            if (entry.getValue() > entry.getKey().limit()) {
                return new Result(0, entry.getKey());
            }
        }
        return null;
    }

    public static boolean isAir(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    public static String prettyName(Material material) {
        return capitalize(material.name());
    }

    /** e.g. "ender chest", used in messages as "in this {container}". */
    public static String containerName(InventoryType type) {
        return containerName(type.name());
    }

    /** @param section a container type name or {@link #BUNDLE} */
    public static String containerName(String section) {
        return section.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String capitalize(String enumName) {
        String[] words = enumName.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    /**
     * One configured limit. All its materials share the one total.
     * Uses identity equality on purpose: it's a map key on every click.
     */
    public static final class Rule {
        private final String key;
        private final String displayName;
        private final Set<Material> materials;
        private final CustomItemMatcher custom;
        private final int limit;

        /**
         * @param key         the key as written in the config, unique within a container type
         * @param displayName name used in chat messages
         * @param materials   materials covered, empty for a custom item rule
         * @param custom      the custom item this rule is for, or null for a material rule
         */
        Rule(String key, String displayName, Set<Material> materials, CustomItemMatcher custom, int limit) {
            this.key = key;
            this.displayName = displayName;
            this.materials = materials;
            this.custom = custom;
            this.limit = limit;
        }

        public CustomItemMatcher custom() {
            return custom;
        }

        public String key() {
            return key;
        }

        public String displayName() {
            return displayName;
        }

        public Set<Material> materials() {
            return materials;
        }

        public int limit() {
            return limit;
        }
    }

    /** All rules for one container type, indexed by material. */
    public static final class LimitTable {
        private final List<Rule> rules;
        private final Map<Material, List<Rule>> byMaterial = new HashMap<>();
        private final List<Rule> customRules = new ArrayList<>();

        LimitTable(List<Rule> rules) {
            this.rules = List.copyOf(rules);
            for (Rule rule : rules) {
                if (rule.custom() != null) {
                    customRules.add(rule);
                }
                for (Material material : rule.materials()) {
                    byMaterial.computeIfAbsent(material, m -> new ArrayList<>()).add(rule);
                }
            }
        }

        public List<Rule> rules() {
            return rules;
        }
    }

    /**
     * The rules of {@code table} that an item counts towards. A custom item (one matching any custom
     * entry in the whole config) only counts towards custom rules, never towards its base material.
     */
    private List<Rule> rulesFor(LimitTable table, ItemStack item) {
        List<Rule> materialRules = table.byMaterial.getOrDefault(item.getType(), List.of());
        if (allCustomMatchers.isEmpty() || !item.hasItemMeta()) {
            return materialRules;
        }

        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        boolean isCustom = false;
        for (CustomItemMatcher matcher : allCustomMatchers) {
            if (matcher.matches(data)) {
                isCustom = true;
                break;
            }
        }
        if (!isCustom) {
            return materialRules;
        }

        List<Rule> result = new ArrayList<>();
        for (Rule rule : table.customRules) {
            if (rule.custom().matches(data)) {
                result.add(rule);
            }
        }
        return result;
    }

    /**
     * @param allowed  how many items may go in
     * @param limiting the rule that capped the amount, or null when nothing was capped
     */
    public record Result(int allowed, Rule limiting) {
    }
}
