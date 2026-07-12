package com.SwagDev.SwagAPI.util;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class ItemBuilder {

    private final ItemStack item;

    public ItemBuilder(Material material) {
        this.item = new ItemStack(material);
    }

    public ItemBuilder(ItemStack base) {
        this.item = base.clone();
    }

    public ItemBuilder amount(int amount) {
        item.setAmount(amount);
        return this;
    }

    public ItemBuilder name(String legacyName) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(ColorUtil.toComponent(legacyName));
            item.setItemMeta(meta);
        }
        return this;
    }

    public ItemBuilder name(Component component) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(component);
            item.setItemMeta(meta);
        }
        return this;
    }

    public ItemBuilder lore(List<Component> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return this;
    }

    public ItemBuilder loreStrings(List<String> lore) {
        return lore(ColorUtil.toComponents(lore));
    }

    public ItemBuilder glow(boolean glow) {
        if (glow) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                item.setItemMeta(meta);
            }
        }
        return this;
    }

    public ItemBuilder flags(ItemFlag... flags) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.addItemFlags(flags);
            item.setItemMeta(meta);
        }
        return this;
    }

    public ItemBuilder skullTexture(String base64) {
        if (!(item.getItemMeta() instanceof SkullMeta skullMeta)) return this;
        PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), "SwagTexture");
        profile.setProperty(new ProfileProperty("textures", base64));
        skullMeta.setPlayerProfile(profile);
        item.setItemMeta(skullMeta);
        return this;
    }

    public ItemStack build() {
        return item.clone();
    }
}
