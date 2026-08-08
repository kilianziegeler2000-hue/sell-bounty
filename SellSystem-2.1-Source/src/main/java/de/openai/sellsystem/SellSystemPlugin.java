package de.openai.sellsystem;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;

public final class SellSystemPlugin extends JavaPlugin implements Listener {

    private Economy economy;
    private NamespacedKey actionKey;
    private NamespacedKey categoryKey;
    private final DecimalFormat fmt = new DecimalFormat("#,##0.##");
    private final Set<UUID> handledClose = new HashSet<>();

    private File progressFile;
    private YamlConfiguration progress;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!setupEconomy()) {
            getLogger().severe("Keine Vault-Economy gefunden.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        actionKey = new NamespacedKey(this, "action");
        categoryKey = new NamespacedKey(this, "category");

        progressFile = new File(getDataFolder(), "progress.yml");
        progress = YamlConfiguration.loadConfiguration(progressFile);

        Objects.requireNonNull(getCommand("sell")).setExecutor(this);
        Objects.requireNonNull(getCommand("sellmulti")).setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private boolean setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Nur Spieler.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("sell")) {
            openSell(player);
            return true;
        }

        openSellMulti(player);
        return true;
    }

    private void openSell(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, c(getConfig().getString("settings.sell-title", "&8Sell")));

        ItemStack filler = plain(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);

        inv.setItem(45, action(Material.RED_DYE, "&cAbbrechen", "cancel", null,
                List.of("&7Items zurücknehmen")));
        inv.setItem(53, action(Material.LIME_DYE, "&aVerkaufen", "confirm", null,
                List.of("&7Alle eingelegten Items verkaufen")));

        player.openInventory(inv);
    }

    private void openSellMulti(Player player) {
        Inventory inv = Bukkit.createInventory(null, 9,
                c(getConfig().getString("settings.sellmulti-title", "&8Sell Multi")));

        ConfigurationSection cats = getConfig().getConfigurationSection("categories");
        if (cats == null) {
            player.openInventory(inv);
            return;
        }

        int slot = 0;
        for (String id : cats.getKeys(false)) {
            if (slot >= 9) break;

            Material icon = Material.matchMaterial(cats.getString(id + ".icon", "STONE"));
            if (icon == null) icon = Material.STONE;

            String name = cats.getString(id + ".name", id);
            int unlocked = unlockedLevels(player, id);
            double multiplier = currentMultiplier(player, id);
            double total = categoryProgress(player, id);
            int nextLevel = unlocked + 1;
            double target = levelTarget(nextLevel);

            List<String> lore = new ArrayList<>();
            lore.add("&7Current multiplier: &a" + formatMultiplier(multiplier) + "x");
            lore.add("&7Unlocked levels: &b" + unlocked);
            lore.add("");
            lore.add("&eClick to view the items in this category");

            if (target > 0) {
                lore.add("");
                lore.add("&7Progress: &f$" + fmt.format(total) + " &7/ &f$" + fmt.format(target));
                lore.add(progressBar(total, target));
                lore.add("&7Still needed: &e$" + fmt.format(Math.max(0, target - total)));
            } else {
                lore.add("");
                lore.add("&aMaximum level reached!");
            }

            inv.setItem(slot++, action(icon, name, "category", id, lore));
        }

        player.openInventory(inv);
    }

    private void openProgress(Player player, String category) {
        String catName = getConfig().getString("categories." + category + ".name", category);
        String title = getConfig().getString("settings.progress-title", "&8%category% Progress")
                .replace("%category%", ChatColor.stripColor(c(catName)));

        Inventory inv = Bukkit.createInventory(null, 54, c(title));

        Material icon = Material.matchMaterial(getConfig().getString("categories." + category + ".icon", "STONE"));
        if (icon == null) icon = Material.STONE;

        int unlocked = unlockedLevels(player, category);
        double current = currentMultiplier(player, category);
        double total = categoryProgress(player, category);

        inv.setItem(0, action(icon, catName, "noop", category, List.of(
                "&7Current multiplier: &a" + formatMultiplier(current) + "x",
                "&7Unlocked levels: &b" + unlocked,
                "",
                "&eClick levels to inspect progress"
        )));

        // Layout in U-Form, ähnlich dem Screenshot.
        int[] slots = {
                10,19,28,37,
                38,39,30,21,12,
                13,14,23,32,41,
                42,43,34,25,16,7
        };

        int maxLevels = Math.min(slots.length, highestConfiguredLevel());
        for (int level = 1; level <= maxLevels; level++) {
            double target = levelTarget(level);
            double mult = levelMultiplier(level);

            Material mat;
            String status;
            if (level <= unlocked) {
                mat = Material.LIME_STAINED_GLASS_PANE;
                status = "&aUnlocked";
            } else if (level == unlocked + 1) {
                mat = Material.YELLOW_STAINED_GLASS_PANE;
                status = "&eCurrent target";
            } else {
                mat = Material.GRAY_STAINED_GLASS_PANE;
                status = "&7Locked";
            }

            double shownProgress = Math.min(total, target);
            List<String> lore = new ArrayList<>();
            lore.add("&7Multiplier: &a" + formatMultiplier(mult) + "x");
            lore.add("&7Target value: &f$" + fmt.format(target));
            lore.add("");
            lore.add("&7Progress: &f$" + fmt.format(shownProgress) + " &7/ &f$" + fmt.format(target));
            lore.add(progressBar(shownProgress, target));
            lore.add("&7Still needed: &e$" + fmt.format(Math.max(0, target - shownProgress)));
            lore.add("");
            lore.add(status);

            inv.setItem(slots[level - 1], action(mat, "&fLevel " + level + " &7(" + formatMultiplier(mult) + "x)",
                    "noop", category, lore));
        }

        inv.setItem(45, action(Material.ARROW, "&fZurück", "back", null, List.of()));
        player.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) return;

        ItemMeta meta = clicked.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;

        event.setCancelled(true);

        String category = meta.getPersistentDataContainer().get(categoryKey, PersistentDataType.STRING);

        switch (action) {
            case "confirm" -> {
                handledClose.add(player.getUniqueId());
                sellGui(player, event.getInventory());
                player.closeInventory();
            }
            case "cancel" -> {
                handledClose.add(player.getUniqueId());
                returnItems(player, event.getInventory());
                player.sendMessage(c(prefix() + getConfig().getString("settings.cancelled", "&7Abgebrochen.")));
                player.closeInventory();
            }
            case "category" -> {
                if (category != null) openProgress(player, category);
            }
            case "back" -> openSellMulti(player);
            default -> {}
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!event.getView().getTitle().equals(c(getConfig().getString("settings.sell-title", "&8Sell")))) return;

        if (handledClose.remove(player.getUniqueId())) return;
        returnItems(player, event.getInventory());
    }

    private void sellGui(Player player, Inventory inv) {
        double baseTotal = 0.0;
        Map<String, Double> categoryBase = new HashMap<>();

        for (int slot = 0; slot < 45; slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) continue;

            double unit = price(item.getType());
            double itemBase = unit * item.getAmount();

            String category = categoryFor(item.getType());
            double multiplier = category == null ? 1.0 : currentMultiplier(player, category);
            baseTotal += itemBase * multiplier;

            if (category != null) {
                // Fortschritt basiert auf Basis-Verkaufswert, nicht auf Bonus.
                categoryBase.merge(category, itemBase, Double::sum);
            }

            inv.setItem(slot, null);
        }

        if (baseTotal <= 0) {
            player.sendMessage(c(prefix() + getConfig().getString("settings.nothing-sold", "&cKeine Items.")));
            return;
        }

        economy.depositPlayer(player, baseTotal);

        for (Map.Entry<String, Double> e : categoryBase.entrySet()) {
            addProgress(player, e.getKey(), e.getValue());
        }

        // Wenn mehrere Kategorien verkauft wurden, ist die genaue Gesamt-Multi gemischt.
        player.sendMessage(c(prefix() + getConfig().getString("settings.sold", "&aVerkauft für &e%money%$")
                .replace("%money%", fmt.format(baseTotal))
                .replace("%multiplier%", "mixed")));
    }

    private void returnItems(Player player, Inventory inv) {
        for (int slot = 0; slot < 45; slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
            for (ItemStack leftover : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
            inv.setItem(slot, null);
        }
    }

    private double price(Material material) {
        if (getConfig().contains("prices." + material.name())) {
            return Math.max(0.0, getConfig().getDouble("prices." + material.name()));
        }
        return Math.max(0.01, getConfig().getDouble("settings.default-price", 1.0));
    }

    private String categoryFor(Material material) {
        ConfigurationSection cats = getConfig().getConfigurationSection("categories");
        if (cats == null) return null;

        // Explizite Materiallisten zuerst.
        for (String id : cats.getKeys(false)) {
            List<String> materials = cats.getStringList(id + ".materials");
            if (materials.stream().anyMatch(s -> s.equalsIgnoreCase(material.name()))) return id;
        }

        // Leere Spezialkategorien automatisch erkennen.
        if (material.isBlock() && cats.contains("blocks")) return "blocks";
        String n = material.name();
        if ((n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL")
                || n.endsWith("_HOE") || n.endsWith("_SWORD")) && cats.contains("tools")) return "tools";

        return null;
    }

    private double categoryProgress(Player player, String category) {
        return progress.getDouble("players." + player.getUniqueId() + "." + category + ".value", 0.0);
    }

    private void addProgress(Player player, String category, double amount) {
        String path = "players." + player.getUniqueId() + "." + category + ".value";
        progress.set(path, categoryProgress(player, category) + amount);
        try {
            progress.save(progressFile);
        } catch (IOException e) {
            getLogger().warning("progress.yml konnte nicht gespeichert werden: " + e.getMessage());
        }
    }

    private int unlockedLevels(Player player, String category) {
        double total = categoryProgress(player, category);
        int unlocked = 0;
        for (int level = 1; level <= highestConfiguredLevel(); level++) {
            if (total >= levelTarget(level)) unlocked = level;
            else break;
        }
        return unlocked;
    }

    private double currentMultiplier(Player player, String category) {
        int unlocked = unlockedLevels(player, category);
        if (unlocked <= 0) return 1.0;
        return levelMultiplier(unlocked);
    }

    private int highestConfiguredLevel() {
        ConfigurationSection levels = getConfig().getConfigurationSection("levels");
        if (levels == null) return 0;
        return levels.getKeys(false).stream()
                .mapToInt(s -> {
                    try { return Integer.parseInt(s); }
                    catch (NumberFormatException ex) { return 0; }
                })
                .max().orElse(0);
    }

    private double levelTarget(int level) {
        if (level <= 0 || !getConfig().contains("levels." + level + ".target")) return -1;
        return getConfig().getDouble("levels." + level + ".target", -1);
    }

    private double levelMultiplier(int level) {
        if (level <= 0) return 1.0;
        return getConfig().getDouble("levels." + level + ".multiplier", 1.0);
    }

    private String progressBar(double value, double target) {
        int bars = 20;
        double ratio = target <= 0 ? 0 : Math.max(0, Math.min(1, value / target));
        int filled = (int) Math.floor(ratio * bars);
        int percent = (int) Math.floor(ratio * 100);

        StringBuilder sb = new StringBuilder("&7[");
        for (int i = 0; i < bars; i++) {
            sb.append(i < filled ? "&6■" : "&8■");
        }
        sb.append("&7] &f").append(percent).append("%");
        return sb.toString();
    }

    private String formatMultiplier(double d) {
        return new DecimalFormat("0.#").format(d);
    }

    private ItemStack plain(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(c(name));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack action(Material material, String name, String action, String category, List<String> lore) {
        ItemStack item = plain(material, name);
        ItemMeta meta = item.getItemMeta();
        if (lore != null && !lore.isEmpty()) meta.setLore(lore.stream().map(this::c).toList());

        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        if (category != null) meta.getPersistentDataContainer().set(categoryKey, PersistentDataType.STRING, category);
        item.setItemMeta(meta);
        return item;
    }

    private String prefix() {
        return getConfig().getString("settings.prefix", "");
    }

    private String c(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
