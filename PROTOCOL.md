# Signalizační protokol – WlanCamera

**Verze protokolu:** v1  
**Transport:** WebSocket (plain, `ws://`) přes Wi-Fi LAN  
**Discovery:** UDP broadcast  
**Video:** WebRTC (H.264 / VP8, receive-only na straně receiveru)

---

## 1. Discovery – UDP Beacon

Receiver pravidelně (každou **1 sekundu**) broadcastuje JSON paket na UDP port **39500**.

### Paket RECEIVER_HERE

```json
{
  "type": "RECEIVER_HERE",
  "v": 1,
  "receiverId": "R-12345678",
  "wsPort": 8888,
  "ipHint": "192.168.43.1",
  "sessionId": "S-20260309-120000",
  "challenge": "a1b2c3d4e5f6a1b2",
  "flags": {
    "acceptTrusted": true,
    "busy": false
  }
}
```

| Pole | Typ | Popis |
|---|---|---|
| `type` | string | Vždy `"RECEIVER_HERE"` |
| `v` | int | Verze protokolu (aktuálně 1) |
| `receiverId` | string | Unikátní ID receiveru (persistováno, formát `R-XXXXXXXX`) |
| `wsPort` | int | TCP port WebSocket serveru |
| `ipHint` | string | IP adresa receiveru v síti (hint pro klienta) |
| `sessionId` | string | ID aktuální session (formát `S-YYYYMMDD-HHmmss`) |
| `challenge` | string | Náhodný hex řetězec pro HMAC ověření, rotuje každých **7 sekund** |
| `flags.acceptTrusted` | bool | Zda receiver přijímá trusted reconnect (proof-based) |
| `flags.busy` | bool | Zda je receiver obsazen – camera by měla počkat |

---

## 2. WebSocket spojení

Camera se připojí na:
```
ws://<ipHint>:<wsPort>/ws
```

WebSocket server implementován jako **raw TCP + HTTP Upgrade handshake** (bez závislosti na externím WS serveru).

---

## 3. Autentizace – dva flow

### Flow 1: Nové párování (QR token)

Používá se pro **první připojení** kamery, nebo při ručním re-párování.
Token je vygenerován v receiveru a zakódován do QR kódu spolu s Wi-Fi credentials.

```
Camera                         Receiver
  │                                │
  │──── HELLO ────────────────────►│
  │  { cameraId, token, sessionId, │
  │    cameraInfo? }               │
  │                                │
  │◄─── HELLO_ACK ─────────────────│
  │  { trusted: false,             │
  │    receiverId, policy }        │
  │                                │
  │  [Receiver čeká na uživatele]  │
  │                                │
  │◄─── APPROVED ──────────────────│  (user tapped Accept)
  │  { receiverId }                │
  │                                │
  │──── OFFER ─────────────────────►│  (SDP)
  │◄─── ANSWER ────────────────────│  (SDP)
  │◄──► ICE ───────────────────────│
  │◄════ video stream ═════════════│
```

**Validace tokenu:**
- `sessionId` musí odpovídat aktuální session receiveru
- `token` musí souhlasit (jednorázový – po použití se nastaví na `"USED"`)
- Token vyprší za **120 sekund** od vygenerování

Po schválení uživatelem receiver uloží kameru jako **TrustedSender** se sdíleným tajemstvím (32 byte náhodný klíč, base64).

---

### Flow 2: Trusted reconnect (beacon/proof)

Používá se pro **opakované připojení** dříve spárované kamery. Bez nutnosti QR kódu nebo interakce uživatele.

```
Camera                         Receiver
  │                                │
  │  [camera přijme UDP beacon     │
  │   s aktuálním challenge]       │
  │                                │
  │──── HELLO ────────────────────►│
  │  { cameraId,                   │
  │    proof: HMAC-SHA256(         │
  │      challenge, sharedSecret)} │
  │                                │
  │  [Receiver ověří HMAC]         │
  │                                │
  │◄─── HELLO_ACK ─────────────────│
  │  { trusted: true,              │
  │    receiverId, policy }        │
  │                                │
  │──── OFFER ─────────────────────►│
  │◄─── ANSWER ────────────────────│
  │◄──► ICE ───────────────────────│
  │◄════ video stream ═════════════│
```

**Výpočet proof:**
```
proof = Base64(HMAC-SHA256(challenge_string_utf8, sharedSecret_bytes))
```
kde `sharedSecret_bytes = Base64.decode(storedSharedSecret)`.

---

## 4. Zprávy – kompletní specifikace

### Camera → Receiver

