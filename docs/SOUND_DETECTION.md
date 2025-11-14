# Sound Detection Mode

## Overview

The sound detection mode enables automatic swing recording triggered by loud sounds (e.g., golf ball strikes) instead of manual API calls. This provides a hands-free experience where the camera continuously monitors for shots and automatically records them.

## Architecture

The sound detection system is cleanly separated from the core camera architecture and consists of three main components:

### 1. SoundDetector (`audio/SoundDetector.kt`)

Low-level audio monitoring component that:
- Uses Android AudioRecord API to capture microphone input
- Analyzes audio amplitude using RMS (Root Mean Square) calculation
- Detects sound spikes above a configurable threshold
- Implements debouncing to prevent duplicate detections
- Completely independent and reusable

**Configuration:**
- `sampleRate`: Audio sample rate (default: 44100 Hz)
- `threshold`: Amplitude threshold 0.0-1.0 (default: 0.3)
- `debounceMs`: Minimum time between detections (default: 500ms)
- `windowSizeMs`: Analysis window size (default: 50ms)

### 2. SoundTriggerManager (`audio/SoundTriggerManager.kt`)

High-level orchestrator that:
- Manages sound detection lifecycle (IDLE → LISTENING → PROCESSING → LISTENING)
- Coordinates between SoundDetector and CameraManager
- Uses existing launch monitor API internally (`armLaunchMonitor` / `shotDetected`)
- Auto-rearms after each shot for continuous operation
- Tracks statistics (shots detected, duration, etc.)

**State Machine:**
- `IDLE`: Not active
- `LISTENING`: Listening for sound, camera armed (continuous recording to buffer)
- `PROCESSING`: Shot detected, extracting video from buffer

**Configuration:**
- `soundThreshold`: Amplitude threshold for detection (0.0-1.0)
- `debounceMs`: Minimum time between shots (default: 2000ms)
- `postShotDelayMs`: Delay after sound to capture follow-through (default: 500ms)
- `rearmDelayMs`: Delay before rearming (default: 1000ms)

### 3. Integration Points

**MainActivity:**
- Creates and manages SoundTriggerManager instance
- Implements UI callbacks (state changes, errors, shot notifications)
- Cleans up resources on destroy

**HttpServerService:**
- Exposes REST API for remote control
- All endpoints in `/api/sound/*` namespace

## API Endpoints

### Start Sound Detection Mode

```
POST /api/sound/start
Content-Type: application/json

{
  "threshold": 0.3,        // Optional, 0.0-1.0
  "debounce_ms": 2000      // Optional, milliseconds
}
```

**Response:**
```json
{
  "status": "started",
  "config": {
    "threshold": 0.3,
    "debounce_ms": 2000
  }
}
```

### Stop Sound Detection Mode

```
POST /api/sound/stop
```

**Response:**
```json
{
  "status": "stopped",
  "shots_detected": 5,
  "duration_seconds": 125.5
}
```

### Get Status

```
GET /api/sound/status
```

**Response:**
```json
{
  "state": "listening",
  "active": true,
  "shots_detected": 3,
  "duration_seconds": 85.2,
  "time_since_last_shot": 12.5,
  "config": {
    "threshold": 0.3,
    "debounce_ms": 2000,
    "post_shot_delay_ms": 500
  }
}
```

### Update Configuration

```
PATCH /api/sound/config
Content-Type: application/json

{
  "threshold": 0.4,
  "debounce_ms": 3000
}
```

**Response:**
```json
{
  "status": "updated",
  "config": {
    "threshold": 0.4,
    "debounce_ms": 3000
  }
}
```

**Note:** Configuration can only be updated when the system is IDLE (not running).

### Get Current Audio Level

```
GET /api/sound/level
```

**Response:**
```json
{
  "level": 0.15
}
```

Useful for calibration - shows current microphone input level (0.0-1.0).

## Usage Examples

### Example 1: Basic Usage

```bash
# Start sound detection with defaults
curl -X POST http://10.0.0.147:8080/api/sound/start

# Check status
curl http://10.0.0.147:8080/api/sound/status

# Stop after practice session
curl -X POST http://10.0.0.147:8080/api/sound/stop
```

### Example 2: Custom Sensitivity

```bash
# Start with higher threshold (less sensitive - for louder environments)
curl -X POST http://10.0.0.147:8080/api/sound/start \
  -H "Content-Type: application/json" \
  -d '{"threshold": 0.5, "debounce_ms": 3000}'
```

### Example 3: Calibration

```bash
# Check current audio level while hitting shots to find optimal threshold
watch -n 0.5 'curl -s http://10.0.0.147:8080/api/sound/level'

# Once you know the typical level (e.g., 0.45), set threshold slightly below (e.g., 0.35)
curl -X POST http://10.0.0.147:8080/api/sound/start \
  -H "Content-Type: application/json" \
  -d '{"threshold": 0.35}'
```

### Example 4: Integration with Launch Monitor

Sound detection mode can run alongside launch monitor mode for redundancy:

```bash
# Sound mode handles trigger, launch monitor sends ball data
curl -X POST http://10.0.0.147:8080/api/sound/start

# When shot is detected by sound, launch monitor can still send ball data
# The system will associate the data with the most recent recording
curl -X POST http://10.0.0.147:8080/api/lm/shot-detected \
  -H "Content-Type: application/json" \
  -d '{"ballSpeed": 165.3, "launchAngle": 12.5}'
```

## How It Works

