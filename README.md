# Cam2Vault — Native Android 14+ HEVC Background Video Recorder

Cam2Vault is a production-grade native Android app in Kotlin implementing high-efficiency HEVC (H.265) video recording that continues flawlessly even when the user locks their screen or switches apps.

## Key Technical Architecture

1. **Camera2 & MediaRecorder Pipeline**:
   - Uses `CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP` to query supported sizes on `MediaRecorder::class.java`.
   - Filters valid frame rates via `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` and `highSpeedVideoFpsRanges`.
   - Displays **only** genuine hardware-supported resolution + FPS combinations (including 4K UHD 3840x2160 at 60fps on phones with 60fps AE / high-speed sensor support).

2. **Android 14+ Foreground Service**:
   - Declares `foregroundServiceType="camera|microphone"` in `AndroidManifest.xml`.
   - Promoted to foreground using `startForeground(ID, notification, FOREGROUND_SERVICE_TYPE_CAMERA or FOREGROUND_SERVICE_TYPE_MICROPHONE)`.
   - Enables `PowerManager.PARTIAL_WAKE_LOCK` to keep the CPU active during screen-off recording.
   - Detaches preview surface safely without stopping the recording `Surface` bound to the Camera2 `CaptureRequest.Builder(TEMPLATE_RECORD)`.

3. **HEVC (H.265) Codec**:
   - MediaRecorder configured with `MediaRecorder.VideoEncoder.HEVC`.
   - Saves 40-50% storage space compared to legacy AVC/H.264 at identical visual quality.
   - Audio recorded via AAC at 192 kbps, 48 kHz.

4. **Scoped Storage MediaStore Persistence**:
   - Directly streams into `MediaStore.Video.Media.EXTERNAL_CONTENT_URI` using `contentResolver.openFileDescriptor(uri, "rw")`.
   - Set to `IS_PENDING = 1` while recording, updated to `0` upon completion with duration and metadata.
   - Files are saved directly to `Movies/Cam2Vault/` and accessible in the system Gallery and Google Photos.

5. **Android 14+ Runtime Permissions**:
   - Requests `android.permission.CAMERA`, `android.permission.RECORD_AUDIO`, and `android.permission.POST_NOTIFICATIONS` simultaneously.
