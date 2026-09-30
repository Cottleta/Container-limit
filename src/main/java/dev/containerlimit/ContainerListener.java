package dev.containerlimit;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.containerlimit.LimitManager.isAir;

/**
 * Blocks every way of getting restricted items into a limited container.
 * <p>
 * Note: this deliberately never calls methods on InventoryView, because it changed from a class
 * to an interface in 1.21 and calling it would break the plugin on either old or new servers.
 * {@link InventoryClickEvent#getInventory()} already returns the top inventory.
 */
public final class ContainerListener implements Listener {

    private static final long MESSAGE_COOLDOWN_MS = 500;

    private final ContainerLimit plugin;
    private final LimitManager limits;
    private final Map<UUID, Long> lastMessage = new HashMap<>();

    public ContainerListener(ContainerLimit plugin, LimitManager limits) {
        this.plugin = plugin;
        this.limits = limits;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getInventory();
        LimitManager.LimitTable table = limits.limitsFor(top);
        if (table == null) return;
        if (!(event.getWhoClicked() instanceof Player player) || limits.canBypass(player, top.getType())) return;

        int rawSlot = event.getRawSlot();
        boolean clickedTop = rawSlot >= 0 && rawSlot < top.getSize();

        switch (event.getAction()) {
            // Actions that only ever take items out of the clicked slot
            case PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE,
                 DROP_ALL_CURSOR, DROP_ONE_CURSOR, DROP_ALL_SLOT, DROP_ONE_SLOT,
                 COLLECT_TO_CURSOR, CLONE_STACK -> {
            }

            case NOTHING -> {
                // Before 1.21.2 the server reports clicking a bundle with a big stack on the cursor
                // as NOTHING, but vanilla still stuffs the cursor items into the bundle.
                if (clickedTop && event.getCurrentItem() != null
                        && event.getCurrentItem().getItemMeta() instanceof BundleMeta) {
                    checkAllOrNothing(event, player, table, top, event.getCursor(), Map.of());
                }
            }

            case PLACE_ALL, PLACE_SOME, PLACE_ONE -> {
                if (clickedTop) handlePlace(event, player, table, top);
            }

            case SWAP_WITH_CURSOR -> {
                // Only the slot's own top-level item counts as leaving: clicking a bundle in the
                // container with items on the cursor is also reported as a swap on some versions,
                // but it actually puts the cursor items INTO the bundle while the bundle stays.
                if (clickedTop) {
                    checkAllOrNothing(event, player, table, top, event.getCursor(),
                            limits.countTopLevel(table, event.getCurrentItem()));
                }
            }

            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                // Number keys (1-9) and the offhand swap key (F) while hovering a container slot
                if (clickedTop) {
                    checkAllOrNothing(event, player, table, top, hotbarItem(event, player),
                            limits.countTopLevel(table, event.getCurrentItem()));
                }
            }

            case MOVE_TO_OTHER_INVENTORY -> {
                // Shift-click from the player's inventory into the container
                if (!clickedTop) handleShiftClick(event, player, table, top);
            }

            default -> {
                // Unknown / newer actions (e.g. the 1.21.2+ bundle actions). Assume the worst:
                // everything on the cursor or hotbar key could end up in the container.
                // PICKUP_FROM_BUNDLE / PICKUP_*_INTO_BUNDLE only move items from the slot to the cursor.
                if (event.getAction().name().startsWith("PICKUP")) return;
                if (clickedTop) {
                    if (!checkAllOrNothing(event, player, table, top, event.getCursor(), Map.of())) return;
                    if (isHotbarClick(event.getClick())) {
                        checkAllOrNothing(event, player, table, top, hotbarItem(event, player), Map.of());
                    }
                } else if (event.getClick().isShiftClick()) {
                    checkAllOrNothing(event, player, table, top, event.getCurrentItem(), Map.of());
                }
            }
        }
    }

    /**
     * Putting items into a bundle. That only ever happens by clicking a bundle with an item or an
     * item with a bundle, in any inventory (including the player's own). The server reports it
     * differently per version (SWAP_WITH_CURSOR / NOTHING before 1.21.2, *_INTO_BUNDLE after), so
     * instead of trusting the action, any click that pairs a bundle with another item is checked
     * as if the whole other stack goes into the bundle. Clicks that take items OUT of a bundle
     * always have an empty cursor or an empty slot, so they're never affected.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBundleClick(InventoryClickEvent event) {
        LimitManager.LimitTable table = limits.bundleLimits();
        if (table == null || event instanceof InventoryCreativeEvent) return;
        if (!(event.getWhoClicked() instanceof Player player) || limits.canBypass(player, LimitManager.BUNDLE)) return;

        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        if (isAir(cursor) || isAir(current)) return;

        // Bundle on bundle could go either way depending on version, so check both directions
        LimitManager.Result violation = null;
        if (isBundle(current)) violation = bundleViolation(table, current, cursor);
        if (violation == null && isBundle(cursor)) violation = bundleViolation(table, cursor, current);
        if (violation != null) {
            event.setCancelled(true);
            deny(player, violation, LimitManager.containerName(LimitManager.BUNDLE));
        }
    }

    /** The limit broken if all of {@code incoming} went into {@code bundle}, or null if it all fits. */
    private LimitManager.Result bundleViolation(LimitManager.LimitTable table, ItemStack bundle, ItemStack incoming) {
        ItemStack[] contents = ((BundleMeta) bundle.getItemMeta()).getItems().toArray(new ItemStack[0]);
        LimitManager.Result result = limits.allowedUnits(table, contents, incoming, incoming.getAmount(), Map.of());
        return result.allowed() < incoming.getAmount() ? result : null;
    }

    private static boolean isBundle(ItemStack item) {
        return item.getItemMeta() instanceof BundleMeta;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getInventory();
        LimitManager.LimitTable table = limits.limitsFor(top);
        if (table == null) return;
        if (!(event.getWhoClicked() instanceof Player player) || limits.canBypass(player, top.getType())) return;

        int addedToTop = 0;
        for (Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
            int rawSlot = entry.getKey();
            if (rawSlot < 0 || rawSlot >= top.getSize()) continue;
            ItemStack before = top.getItem(rawSlot);
            int beforeAmount = isAir(before) ? 0 : before.getAmount();
            addedToTop += entry.getValue().getAmount() - beforeAmount;
        }
        if (addedToTop <= 0) return;

        LimitManager.Result result = limits.allowedUnits(table, top, event.getOldCursor(), addedToTop, Map.of());
        if (result.allowed() < addedToTop) {
            // Cancelling a drag leaves the whole stack on the cursor
            event.setCancelled(true);
            deny(player, result, top.getType());
        }
    }

    /** Hoppers, droppers and hopper minecarts pushing items into a limited container. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMoveItem(InventoryMoveItemEvent event) {
        Inventory destination = event.getDestination();
        LimitManager.LimitTable table = limits.limitsFor(destination.getType());
        if (table == null) return;
        if (destination.getType() == InventoryType.ENDER_CHEST
                && destination.getHolder() instanceof Player owner && limits.canBypass(owner, InventoryType.ENDER_CHEST)) {
            return;
        }

        ItemStack item = event.getItem();
        if (limits.allowedUnits(table, destination, item, item.getAmount(), Map.of()).allowed() < item.getAmount()) {
            event.setCancelled(true);
        }
    }

    /** Hoppers and hopper minecarts sucking up dropped items. Picks up only as many as the limit allows. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickupItem(InventoryPickupItemEvent event) {
        Inventory inventory = event.getInventory();
        LimitManager.LimitTable table = limits.limitsFor(inventory.getType());
        if (table == null) return;

        Item entity = event.getItem();
        ItemStack stack = entity.getItemStack();
        LimitManager.Result result = limits.allowedUnits(table, inventory, stack, stack.getAmount(), Map.of());
        if (result.allowed() >= stack.getAmount()) return;

        event.setCancelled(true);
        if (result.allowed() > 0) {
            ItemStack toAdd = stack.clone();
            toAdd.setAmount(result.allowed());
            int notAdded = 0;
            for (ItemStack leftover : inventory.addItem(toAdd).values()) {
                notAdded += leftover.getAmount();
            }
            int moved = result.allowed() - notAdded;
            if (moved >= stack.getAmount()) {
                entity.remove();
            } else if (moved > 0) {
                stack.setAmount(stack.getAmount() - moved);
                entity.setItemStack(stack);
            }
        }
    }

    /**
     * Placing a container item that already holds items (a filled shulker box, or a creative
     * ctrl+pick-blocked chest) and placing a chest next to another to merge them into a double chest.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack[] placedContents = containerContents(event.getItemInHand());
        Block block = event.getBlockPlaced();

        InventoryType type = placedContents != null ? containerType(event.getItemInHand()) : null;
        List<ItemStack> contents = new ArrayList<>();
        if (placedContents != null) contents.addAll(Arrays.asList(placedContents));

        // Double chest: count the other half too, since they become one container
        if (block.getBlockData() instanceof org.bukkit.block.data.type.Chest chestData
                && chestData.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE) {
            BlockFace towardsOther = chestData.getType() == org.bukkit.block.data.type.Chest.Type.LEFT
                    ? clockwise(chestData.getFacing())
                    : clockwise(clockwise(clockwise(chestData.getFacing())));
            if (block.getRelative(towardsOther).getState() instanceof Chest other) {
                contents.addAll(Arrays.asList(other.getBlockInventory().getContents()));
                type = InventoryType.CHEST;
            }
        }

        if (type == null || contents.isEmpty()) return;
        LimitManager.LimitTable table = limits.limitsFor(type);
        if (table == null || limits.canBypass(player, type)) return;

        LimitManager.Result violation = limits.firstViolation(table, contents.toArray(new ItemStack[0]));
        if (violation != null) {
            event.setCancelled(true);
            deny(player, violation, type);
        }
    }

    /** Dispensers can place filled shulker boxes as blocks. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        ItemStack item = event.getItem();
        ItemStack[] contents = containerContents(item);
        if (contents == null) return;
        LimitManager.LimitTable table = limits.limitsFor(containerType(item));
        if (table != null && limits.firstViolation(table, contents) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastMessage.remove(event.getPlayer().getUniqueId());
    }

    /** Cursor -> container slot. Places as many as the limit allows, the rest stays on the cursor. */
    private void handlePlace(InventoryClickEvent event, Player player, LimitManager.LimitTable table, Inventory top) {
        ItemStack cursor = event.getCursor();
        if (isAir(cursor)) return;
        cursor = cursor.clone();

        ItemStack slotItem = event.getCurrentItem();
        int existing = isAir(slotItem) ? 0 : slotItem.getAmount();
        int units = switch (event.getAction()) {
            case PLACE_ONE -> 1;
            case PLACE_SOME -> Math.min(cursor.getAmount(),
                    Math.min(cursor.getMaxStackSize(), top.getMaxStackSize()) - existing);
            default -> cursor.getAmount();
        };
        if (units <= 0) return;

        LimitManager.Result result = limits.allowedUnits(table, top, cursor, units, Map.of());
        if (result.allowed() >= units) return;

        event.setCancelled(true);
        deny(player, result, top.getType());

        if (result.allowed() > 0) {
            ItemStack placed = cursor.clone();
            placed.setAmount(existing + result.allowed());
            top.setItem(event.getSlot(), placed);

            int left = cursor.getAmount() - result.allowed();
            cursor.setAmount(Math.max(left, 1));
            player.setItemOnCursor(left > 0 ? cursor : null);
            resync(player);
        }
    }

    /** Shift-click into the container. Moves as many as the limit allows, the rest stays in the slot. */
    private void handleShiftClick(InventoryClickEvent event, Player player, LimitManager.LimitTable table, Inventory top) {
        ItemStack item = event.getCurrentItem();
        Inventory source = event.getClickedInventory();
        if (isAir(item) || source == null) return;
        item = item.clone();

        int units = item.getAmount();
        LimitManager.Result result = limits.allowedUnits(table, top, item, units, Map.of());
        if (result.allowed() >= units) return;

        event.setCancelled(true);
        deny(player, result, top.getType());

        // Furnaces, brewing stands etc. have special slot rules for shift-click, so only
        // do partial moves into plain storage containers.
        if (result.allowed() > 0 && isPlainStorage(top.getType())) {
            ItemStack toMove = item.clone();
            toMove.setAmount(result.allowed());
            int notMoved = 0;
            for (ItemStack leftover : top.addItem(toMove).values()) {
                notMoved += leftover.getAmount();
            }
            int moved = result.allowed() - notMoved;
            if (moved > 0) {
                int remaining = item.getAmount() - moved;
                item.setAmount(Math.max(remaining, 1));
                source.setItem(event.getSlot(), remaining > 0 ? item : null);
            }
            resync(player);
        }
    }

    /**
     * Cancels the click if not all of {@code incoming} fits.
     *
     * @return true if the click is still allowed
     */
    private boolean checkAllOrNothing(InventoryClickEvent event, Player player, LimitManager.LimitTable table,
                                      Inventory top, ItemStack incoming, Map<LimitManager.Rule, Integer> leaving) {
        if (isAir(incoming)) return true;
        int units = incoming.getAmount();
        LimitManager.Result result = limits.allowedUnits(table, top, incoming, units, leaving);
        if (result.allowed() >= units) return true;

        event.setCancelled(true);
        deny(player, result, top.getType());
        return false;
    }

    private static ItemStack hotbarItem(InventoryClickEvent event, Player player) {
        PlayerInventory inventory = player.getInventory();
        int button = event.getHotbarButton();
        if (event.getClick() == ClickType.SWAP_OFFHAND || button == 40 || button < 0) {
            return inventory.getItemInOffHand();
        }
        return inventory.getItem(button);
    }

    private static boolean isHotbarClick(ClickType click) {
        return click == ClickType.NUMBER_KEY || click == ClickType.SWAP_OFFHAND;
    }

    private static boolean isPlainStorage(InventoryType type) {
        return switch (type) {
            case CHEST, ENDER_CHEST, BARREL, SHULKER_BOX, HOPPER, DISPENSER, DROPPER -> true;
            default -> false;
        };
    }

    /** Stored contents of a container item (shulker box etc.), or null if it isn't one. */
    private static ItemStack[] containerContents(ItemStack item) {
        if (isAir(item) || !(item.getItemMeta() instanceof BlockStateMeta meta) || !meta.hasBlockState()) return null;
        return meta.getBlockState() instanceof Container container ? container.getInventory().getContents() : null;
    }

    private static InventoryType containerType(ItemStack item) {
        BlockState state = ((BlockStateMeta) item.getItemMeta()).getBlockState();
        return ((Container) state).getInventory().getType();
    }

    private static BlockFace clockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> face;
        };
    }

    private void deny(Player player, LimitManager.Result result, InventoryType type) {
        deny(player, result, LimitManager.containerName(type));
    }

    /** @param containerName shown as {container} in the message, e.g. "ender chest" */
    private void deny(Player player, LimitManager.Result result, String containerName) {
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (last != null && now - last < MESSAGE_COOLDOWN_MS) return;
        lastMessage.put(player.getUniqueId(), now);

        LimitManager.Rule rule = result.limiting();
        if (rule == null) return;
        String key = rule.limit() == 0 ? "blocked" : "limit";
        player.sendMessage(plugin.message(key)
                .replace("{item}", rule.displayName())
                .replace("{limit}", String.valueOf(rule.limit()))
                .replace("{container}", containerName));
    }

    /** Make sure the client shows the real inventory after we changed things in a cancelled click. */
    private void resync(Player player) {
        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
    }
}
