# WlanCameraServer

**Verze:** 1.0  
**Platforma:** Android 13+ (API 33+), cílová API 36  
**Nasazení:** `/product/priv-app` (privilegovaná systémová aplikace)  
**Package:** `skoda.app.wlancameraserver`

---

## Přehled

WlanCameraServer je Android aplikace nasazená jako **privilegovaný priv-app** na infotainmentové
jednotce (Škoda/VW/CARIAD ekosystém). Funguje jako **WebRTC příjemce (receiver)** – čeká na
připojení kamery (sender) přes Wi-Fi hotspot, provede WebSocket signalizaci a zobrazuje příchozí
video stream.

```
┌─────────────────────┐        Wi-Fi AP        ┌──────────────────────┐
│  Kamera (Sender)    │ ◄─────────────────────► │  WlanCameraServer    │
│  Android / iOS app  │   WebSocket + WebRTC    │  (Infotainment HU)   │
└─────────────────────┘                         └──────────────────────┘
         │                                               │
         │  1. UDP Beacon (broadcast)                    │
         │  2. WebSocket HELLO/OFFER/ICE                 │
         │  3. WebRTC video stream (H.264/VP8)           │
```

---

## Architektura

### Balíčková struktura

```
skoda.app.wlancameraserver/
├── ui/
│   ├── MainActivity.kt          – hlavní obrazovka, stav, video renderer
│   ├── QrActivity.kt            – zobrazení QR kódu pro párování
│   └── SettingsActivity.kt      – nastavení portů, hotspot, auto-accept
│
├── service/
│   └── ReceiverForegroundService.kt  – foreground service, koordinuje vše
│
├── signaling/
│   └── SignalingController.kt   – WS server logika, HELLO/OFFER/ICE/ANSWER
│
├── network/
│   ├── WsServer.kt              – raw TCP WebSocket server (bez OkHttp server)
│   ├── WsServerClient.kt        – zpracování jednoho WS klienta (handshake, framy)
│   └── UdpBeaconManager.kt      – UDP broadcast RECEIVER_HERE beacon
│
├── hotspot/
│   ├── HotspotManager.kt        – unified entry point pro Wi-Fi credentials
│   └── SystemHotspotReader.kt   – čtení SSID+PSK ze systémového AP (reflexe/Settings)
│
├── webrtc/
│   └── WebRtcManager.kt         – PeerConnection, SDP handling, video track
│
├── data/
│   └── ReceiverRepository.kt    – SharedPreferences: identity, trusted senders, settings
│
└── model/
    └── Models.kt                 – datové třídy: AppState, WsMessage, QrPayload, ...
```

---

## Tok připojení

### Flow 1 – Nové párování (QR kód)

```
Receiver (HU)                    Camera (Sender)
     │                                │
     │── UDP Beacon (každou 1s) ─────►│  { type: RECEIVER_HERE, challenge, wsPort, ... }
     │                                │
     │◄─ WebSocket connect ───────────│  ws://192.168.x.x:8888/ws
     │                                │
     │◄─ HELLO ───────────────────────│  { cameraId, token, sessionId }
     │                                │
     │── HELLO_ACK ──────────────────►│  { trusted: false }
     │                                │
     │  [dialog v UI – Přijmout/Odmítnout]
     │                                │
     │── APPROVED ───────────────────►│
     │                                │
     │◄─ OFFER (SDP) ─────────────────│
     │── ANSWER (SDP) ───────────────►│
     │◄─► ICE candidates ─────────────│
     │                                │
     │◄══ WebRTC video stream ════════│
```

### Flow 2 – Trusted reconnect (beacon/proof)

```
Receiver (HU)                    Camera (Sender)
     │                                │
     │── UDP Beacon ─────────────────►│  { challenge: "abc123", flags.acceptTrusted: true }
     │                                │
     │◄─ WebSocket connect ───────────│
     │◄─ HELLO ───────────────────────│  { cameraId, proof: HMAC-SHA256(challenge, sharedSecret) }
     │                                │
     │── HELLO_ACK ──────────────────►│  { trusted: true }
     │                                │
     │◄─ OFFER ───────────────────────│
     │── ANSWER ─────────────────────►│
     │◄══ WebRTC video stream ════════│
```

