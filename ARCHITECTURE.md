# Architektura WlanCameraServer

## Přehled vrstev

```
┌────────────────────────────────────────────────────────────────┐
│                         UI vrstva                              │
│  MainActivity  │  QrActivity  │  SettingsActivity              │
│  (LiveData observers, SurfaceViewRenderer, dialogy)            │
└──────────────────────────┬─────────────────────────────────────┘
                           │ bind / observe LiveData
┌──────────────────────────▼─────────────────────────────────────┐
│              ReceiverForegroundService                         │
│  (koordinátor, foreground service, reconnect logika)           │
│  LiveData: appState, ssid, psk, serverIp, videoTrack, ...      │
└──────┬──────────────┬──────────────┬───────────────────────────┘
       │              │              │
       ▼              ▼              ▼
┌──────────┐  ┌──────────────┐  ┌───────────────┐
│ Hotspot  │  │  Signaling   │  │   WebRTC      │
│ Manager  │  │  Controller  │  │   Manager     │
│          │  │              │  │               │
│ System   │  │  WsServer    │  │  PeerConn.    │
│ Hotspot  │  │  UdpBeacon   │  │  Video sink   │
│ Reader   │  │  Manager     │  │               │
└──────────┘  └──────────────┘  └───────────────┘
                     │
               ┌─────▼──────┐
               │ Repository │
               │ (prefs,    │
               │  trusted,  │
               │  settings) │
               └────────────┘
```

---

## ReceiverForegroundService – centrální koordinátor

Služba drží všechny komponenty živé i na pozadí. Je spuštěna jako foreground service
s notifikací (typ `connectedDevice`).

### Životní cyklus

```
onCreate()
  ├── new ReceiverRepository
  ├── new HotspotManager
  ├── new WebRtcManager
  └── new SignalingController

onStartCommand(ACTION_START)
  └── startReceiver()
        ├── startForeground(notifikace)
        ├── hotspotManager.startHotspot(callback)
        │     └── onStarted(ssid, psk)
        │           ├── detectHotspotIp()
        │           └── signalingController.startServer(ssid, psk, ip)
        │                 ├── WsServer.start()  ← bind port
        │                 └── UdpBeacon.start() ← broadcast každou 1s
        └── [čekání na kameru]

onStartCommand(ACTION_STOP)
  └── stopReceiver()
        ├── signalingController.stopServer()
        ├── webRtcManager.close()
        ├── hotspotManager.stopHotspot()
        └── stopForeground()

onDestroy()
  ├── stopReceiver()
  └── webRtcManager.release()  ← uvolnění EGL kontextu
```

### Reconnect logika

```
WebRTC DISCONNECTED/FAILED
  │
  ├── webRtcManager.close()
  ├── signalingController.resetForReconnect()  ← uvolní connectedClientId
  │     └── udpBeacon.busy = false             ← kamera se může znovu připojit
  │
  └── reconnectHandler.postDelayed(delay)
        delay: [2s, 5s, 10s, 15s, 30s, 30s, ...]
        │
        └── resetWebRtcForReconnect()
              └── videoTrack = null, webRtcManager.close()
                  [čekání na nový HELLO + OFFER]
```

---

## SignalingController

Zpracovává veškerou WebSocket komunikaci. Implementuje `WsServer.WsServerListener`.

### Stavový automat autentizace

```
[nový WS klient]
      │
      ▼
onClientConnected() → state = AUTHENTICATING
      │
      ▼
onMessage("HELLO")
      │
      ├── [má proof + je trusted + autoAccept=true]
      │         │
      │         └── verifikace HMAC → acceptClient(trusted=true)
      │               └── HELLO_ACK(trusted=true) → kamera pošle OFFER
      │
      └── [má token]
                │
                └── ověření sessionId + tokenExpiry + token
                      └── pendingApproval[clientId] = cameraId
                            └── HELLO_ACK(trusted=false)
                                  └── state = PENDING_APPROVAL
                                        [čeká na user]
                                        │
                                ┌───────┴────────┐
                                │ Accept          │ Reject
                                ▼                 ▼
                         acceptClient()      REJECTED + disconnect
                         APPROVED →
                         kamera pošle OFFER

[OFFER přijat]
      │
      └── eventListener.onOffer() → WebRtcManager.handleRemoteOffer()
            └── vytvoří ANSWER → signalingController.sendAnswer()

[ICE přijat]
      │
      └── WebRtcManager.addRemoteIceCandidate()

[WebRtcManager.onLocalIceCandidate]
      │
      └── signalingController.sendIce() → kamera
```

---

## WsServer + WsServerClient

### WsServer
- Raw TCP ServerSocket na daném portu
- `SO_REUSEADDR = true` (umožňuje restart bez čekání na timeout portu)
- Bind na `0.0.0.0` (všechna rozhraní)
- Pro každého klienta spustí `WsServerClient` v novém vlákně (CachedThreadPool)

### WsServerClient
- Provede HTTP Upgrade handshake (WebSocket RFC 6455)
- Čte WebSocket framy (text frames)
- Parsuje JSON → `WsMessage`
- Odesílá WebSocket text framy (`send(json)`)

---

## UdpBeaconManager

```
start()
  ├── acquireMulticastLock()  ← nutné pro broadcast na Android
  ├── ScheduledExecutor (2 vlákna)
  ├── challengeRotation: každých 7s → nový generateChallenge()
  └── beaconSend: každých 1000ms → sendBeacon()
        │
        └── getSubnetBroadcast()
              ├── NetworkInterface enumeration
              ├── hledá non-loopback IPv4 s prefixLength
              └── vypočítá broadcast adresu
                    └── DatagramSocket.send(packet) → UDP broadcast
```