1. **Initialization**: `SoundTriggerManager.start()` creates a `SoundDetector` and starts listening
2. **Arm Camera**: Calls `CameraManager.armLaunchMonitor()` to begin continuous recording to buffer
3. **Listen**: `SoundDetector` monitors microphone, calculates RMS amplitude of audio samples
4. **Detect**: When amplitude exceeds threshold, `onSoundDetected` callback is triggered
5. **Extract**: Calls `CameraManager.shotDetected()` to extract last N seconds from buffer
6. **Process**: Video extraction happens in background, metadata saved with fileSize=0 initially
7. **Complete**: When extraction finishes, metadata updated with actual file size
8. **Rearm**: After configurable delay, automatically arms camera for next shot (back to step 2)

## Design Benefits

### Clean Separation
- Sound detection is completely separate from core camera logic
- Can be enabled/disabled independently
- No pollution of camera recording code

### Reuses Existing Infrastructure
- Uses launch monitor API internally (`armLaunchMonitor` / `shotDetected`)
- No duplication of video extraction logic
- Consistent behavior with API-based triggering

### Auto-Rearming
- Continuous operation without manual intervention
- Configurable delays prevent false triggers
- Suitable for driving range practice sessions

### Configurable
- Threshold adjustable for different environments
- Debounce timing prevents duplicate shots
- Post-shot delay captures full follow-through

## Tuning Recommendations

### Environment-Specific Settings

**Indoor Simulator (Quiet)**
```json
{
  "threshold": 0.2,
  "debounce_ms": 1500
}
```

**Outdoor Driving Range (Windy)**
```json
{
  "threshold": 0.5,
  "debounce_ms": 3000
}
```

**Home Garage with Net**
```json
{
  "threshold": 0.3,
  "debounce_ms": 2000
}
```

### Calibration Process

1. Start with default threshold (0.3)
2. Monitor audio level while hitting shots: `GET /api/sound/level`
3. Note the typical level when ball is struck (e.g., 0.45)
4. Set threshold ~0.1 below the strike level (e.g., 0.35)
5. Test with a few shots, adjust if needed
6. Increase debounce if getting duplicate triggers
7. Decrease threshold if missing shots

## Limitations

- **Ambient Noise**: May trigger on loud sounds other than ball strikes (claps, bangs, etc.)
- **Distance**: Microphone must be close enough to hear the strike clearly
- **Timing**: There's inherent latency between sound detection and video extraction
- **Battery**: Continuous audio monitoring and video recording drain battery faster
- **Compatibility**: Requires microphone permission and decent audio hardware

## Future Enhancements

Potential improvements for sound detection:

1. **Frequency Analysis**: Detect specific frequency signature of ball strikes (FFT)
2. **Machine Learning**: Train model to recognize golf swing sounds vs. other noises
3. **Adaptive Threshold**: Automatically adjust based on ambient noise level
4. **Visual Confirmation**: Use camera motion detection to confirm swing (hybrid approach)
5. **Multi-Microphone**: Use stereo/multiple mics for better directionality
6. **Calibration Wizard**: Guided UI to help users find optimal settings
7. **Sound Profile Library**: Pre-configured profiles for common scenarios (driver, iron, wedge)

## Troubleshooting

**Problem: Not detecting shots**
- Check audio level: `GET /api/sound/level` while hitting
- Lower threshold if strike level is below current threshold
- Verify microphone isn't muted or covered
- Check microphone permission is granted

**Problem: False triggers**
- Increase threshold to reduce sensitivity
- Increase debounce time to prevent duplicates
- Move phone away from other noise sources
- Use windscreen if outdoors

**Problem: Delayed captures**
- Reduce post_shot_delay_ms (may cut off follow-through)
- Ensure phone has sufficient CPU/memory
- Close other apps to free resources

**Problem: Missing follow-through**
- Increase post_shot_delay_ms (currently 500ms)
- Adjust recording duration in config
- Ensure buffer is long enough (maxLMDuration = 60s)

## Code Examples

### Python Integration

```python
import requests
import time

# Start sound detection
response = requests.post('http://10.0.0.147:8080/api/sound/start',
                         json={'threshold': 0.3, 'debounce_ms': 2000})
print(f"Started: {response.json()}")

# Monitor for 5 minutes
start_time = time.time()
while time.time() - start_time < 300:
    status = requests.get('http://10.0.0.147:8080/api/sound/status').json()
    print(f"Shots: {status['shots_detected']}, State: {status['state']}")
    time.sleep(5)

# Stop and get results
response = requests.post('http://10.0.0.147:8080/api/sound/stop')
print(f"Stopped: {response.json()}")
```

### Web Interface Integration

```javascript
// Start sound mode
async function startSoundMode(threshold = 0.3) {
  const response = await fetch('/api/sound/start', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({threshold, debounce_ms: 2000})
  });
  return response.json();
}

// Poll status
async function pollSoundStatus() {
  const response = await fetch('/api/sound/status');
  const data = await response.json();

  document.getElementById('shots-count').textContent = data.shots_detected;
  document.getElementById('state').textContent = data.state;

  // Update UI based on state
  if (data.state === 'listening') {
    document.getElementById('indicator').classList.add('listening');
  }
}

// Start monitoring
startSoundMode(0.3);
setInterval(pollSoundStatus, 1000);
```

## See Also

- [ARCHITECTURE.md](ARCHITECTURE.md) - Overall system architecture
- [USAGE.md](USAGE.md) - Complete API documentation
- [QUICKSTART.md](QUICKSTART.md) - Getting started guide
