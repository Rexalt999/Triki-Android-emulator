# TrikiEmu na Androida

Port firmware'u ESP32 (`firmware/src/main.cpp`) na aplikację Android: **telefon udaje kapsel
Żabka Triki** — reklamuje się jak Triki (BLE *peripheral*), wystawia Nordic UART + Battery + Device
Information i po komendzie START nadaje strumień IMU ~100 Hz (ramki 14 B, cięte 20/20/2 B).
Zamiast płytki ESP32 i narzędzi PC ruch podajesz **z czujników telefonu**, przyciskami na
ekranie, ze wzorca albo z pliku CSV.

Protokół jest identyczny jak w ESP32 — zob. [../firmware/README.md](../firmware/README.md).

## Ważne ograniczenia (przeczytaj przed użyciem)

1. **MAC nie da się odtworzyć.** Żappka — według głównego README — rozpoznaje zapamiętany kapsel po
   **adresie MAC** (emulator z innym adresem dostaje „nie znaleziono triki"). ESP32 ustawia
   statyczny adres random przez `ble_hs_id_set_rnd()`; **Android nie daje aplikacji żadnego
   sposobu ustawienia adresu reklamy** (system używa własnego, rotującego adresu prywatnego;
   obejście wymaga roota/zmian w stosie BT). Aplikacja odtwarza całą resztę kontraktu (nazwa,
   manufacturer data, UUID w scan response, GATT, strumień), więc centrale dopasowujące po
   nazwie/UUID (np. nRF Connect, skaner TrikiScope — *niesprawdzone*) powinny ją widzieć,
   ale **czy Żappka ją zaakceptuje, nie jest zweryfikowane** i — jeśli dopasowuje po MAC — nie zadziała.
2. **Dwa telefony.** Telefon nie połączy się sam ze sobą: ten z TrikiEmu udaje kapsel, a Żappka (lub
   skaner) musi działać na **drugim** telefonie.
3. **Zapis wyniku w grach nadal wymaga prawdziwego kapsla** (uwierzytelnienie challenge–response,
   zob. główne README). Emulator ignoruje komendy `09`/`0a`. Zakres projektu bez zmian:
   interop / nauka / testy — nie farmienie nagród.
4. Android **nie pozwala peripheral'owi żądać interwału połączenia** (ESP32 prosił o 7,5–15 ms) —
   wybiera go central (drugi telefon). Zwykle wystarcza na ~100 Hz.
5. Aplikacja **na czas działania zmienia nazwę Bluetooth telefonu** (reklama używa nazwy adaptera)
   na `Triki 1234567890` (możesz zmienić, max 20 bajtów) i **przywraca ją po wyłączeniu BLE** albo
   przy następnym uruchomieniu aplikacji, gdyby została zabita.
6. Trzymaj aplikację **na pierwszym planie** (ekran jest utrzymywany włączony). W tle Android
   ogranicza czujniki; strumień nadal pójdzie, ale z nieaktualnymi odczytami.

## Wymagania

- Android 8.0+ (API 26), telefon ze wsparciem **BLE peripheral** (reklamowanie) — większość
  telefonów z ostatnich lat; aplikacja zgłosi błąd, jeśli brak.
- Uprawnienia: Android 12+ „Urządzenia w pobliżu" (`BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`);
  starsze wersje — zwykłe uprawnienia Bluetooth. Lokalizacja **nie** jest potrzebna.

## Jak zdobyć APK

**A. GitHub Actions (bez instalowania czegokolwiek).** Wypchnij repo na GitHuba (albo użyj forka):
workflow [`android.yml`](../.github/workflows/android.yml) zbuduje APK; pobierz artefakt
**TrikiEmu-debug-apk** z zakładki *Actions* (zip z `app-debug.apk`). Można też uruchomić ręcznie
(*Run workflow*).

**B. Android Studio.** *File → Open* → katalog `android/` → *Build → Build APK(s)*.
Plik: `android/app/build/outputs/apk/debug/app-debug.apk`.

**C. Wiersz poleceń** (JDK 17 + Android SDK z platformą 34, `ANDROID_HOME` ustawione):

```bash
cd android
./gradlew assembleDebug
```

Instalacja: skopiuj `app-debug.apk` na telefon i otwórz (zezwól na instalację z nieznanych
źródeł) albo `adb install app-debug.apk`. APK jest podpisany kluczem debug — do własnego użytku.

## Użycie

1. Uruchom **TrikiEmu**, przyznaj uprawnienia „Urządzenia w pobliżu".
2. Wybierz **Źródło ruchu** (domyślnie czujniki telefonu).
3. **Włącz BLE (reklama)** — status przejdzie na „reklama…".
4. Na drugim telefonie: Żappka (lub nRF Connect/TrikiScope) → połącz. Po komendzie START status:
   **STRUMIEŃ** (licznik ramek i „pominięte cykle" — jak w logu serial na ESP32).
5. **Wyłącz BLE** po zakończeniu (przywraca nazwę Bluetooth).

### Źródła ruchu

| Źródło | Odpowiednik z wersji ESP32/PC |
|---|---|
| Czujniki telefonu | wbudowany IMU M5StickC Plus2 — akcelerometr [g] + żyroskop [°/s], osie bez przemapowania (telefon ekranem do góry: az = +1 g) |
| Spoczynek | domyślne źródło na gołym ESP32 (az = 1 g) |
| Sterowanie ręczne | `tools/pc_keyboard.py`: obrót/przechył (`q e a d w s`), FLAP (`f`), zeruj, siła obrotu/flapa, **rzut** z N obrotami (1–20) |
| Wzorce spin / tilt | `tools/pc_motion_feed.py --pattern spin|tilt` |
| Plik CSV | `tools/pc_motion_file.py`: 7 kolumn `t,gx,gy,gz,ax,ay,az` (keyframe'y, interpolacja) lub 6 kolumn (próbki 50 Hz); `tools/motion_examples/demo.csv` działa bez zmian; „Zapętl" |

Niezportowane (to narzędzia po stronie PC/RE): `pc_game2_drive.py` (ruch myszy → gyro Z; w apce użyj
czujników telefonu lub przycisków `◀ LEWO / PRAWO ▶`), `pc_replay_capture.py`, `parse_btsnoop.py`,
`btsnoop_att.py`.

## Zgodność z firmware ESP32

Zweryfikowane testami na JVM względem kodu Pythona/firmware z tego repo: kodowanie ramki 14 B
(skale 131 / 2048, zaokrąglanie jak `lroundf`, saturacja int16 — 3008 wektorów bajt w bajt),
ciecie 20/20/2, rozpoznawanie START/STOP po prefiksie, pętla ruchu ręcznego i rzutu
(2200 kroków), wzorce i odtwarzacz CSV. Warstwa BLE (reklama, GATT, wysyłka) korzysta z API Androida
i **wymaga sprawdzenia na prawdziwym telefonie**.

### Różnice względem ESP32

- Wysyłka notyfikacji idzie przez kolejkę z potwierdzeniem `onNotificationSent` (wymóg Androida);
  kolejka mieści 2 cykle (~60 ms), kolejne cykle są pomijane **w całości** (licznik „pominięte
  cykle"), więc ramki nigdy nie są rozrywane — jak w firmware.
- Poziom baterii stały: 100 (jak na gołym ESP32); bez notyfikacji zmian.
- Flagi reklamy (`0x06`) dodaje system; kolejność pól ADV może się różnić od kapsla.
- Brak ekranu/przycisków M5, deep sleep i auto-IDLE po 3 min.

## Struktura

```
android/
  app/src/main/java/pl/trikiemu/app/
    Protocol.java        kontrakt: UUID-y, komendy, ramka IMU, ciecie 20 B (czysta Java)
    TrikiPeripheral.java reklama + serwer GATT + silnik strumienia 100 Hz
    MainActivity.java    UI (programowe, bez XML i bez bibliotek)
    MotionSource.java    interfejs zrodla ruchu
    PhoneMotion / RestMotion / ManualMotion / PatternMotion / CsvMotion
```

Brak zależności bibliotecznych (tylko Android SDK).
