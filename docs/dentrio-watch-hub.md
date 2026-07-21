# Dentrio Watch Hub — plan implementacji (fork)

**Gałąź:** `feat/dentrio-watch-hub` | **Status:** plan zatwierdzony, implementacja etapami
**Kontekst:** tablet gabinetowy (FreeKiosk, tryb kiosk z WebView na system stomy) staje się
hubem dla zegarka Wear OS (TicWatch Pro 5) sparowanego przez Bluetooth. Kontrakt serwerowy
(Etap A) jest zamrożony w repo stomy: `docs/superpowers/specs/2026-07-10-watch-companion-design.md`
i `docs/processes/messenger/README.md` (sekcja „Kompan-zegarek gabinetu").

## Kontrakt hub ↔ serwer stomy (zamrożony)

- **Auth:** `GET /api/auth/csrf` → `POST /api/auth/callback/tablet` (form: `csrfToken`,
  `deviceToken`, `deviceId`, `json=true`) → cookie sesyjne NextAuth (ważne ~100 lat).
- **REST:** `GET /api/tablet/watch/summary` (gabinet, `conversationId`, bieżąca wizyta z
  `minutesOverrun`, następna z `isWaiting`/`minutesWaiting`, ostatnie 10 wiadomości z
  `reactions` + `myReactions`), `POST /api/tablet/watch/message {content}`,
  `POST /api/tablet/watch/help-call {note?}` (429 = cooldown 30 s),
  `POST /api/messenger/messages/{id}/reactions {emoji}` (toggle).
- **Socket.IO** (port 3003): nasłuch globalnie broadcastowanych `appointment-updated`
  (filtr `data.roomId === room.id z summary`), `join-conversation {conversationId}` →
  `message-new`, `message-reaction-update`. **NIGDY nie emitować `messenger-auth`**
  (zanieczyszcza prezencję personelu). Każdy sygnał socketowy ⇒ re-fetch summary
  (payloady niosą tylko ID).

## Architektura w forku

```
┌───────────────────────── :app (com.freekiosk) ─────────────────────────┐
│ RN/TS: ClinicHubSettingsSection (AdvancedTab) ──▶ HubModule (bridge)   │
│ Kotlin: WatchHubService (FGS, specialUse — jak KioskWatchdogService)   │
│   ├─ ClinicHubClient  (OkHttp+CookieJar: login, REST; socket.io-java)  │
│   └─ WearRelay        (DataClient: stan → /watch/summary;              │
│                        MessageClient: eventy + akcje z zegarka)        │
└────────────────────────────────────────────────────────────────────────┘
            ▲ Bluetooth / Wearable Data Layer (ten sam applicationId+klucz)
┌────────── :wear (Compose for Wear, minSdk 30) ──────────┐
│ Tile teraz/następny • powiadomienia czatu z akcjami      │
│ (👍 + szybkie odpowiedzi) • alarm przekroczenia czasu     │
│ • przycisk 🆘 z potwierdzeniem • Ongoing Activity        │
└──────────────────────────────────────────────────────────┘
```

**Decyzje** (na bazie eksploracji kodu 2026-07-10):
- Cała ścieżka danych w Kotlinie (wzorzec = stack MQTT: `mqtt/MqttModule.kt` +
  `KioskMqttClient.kt`); JS tylko start/stop/status przez bridge — jak `MqttModule.ts`.
- Sesja NextAuth w OkHttp `CookieJar` (persystowana w EncryptedSharedPreferences);
  `deviceToken` w Keychain przez `secureStorage.ts` (wzorzec `freekiosk_mqtt_password`).
- Konfiguracja: klucze `@kiosk_hub_enabled`, `@kiosk_hub_server_url`, `@kiosk_hub_socket_url`,
  `@kiosk_hub_device_id` w `storage.ts`; sekcja UI w `AdvancedTab.tsx` (wzorzec
  `MqttSettingsSection.tsx` — status na żywo, test połączenia).
- Keep-alive: FGS `START_STICKY` + hak `checkAndReconnect()` w `OverlayService`
  (obok MQTT) + start z `BootReceiver` + autostart w `KioskScreen` useEffect (~690-775).
  ZAIMPLEMENTOWANE (2026-07-22) jako `hub/HubForegroundService.kt` (`specialUse`
  subtype `clinic_hub`): startowany/zatrzymywany razem z hubem w `HubModule`,
  trzyma priorytet procesu gdy inna apka jest na wierzchu (root-cause: kiosk
  zabijał relay, wiadomości z zegarka szły w próżnię); po ubiciu procesu
  START_STICKY wstaje z null intentem i — jeśli `@kiosk_hub_enabled` — relansuje
  `MainActivity` (pętla co 30 s, cooldown 60 s), a autostart JS przywraca hub.
- Data Layer paths (ZAIMPLEMENTOWANE — patrz `hub/WearRelay.kt` i moduł `:wear`):
  tablet→zegarek: `/watch/summary` (DataItem urgent, pełny JSON summary + `updatedAt`;
  push przy zmianie + keepalive 5 min), `/watch/chat-message` (Message, „nowa
  wiadomość" → powiadomienie), `/watch/action-result` (Message, `{action, ok,
  message?}` — feedback akcji, np. cooldown 🆘). Zegarek→tablet:
  `/watch/action/reaction {messageId, emoji}`, `/watch/action/quick-reply {content}`,
  `/watch/action/help-call {note?}`, `/watch/battery {level, charging}` (tablet
  forwarduje do `POST /api/tablet/report-status`), `/watch/summary-request {}`.

## Etapy

**B1 — szkielet + połączenie kliniczne (bez zegarka, testowalne od razu):**
1. `android/app/build.gradle`: deps `io.socket:socket.io-client:2.1.2` (uwaga na
   META-INF merge — precedens: excludes Netty dla HiveMQ, linie ~150-154),
   `com.squareup.okhttp3:okhttp`, `com.google.android.gms:play-services-wearable`.
2. Pakiet `com.freekiosk.hub`: `HubPackage.kt`, `HubModule.kt`
   (`startHub(config, Promise)`, `stopHub`, `getHubState(Promise)`, eventy
   `onHubConnectionChanged`, `onHubSummaryChanged`), rejestracja w `MainApplication.kt`.
3. `ClinicHubClient.kt`: login (csrf→callback/tablet), `fetchSummary()`,
   `sendQuickReply()`, `sendHelpCall()`, `toggleReaction()`; retry/backoff.
4. `WatchHubService.kt` (FGS, manifest `specialUse`): trzyma klienta + socket,
   re-fetch summary na eventy i co 60 s fallback.
5. TS: `src/utils/HubModule.ts` (bridge), klucze w `storage.ts`,
   `saveSecureHubToken()` w `secureStorage.ts`, `ClinicHubSettingsSection.tsx`
   w `AdvancedTab` (URL, deviceId, token, przełącznik, status, „Testuj połączenie"),
   autostart w `KioskScreen`, hak reconnect w `OverlayService`, start w `BootReceiver`.
   Klucze hub (bez tokena!) do allow-listy `BackupService.ts`.
   Weryfikacja B1: emulator/tablet → ustawienia → połączono; logcat pokazuje summary
   i reakcje na eventy socketa (dev stoma).

**B2 — relay Data Layer (:app) — ZAIMPLEMENTOWANE (2026-07-12):**
`hub/WearRelay.kt` — DataItem `/watch/summary` po każdej zmianie summary (dedup po
treści + keepalive 5 min, `setUrgent`), broadcast `/watch/chat-message` na `message-new`,
`MessageClient.OnMessageReceivedListener` na `/watch/action/*` + `/watch/battery` +
`/watch/summary-request` → wywołania `ClinicHubClient` na jego executorze → odpowiedź
`/watch/action-result`. Relay tworzony/wyłączany w `HubModule` razem z klientem huba.
(CapabilityClient „zegarek połączony" w ustawieniach — do zrobienia później.)

**C — moduł `:wear` (Compose for Wear) — ZAIMPLEMENTOWANE MVP (2026-07-12):**
`include ':wear'` w `settings.gradle` (czysty Kotlin/Compose, poza autolinkingiem RN);
**ten sam `applicationId com.freekiosk` i klucz podpisu (debug.keystore z `:app`)** —
warunek dostarczania Data Layer; APK sideloadowany na zegarek przez adb (bez
`wearApp` embedding). Kod: `model/WatchSummary.kt` (defensywny parser),
`data/WatchStateHolder.kt` (StateFlow współdzielony serwis↔UI + seed z persystowanego
DataItem), `comm/WatchComm.kt` (wysyłki + raport baterii zegarka przy zmianie/15 min,
piggyback na odbiorze summary), `service/WatchDataListenerService.kt`
(WearableListenerService: summary→stan, chat→powiadomienie, wynik akcji→flow;
alert wibracyjny gdy `minutesOverrun>0 && next.isWaiting`, raz na wizytę),
`service/WatchActionReceiver.kt` (akcje powiadomień), `notif/WatchNotifications.kt`
(czat: 👍 + „Już idę"/„Za 5 minut"; kanał alertów z wibracją), `MainActivity.kt`
(Compose: gabinet + teraz/następny, pulsujące czerwone pole „W poczekalni X min",
czat z toggle 👍, szybkie odpowiedzi, 🆘 z dialogiem potwierdzenia, banner wyników,
znacznik nieświeżych danych >3 min). Do zrobienia później: Tile, Ongoing Activity,
CapabilityClient, pełnoekranowy alarm zamiast powiadomienia.

**Integracja sprzętowa (dawny SPIKE 0, na końcu):** parowanie TicWatch Pro 5 z tabletem
(Mobvoi Health + Google Play na tablecie), test zasięgu BT w gabinecie, bateria na zmianie.
Fallback gdy parowanie z tabletem niemożliwe: transport wymienny — zamiast WearRelay
bezpośredni socket WiFi z zegarka (kontrakt REST/socket ten sam).

## Etapy dodatkowe (zgłoszone 2026-07-10)

**B4 — aktualizacje z serwera kliniki (zamiast GitHub Releases):**
- Fork: `UpdateModule.kt` (dziś: GitHub API + pobranie APK przez `HttpURLConnection`)
  dostaje konfigurowalny `updateUrl` (klucz `@kiosk_hub_update_url`, domyślnie pusty =
  zachowanie upstreamowe). Manifest JSON: `{ versionName, versionCode, apkUrl, notes }`.
- Stoma: upload APK w panelu admina (Ustawienia → Tablety → „Aplikacja kiosku"),
  endpointy `GET /api/tablets/app-update/manifest` + `GET /api/tablets/app-update/apk`
  (serwowane z `https://portal.local/...` — instancja kliniki). Tablet sprawdza przy
  starcie + raz dziennie; instalacja jak dziś (REQUEST_INSTALL_PACKAGES, Device Owner).

**B5 — zarządzanie tabletami w panelu stomy przez REST API FreeKioska:**
- FreeKiosk ma wbudowany serwer HTTP (NanoHTTPD, auth `X-Api-Key`, docs/rest-api.md):
  status urządzenia (bateria, ekran, WiFi, URL), komendy (screenOn/Off, brightness,
  loadUrl, reload, tts, toast, screenshot, restart aplikacji).
- Stoma (LAN wspólny z tabletami): w `TabletDevice` dodać `kioskApiUrl` + `kioskApiKey`
  (szyfrowane), panel Ustawienia → Tablety rozbudować o kartę „Zarządzanie":
  podgląd statusu na żywo, akcje zdalne, zbiorcze operacje (np. wygaś ekrany po
  godzinach). Proxy przez serwer stomy (przeglądarka nie ma dostępu do IP tabletów
  przy HTTPS — mixed content).

## Zasady forka

- Sync z upstreamem: `git fetch upstream && git push origin upstream/main:main`,
  potem merge `main` → `feat/dentrio-watch-hub`.
- Zmiany ogólnego zastosowania (nie-kliniczne) wydzielać do osobnych gałęzi od `main`
  i PR-ować do upstreamu (RushB-fr/freekiosk).
- Konwencje repo: CLAUDE.md (moduł natywny = Kotlin + bridge TS aktualizowane razem),
  conventional commits.
