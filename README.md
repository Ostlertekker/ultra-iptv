# OSTLERTEKKER PRESENTS – ULTRA IPTV

**THE ULTIMATE STREAMING EXPERIENCE** – native Android-TV-/Fire-TV-App (Kotlin, Jetpack Compose, Media3).

ULTRA IPTV ist ein **Player**. Er enthält keine Inhalte. Nutze ausschließlich eigene, lizenzierte oder vom Anbieter autorisierte Quellen. DRM- und Zugangsbeschränkungen werden nicht umgangen.

---

## Installation auf dem Fire TV (ohne PC)

1. Fire TV: **Einstellungen › Mein Fire TV › Entwickleroptionen › Apps unbekannter Herkunft** → für die App **Downloader** aktivieren.
   (Entwickleroptionen fehlen? *Mein Fire TV › Info* öffnen und 7× auf den Gerätenamen klicken.)
2. App **Downloader** aus dem Amazon Appstore installieren und öffnen.
3. Diese Adresse eingeben:
   ```
   https://github.com/Ostlertekker/ultra-iptv/releases/latest/download/UltraIPTV.apk
   ```
4. **Installieren** wählen. Danach erscheint ULTRA IPTV unter *Apps* (mit TV-Banner).
5. Beim ersten Start: Quelle einrichten (Xtream-Codes-Server + Benutzer + Passwort **oder** M3U-Adresse, optional XMLTV-EPG).

Updates: Jeder Push auf `main` baut automatisch eine neue Version (GitHub Actions) und veröffentlicht sie als *Latest Release* – einfach den Downloader-Link erneut aufrufen.

Mit PC/ADB: `adb connect <IP-des-Fire-TV>` und `adb install -r UltraIPTV.apk`.

| Datei | Zweck |
|---|---|
| `UltraIPTV.apk` | Release-Build (signiert), für den normalen Gebrauch |
| `UltraIPTV-debug.apk` | Debug-Build (eigene Paket-ID `…ultraiptv.debug`, parallel installierbar) |

---

## Funktionen

