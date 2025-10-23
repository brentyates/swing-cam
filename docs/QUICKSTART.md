# SwingCam - Quick Start Guide

## 5-Minute Setup

### 1. Open in Android Studio
- Open Android Studio
- Select "Open an Existing Project"
- Navigate to the `swing-cam` project directory
- Wait for Gradle sync to complete

### 2. Connect Your Pixel 9
- Connect via USB
- Enable USB debugging on phone
- Trust computer when prompted

### 3. Build & Install
- Click the green "Run" button (▶️) in Android Studio
- Select your Pixel 9 from the device dropdown
- App will build and install automatically

### 4. Grant Permissions
When app launches, grant:
- Camera
- Microphone
- Notifications (Android 13+)

### 5. Start Server
- Tap "Start Server" button
- Note the IP address shown (e.g., `http://192.168.1.100:8080`)

### 6. Test Recording

**From another device on same WiFi:**
```bash
curl -X POST http://192.168.1.100:8080/api/record
```

**Or use the manual button in the app**

## That's It! 🎉

Your phone is now recording golf swings via HTTP API.

## Common Use Cases

### Golf Simulator Integration
Add this to your simulator's webhook/HTTP trigger settings:
```
http://YOUR_PHONE_IP:8080/api/record
```

### Remote Control from Computer
```bash
# Check if server is running
curl http://192.168.1.100:8080/

# Start recording
curl -X POST http://192.168.1.100:8080/api/record

# View recordings list
curl http://192.168.1.100:8080/api/recordings
```

### Shell Script Trigger
```bash
#!/bin/bash
# record-swing.sh
PHONE_IP="192.168.1.100"
curl -X POST http://$PHONE_IP:8080/api/record
echo "Recording started on phone!"
```

## Troubleshooting

**"Server won't start"**
- Check notification permission is granted
- Restart the app

**"Can't connect from other device"**
- Verify both on same WiFi network
- Check phone's IP address in app
- Try pinging the phone: `ping 192.168.1.100`

**"Recording failed"**
- Ensure storage space available
- Check camera permission granted
- Try manual recording button first

**"Need to change duration"**
- Currently must edit `Config.kt` default value
- Or manually create config.json in app storage
- Future: Will add settings UI

## Next Steps

See:
- [README.md](../README.md) - Full documentation
- [USAGE.md](USAGE.md) - Detailed API guide
- [ARCHITECTURE.md](ARCHITECTURE.md) - Project structure
- [CLAUDE.md](../CLAUDE.md) - Development guide

## Pro Tips

1. **Keep phone plugged in** during recording sessions
2. **Use landscape mode** for best framing
3. **Position 10-15 feet away** for full swing capture
4. **Good lighting** makes huge difference
5. **Test API before your round** to ensure it's working
