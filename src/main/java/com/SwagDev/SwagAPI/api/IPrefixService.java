package com.SwagDev.SwagAPI.api;

/**
 * Web-panel-configurable message-prefix overrides shared across every Swag plugin
 * (SwagAPI's control panel, under "Message Prefixes"). A plugin that currently builds its own
 * hardcoded/local-config prefix (e.g. SwagStack's literal {@code "[SwagStack] "}) calls {@link
 * #getPrefix(String, String)} wherever it does that, passing its own plugin name and its
 * existing default as the fallback — nothing changes in appearance for that plugin until an
 * admin actually sets an override via the panel.
 *
 * <p>Returned values are MiniMessage-formatted strings (never legacy '&amp;'/'§'), matching the
 * convention already used throughout this ecosystem — callers parse the result with whatever
 * MiniMessage instance they already have (adventure-text-minimessage is on every plugin's
 * classpath transitively via Paper API, no new dependency needed).</p>
 */
public interface IPrefixService {

    /**
     * Effective prefix for {@code pluginName}: a per-plugin override if the admin has set one
     * for exactly this plugin, else the panel's single global override if THAT is set, else
     * {@code fallback} unchanged — so a plugin with nothing configured via the panel looks
     * exactly as it always has.
     *
     * @param pluginName this plugin's own name, exactly as it appears in plugin.yml (e.g.
     *                    {@code "SwagStack"}) — must match for the panel's per-plugin override
     *                    row to apply.
     * @param fallback    this plugin's own current default prefix (MiniMessage format), used
     *                    verbatim when no override applies anywhere in the chain.
     */
    String getPrefix(String pluginName, String fallback);
}
