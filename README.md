# ContainerLimit

A Spigot/Paper plugin that limits or blocks specific items in ender chests, chests, shulker boxes, bundles and other containers. It works with vanilla items, groups of items, and custom items from other plugins.

## Features

- **Separate limits for each container type**, including what can go inside bundles, item frames, decorated pots and shelves
- **Limit or block items:** set a maximum per container, or `0` to block an item completely
- **Partial placing:** if you try to put in more than is allowed, as many as fit go in and the rest stays with you
- **Item groups:** one entry can cover every color of wool, every shulker box, and so on
- **Custom items from other plugins,** recognised by their hidden plugin tag, so renaming doesn't get around it
- **Hard to get around:** covers shift-clicks, number keys, dragging, hoppers, items hidden inside shulker boxes and bundles, pre-filled shulker boxes and double chests

## Installation

1. Download the jar from [Releases](../../releases) and put it in your server's `plugins/` folder.
2. Restart the server. `plugins/ContainerLimit/config.yml` is created.
3. Set up your limits (see below) and run `/climit reload`.

## Configuration

Limits go under `containers:`. Each container type gets its own list of `item: limit` entries:

```yaml
containers:
  ENDER_CHEST:          # container type
    ELYTRA: 1           # at most 1 elytra per ender chest
    TOTEM_OF_UNDYING: 0 # 0 = blocked
  CHEST:
    WOOL: 64            # at most 64 wool in total, any colors
  BUNDLE:
    ENDER_PEARL: 8      # at most 8 pearls inside each bundle
```

A limit counts the item across **all slots** of one container. A double chest counts as one container.

### Container types

| Key | Covers |
|---|---|
| `ENDER_CHEST` | Ender chests |
| `CHEST` | Chests, trapped chests, double chests, chest minecarts and boats |
| `BARREL`, `SHULKER_BOX`, `DISPENSER`, `DROPPER`, `CRAFTER` | The placed blocks |
| `HOPPER` | Hoppers and hopper minecarts |
| `FURNACE`, `BLAST_FURNACE`, `SMOKER`, `BREWING` | The placed blocks |
| `DECORATED_POT` | Decorated pots |
| `SHELF` | All wood types of shelves (1.21.9+) |
| `ITEM_FRAME` | Item frames and glow item frames |
| `BUNDLE` | What can go **inside** each bundle |

### Item keys

| Example | Matches |
|---|---|
| `ELYTRA` | One item ([material names](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/Material.html)) |
| `WOOL` | A [Minecraft tag](https://minecraft.wiki/w/Tag), e.g. `WOOL`, `BEDS`, `BANNERS`, `LOGS` |
| `'*_WOOL'` | A wildcard over item names, **in quotes** |
| `'custom:plugin:tag=value'` | A custom item from another plugin, **in quotes** |

- **Tags and wildcards share one limit:** `WOOL: 64` means 64 wool in total, not 64 of each color.
- **Overlapping entries all apply:** `WOOL: 64` plus `RED_WOOL: 0` allows up to 64 wool of any color except red.

### Custom items

1. Hold the custom item and run `/climit hand`.
2. It lists the item's plugin tags as ready-made config lines. Click one to copy it.
3. Paste it under a container and run `/climit reload`.

```yaml
ENDER_CHEST:
  'custom:someplugin:item_type=example': 0  # tag has exactly this value
  'custom:someplugin:item_id': 5            # tag exists, any value
```

Pick a tag that's the same on every copy of the item, like an item ID or type. A custom item never counts as its base item. If `/climit hand` finds no plugin tags, the item can only be limited by its item type.

### Other settings

```yaml
# Count items inside shulker boxes and bundles, so blocked items can't be hidden in them
count-container-contents: true

# Skip other plugins' menus (e.g. a shop GUI that looks like a chest)
ignore-plugin-inventories: true

# Chat messages, & color codes work.
# Placeholders: {item}, {limit}, {container}
messages:
  prefix: "&8[&5ContainerLimit&8] "
  blocked: "&c{item} is not allowed in this {container}."
  limit: "&cYou can only store {limit}x {item} in one {container}."
```

If there's a mistake in the config, `/climit reload` shows it in chat and keeps the old settings.

## Commands & Permissions

| Command | Description |
|---|---|
| `/climit reload` | Reload the config and show any errors |
| `/climit list` | Show all limits |
| `/climit hand` | Show the plugin tags on your held item |

`/containerlimit` works too.

| Permission | Description | Default |
|---|---|---|
| `containerlimit.admin` | Use the commands | op |
| `containerlimit.bypass` | Ignore all limits | nobody |
| `containerlimit.bypass.<type>` | Ignore limits for one container type, e.g. `containerlimit.bypass.chest` | nobody |

## Good to know

- **Items already stored:** items that were in a container before a limit was added stay there. Only new items are blocked.
- **Creative mode:** the creative inventory isn't checked, because creative players can spawn any item anyway.
- **Bundles:** items go into a bundle all at once or not at all.

## Compatibility

Minecraft **1.18 – 1.21.x** on **Spigot, Paper** and Paper forks (Purpur, Pufferfish, …), with **Java 17+**. Folia isn't supported.

## License

[MIT](LICENSE)
