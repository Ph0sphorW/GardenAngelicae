package org.icarus.angelicae.garden.utility;

import javax.annotation.Nonnull;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class PlayerSkinUtil {

    /**
     * Returns Base64 Skin
     * @param uuid Player UUID
     * @param hashCode Hash
     * @return The base64 skin
     */
    public static @Nonnull String fromHashCode(String hashCode) {
        return fromURL("http://textures.minecraft.net/texture/" + hashCode);
    }

    public static String fromURL(String url) {
        String value = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
