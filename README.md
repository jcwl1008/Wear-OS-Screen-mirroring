# Wear Mirror

Mirror your Wear OS watch to a browser on the same Wi-Fi network. Open the viewer with a QR code or the address shown on the watch. To show it on a TV, connect the computer by HDMI or use your computer's supported casting feature.

[Download Wear Mirror v0.2 APK](downloads/WearMirror_v0.2.apk?raw=true)

## Quick start

1. Install the APK on your Wear OS watch. Wear Mirror requires Android API 30 or later and system screen capture support.
2. Connect the watch and your computer to the same Wi-Fi network.
3. Open Wear Mirror, tap **開始投影**, and approve the system screen capture prompt.
4. Scan the QR code or open the displayed address in the computer's browser. Use **全螢幕** to show the watch face full screen. Tap **停止投影** on the watch to end the session.

The viewer uses local HTTP. Use a trusted Wi-Fi network and share the session address only with your intended viewers.

## Build from source

Use Bash with JDK 17, Android SDK Platform 35, Build Tools 35.0.0, and the ZXing core 3.5.3 JAR. Set `JAVA_HOME`, `ANDROID_HOME`, and `ZXING_CORE_JAR` to their absolute paths. Set `WEAR_MIRROR_KEYSTORE` to your own signing keystore with alias `wearmirror`, and supply its password through `WEAR_MIRROR_STORE_PASSWORD`. Run `bash build.sh` from the checkout. The APK is written to a `deliverables` folder next to the checkout.

## 繁體中文

手錶同電腦連同一個 Wi-Fi。喺手錶開 Wear Mirror，撳「開始投影」同批准擷取螢幕。用電腦掃手錶上嘅 QR Code，或者喺瀏覽器輸入畫面顯示嘅網址。電腦可經 HDMI 或支援嘅無線投影功能接駁電視。