---

## Wi-Fi Hotspot – režimy

### Režim 1: System Hotspot (výchozí – infotainment)

Aplikace **nespouští žádný vlastní hotspot**. Čte přihlašovací údaje z již běžícího
systémového AP (infotainment jednotka):

1. `WifiManager.getSoftApConfiguration()` přes Java reflexi (privilegovaná app)
2. `Settings.Global` / `Settings.System` – OEM klíče (`wifi_ap_ssid`, `tethering_ssid`, ...)
3. Manuálně zadané SSID+PSK v Nastavení (fallback)

### Režim 2: LocalOnlyHotspot (legacy – telefon/tablet)

Spouští vlastní hotspot přes `WifiManager.startLocalOnlyHotspot()`.
Vyžaduje `ACCESS_FINE_LOCATION` + `NEARBY_WIFI_DEVICES`.

**Přepínání:** Nastavení → přepínač „Systémový hotspot".

---

## Oprávnění

### AndroidManifest.xml

| Oprávnění | Účel |
|---|---|
| `INTERNET` | WebSocket server, WebRTC |
| `NETWORK_SETTINGS` | Privilegované síťové operace |
| `ACCESS_NETWORK_STATE` | Detekce dostupné sítě |
| `ACCESS_WIFI_STATE` | Čtení Wi-Fi info, MulticastLock |
| `CHANGE_WIFI_STATE` | LocalOnlyHotspot (legacy) |
| `CHANGE_NETWORK_STATE` | Síťové nastavení |
| `ACCESS_FINE_LOCATION` | Povinné pro hotspot / Wi-Fi info |
| `ACCESS_COARSE_LOCATION` | Povinné pro hotspot / Wi-Fi info |
| `CHANGE_WIFI_MULTICAST_STATE` | UDP broadcast MulticastLock |
| `FOREGROUND_SERVICE` | Foreground service |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Typ foreground service |
| `WAKE_LOCK` | CPU alive při streamování |
| `POST_NOTIFICATIONS` | Notifikace (Android 13+) |
| `NEARBY_WIFI_DEVICES` | LocalOnlyHotspot (Android 13+) |

### priv-app oprávnění (`WlanCameraServer_privapp_permissions.xml`)

Soubor nutno zkopírovat do `/product/etc/permissions/` na cílové jednotce:

```
/product/etc/permissions/WlanCameraServer_privapp_permissions.xml
```

Obsahuje privilegovaná oprávnění: `NETWORK_SETTINGS`, `ACCESS_FINE_LOCATION`,
`NEARBY_WIFI_DEVICES`, `POST_NOTIFICATIONS`, plus CARIAD/automotive oprávnění.

---

## Nasazení jako priv-app

### 1. Build

```bash
./gradlew assembleRelease
# nebo assembleDebug pro testování
```

APK: `app/build/outputs/apk/release/app-release.apk`

### 2. Struktura na zařízení

```
/product/priv-app/WlanCameraServer/
    WlanCameraServer.apk

/product/etc/permissions/
    WlanCameraServer_privapp_permissions.xml
```

### 3. Push přes ADB (vývojové zařízení s root/adb shell)

```bash
adb root
adb remount

adb push app/build/outputs/apk/debug/app-debug.apk /product/priv-app/WlanCameraServer/WlanCameraServer.apk
adb push WlanCameraServer_privapp_permissions.xml /product/etc/permissions/WlanCameraServer_privapp_permissions.xml

adb shell chmod 644 /product/priv-app/WlanCameraServer/WlanCameraServer.apk
adb shell chmod 644 /product/etc/permissions/WlanCameraServer_privapp_permissions.xml

adb reboot
```

> **Důležité:** Po zkopírování APK je nutný **reboot** – Android packagemanager
> čte priv-app oprávnění pouze při startu systému.

### 4. Ověření instalace

```bash
adb shell pm list packages | grep wlancamera
# Výstup: package:skoda.app.wlancameraserver

adb shell dumpsys package skoda.app.wlancameraserver | grep -i "install\|priv\|flags"
```

