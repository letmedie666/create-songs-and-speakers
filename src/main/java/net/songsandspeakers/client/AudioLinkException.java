package net.songsandspeakers.client;

import net.minecraft.network.chat.Component;

/** A problem with a music link that can be explained to the player. */
public class AudioLinkException extends Exception {
    private final String key;
    private final Object[] args;

    public AudioLinkException(String key, Object... args) {
        super(key);
        this.key = key;
        this.args = args;
    }

    public String key() { return key; }

    public Object firstArg() { return args.length > 0 ? args[0] : null; }

    public Component describe() {
        return Component.translatable("error.songsandspeakers." + key, args);
    }
}
