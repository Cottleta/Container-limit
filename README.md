# ContainerLimit

A Spigot/Paper plugin that limits or blocks specific items in ender chests, chests, shulker boxes, bundles and other containers. You can also limit custom items from other plugins, like LifestealZ hearts.

```yaml
containers:
  ENDER_CHEST:
    ELYTRA: 1                                   # max 1 elytra per ender chest
    TOTEM_OF_UNDYING: 0                         # 0 = blocked
    'custom:lifestealz:customitemtype=heart': 0 # custom item from another plugin
  BUNDLE:
    ENDER_PEARL: 8                              # max 8 pearls inside each bundle
```

## Features

- **Separate limits for each container type:** ender chests, chests, barrels, shulker boxes, hoppers, dispensers, droppers, furnaces, brewing stands, crafters and bundles.
- **Limit or block items:** set any number as the maximum, or `0` to block an item completely. The limit counts every slot in the container, not per stack.
- **Partial placing:** if you try to put in more than is allowed, as many as fit go in and the rest stays on your cursor or in its slot.
- **Item groups:** one entry can cover several items, using Minecraft tags (`WOOL` for all 16 colors) or wildcards (`'*SHULKER_BOX'`).
- **Custom items from other plugins:** items are recognised by the hidden tag their plugin puts on them, so renaming an item in an anvil doesn't get around the limit.
- **Items hidden inside other items are counted:** blocked items can't be smuggled in inside a shulker box or bundle.
- **Ways around the limits are blocked:** see [Blocked workarounds](#blocked-workarounds).
- **Config reload with checks:** `/climit reload` reports YAML errors and unknown item names in chat instead of failing silently.

## Installation

1. Download `ContainerLimit-<version>.jar` and put it in your server's `plugins/` folder.
2. Restart the server. `plugins/ContainerLimit/config.yml` is created.
3. Set your limits in `config.yml` and run `/climit reload`.

## Configuration

Each container type gets its own list of `item: limit` entries:

```yaml
containers:
  ENDER_CHEST:
    ELYTRA: 1
    ENCHANTED_GOLDEN_APPLE: 0
  CHEST:
    NETHERITE_BLOCK: 16
  SHULKER_BOX:
    TOTEM_OF_UNDYING: 1
```

### Container types

| Key | Covers |
|---|---|
| `ENDER_CHEST` | Ender chests, including ones opened by commands like `/enderchest` |
| `CHEST` | Chests, trapped chests, double chests, chest minecarts, chest boats |
| `BARREL`, `SHULKER_BOX` | The placed blocks |
| `HOPPER` | Hoppers and hopper minecarts |
| `DISPENSER`, `DROPPER`, `CRAFTER` | The placed blocks (`CRAFTER` is 1.21+) |
| `FURNACE`, `BLAST_FURNACE`, `SMOKER`, `BREWING` | The placed blocks |
| `BUNDLE` | Special: limits what can go **inside** each bundle, including colored bundles |

A double chest counts as one container.

### Item keys

| Key | Matches |
|---|---|
| `ELYTRA` | One item ([material names](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/Material.html)) |
| `WOOL` | A [Minecraft tag](https://minecraft.wiki/w/Tag), e.g. `WOOL`, `BEDS`, `BANNERS`, `CANDLES`, `LOGS`, `SHULKER_BOXES` |
| `'*_WOOL'` | A wildcard over item names. **Must be in quotes.** |
| `'custom:plugin:tag=value'` | A custom item from another plugin. See [Custom items](#custom-items). |

A tag or wildcard entry shares **one limit across all its items**: `WOOL: 64` means 64 wool in total, in any mix of colors. If several entries cover the same item, all of them apply. For example, `WOOL: 64` together with `RED_WOOL: 0` allows up to 64 wool of any color except red.

### Custom items

Many plugins make custom items from normal items. For example, LifestealZ hearts are renamed nether stars. To limit these:

1. Hold the item and run `/climit hand`.
2. The command lists the item's hidden plugin tags as ready-made config lines. Click one to copy it.
3. Paste the line under a container in `config.yml` and run `/climit reload`.

```yaml
ENDER_CHEST:
  'custom:lifestealz:customitemtype=heart': 0   # tag has exactly this value
  'custom:someplugin:item_id': 5                # tag exists, any value
```

Pick a tag that's the same on every copy of the item, like an item ID or type. A custom item never counts as its base item: once hearts are defined, `NETHER_STAR` only means real nether stars.

> This works for plugins that store data with Bukkit's `PersistentDataContainer` (shown as `PublicBukkitValues` in `/data get`), which most plugins do. If `/climit hand` says the item has no plugin tags, it can only be limited by its item type.

### Other options

```yaml
# Count items inside shulker boxes and bundles, so blocked items can't be smuggled in
count-container-contents: true

# Skip other plugins' menus (e.g. a shop GUI that looks like a chest). Ender chests are always checked.
ignore-plugin-inventories: true
```

All player messages can be changed under `messages:` in `config.yml`, including `&` color codes.

## Commands

The main command is `/containerlimit`, and `/climit` is a shorter alias.

| Command | Description |
|---|---|
| `/climit reload` | Reload `config.yml` and show any errors or warnings |
| `/climit list` | Show all configured limits |
| `/climit hand` | Show the plugin tags on your held item, for setting up custom items |

## Permissions

| Permission | Description | Default |
|---|---|---|
| `containerlimit.admin` | Use `/climit` | op |
| `containerlimit.bypass` | Ignore all limits | nobody |
| `containerlimit.bypass.<type>` | Ignore limits for one container type, e.g. `containerlimit.bypass.ender_chest` or `containerlimit.bypass.bundle` | nobody |

Ops don't bypass limits unless you give them one of the bypass permissions.

## Blocked workarounds

The plugin handles every way items can get into a container:

- **Clicks:** normal clicks, right-clicks, shift-clicks, dragging across slots, number keys (1–9) and the offhand key (F)
- **Items inside items:** blocked items inside shulker boxes and bundles, even bundles inside bundles
- **Bundles already in a container:** stuffing items into a bundle that's already inside a limited container
- **Hoppers and droppers:** moving items into a container, and hoppers picking items up off the ground
- **Placing a pre-filled shulker box:** by a player or by a dispenser
- **Merging chests:** combining two single chests into a double chest that would go over the limit
- **Commands and other plugins:** ender chests opened by commands or other plugins (`/ec`, `/invsee`, …)

### Known limitations

- **Items already stored:** items that were in a container before a limit was added stay there. The plugin only blocks new items going in.
- **Creative mode:** the creative inventory isn't checked, because creative players can spawn any item anyway. Give staff a bypass permission instead of creative.
- **Bundles:** items go into a bundle all at once or not at all. There's no partial insert.
- **Custom ender chest copies:** a plugin that shows a copy of the ender chest in a normal chest window can't be told apart from a plugin menu.

## Compatibility

| | |
|---|---|
| **Minecraft** | 1.18 – 1.21.x (1.17 only if the server runs on Java 17) |
| **Server** | Spigot, Paper and Paper forks (Purpur, Pufferfish, …) |
| **Not supported** | Folia, Forge/Fabric/NeoForge, proxies (BungeeCord/Velocity) |
| **Java** | 17 or newer |

## Building

You need JDK 17+ and Maven.

```bash
mvn clean package
```

The jar is written to `target/ContainerLimit-<version>.jar`.

Every push is also built automatically by GitHub Actions. Download the jar from a run's **Artifacts** section on the Actions tab.

## License

[MIT](LICENSE)