---

## HotspotManager

### Režim useSystemHotspot=true (výchozí)

```
startHotspot() → readSystemCredentials()
  │
  └── SystemHotspotReader.read()
        │
        ├── 1. WifiManager.getSoftApConfiguration() via reflexe
        │         (getWifiSsid() nebo getSsid(), getPassphrase())
        │
        ├── 2. Settings.Global / Settings.System
        │         (klíče: wifi_ap_ssid, tethering_ssid, mobile_hotspot_ssid, ...)
        │
        └── 3. manuální fallback (manualSsid + manualPsk ze Settings)
```

### Režim useSystemHotspot=false (legacy)

```
startHotspot() → startLocalOnlyHotspot()
  └── WifiManager.startLocalOnlyHotspot(callback, handler)
        └── onStarted(reservation)
              └── reservation.softApConfiguration → SSID + PSK
```

---

## WebRtcManager

```
init()
  ├── EglBase.create()
  ├── PeerConnectionFactory.initialize()
  └── PeerConnectionFactory.builder()
        ├── DefaultVideoDecoderFactory(eglBase)
        └── DefaultVideoEncoderFactory(eglBase)

createPeerConnection()
  └── createPeerConnection(RTCConfiguration, Observer)
        ├── IceServers: [] (pouze LAN)
        ├── SdpSemantics: UNIFIED_PLAN
        └── ContinualGathering: GATHER_CONTINUALLY

handleRemoteOffer(sdp, onAnswer)
  ├── setRemoteDescription(OFFER)
  └── createAnswer()
        └── setLocalDescription(ANSWER)
              └── onAnswer(answerSdp)

addRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
  └── peerConnection.addIceCandidate()

[Observer callbacks]
  ├── onIceConnectionChange(CONNECTED) → listener.onConnected()
  ├── onIceConnectionChange(DISCONNECTED/FAILED) → listener.onDisconnected/Failed()
  └── onAddTrack(receiver, streams)
        └── listener.onVideoTrack(videoTrack)

initVideoRenderer(SurfaceViewRenderer)
  └── surfaceView.init(eglBase.context, null)

close()
  └── peerConnection.close()  ← NERELEASUJE factory/EGL

release()
  ├── peerConnection.dispose()
  ├── peerConnectionFactory.dispose()
  └── eglBase.release()
```

---

## ReceiverRepository – datové úložiště

Všechna data persistována v `SharedPreferences` ("receiver_prefs").

| Klíč | Typ | Obsah |
|---|---|---|
| `receiver_id` | String | Unikátní ID receiveru (R-XXXXXXXX), generováno jednou |
| `trusted_senders` | JSON String | List TrustedSender objektů |
| `auto_accept_trusted` | Boolean | Automaticky přijímat known cameras |
| `single_camera_only` | Boolean | Odmítat druhé kamery |
| `ws_port` | Int | WebSocket port (default 8888) |
| `udp_beacon_port` | Int | UDP port (default 39500) |
| `hotspot_ssid` | String | Uložené SSID z posledního úspěšného startu |
| `hotspot_psk` | String | Uložené heslo z posledního úspěšného startu |
| `use_system_hotspot` | Boolean | Režim hotspotu |
| `manual_ssid` | String | Manuálně zadané SSID |
| `manual_psk` | String | Manuálně zadané heslo |

### TrustedSender

```kotlin
data class TrustedSender(
    val cameraId: String,       // ID kamery (C-XXXXXXXX)
    val sharedSecret: String,   // 32-byte náhodný klíč, base64
    val pairedAt: Long,         // timestamp prvního párování
    val lastSeenAt: Long,       // timestamp posledního spojení
    val allowed: Boolean = true // false = kamera je zablokována
)
```

---

## UI vrstva

### MainActivity

- Binduje se na `ReceiverForegroundService` přes `ServiceConnection`
- Observuje LiveData: `appState`, `ssid`, `serverIp`, `connectedCameraId`, `errorMessage`,
  `pendingCameraId`, `videoTrack`
- `SurfaceViewRenderer` – inicializován lazy při příchodu prvního video tracku
- Dialog `showApprovalDialog()` – zobrazí se když `pendingCameraId` není null

### QrActivity

- Přijímá QR payload jako JSON přes Intent extra `EXTRA_QR_JSON`
- Generuje QR bitmap přes `BarcodeEncoder` (ZXing)
- Zobrazuje countdown timer do vypršení tokenu
- Po vypršení: uživatel se vrátí do MainActivity, ta zavolá `renewQr()` a znovu otevře QrActivity

### SettingsActivity

- Přímý přístup k `ReceiverRepository` (bez service bindingu)
- Přepínač „Systémový hotspot" skryje/zobrazí pole pro manuální SSID+PSK
- „Reset Wi-Fi údajů" smaže uložené SSID/PSK → při dalším startu se znovu přečtou

---

## Datové třídy (Models.kt)

```
AppState (enum)
  IDLE → STARTING_HOTSPOT → HOTSPOT_READY → STARTING_SERVER
  → WAITING_FOR_SENDER → AUTHENTICATING → PENDING_APPROVAL
  → NEGOTIATING_WEBRTC → STREAMING
  (kdykoli) → ERROR

QrPayload       – data zakódovaná do QR kódu
WsMessage       – generická WS zpráva (type + volitelná pole)
CameraInfo      – model + app z HELLO zprávy
Policy          – singleCameraOnly
UdpBeacon       – UDP broadcast paket
BeaconFlags     – acceptTrusted, busy
TrustedSender   – persistovaný trusted klient
AppSettings     – uživatelská nastavení
```
