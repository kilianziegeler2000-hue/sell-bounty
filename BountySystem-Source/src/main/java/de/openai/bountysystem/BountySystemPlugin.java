package de.openai.bountysystem;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;

public final class BountySystemPlugin extends JavaPlugin implements Listener {
    private Economy economy;
    private File dataFile;
    private YamlConfiguration data;
    private final DecimalFormat format = new DecimalFormat("0.##");

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!setupEconomy()) {
            getLogger().severe("Keine Vault-Economy gefunden.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        dataFile = new File(getDataFolder(), "bounties.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        Objects.requireNonNull(getCommand("bounty")).setExecutor(this);
        Objects.requireNonNull(getCommand("bounty")).setTabCompleter((sender, command, alias, args) -> {
            if (args.length == 1) return filter(args[0], List.of("add"));
            if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
                return filter(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            }
            return Collections.emptyList();
        });

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
        if (args.length == 0) {
            showTop(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("add")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Nur Spieler können Bounties setzen.");
                return true;
            }
            if (!player.hasPermission("bountysystem.add")) {
                send(player, "&cKeine Berechtigung.");
                return true;
            }
            if (args.length < 3) {
                send(player, "&eBenutzung: /bounty add <spieler> <betrag>");
                return true;
            }

            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            if (!target.hasPlayedBefore() && !target.isOnline()) {
                send(player, "&cSpieler nicht gefunden.");
                return true;
            }
            if (target.getUniqueId().equals(player.getUniqueId())) {
                send(player, "&cDu kannst keine Bounty auf dich selbst setzen.");
                return true;
            }

            double amount;
            try {
                amount = Double.parseDouble(args[2].replace(',', '.'));
            } catch (NumberFormatException ex) {
                send(player, getConfig().getString("settings.invalid"));
                return true;
            }

            double min = Math.max(0.01, getConfig().getDouble("settings.minimum-bounty", 1.0));
            if (amount < min) {
                send(player, "&cMinimum: &e" + format.format(min) + "$");
                return true;
            }

            if (!economy.has(player, amount)) {
                send(player, getConfig().getString("settings.not-enough"));
                return true;
            }

            if (!economy.withdrawPlayer(player, amount).transactionSuccess()) {
                send(player, "&cGeld konnte nicht abgezogen werden.");
                return true;
            }

            String targetName = target.getName() == null ? args[1] : target.getName();
            double total = getBounty(target.getUniqueId()) + amount;
            setBounty(target.getUniqueId(), targetName, total);

            send(player, getConfig().getString("settings.added")
                    .replace("%money%", format.format(amount))
                    .replace("%target%", targetName));

            if (target.isOnline() && target.getPlayer() != null) {
                send(target.getPlayer(), getConfig().getString("settings.received")
                        .replace("%money%", format.format(amount))
                        .replace("%total%", format.format(total)));
            }
            return true;
        }

        showTop(sender);
        return true;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) return;

        double bounty = getBounty(victim.getUniqueId());
        if (bounty <= 0) return;

        economy.depositPlayer(killer, bounty);
        setBounty(victim.getUniqueId(), victim.getName(), 0.0);

        send(killer, getConfig().getString("settings.claimed")
                .replace("%target%", victim.getName())
                .replace("%money%", format.format(bounty)));
    }

    private void showTop(CommandSender sender) {
        int size = Math.max(1, getConfig().getInt("settings.top-size", 10));
        List<Entry> entries = new ArrayList<>();

        var section = data.getConfigurationSection("bounties");
        if (section != null) {
            for (String uuid : section.getKeys(false)) {
                double amount = section.getDouble(uuid + ".amount", 0.0);
                if (amount <= 0) continue;
                String name = section.getString(uuid + ".name", "Unbekannt");
                entries.add(new Entry(name, amount));
            }
        }

        entries.sort(Comparator.comparingDouble(Entry::amount).reversed());

        sender.sendMessage(c(getConfig().getString("settings.top-title")));
        if (entries.isEmpty()) {
            sender.sendMessage(c("&7Noch keine aktiven Bounties."));
            return;
        }

        for (int i = 0; i < Math.min(size, entries.size()); i++) {
            Entry e = entries.get(i);
            sender.sendMessage(c("&e#" + (i + 1) + " &f" + e.name() + " &7- &c" + format.format(e.amount()) + "$"));
        }
    }

    private double getBounty(UUID uuid) {
        return data.getDouble("bounties." + uuid + ".amount", 0.0);
    }

    private void setBounty(UUID uuid, String name, double amount) {
        String path = "bounties." + uuid;
        if (amount <= 0) data.set(path, null);
        else {
            data.set(path + ".name", name);
            data.set(path + ".amount", amount);
        }
        saveData();
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("bounties.yml konnte nicht gespeichert werden: " + e.getMessage());
        }
    }

    private List<String> filter(String input, List<String> values) {
        String lower = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private String c(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private void send(Player player, String text) {
        player.sendMessage(c(getConfig().getString("settings.prefix", "") + (text == null ? "" : text)));
    }

    private record Entry(String name, double amount) {}
}
