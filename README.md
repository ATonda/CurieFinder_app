# ⚛️ CurieFinder

**🇨🇿 [Čeština](#-čeština) | 🇬🇧 [English](#-english)**

---

## 🇨🇿 Čeština

**Android aplikace pro detekci, mapování a vyhledávání ionizujícího záření.**

CurieFinder není náhrada za výrobní aplikace přístrojů — je to terénní nástroj určený pro **lokalizaci zdrojů záření**, mapování míst se zvýšenou aktivitou, prospekci a sledování rudných žil. Optimalizováno pro práci na malé ploše (metry až stovky metrů), ne pro záznam dlouhých tras.

### 📱 Funkce

- Zobrazení CPS a µSv/h v reálném čase
- Vyhlazený údaj pomocí exponenciálního klouzavého průměru (EMA)
- Záznam GPS tras → export do CSV
- Generování **teplotní mapy (heatmap)** s podkladem OpenStreetMap
- **Body zájmu (POI)** s ikonami a popisy
- Import/export CSV (trasy, heatmapy, vlastní vrstvy, důlní díla)
- Zvuková signalizace — režim kliknutí nebo tónu
- Sledování pozadí (BG) — přijímáno ze zařízení paketem `BG=`
- 6 jazyků: 🇨🇿 CS · 🇸🇰 SK · 🇮🇹 IT · 🇬🇧 EN · 🇵🇱 PL · 🇭🇺 HU

### 🔌 Podporovaná zařízení

| Zařízení | Protokol | CPS | µSv/h | BG |
|---|---|:---:|:---:|:---:|
| CurieFinder HW | BT Classic SPP | ✓ | — | ✓ |
| Modul CurieBT | BT Classic SPP | ✓ | volitelně | — |
| Raysid | BLE | ✓ | ✓ | — |
| RadiaCode RC-10x | BLE | ✓ | ✓ | — |
| RadPro ≥ 3.1.1 (FNIRSI GC-01) | USB CDC | ✓ | ✓ | — |

> µSv/h se zobrazuje pouze pokud zařízení posílá `RATE=` ve svém datovém paketu.
> BG (pozadí) se zobrazuje pouze pokud zařízení posílá `BG=`.

### 🛠️ Modul CurieBT

Externí Bluetooth adaptér pro libovolný stávající Geiger-Müllerův přístroj.
Firmware pouze pro **ESP32 WROOM-32** — ESP32-S3, C3 a S2 nejsou podporovány (nemají BT Classic SPP).

**Klíčové parametry:**
- GPIO 23 — vstup impulzů z výstupu komparátoru (max 3,3 V — pro 5V logiku nutný dělič napětí)
- GPIO 34 — ADC měření napětí baterie (pouze vstup, max 3,3 V)
- WiFi konfigurační portál: 60 s po zapnutí na adrese `192.168.4.1`
- Hluboký spánek po 5 minutách bez BT připojení
- Frekvence aktualizace: 4× za sekundu

**Podporované GM trubice:** SBM-20, SBM-19, SI-3BG, SI-22G, LND-712, J305, vlastní faktor

### 📂 Formát CSV

```
# CurieFinder CSV v1
# title=Název trasy
# created=2026-05-28 10:50:00

timestamp,lat,lon,cps,poi,label,icon,rate
2026-05-28 10:50:15,50.0872,14.4213,5.20,,,,2.89
```

Vlastní POI / vrstvy:
```
# CurieFinder
lat,lon,label,icon,desc
50.0872,14.4213,Šachta Antonín,mine,Hloubka 45 m
```

### 📲 Instalace

Stáhni nejnovější APK ze sekce [Releases](https://github.com/ATonda/CurieFinder_app/releases) a nainstaluj přímo do zařízení s Androidem (Android 8.0+).

> Povol instalaci z neznámých zdrojů: **Nastavení → Zabezpečení → Neznámé zdroje**

### 📄 Licence

Volně k použití. Vyžadováno uvedení autora — **© ATonda**

### 🔗 Odkazy

- 📖 [Wiki — uživatelská příručka](https://github.com/ATonda/CurieFinder_app/wiki)
- 🐛 [Issues a hlášení chyb](https://github.com/ATonda/CurieFinder_app/issues)
- 📦 [Releases / stažení APK](https://github.com/ATonda/CurieFinder_app/releases)

---

## 🇬🇧 English

**Android application for ionizing radiation detection, mapping and prospecting.**

CurieFinder is not a replacement for manufacturer apps — it is a field tool designed for **localization of radiation sources**, mapping areas with elevated activity, prospecting and tracing ore veins. Optimized for small-area work (meters to hundreds of meters), not long-distance track logging.

### 📱 Features

- Real-time CPS and µSv/h display
- Smoothed readout via Exponential Moving Average (EMA)
- GPS track logging → CSV export
- **Heatmap** generation with OpenStreetMap overlay
- **Points of Interest (POI)** with icons and descriptions
- Import/export CSV (tracks, heatmaps, custom layers, mine workings)
- Sound signalization — click or tone mode
- Background (BG) tracking — received from device via `BG=` packet
- 6 languages: 🇨🇿 CS · 🇸🇰 SK · 🇮🇹 IT · 🇬🇧 EN · 🇵🇱 PL · 🇭🇺 HU

### 🔌 Supported Devices

| Device | Protocol | CPS | µSv/h | BG |
|---|---|:---:|:---:|:---:|
| CurieFinder HW | BT Classic SPP | ✓ | — | ✓ |
| CurieBT module | BT Classic SPP | ✓ | optional | — |
| Raysid | BLE | ✓ | ✓ | — |
| RadiaCode RC-10x | BLE | ✓ | ✓ | — |
| RadPro ≥ 3.1.1 (FNIRSI GC-01) | USB CDC | ✓ | ✓ | — |

> µSv/h is displayed only if the device sends `RATE=` in its data packet.
> BG (background) is displayed only if the device sends `BG=`.

### 🛠️ CurieBT Module

External Bluetooth adapter for any existing Geiger-Mueller instrument.
Firmware for **ESP32 WROOM-32** only — ESP32-S3, C3 and S2 are not supported (no BT Classic SPP).

**Key parameters:**
- GPIO 23 — pulse input from comparator output (3.3V max — voltage divider required for 5V logic)
- GPIO 34 — battery voltage ADC (input-only, 3.3V max)
- WiFi configuration portal: 60 s after power-on at `192.168.4.1`
- Deep sleep after 5 min without BT connection
- Update rate: 4× per second

**Supported GM tubes:** SBM-20, SBM-19, SI-3BG, SI-22G, LND-712, J305, custom factor

### 📂 CSV Format

```
# CurieFinder CSV v1
# title=Track name
# created=2026-05-28 10:50:00

timestamp,lat,lon,cps,poi,label,icon,rate
2026-05-28 10:50:15,50.0872,14.4213,5.20,,,,2.89
```

Custom POI / layers:
```
# CurieFinder
lat,lon,label,icon,desc
50.0872,14.4213,Shaft Antonín,mine,Depth 45m
```

### 📲 Installation

Download the latest APK from [Releases](https://github.com/ATonda/CurieFinder_app/releases) and install directly on your Android device (Android 8.0+).

> Allow installation from unknown sources: **Settings → Security → Unknown sources**

### 📄 License

Free to use. Attribution required — **© ATonda**

### 🔗 Links

- 📖 [Wiki — User Manual](https://github.com/ATonda/CurieFinder_app/wiki)
- 🐛 [Issues & Bug Reports](https://github.com/ATonda/CurieFinder_app/issues)
- 📦 [Releases / APK Download](https://github.com/ATonda/CurieFinder_app/releases)
- 