| Bereich | Umsetzung |
|---|---|
| **Intro** | Partikel setzen sich zum OSTLERTEKKER-Logo zusammen, Lichtspuren, 3D-Extrusion, metallischer Glanzstreifen, „ULTRA IPTV“-Reveal. Original-Sound wird zur Laufzeit synthetisiert (Sub-Bass, Riser, metallische Akzente, Impact, Schimmer). Erststart lang, danach kurz, jederzeit per Taste überspringbar, abschaltbar. |
| **ThemeEngine** | 10 Themes: GALAXY, BLACK GOLD, CYBERPUNK, GLASS PREMIUM, AURORA, OCEAN, MATRIX, SPACE, DIAMOND, RED INFERNO. Jedes Theme ändert Hintergrund-Effekt, Farben, Schrift, Karten, Buttons, Fokus, Ecken, Menü-Effekte, Übergänge, Ladeanzeige und Klänge. Galerie mit Live-Vorschau, Akzentfarbe, eigenes Hintergrundbild, Intensitäten, Zurücksetzen. Neue Themes: `ThemeRegistry.register(spec)`. |
| **Galaxy-Menü** | `AnimatedMenuLabel` + `NavRail`: Verlaufsschrift mit wandernden Reflexen, kosmisches Glühen, umlaufende Sterne, Starburst beim Fokuswechsel, Sternschnuppen hinter den Labels auf variierenden, leicht gekrümmten Bahnen. Kein Flackern, Text bleibt lesbar. |
| **Animationsprofile** | ECONOMY / BALANCED / ULTRA, automatisch nach Geräteprofil oder manuell; Bewegung reduzieren; Hintergrundanimation aus; Pause bei App im Hintergrund (lifecycle-aware); Partikel vorab berechnet (keine Allokation pro Frame). |
| **Live TV** | M3U und Xtream Codes API, Kategorien, Logos, Favoriten, Zuletzt gesehen, Sortierung, Jetzt/Danach, Live-Vorschau (ab Geräteprofil STANDARD), schnelles Umschalten (CH▲/▼ oder Pfeiltasten im Player), Senderliste im Player. |
| **EPG** | XMLTV (auch .gz), streamend geparst, Zeitzonen aus den Daten, manuelle Korrektur, TV-Guide mit Zeitleiste, automatische Aktualisierung. |
| **Filme / Serien** | Poster-Raster mit Paging, Kategorien, Genres, Sortierung, Details, Merkliste, Fortsetzen, Staffeln/Episoden, Fortschrittsbalken, automatische nächste Folge. |
| **Smart Skip** | Intro / Rückblick / Abspann / Credits. Quellen: Anbieter-Zeitmarken, eigene Markierungen, bestätigte Markierungen, Staffel-Vorschläge. Jede Marke wird gegen die echte Episodenlänge validiert; automatisch nur ab 80 % Sicherheit; zurückspulen = manuelle Übersteuerung. Countdown, Abbrechen, „Nächste Folge“. |
| **Player** | Media3/ExoPlayer (HLS, DASH, MPEG-TS, MP4 …), Hardware-Decoder mit Fallback, adaptive Bitrate, Pufferprofile, Ton-/Untertitelspuren, Bildformat, Tempo, automatische Wiederverbindung, Statistik-Overlay (Decoder, Auflösungen, FPS, verworfene Frames, Durchsatz). Ein Player wird wiederverwendet. |
| **UltraQualityEngine** | AUTO / BESTE / DATENSPARER / MANUELL, begrenzt auf echte Decoder- und Display-Fähigkeiten. Bildwiederholraten-Anpassung (z. B. 25 fps → 50 Hz), nur wenn das Display den Modus anbietet. 4K und 120 FPS werden nie vorgetäuscht. |
| **DeviceCapabilityManager** | Hersteller, Modell, Fire OS, RAM, Decoder (HW/SW, max. Auflösung, 4K120), Display-Modi, HDR-Typen, Audio-Passthrough (AC-3, E-AC-3, Atmos/JOC, DTS, TrueHD), thermischer Zustand. Profile ENTRY/STANDARD/PREMIUM/HIGH PERFORMANCE. |
| **Diagnose** | Alle Werte mit Kennzeichnung GEMESSEN / GESCHÄTZT / NICHT VERFÜGBAR. |
| **Profile** | Mehrere Profile mit Avatar, PIN (PBKDF2-Hash), Altersgrenze, eigenem Theme, Favoriten, Verlauf, Audio-/Untertitelsprache. |
| **Sicherheit** | Zugangsdaten verschlüsselt (EncryptedSharedPreferences/Keystore), Xtream-Passwörter nie in der Datenbank (Stream-URLs werden erst beim Abspielen gebaut), Log-Schwärzung, Eingabeprüfung, Timeouts, Room mit gebundenen Parametern. |

Bedienung komplett per Fernbedienung: D-Pad, OK, Zurück, Play/Pause, ⏩/⏪, CH▲/▼, Menü. OK lange drücken auf einem Sender = Favorit.

## Neu in Version 1.1

| Bereich | Umsetzung |
|---|---|
| **Live-TV-Vorschau** | Sender auswählen genügt: Name, Nummer, Logo, Land, Jetzt/Danach mit Fortschritt und Gesundheits-/Qualitätswerte erscheinen sofort; die Vorschau startet nach 0,35 s Verweildauer im selben Player – beim Drücken von OK läuft der Sender ohne Neustart im Vollbild weiter. |
| **App-Updater** | Einstellungen › App-Updates: prüft GitHub-Releases, lädt die neue APK und startet die Installation (Einstellungen/Favoriten bleiben erhalten). Automatische tägliche Prüfung mit Benachrichtigung. |
| **Multi-Accounts** | Beliebig viele Anbieter-Konten, umschaltbar, pro Profil festlegbar, Kontostatus (Ablauf, Verbindungen) für Xtream. Funktioniert unabhängig vom Standort; Verbindungslimits des Anbieters werden nicht umgangen. |
| **Intelligente Suche** | Versteht natürliche Anfragen („deutsche Horrorfilme 80er“, „Filme mit Tom Hanks“, „türkische Sender“), Tippfehler-Toleranz, Umlaut-Normalisierung, Vorschläge, Verlauf, Sprachsuche (System-Spracherkenner bzw. Fire-TV-Mikrofon in der Tastatur), „Ähnliche Titel“. Läuft komplett lokal – keine Cloud-KI. |
| **TV World Map** | Weltkarte mit allen erkannten Ländern; Länder werden automatisch aus Kategorie-/Sendernamen erkannt (DE:, \|TR\|, UK …). |
| **Stream-Analyse** | Sender-Gesundheitsprüfung (Antwortzeit, Datenrate, HLS-Auflösung, TS-Sync), Sender-Qualität aus echter Wiedergabe, Buffering-Analyse mit Empfehlung, automatische Fehlererkennung (401/403/404 erklärt) und Ausweichsender. |
| **Netzwerk** | Verbindungsdiagnose (Netzart, IP, DNS/TCP/Anmeldung beim Anbieter, Kontolimits) und Speedtest (Ping, Jitter, Download, Upload, Anbieter-Durchsatz). |
| **Backup & Sync** | Backup/Wiederherstellung (optional mit Konten), Gerätekopplung im Heimnetz mit 6-stelligem Code, Browser-Oberfläche fürs Handy, Übernahme von einem anderen Gerät. |
| **Watch Together** | Mehrere Fernseher im selben Heimnetz folgen dem Host (Sender/Film, Position, Pause). |
| **Benachrichtigungen** | Programm-Erinnerungen, neue Folgen bei Lieblingsserien, Kontoablauf, App-Updates; automatische Playlist-Aktualisierung im Hintergrund. |
| **Player** | Gemessene Bildrate, Auflösungs-/HDR-Anzeige, Buffer- und Nachlade-Statistik, automatische Qualitätsoptimierung, Untertitel-Stil (Größe, Farbe, Hintergrund), Info-Banner beim Umschalten, Mini-Player, Bild-in-Bild (wo unterstützt), Hintergrund-Audio per Media-Session. |
| **Startseite** | Dynamische Kategorien: Neu diese Woche, 4K/UHD, Sport live, Kinder & Familie, Klassiker, Sender aus deinem Land, Lieblingsgenre. |

