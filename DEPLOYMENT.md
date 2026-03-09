# Nasazení WlanCameraServer – priv-app

Tento dokument popisuje postup nasazení aplikace **WlanCameraServer** jako privilegovaného
systémového priv-app na infotainmentové jednotce Škoda/VW (CARIAD ekosystém).

---

## Předpoklady

- ADB přístup k cílovému zařízení s `adb root` a `adb remount` (vývojová/testovací jednotka)
- Android 13+ (API 33+)
- Oddíl `/product` musí být zapisovatelný (`adb remount`)
- Na produkčních zařízeních APK musí být podepsáno platformovým klíčem

---

## Adresářová struktura na zařízení

```
/product/
├── priv-app/
│   └── WlanCameraServer/
│       └── WlanCameraServer.apk          ← APK aplikace
│
└── etc/
    └── permissions/
        └── WlanCameraServer_privapp_permissions.xml  ← XML s oprávněními
```

---

## Krok 1 – Build

```powershell
cd C:\Users\tomas\AndroidStudioProjects\WlanCameraServer

# Debug build (pro testování)
.\gradlew assembleDebug

# Release build (pro produkci)
.\gradlew assembleRelease
```

Výstup APK:
- Debug: `app\build\outputs\apk\debug\app-debug.apk`
- Release: `app\build\outputs\apk\release\app-release.apk`

---

## Krok 2 – Push na zařízení

```bash
# Získat root přístup a remount
adb root
adb remount

# Vytvořit adresář pro priv-app
adb shell mkdir -p /product/priv-app/WlanCameraServer

# Nahrát APK
adb push app/build/outputs/apk/debug/app-debug.apk \
    /product/priv-app/WlanCameraServer/WlanCameraServer.apk

# Nahrát XML s oprávněními
adb push WlanCameraServer_privapp_permissions.xml \
    /product/etc/permissions/WlanCameraServer_privapp_permissions.xml

# Nastavit správná oprávnění souborového systému
adb shell chmod 644 /product/priv-app/WlanCameraServer/WlanCameraServer.apk
adb shell chmod 644 /product/etc/permissions/WlanCameraServer_privapp_permissions.xml

# Reboot (NUTNÝ – packagemanager čte priv-app pouze při startu)
adb reboot
```

### PowerShell verze (Windows)

```powershell
adb root
adb remount
adb shell mkdir -p /product/priv-app/WlanCameraServer
adb push "app\build\outputs\apk\debug\app-debug.apk" "/product/priv-app/WlanCameraServer/WlanCameraServer.apk"
adb push "WlanCameraServer_privapp_permissions.xml" "/product/etc/permissions/WlanCameraServer_privapp_permissions.xml"
adb shell chmod 644 /product/priv-app/WlanCameraServer/WlanCameraServer.apk
adb shell chmod 644 /product/etc/permissions/WlanCameraServer_privapp_permissions.xml
adb reboot
```

---

## Krok 3 – Ověření po rebootu

```bash
# Ověřit instalaci
adb shell pm list packages | grep wlancamera
# Očekávaný výstup: package:skoda.app.wlancameraserver

# Ověřit priv-app status a udělená oprávnění
adb shell dumpsys package skoda.app.wlancameraserver | grep -A5 "install\|privapp\|flags"

# Zkontrolovat, zda jsou privilegovaná oprávnění udělena
adb shell dumpsys package skoda.app.wlancameraserver | grep -i "NETWORK_SETTINGS\|granted=true"

# Spustit aplikaci přes ADB
adb shell am start -n skoda.app.wlancameraserver/.ui.MainActivity
```

---

## Krok 4 – Aktualizace bez rebootu (pro iterativní vývoj)

Na vývojovém zařízení lze použít rychlejší postup:

```bash
adb root
adb remount
adb push app/build/outputs/apk/debug/app-debug.apk \
    /product/priv-app/WlanCameraServer/WlanCameraServer.apk
adb shell pm install -r /product/priv-app/WlanCameraServer/WlanCameraServer.apk
```

> **Poznámka:** `pm install -r` může fungovat bez rebootu pro update kódu,
> ale změny v oprávněních (permissions XML) vyžadují vždy reboot.

---

## Soubor WlanCameraServer_privapp_permissions.xml

Tento soubor **musí být přítomen** v `/product/etc/permissions/` jinak Android
odmítne udělit privilegovaná oprávnění a aplikace nebude moci číst Wi-Fi konfiguraci
(`NETWORK_SETTINGS`), ani spustit LocalOnlyHotspot.

