package de.openai.sellsystem;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
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

import java.text.DecimalFormat;
import java.util.*;

public final class SellSystemPlugin extends JavaPlugin implements Listener {

    private Economy economy;
    private NamespacedKey actionKey;
    private NamespacedKey categoryKey;
    private NamespacedKey amountKey;
    private final DecimalFormat format = new DecimalFormat("0.##");
    private final Set<UUID> confirmedSell = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!setupEconomy()) {
            getLogger().severe("Keine Vault-Economy gefunden. SellSystem wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        actionKey = new NamespacedKey(this, "action");
        categoryKey = new NamespacedKey(this, "category");
        amountKey = new NamespacedKey(this, "amount");

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
            sender.sendMessage("Nur Spieler können diesen Befehl benutzen.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("sell")) {
            openSell(player);
            return true;
        }

        if (command.getName().equalsIgnoreCase("sellmulti")) {
            openSellMulti(player);
            return true;
        }

        return true;
    }

    private void openSell(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, c(getConfig().getString("settings.sell-title", "&8Sell")));

        ItemStack filler = simple(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int slot = 45; slot < 54; slot++) inv.setItem(slot, filler);

        inv.setItem(45, action(Material.RED_DYE, "&cAbbrechen", "cancel", null, 0,
                List.of("&7Items zurücknehmen und schließen")));
        inv.setItem(53, action(Material.LIME_DYE, "&aVerkaufen", "confirm", null, 0,
                List.of("&7Klick zum Bestätigen")));

        player.openInventory(inv);
    }

    private void openSellMulti(Player player) {
        Inventory inv = Bukkit.createInventory(null, 9, c(getConfig().getString("settings.sellmulti-title", "&8Sell Multi")));

        ConfigurationSection section = getConfig().getConfigurationSection("categories");
        if (section == null) {
            player.openInventory(inv);
            return;
        }

        int slot = 0;
        for (String id : section.getKeys(false)) {
            if (slot >= inv.getSize()) break;
            Material icon = Material.matchMaterial(section.getString(id + ".icon", "STONE"));
            if (icon == null) icon = Material.STONE;
            String name = section.getString(id + ".name", id);
            inv.setItem(slot++, action(icon, name, "category", id, 0,
                    List.of("&7Klick zum Öffnen")));
        }

        player.openInventory(inv);
    }

    private void openAmount(Player player, String category) {
        String name = getConfig().getString("categories." + category + ".name", category);
        String title = getConfig().getString("settings.amount-title", "&8Sell Multi: %category%")
                .replace("%category%", ChatColor.stripColor(c(name)));

        Inventory inv = Bukkit.createInventory(null, 54, c(title));

        // Ähnlich dem Screenshot: Nummern 1-20 in einer kompakten Form.
        int[] slots = {10,19,28,37, 38,39,30,21,12,13,14,23,32,41,42,43,34,25,16,7};

        for (int i = 1; i <= 20; i++) {
            Material mat = (i == 20) ? Material.LIGHT_GRAY_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
            inv.setItem(slots[i - 1], action(mat, "&f" + i, "sell-amount", category, i,
                    List.of("&7Verkaufe &f" + i + " &7passende Items")));
        }

        Material icon = Material.matchMaterial(getConfig().getString("categories." + category + ".icon", "STONE"));
        if (icon == null) icon = Material.STONE;
        inv.setItem(0, action(icon, name, "noop", category, 0, List.of("&7Kategorie")));

        inv.setItem(45, action(Material.ARROW, "&fZurück", "back", null, 0, List.of()));
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
        Integer amount = meta.getPersistentDataContainer().get(amountKey, PersistentDataType.INTEGER);

        switch (action) {
            case "confirm" -> {
                confirmedSell.add(player.getUniqueId());
                sellInventory(player, event.getInventory());
                player.closeInventory();
            }
            case "cancel" -> {
                returnSellItems(player, event.getInventory());
                player.sendMessage(c(prefix() + getConfig().getString("settings.cancelled", "&7Verkauf abgebrochen.")));
                player.closeInventory();
            }
            case "category" -> {
                if (category != null) openAmount(player, category);
            }
            case "sell-amount" -> {
                if (category != null && amount != null) sellCategoryAmount(player, category, amount);
            }
            case "back" -> openSellMulti(player);
            default -> {}
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        String sellTitle = c(getConfig().getString("settings.sell-title", "&8Sell"));
        if (!event.getView().getTitle().equals(sellTitle)) return;

        // Wenn per Haken bestätigt wurde, wurde das Inventar bereits verarbeitet.
        if (confirmedSell.remove(player.getUniqueId())) return;

        returnSellItems(player, event.getInventory());
    }

    private void sellInventory(Player player, Inventory inv) {
        double total = 0.0;

        for (int slot = 0; slot < 45; slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) continue;

            double unit = price(item.getType());
            if (unit <= 0) continue;

            total += unit * item.getAmount();
            inv.setItem(slot, null);
        }

        // Nicht verkaufbare Items zurückgeben.
        for (int slot = 0; slot < 45; slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            giveBack(player, item);
            inv.setItem(slot, null);
        }

        if (total <= 0) {
            player.sendMessage(c(prefix() + getConfig().getString("settings.nothing-sold",
                    "&cDu hast keine verkaufbaren Items eingelegt.")));
            return;
        }

        economy.depositPlayer(player, total);
        player.sendMessage(c(prefix() + getConfig().getString("settings.sold", "&aVerkauft für &e%money%$&a.")
                .replace("%money%", format.format(total))));
    }

    private void returnSellItems(Player player, Inventory inv) {
        for (int slot = 0; slot < 45; slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            giveBack(player, item);
            inv.setItem(slot, null);
        }
    }

    private void sellCategoryAmount(Player player, String category, int amount) {
        Set<Material> materials = categoryMaterials(category);
        if (materials.isEmpty()) return;

        int available = countMatching(player, materials);
        if (available < amount) {
            player.sendMessage(c(prefix() + getConfig().getString("settings.not-enough-items",
                    "&cDu hast nicht genug passende Items.")));
            return;
        }

        int left = amount;
        double total = 0.0;

        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length && left > 0; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir() || !materials.contains(item.getType())) continue;

            double unit = price(item.getType());
            if (unit <= 0) continue;

            int take = Math.min(left, item.getAmount());
            total += take * unit;
            left -= take;

            if (take == item.getAmount()) {
                player.getInventory().setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - take);
            }
        }

        if (total <= 0) {
            player.sendMessage(c(prefix() + getConfig().getString("settings.nothing-sold",
                    "&cDu hast keine verkaufbaren Items.")));
            return;
        }

        economy.depositPlayer(player, total);
        player.sendMessage(c(prefix() + getConfig().getString("settings.sold", "&aVerkauft für &e%money%$&a.")
                .replace("%money%", format.format(total))));
    }

    private int countMatching(Player player, Set<Material> materials) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir() || !materials.contains(item.getType())) continue;
            if (price(item.getType()) <= 0) continue;
            count += item.getAmount();
        }
        return count;
    }

    private Set<Material> categoryMaterials(String category) {
        Set<Material> out = new LinkedHashSet<>();
        for (String raw : getConfig().getStringList("categories." + category + ".materials")) {
            Material mat = Material.matchMaterial(raw);
            if (mat != null) out.add(mat);
        }
        return out;
    }

    private double price(Material material) {
        return Math.max(0.0, getConfig().getDouble("prices." + material.name(), 0.0));
    }

    private ItemStack simple(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(c(name));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack action(Material material, String name, String action, String category, int amount, List<String> lore) {
        ItemStack item = simple(material, name);
        ItemMeta meta = item.getItemMeta();

        if (lore != null && !lore.isEmpty()) {
            meta.setLore(lore.stream().map(this::c).toList());
        }

        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        if (category != null) meta.getPersistentDataContainer().set(categoryKey, PersistentDataType.STRING, category);
        if (amount > 0) meta.getPersistentDataContainer().set(amountKey, PersistentDataType.INTEGER, amount);

        item.setItemMeta(meta);
        return item;
    }

    private void giveBack(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private String prefix() {
        return getConfig().getString("settings.prefix", "");
    }

    private String c(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
