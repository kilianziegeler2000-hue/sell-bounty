# SellSystem 2.1

Paper 1.21.4 / Java 21 / Vault.

## /sell
- Öffnet ein Sell-GUI.
- Alle Minecraft-Items sind verkaufbar.
- Nicht eingetragene Items benutzen `settings.default-price`.
- Spezielle Preise können in `prices:` überschrieben werden.
- Unten rechts bestätigen, unten links abbrechen.

## /sellmulti
- Zeigt Kategorien.
- Jede Kategorie hat eigenen Verkaufs-Fortschritt.
- Start: 1.0x.
- Level 1 schaltet 1.2x bei 20.000$ Verkaufswert frei.
- Weitere Stufen und Ziele stehen in `config.yml -> levels`.
- Verkäufe über `/sell` erhöhen automatisch den Fortschritt der passenden Kategorie.