---

## Nastavení aplikace

| Nastavení | Výchozí | Popis |
|---|---|---|
| Systémový hotspot | `true` | Čti SSID z existujícího AP (infotainment) |
| Manuální SSID | `""` | Fallback SSID pokud reflexe/Settings selhají |
| Manuální heslo | `""` | Fallback heslo |
| WS port | `8888` | Port WebSocket serveru |
| UDP beacon port | `39500` | Port pro UDP RECEIVER_HERE broadcast |
| Auto-accept trusted | `true` | Automaticky přijmout známé kamery |
| Single camera only | `true` | Odmítat druhé kamery pokud je jedna připojena |

---

## Protokol zpráv (WebSocket JSON)

### Camera → Receiver

```json
{ "type": "HELLO", "v": 1, "cameraId": "C-abc12345",
  "sessionId": "S-20260309-120000", "token": "T-ff00aa11bb",
  "cameraInfo": { "model": "Pixel 8", "app": "WlanCameraSender/1.0" } }

{ "type": "HELLO", "v": 1, "cameraId": "C-abc12345",
  "proof": "<HMAC-SHA256(challenge, sharedSecret) base64>" }

{ "type": "OFFER", "sdp": "v=0\r\no=- ..." }

{ "type": "ICE", "candidate": "candidate:...", "sdpMid": "0", "sdpMLineIndex": 0 }
```

### Receiver → Camera

```json
{ "type": "HELLO_ACK", "trusted": true|false,
  "receiverId": "R-12345678",
  "policy": { "singleCameraOnly": true } }

{ "type": "APPROVED", "receiverId": "R-12345678" }

{ "type": "ANSWER", "sdp": "v=0\r\no=- ..." }

{ "type": "ICE", "candidate": "candidate:...", "sdpMid": "0", "sdpMLineIndex": 0 }

{ "type": "ERROR", "code": "BUSY|INVALID_TOKEN|TOKEN_EXPIRED|...", "message": "..." }

{ "type": "REJECTED", "code": "USER_REJECTED" }
```

### UDP Beacon (broadcast → port 39500)

```json
{
  "type": "RECEIVER_HERE", "v": 1,
  "receiverId": "R-12345678",
  "wsPort": 8888,
  "ipHint": "192.168.43.1",
  "sessionId": "S-20260309-120000",
  "challenge": "a1b2c3d4e5f6",
  "flags": { "acceptTrusted": true, "busy": false }
}
```

### QR kód payload (JSON zakódovaný do QR)

```json
{
  "v": 1, "role": "receiver",
  "receiverId": "R-12345678",
  "ssid": "HotspotSSID",
  "psk": "HotspotPassword",
  "wsUrl": "ws://192.168.43.1:8888/ws",
  "sessionId": "S-20260309-120000",
  "token": "T-ff00aa11bb",
  "tokenExpSec": 120
}
```

---

## Stavový automat (AppState)

```
IDLE
 │ [Start]
 ▼
STARTING_HOTSPOT
 │ [hotspot/wifi credentials ready]
 ▼
HOTSPOT_READY → STARTING_SERVER
 │ [WsServer + UdpBeacon started]
 ▼
WAITING_FOR_SENDER
 │ [WS klient připojen]
 ▼
AUTHENTICATING
 │ [HELLO zpracováno – nový klient]
 ▼
PENDING_APPROVAL ──► [User Reject] ──► WAITING_FOR_SENDER
 │ [User Accept]
 ▼
NEGOTIATING_WEBRTC
 │ [OFFER/ANSWER/ICE hotovo, video přichází]
 ▼
STREAMING
 │ [odpojení/chyba]
 ▼
WAITING_FOR_SENDER  ← (auto-reconnect, WS/hotspot zůstanou aktivní)
```

---

## Reconnect logika

- Při přerušení WebRTC (DISCONNECTED / FAILED) se **hotspot a WS server nezastaví**
- `SignalingController.resetForReconnect()` uvolní `connectedClientId` → kamera se může ihned připojit
- Naplánuje se reset `PeerConnection` s exponenciální prodlevou: **2s, 5s, 10s, 15s, 30s**
- Po úspěšném reconnectu se čítač vynuluje