#### HELLO (nové párování – QR token)
```json
{
  "type": "HELLO",
  "v": 1,
  "cameraId": "C-abc12345",
  "sessionId": "S-20260309-120000",
  "token": "T-ff00aa11bb",
  "cameraInfo": {
    "model": "Pixel 8",
    "app": "WlanCameraSender/1.0"
  }
}
```

#### HELLO (trusted reconnect – proof)
```json
{
  "type": "HELLO",
  "v": 1,
  "cameraId": "C-abc12345",
  "proof": "Base64EncodedHmacSha256=="
}
```

#### OFFER
```json
{
  "type": "OFFER",
  "sdp": "v=0\r\no=- 123456789 2 IN IP4 127.0.0.1\r\n..."
}
```

#### ICE
```json
{
  "type": "ICE",
  "candidate": "candidate:1 1 udp 2113937151 192.168.43.100 54321 typ host",
  "sdpMid": "0",
  "sdpMLineIndex": 0
}
```

---

### Receiver → Camera

#### HELLO_ACK (nové párování)
```json
{
  "type": "HELLO_ACK",
  "trusted": false,
  "receiverId": "R-12345678",
  "policy": {
    "singleCameraOnly": true
  }
}
```

#### HELLO_ACK (trusted)
```json
{
  "type": "HELLO_ACK",
  "trusted": true,
  "receiverId": "R-12345678",
  "policy": {
    "singleCameraOnly": true
  }
}
```

#### APPROVED
```json
{
  "type": "APPROVED",
  "receiverId": "R-12345678"
}
```

#### ANSWER
```json
{
  "type": "ANSWER",
  "sdp": "v=0\r\no=- 987654321 2 IN IP4 127.0.0.1\r\n..."
}
```

#### ICE
```json
{
  "type": "ICE",
  "candidate": "candidate:1 1 udp 2113937151 192.168.43.1 12345 typ host",
  "sdpMid": "0",
  "sdpMLineIndex": 0
}
```

#### REJECTED
```json
{
  "type": "REJECTED",
  "code": "USER_REJECTED"
}
```

#### ERROR
```json
{
  "type": "ERROR",
  "code": "BUSY",
  "message": "Another camera is connected"
}
```

---

## 5. Chybové kódy

| Kód | Situace |
|---|---|
| `BUSY` | Receiver je obsazen jinou kamerou |
| `INVALID_HELLO` | Chybí `cameraId` v HELLO |
| `UNAUTHORIZED` | HELLO bez tokenu ani proof |
| `INVALID_SESSION` | `sessionId` neodpovídá aktuální session |
| `TOKEN_EXPIRED` | Token vypršel (> 120s) |
| `INVALID_TOKEN` | Token nesouhlasí |
| `INVALID_PROOF` | HMAC proof nesouhlasí |
| `NOT_AUTHENTICATED` | OFFER/ICE před HELLO |
| `USER_REJECTED` | Uživatel odmítl připojení |

---

## 6. QR kód payload

QR kód zobrazovaný v aplikaci obsahuje JSON zakódovaný jako text. Platnost **120 sekund**.

```json
{
  "v": 1,
  "role": "receiver",
  "receiverId": "R-12345678",
  "ssid": "HotspotSSID",
  "psk": "HotspotPassword",
  "wsUrl": "ws://192.168.43.1:8888/ws",
  "sessionId": "S-20260309-120000",
  "token": "T-ff00aa11bb",
  "tokenExpSec": 120
}
```

Kamera po naskenování:
1. Připojí se na `ssid` s heslem `psk`
2. Připojí WS na `wsUrl`
3. Odešle HELLO s `token` + `sessionId`

---

## 7. WebRTC konfigurace

- **Semantika:** Unified Plan
- **Směr:** Receive-only (kamera posílá video, receiver zobrazuje)
- **ICE:** Pouze lokální kandidáti (LAN) – žádné STUN/TURN servery
- **Gathering:** Continual (průběžné přidávání ICE kandidátů)
- **Video codec:** H.264 nebo VP8 (dle schopností zařízení)

---

## 8. Bezpečnost

- **Token** je jednorázový (invalidován po prvním použití)
- **sharedSecret** je 32-byte náhodný klíč (CSPRNG), uložen v `SharedPreferences`
- **Challenge** rotuje každých 7 sekund → replay útok na HMAC je neproveditelný
- Komunikace je **nešifrovaná** (plain WebSocket) – vhodné pouze pro důvěryhodnou LAN
- Pro produkční nasazení zvažte `wss://` + TLS certifikát