**Grenzen:** Watch Together und Gerätesync funktionieren im selben Heimnetz (kein Cloud-Server). Bild-in-Bild bieten viele Fire-TV-Modelle nicht an – dann gibt es den Mini-Player. Darsteller-Suche hängt von den Angaben des Anbieters ab.

## CRAZY MOTION ENGINE (Version 1.2)

Modulare Animations-Engine (`core/motion`), integriert in Navigation, Menüs, Karten und Player.

* **Menu Entrance (14):** Gravity Drop, Explosion, Hologram Materialize, 3D Flip, Cyber Glitch, Liquid Morph, Black Hole, Meteor Shower, Domino, Magnetic Pull, Shatter Glass, Portal, Matrix Rain, Elastic Bounce – auf Menüleiste, Poster-/Senderreihen, Raster und Player-Steuerung.
* **Navigation Effects (8):** Magnetic Focus, 3D Tilt, Hover Explosion, Elastic Focus, Depth Shift, Pulse Glow, Perspective Zoom, Trail Effect – zentral in `tvFocusable`, also auf jedem fokussierbaren Element.
* **Page Transitions (7):** Portal, Shatter, Warp Speed, Liquid, Glitch, Zoom Tunnel, Cinematic Reveal – für App-Seiten, das Öffnen des Players und den Senderwechsel (als Overlay über dem Video, der Stream startet ohne Verzögerung). Dazu 7 Ausgangsanimationen.
* **Animation Studio** (Einstellungen): Eingang/Ausgang/Übergänge/Fokus wählen, Geschwindigkeit, Intensität, Partikel, jede Animation einzeln an/aus, Zufallsmodus, eigene Profile + 4 Vorlagen, Live-Vorschau jeder Animation.
* **Technik:** GPU-Transformationen über `graphicsLayer` (RenderNode), Clip-Reveals/Overlays über `drawWithContent`, analytische Physik (Gravitation mit Rückprall, gedämpfte Federn, Bounce), Federn für Fokus. Keine Tasten werden blockiert, neue Seiten sind sofort bedienbar. Reduzierter Modus, automatische Vereinfachung auf Entry-Geräten (weniger Partikel/Splitter, kürzere Laufzeit), Partikel- und Ereignis-Obergrenzen, Overlays laufen nur solange Effekte aktiv sind.

---

## Ehrliche Grenzen

