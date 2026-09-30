package dev.containerlimit;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;

/**
 * Recognises custom items from other plugins by the hidden tag those plugins put on them
 * (Bukkit's PersistentDataContainer, shown as "PublicBukkitValues" in /data get).
 * <p>
 * Config syntax:
 * <ul>
 *     <li>{@code custom:lifestealz:customitemtype=heart} - the tag has exactly that value</li>
 *     <li>{@code custom:lifestealz:customitemtype} - the tag exists, any value</li>
 * </ul>
 */
public final class CustomItemMatcher {

    public static final String PREFIX = "custom:";

    /** Tag value types we know how to read and compare as text. */
    private static final List<PersistentDataType<?, ?>> READABLE_TYPES = List.of(
            PersistentDataType.STRING, PersistentDataType.INTEGER, PersistentDataType.LONG,
            PersistentDataType.SHORT, PersistentDataType.BYTE, PersistentDataType.DOUBLE,
            PersistentDataType.FLOAT);

    private final NamespacedKey key;
    private final String value;

    private CustomItemMatcher(NamespacedKey key, String value) {
        this.key = key;
        this.value = value;
    }

    public static boolean isCustomKey(String configKey) {
        return configKey.trim().toLowerCase(Locale.ROOT).startsWith(PREFIX);
    }

    /** @return the matcher, or null if the config key isn't valid */
    public static CustomItemMatcher parse(String configKey) {
        String body = configKey.trim().substring(PREFIX.length());
        int equals = body.indexOf('=');
        String keyPart = (equals < 0 ? body : body.substring(0, equals)).trim().toLowerCase(Locale.ROOT);
        String valuePart = equals < 0 ? null : body.substring(equals + 1).trim();

        if (!keyPart.contains(":")) return null; // a tag without a namespace is almost always a typo
        NamespacedKey key = NamespacedKey.fromString(keyPart);
        if (key == null || (valuePart != null && valuePart.isEmpty())) return null;
        return new CustomItemMatcher(key, valuePart);
    }

    public boolean matches(PersistentDataContainer container) {
        if (value == null) {
            return container.getKeys().contains(key);
        }
        String actual = readValue(container, key);
        return actual != null && actual.equalsIgnoreCase(value);
    }

    /** The tag's value as text, or null if it's missing or a type we can't read (e.g. a nested tag). */
    public static String readValue(PersistentDataContainer container, NamespacedKey key) {
        for (PersistentDataType<?, ?> type : READABLE_TYPES) {
            if (container.has(key, type)) {
                return String.valueOf(container.get(key, type));
            }
        }
        return null;
    }

    /** Name used in chat, e.g. "Heart" for lifestealz:customitemtype=heart. */
    public String displayName() {
        String raw = value != null ? value : key.getKey();
        raw = raw.replace('_', ' ');
        return raw.isEmpty() ? raw : Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }
}
