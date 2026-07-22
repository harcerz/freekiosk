# DenTRIO — gotowe APK + instalacja

| Plik                 | Urządzenie                  | Uwagi                                   |
| -------------------- | --------------------------- | --------------------------------------- |
| `dentrio-tablet.apk` | tablet gabinetowy (Android) | build `:app:assembleRelease`            |
| `dentrio-wear.apk`   | TicWatch / Wear OS (SDK 30+) | build `:wear:assembleRelease`, ten sam `applicationId` i keystore co tablet (warunek Data Layer) |

Instalacja z `-r` **zachowuje dane** (parowanie z kliniką, token, konfigurację) —
te same polecenia służą do pierwszej instalacji i do aktualizacji.

## Tablet

```powershell
# USB (jedyne urządzenie) :
.\install-dentrio.ps1 -Target tablet

# WiFi:
.\install-dentrio.ps1 -Target tablet -Device 192.168.1.87:5555
```

## Zegarek — pierwsze podłączenie

1. Na zegarku: **Ustawienia → Dla deweloperów → Debugowanie bezprzewodowe**
   (włącz) → **Sparuj nowe urządzenie** — pokaże `ip:port` + 6-cyfrowy kod.
2. ```powershell
   .\install-dentrio.ps1 -Target watch -Pair <ip:port-parowania> -Device <ip>:5555 -SetWatchface
   ```
   Kod wpisujesz w konsoli. Port **5555** jest stabilniejszy niż rotujący 36xxx
   (gdy nie działa: `adb mdns services` pokaże aktualny port `_adb-tls-connect`).

Kolejne instalacje: samo `-Target watch -Device <ip>:5555`.

### Pułapki (sprawdzone na TicWatch Pro 5 / Lenovo TB373FU)

- **Ekran zegarka zasypia w sekundy przy zdjętym zegarku** (off-body detection) —
  operacje interaktywne rób z zegarkiem na ręce albo wybudzaj tuż przed akcją.
- **Po restarcie/resecie zegarka parowanie ADB przepada** (nowy GUID) → ponowne
  `-Pair` z nowym kodem.
- **Wireless debugging wyłącza się, gdy wraca companion BT** (Wear gasi WiFi) —
  najlepiej instalować, gdy tablet/telefon-companion jest chwilowo poza zasięgiem
  albo od razu po włączeniu debugowania.
- **Debug APK nie działa poza Metro** („X has not been registered") — w tym
  katalogu są wyłącznie buildy **release** i tylko takie instaluj.
- Skrypt nadaje zegarkowi `REQUEST_INSTALL_PACKAGES` — przyszłe aktualizacje
  wysyłane przez tablet-hub potwierdza się jednym tapnięciem na tarczy.

## Odświeżenie APK w tym katalogu

```bash
cd android
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:assembleRelease :wear:assembleRelease
cp app/build/outputs/apk/release/app-release.apk  ../apk/dentrio-tablet.apk
cp wear/build/outputs/apk/release/wear-release.apk ../apk/dentrio-wear.apk
```

(Sprawdź exit code i mtime plików — pipe przez `tail` maskuje błędy builda.)