---

## Závislosti

| Knihovna | Verze | Účel |
|---|---|---|
| `androidx.core:core-ktx` | 1.15.0 | Kotlin Android extensions |
| `androidx.appcompat:appcompat` | 1.7.0 | Zpětná kompatibilita UI |
| `com.google.android.material:material` | 1.12.0 | Material Design komponenty |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.8.7 | ViewModel |
| `androidx.lifecycle:lifecycle-livedata-ktx` | 2.8.7 | LiveData |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | HTTP/WS klient (pro výhledové použití) |
| `com.google.code.gson:gson` | 2.11.0 | JSON serializace |
| `com.google.zxing:core` | 3.5.3 | QR kód generování |
| `com.journeyapps:zxing-android-embedded` | 4.3.0 | QR scanner UI |
| `io.getstream:stream-webrtc-android` | 1.3.10 | WebRTC PeerConnection |

---

## Minimální požadavky

- Android **13** (API 33) nebo vyšší
- Wi-Fi (hotspot nebo připojení ke stejné síti jako kamera)
- Zařízení musí být v roli **priv-app** pro `NETWORK_SETTINGS` a reflexní přístup k `SoftApConfiguration`

---

## Ladění (Debug)

### Logcat filtry

```bash
# Celý projekt
adb logcat -s MainActivity SignalingController WsServer WsServerClient UdpBeaconManager WebRtcManager HotspotManager SystemHotspotReader ReceiverService

# Pouze chyby
adb logcat *:E -s WsServer SignalingController WebRtcManager
```

### Hlavní tagy

| Tag | Třída | Co loguje |
|---|---|---|
| `MainActivity` | MainActivity | UI stav, lifecycle |
| `ReceiverService` | ReceiverForegroundService | Start/stop, reconnect |
| `SignalingController` | SignalingController | HELLO/OFFER/ICE zprávy, auth |
| `WsServer` | WsServer | Binding portu, accept klientů |
| `WsServerClient` | WsServerClient | WS handshake, framy |
| `UdpBeaconManager` | UdpBeaconManager | Beacon odesílání, broadcast IP |
| `WebRtcManager` | WebRtcManager | PeerConnection stav, ICE, video |
| `HotspotManager` | HotspotManager | Hotspot start/stop |
| `SystemHotspotReader` | SystemHotspotReader | Čtení SSID přes reflexi/Settings |

---

## Časté problémy

### „EADDRINUSE – address already in use"

Port `8888` je obsazený. Řešení:
- Změňte WS port v Nastavení
- Nebo zastavte aplikaci (Stop) a znovu spusťte
- `WsServer` používá `SO_REUSEADDR` – druhý start po krátkém čase by měl fungovat

### Kamera nenalézá receiver (UDP beacon)

1. Zkontrolujte, zda je kamera na stejné Wi-Fi síti jako receiver
2. Logcat `UdpBeaconManager`: sledujte `getSubnetBroadcast()` – musí vracet broadcast IP (ne null)
3. Zkontrolujte `CHANGE_WIFI_MULTICAST_STATE` oprávnění

### WebSocket se nepřipojí

1. Logcat `WsServer`: `WS server listening on port XXXX` – ověřte, že server nastartoval
2. Logcat `WsServerClient`: sledujte WS handshake fázi
3. Ověřte, že `ipHint` v beacon odpovídá skutečné IP hotspotu

### Reflexe SoftApConfiguration selhala

Zařízení nemusí dovolovat reflexní přístup ani jako priv-app. Použijte manuální fallback:
Nastavení → vypněte „Systémový hotspot" → zadejte SSID a heslo ručně.

### Video se nezobrazuje

1. Logcat `WebRtcManager`: sledujte `onVideoTrack`, `onIceConnectionChange: CONNECTED`
2. Zkontrolujte, zda `SurfaceViewRenderer` byl inicializován (`initVideoRenderer()`)
3. WebRTC používá pouze lokální ICE (LAN only, bez STUN/TURN) – kamera musí být na stejné síti
