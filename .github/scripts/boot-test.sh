#!/bin/bash
# Autostart-Test im Emulator: echter Neustart, doppelte Bootmeldung, Standby, Absturz.
# Ergebnis: boot-report/summary.txt (+ Bildschirmfotos, Protokoll, logcat-Auszug). Bricht den Build nicht ab.
set +e
PKG=de.ostlertekker.ultraiptv.debug
ACT=$PKG/de.ostlertekker.ultraiptv.MainActivity
OUT=boot-report; mkdir -p $OUT
S=$OUT/summary.txt
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "API $api · $(adb shell getprop ro.product.model | tr -d '\r') · $(adb shell getprop ro.build.characteristics | tr -d '\r')" > $S

top() { adb shell dumpsys activity activities 2>/dev/null | grep -E "topResumedActivity|mResumedActivity|ResumedActivity:" | head -1 | tr -d '\r'; }
isTop() { top | grep -q "$PKG/"; }
count() { adb shell dumpsys activity activities 2>/dev/null | grep -cE "ActivityRecord\{[^}]*$PKG/de.ostlertekker.ultraiptv.MainActivity"; }
waitBoot() { adb wait-for-device; for i in $(seq 1 120); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && return 0; sleep 2; done; return 1; }
prefs() {
  adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs; cat > shared_prefs/autostart.xml'" <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="master" value="true" />
    <boolean name="on" value="true" />
    <boolean name="crash" value="$1" />
</map>
XML
}
result() { echo "$1: $2" | tee -a $S; }
shot() { adb exec-out screencap -p > $OUT/$1.png 2>/dev/null; }

adb install -r -g app/build/outputs/apk/debug/*.apk >/dev/null || { result "Installation" "FEHLER"; exit 0; }
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
echo "Overlay-Berechtigung: $(adb shell appops get $PKG SYSTEM_ALERT_WINDOW | tr -d '\r')" >> $S

# Einmal von Hand öffnen (hebt den „gestoppt“-Zustand nach der Installation auf), dann Einstellung setzen
adb shell am start -W -n $ACT >/dev/null; sleep 12
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am kill $PKG; sleep 1
prefs false

# --- Test 1: echter Neustart
adb reboot; waitBoot || result "Test 1 Neustart" "Emulator startet nicht neu"
for i in $(seq 1 30); do isTop && break; sleep 2; done
sleep 4; shot 1_nach_neustart
if isTop; then result "Test 1 Neustart → App vorne" "OK ($(top))"; else result "Test 1 Neustart → App vorne" "FEHLER ($(top))"; fi
echo "Instanzen MainActivity: $(count)" >> $S

# --- Test 2: zweite Bootmeldung im selben Boot → kein Doppelstart
adb shell input keyevent KEYCODE_HOME; sleep 3
adb root >/dev/null 2>&1; sleep 3
br=$(adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p $PKG 2>&1 | tr -d "\r"); echo "Broadcast: $br" >> $S
sleep 15
if ! echo "$br" | grep -q "Broadcast completed"; then result "Test 2 doppelte Bootmeldung" "nicht prüfbar (Senden nicht erlaubt)"; elif isTop; then result "Test 2 doppelte Bootmeldung ignoriert" "FEHLER (App kam erneut nach vorne)"; else result "Test 2 doppelte Bootmeldung ignoriert" "OK"; fi

# --- Test 3: Standby → Aufwachen startet die App nicht neu, keine zweite Instanz
adb shell am start -W -n $ACT >/dev/null; sleep 5
before=$(count)
adb shell input keyevent KEYCODE_SLEEP; sleep 6; adb shell input keyevent KEYCODE_WAKEUP; sleep 6
after=$(count)
if [ "$after" = "$before" ]; then result "Test 3 Standby ohne Doppelstart" "OK ($before → $after Instanz)"; else result "Test 3 Standby ohne Doppelstart" "FEHLER ($before → $after)"; fi

# --- Test 4: Absturz im Vordergrund → App öffnet sich wieder; zweiter/dritter Absturz → Sperre greift
adb shell am force-stop $PKG; prefs true
adb shell am start -W -n $ACT >/dev/null; sleep 8
adb shell am crash $PKG >/dev/null 2>&1; sleep 15
if isTop; then result "Test 4 Absturz → neu geöffnet" "OK"; else result "Test 4 Absturz → neu geöffnet" "FEHLER ($(top))"; fi
adb shell am crash $PKG >/dev/null 2>&1; sleep 15
adb shell am crash $PKG >/dev/null 2>&1; sleep 15
if isTop; then result "Test 5 Neustart-Sperre nach 2 Abstürzen" "FEHLER (öffnet sich weiter)"; else result "Test 5 Neustart-Sperre nach 2 Abstürzen" "OK"; fi

# --- Test 6: nach „Beenden erzwingen“ → Android stellt keine Bootmeldung zu (Plattformgrenze, nur dokumentiert)
prefs false
adb shell am force-stop $PKG
adb reboot; waitBoot; sleep 40
if isTop; then result "Test 6 nach Beenden erzwingen + Neustart" "App öffnete sich"; else result "Test 6 nach Beenden erzwingen + Neustart" "App öffnete sich nicht (erwartet: Android sperrt gestoppte Apps)"; fi

adb shell "run-as $PKG cat shared_prefs/autostart.xml" > $OUT/autostart_prefs.xml 2>/dev/null
adb logcat -d | grep -E "UltraAutoStart|BackgroundActivityStart|Background activity|BAL|ActivityTaskManager.*$PKG" > $OUT/logcat.txt 2>/dev/null
echo; cat $S
exit 0
