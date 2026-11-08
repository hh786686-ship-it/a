# Experimental Call Mode (v1.9)

Use only with your own phone, and observe local rules about consent for transcribing a conversation.

1. Install the app. Open **Live Chinese Offline**, grant microphone and display-over-other-apps permissions.
2. Turn on **Experimental Call Mode (speakerphone)** in the app.
3. Tap **Enable Call Listening in Accessibility settings** and explicitly enable **Live Chinese Call Listening**. On devices that restrict sideloaded accessibility access, check App info for an "Allow restricted settings" menu if appropriate.
4. Return to the app, verify the indicator says **Call Mode READY**, and tap **Start listening** while the app is visible.
5. Switch your phone/WhatsApp/WeChat call to **speakerphone** so incoming speech is physically audible to the same phone's microphone. Leave the floating captions over the call or Home screen.
6. If Android reports that capture is silenced, or the remote speaker isn't recognised, disable Call Mode or stop listening; this is a platform/device limitation, not a guaranteed workaround.

Important restrictions: A standard APK cannot access the other participant's digital downlink audio without privileged OS permissions. Accessibility Service + VOICE_RECOGNITION source can share acoustic microphone during some calls (Android API capture policy), but speakerphone and manufacturer voice processing decide whether the remote person's voice reaches the microphone. Your own speech may also be transcribed. Other apps (especially apps that use voice-communication privacy sources) can still silence capture. The app never reads accessibility window text or screen contents, and no microphone recordings are saved or uploaded. The original Mandarin-only streaming Zipformer model from v1.7 is retained; English recognition is not implemented.

Testing: GitHub Actions validates APK assembly, model decoder, microphone and overlay in an emulator and may test the optional Accessibility Service. It **cannot simulate a real phone call on your Samsung A16**, so phone-call compatibility, speakerphone intelligibility and speech latency are not proven.
