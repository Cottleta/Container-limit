package dev.containerlimit;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ContainerLimit extends JavaPlugin implements TabExecutor {

    private LimitManager limits;

    @Override
    public void onEnable() {
        migrateOldConfig();
        saveDefaultConfig();
        limits = new LimitManager(this);
        limits.load();

        getServer().getPluginManager().registerEvents(new ContainerListener(this, limits), this);

        PluginCommand command = getCommand("containerlimit");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
    }

    /** The plugin used to be called EnderChestLimit; carry its config over on the first start. */
    private void migrateOldConfig() {
        File newConfig = new File(getDataFolder(), "config.yml");
        File oldConfig = new File(getDataFolder().getParentFile(), "EnderChestLimit/config.yml");
        if (newConfig.exists() || !oldConfig.exists()) return;
        try {
            getDataFolder().mkdirs();
            Files.copy(oldConfig.toPath(), newConfig.toPath());
            getLogger().info("Copied config.yml from the old EnderChestLimit folder. "
                    + "Permissions are now containerlimit.* instead of enderchestlimit.*");
        } catch (IOException e) {
            getLogger().warning("Couldn't copy the old EnderChestLimit config: " + e.getMessage());
        }
    }

    public String message(String key) {
        String prefix = getConfig().getString("messages.prefix", "");
        String text = getConfig().getString("messages." + key, "");
        return color(prefix + text);
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            // reloadConfig() silently falls back to the jar's defaults on a YAML error,
            // so parse the file ourselves first and refuse to reload a broken config.
            File file = new File(getDataFolder(), "config.yml");
            if (!file.exists()) {
                saveDefaultConfig();
            }
            try {
                new YamlConfiguration().load(file);
            } catch (IOException | InvalidConfigurationException e) {
                sender.sendMessage(message("reload-error"));
                String detail = String.valueOf(e.getMessage()).lines().limit(4)
                        .reduce((a, b) -> a + "\n" + b).orElse("");
                sender.sendMessage(ChatColor.RED + detail);
                getLogger().severe("config.yml has an error, not reloaded: " + e.getMessage());
                return true;
            }

            reloadConfig();
            List<String> problems = limits.load();
            sender.sendMessage(message("reloaded").replace("{count}", String.valueOf(limits.totalLimitCount())));
            for (String problem : problems) {
                sender.sendMessage(ChatColor.YELLOW + "Warning: " + problem);
            }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            Map<String, Map<String, Integer>> all = limits.getAllLimits();
            if (all.isEmpty()) {
                sender.sendMessage(message("list-empty"));
                return true;
            }
            String headerFormat = getConfig().getString("messages.list-header", "{container}:");
            String entryFormat = getConfig().getString("messages.list-entry", "- {item}: {limit}");
            all.forEach((section, entries) -> {
                sender.sendMessage(color(headerFormat.replace("{container}", section)));
                entries.forEach((material, limit) -> sender.sendMessage(color(entryFormat
                        .replace("{item}", material)
                        .replace("{limit}", limit == 0 ? "blocked" : String.valueOf(limit)))));
            });
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("hand")) {
            showHand(sender);
            return true;
        }
        sender.sendMessage(message("usage"));
        return true;
    }

    /** Shows the hidden plugin tags on the held item as ready-to-paste config lines. */
    private void showHand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this.");
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (LimitManager.isAir(item)) {
            sender.sendMessage(ChatColor.RED + "Hold the item in your main hand.");
            return;
        }

        sender.sendMessage(ChatColor.GRAY + "Item type: " + ChatColor.WHITE + item.getType().name());
        PersistentDataContainer data = item.hasItemMeta() ? item.getItemMeta().getPersistentDataContainer() : null;
        if (data == null || data.getKeys().isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "This item has no plugin tags, so it can only be limited by its item type.");
            return;
        }

        sender.sendMessage(ChatColor.GRAY + "Plugin tags (click one to copy it, then paste it under a container in config.yml):");
        for (NamespacedKey key : data.getKeys()) {
            String value = CustomItemMatcher.readValue(data, key);
            String line = value != null
                    ? "'" + CustomItemMatcher.PREFIX + key + "=" + value + "': 0"
                    : "'" + CustomItemMatcher.PREFIX + key + "': 0";
            TextComponent component = new TextComponent(ChatColor.DARK_GRAY + "- " + ChatColor.AQUA + line);
            component.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, line));
            component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("Click to copy")));
            player.spigot().sendMessage(component);
        }
        sender.sendMessage(ChatColor.GRAY + "Pick a tag that's the same on every copy of this item, like an item id or type.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            for (String option : List.of("reload", "list", "hand")) {
                if (option.startsWith(args[0].toLowerCase())) {
                    result.add(option);
                }
            }
        }
        return result;
    }
}