```xml
<permissions>
    <privapp-permissions package="skoda.app.wlancameraserver">
        <!-- Android standardní oprávnění -->
        <permission name="android.permission.ACCESS_NETWORK_STATE" />
        <permission name="android.permission.POST_NOTIFICATIONS" />
        <permission name="android.permission.ACCESS_WIFI_STATE" />
        <permission name="android.permission.ACCESS_FINE_LOCATION" />
        <permission name="android.permission.NEARBY_WIFI_DEVICES" />
        <permission name="android.permission.NETWORK_SETTINGS" />
        <!-- Automotive / CARIAD oprávnění -->
        <permission name="android.car.permission.CAR_IDENTIFICATION" />
        <permission name="android.car.permission.CAR_INFO" />
        <permission name="technology.cariad.magic.service.permission.ALL" />
        <permission name="com.volkswagenag.esm.ONLINEMANUALS_RW" />
        <permission name="com.volkswagenag.privacymode.settings.READ_ACCESS" />
        <permission name="de.esolutions.fw.android.rsi.gateway.BIND_RSI_ADMIN" />
        <permission name="de.esolutions.fw.rsi.permission.CONSUME_ONLINEUPDATEUI" />
    </privapp-permissions>
</permissions>
```

---

## Ladění po nasazení

### Logcat – sledování spuštění aplikace

```bash
# Celý výstup WlanCameraServer
adb logcat -s MainActivity:V ReceiverService:V SignalingController:V \
           WsServer:V WsServerClient:V UdpBeaconManager:V \
           WebRtcManager:V HotspotManager:V SystemHotspotReader:V

# Pouze chyby ze všech tagů
adb logcat *:S MainActivity:E ReceiverService:E SignalingController:E WsServer:E
```

### Ověření oprávnění za běhu

```bash
# Všechna udělená oprávnění
adb shell dumpsys package skoda.app.wlancameraserver | grep "granted=true"

# Ověřit, zda je app označena jako privileged
adb shell dumpsys package skoda.app.wlancameraserver | grep "pkgFlags"
# Hledejte: PRIVILEGED
```

### Zjistit IP adresy na zařízení

```bash
adb shell ip addr show | grep "inet "
# Nebo
adb shell ifconfig | grep "addr:"
```

---

## Produkční nasazení (AOSP build systém)

Pro integraci do AOSP build systému vytvořte `Android.bp` (Soong):

```bp
android_app {
    name: "WlanCameraServer",
    srcs: ["app/src/main/java/**/*.kt"],
    manifest: "app/src/main/AndroidManifest.xml",
    resource_dirs: ["app/src/main/res"],
    sdk_version: "36",
    min_sdk_version: "33",
    privileged: true,
    certificate: "platform",  // nebo vlastní klíč
    product_specific: true,
    static_libs: [
        // seznam závislostí jako prebuilt AAR
    ],
}
```

A soubor oprávnění zkopírovat do:
```
device/<vendor>/<product>/permissions/WlanCameraServer_privapp_permissions.xml
```

s referenci v `device.mk`:
```makefile
PRODUCT_COPY_FILES += \
    device/vendor/product/permissions/WlanCameraServer_privapp_permissions.xml:\
    $(TARGET_COPY_OUT_PRODUCT)/etc/permissions/WlanCameraServer_privapp_permissions.xml
```

---

## Řešení problémů při nasazení

### „Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"

APK je podepsáno jiným klíčem než nainstalovaná verze.
```bash
adb shell pm uninstall skoda.app.wlancameraserver
# Pak znovu push + install
```

### „Permission not granted: NETWORK_SETTINGS"

Soubor permissions XML buď chybí nebo má špatnou cestu/obsah.
```bash
adb shell cat /product/etc/permissions/WlanCameraServer_privapp_permissions.xml
# Ověřte, že package name odpovídá: skoda.app.wlancameraserver
adb reboot  # vždy nutný po změně permissions XML
```

### „adb remount: not supported"

Zařízení nemá povolený remount. Zkuste:
```bash
adb disable-verity
adb reboot
adb root
adb remount
```

### App se neobjevuje v launcheru

Zkontrolujte AndroidManifest – `MainActivity` musí mít:
```xml
<intent-filter>
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.LAUNCHER" />
</intent-filter>
```
