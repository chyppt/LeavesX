package org.leavesmc.leaves.command.bot;

import java.util.Locale;
import org.leavesmc.leaves.LeavesConfig;

/** Selects built-in {@code /bot} command messages from the server language configured by Leaves. */
public final class BotCommandLocale {

    private BotCommandLocale() {
    }

    /**
     * Returns Chinese for every Minecraft Chinese locale and English for all other server languages.
     *
     * <p>The setting is startup-locked by Leaves, so this check is deterministic and does not need a separate cache
     * or reload hook.</p>
     */
    public static String message(final String english, final String chinese) {
        final String language = LeavesConfig.mics.serverLang;
        if (language == null) {
            return english;
        }
        final String normalized = language.toLowerCase(Locale.ROOT).replace('-', '_');
        return (normalized.equals("zh") || normalized.startsWith("zh_")) ? chinese : english;
    }
}
