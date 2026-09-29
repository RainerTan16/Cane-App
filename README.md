# Smart Cane App
Android companion app (Bluetooth Classic SPP) for the Smart Walking Stick.

Protocol (newline-terminated text):
- ESP32 -> app: `SOS` or `SOS:lat,lng`
- app -> ESP32: `APP_CONNECTED`, `SMS_OK` (play 0040), `SMS_FAIL` (play 0041), `TEST`

Install: push to GitHub, then `git tag v1.0 && git push --tags`. APK appears in Releases.