* **KI-Upscaling:** Das Modul `UltraAIUpscaling` prüft Gerät, Speicher und Temperatur, enthält aber in dieser Version **kein** neuronales Modell. Es meldet daher „nicht verfügbar“. Fire-TV-Sticks haben dafür neben dem Video-Decoder keine realistische Echtzeit-Reserve. Die normale TV-Skalierung wird nicht als KI bezeichnet.
* **Smart Skip ohne Zeitmarken:** Xtream/M3U liefern fast nie Intro-Zeitmarken. Automatisches Überspringen funktioniert deshalb, sobald du eine Folge einmal markierst (Player › Smart Skip) – die übrigen Folgen der Staffel bekommen das als Vorschlag. Audio-/Bild-Fingerprinting läuft bewusst nicht auf dem Stick.
* **HDR10+/Dolby Vision/Atmos:** werden nur genutzt, wenn Quelle, Gerät, Lizenz und HDMI-Kette es melden. Die App behauptet nichts, was nicht gemeldet wird.
* **Altersfreigaben:** IPTV-Anbieter liefern meist keine FSK-Daten pro Titel. Der Jugendschutz filtert Erwachsenen-Kategorien (XXX/Adult/18+) und schützt Profile per PIN.
* **Audio-Verzögerung:** stellt Fire OS systemweit bereit; Media3 bietet dafür keine zuverlässige App-Einstellung.
* **Vega OS:** Die neueren Fire-TV-Geräte mit Vega OS führen keine Android-APKs aus – dafür wäre ein separates Projekt nötig.
* **Tests:** Unit-Tests laufen in CI. Der instrumentierte Datenbanktest (`connectedDebugAndroidTest`) braucht ein echtes Gerät. Hardware-Dekodierung, HDR und Bildwiederholraten lassen sich nur auf echten Fire-TV-Geräten prüfen.

---

## Eigenes OSTLERTEKKER-Logo einsetzen

Der Platzhalter ist ein typografisches Logo. Für das echte Logo:

1. Logo als transparentes PNG (empfohlen ≥ 1600 px breit) oder Vector Drawable (aus SVG via Android Studio › *New › Vector Asset*) speichern als
   `app/src/main/res/drawable-nodpi/ostlertekker_logo.png` (bzw. `res/drawable/ostlertekker_logo.xml`).
2. Committen und pushen – das Intro tastet automatisch das Logo ab, lässt die Partikel daraus entstehen und legt 3D-Tiefe und Glanz darauf.

Banner (`res/drawable-xhdpi/banner.png`, 320×180) und Icon (`res/mipmap-*/ic_launcher.png`) lassen sich genauso ersetzen.

---

## Selbst bauen

Voraussetzungen: JDK 17, Android SDK (API 35). `./gradlew assembleDebug assembleRelease testDebugUnitTest`

APKs: `app/build/outputs/apk/{debug,release}/`

**Signatur:** Der Release-Build wird mit dem mitgelieferten Sideload-Schlüssel `signing/ultra-iptv-sideload.jks` signiert, damit Updates sich über die alte Version installieren lassen. Für eine Store-Veröffentlichung einen eigenen Schlüssel als GitHub-Secrets hinterlegen (`ULTRA_KEYSTORE_B64`, `ULTRA_KEYSTORE_PASSWORD`, `ULTRA_KEY_ALIAS`, `ULTRA_KEY_PASSWORD`) – der Workflow nutzt ihn dann automatisch.

---

## Architektur

Ein Gradle-Modul (`app`) mit strikt getrennten Paketen nach Clean Architecture / MVVM. Die Paketgrenzen entsprechen den geplanten Modulen und lassen sich 1:1 in Gradle-Module auslagern:

```
core/model          Domänenmodelle
core/database       Room (Entities, DAOs)
core/network        M3U-, XMLTV-Parser, Xtream-URLs, Log-Schwärzung
core/data           Repositories (Sync, Inhalte, EPG, Nutzer, Einstellungen/DataStore)
core/security       Verschlüsselter Speicher, Stream-Resolver, PIN-Hash, Validierung
core/device         DeviceCapabilityManager
core/player         PlayerEngine (Media3), UltraQualityEngine
core/audio          AudioExperienceEngine (Synthese, UI-/Intro-Sounds)
core/design         ThemeEngine, ThemeRegistry, 10 Themes
core/animation      Lifecycle-bewusste Hintergrund-Effekte, Partikel
core/ui             TV-Komponenten, Galaxy-Menü-Engine, NavRail
feature/*           intro, home, livetv (+EPG), movies, series, favorites, search,
                    settings (+Themes, Diagnose), profiles, player, smartskip, upscaling
di/                 Hilt-Module
```

Stack: Kotlin 2.0, Compose (BOM 2024.12), Media3 1.5, Hilt 2.52 (KSP), Room 2.6, DataStore, Retrofit/OkHttp, kotlinx.serialization (Streaming-JSON für große Kataloge), Coil, Gradle Version Catalog.
