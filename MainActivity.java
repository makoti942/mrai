package com.voiceagent.app;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final String TAG = "VoiceAgent";
    private static final int REQ_PERMISSIONS = 1001;

    private static final String[] PERMS = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.VIBRATE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.FLASHLIGHT,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.SYSTEM_ALERT_WINDOW,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestPermissionsIfNeeded();
    }

    private void requestPermissionsIfNeeded() {
        ArrayList<String> needed = new ArrayList<>();
        for (String p : PERMS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_PERMISSIONS);
        } else {
            startVoiceService();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        startVoiceService();
    }

    private void startVoiceService() {
        Intent intent = new Intent(this, VoiceCommandService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        finish();
    }

    // ──────────────── BOOT RECEIVER (auto-start) ────────────────

    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                Log.d(TAG, "Boot completed — starting VoiceAgent");
                Intent svc = new Intent(ctx, VoiceCommandService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(svc);
                } else {
                    ctx.startService(svc);
                }
            }
        }
    }

    // ──────────────── FOREGROUND VOICE SERVICE ────────────────

    public static class VoiceCommandService extends Service {

        private static final String CHAN_ID = "voiceagent_channel";
        private static final int NOTIF_ID = 9001;
        private static final long WAKE_WORD_TIMEOUT_MS = 5000;
        private static final long COMMAND_TIMEOUT_MS = 6000;
        private static final long SILENCE_MS = 600;

        private SpeechRecognizer recognizer;
        private TextToSpeech tts;
        private AudioManager audioManager;
        private CameraManager cameraManager;
        private WifiManager wifiManager;
        private NotificationManager notificationManager;
        private Vibrator vibrator;
        private PowerManager.WakeLock wakeLock;

        private String torchCameraId;
        private boolean torchOn;
        private boolean isListening = false;
        private boolean wakeWordDetected = false;
        private boolean commandMode = false;
        private boolean ttsSpeaking = false;
        private long lastSpeechTime = 0;
        private int maxVolume;

        private final Handler handler = new Handler(Looper.getMainLooper());
        private Runnable wakeTimeoutRunnable;
        private Runnable commandTimeoutRunnable;

        // Bilingual intent map
        private final Map<String, IntentAction> intentMap = new HashMap<>();

        private interface IntentAction {
            String execute(IntentParams p);
        }

        private static class IntentParams {
            String rawText;
            String number;
            String contact;
            String app;
            String message;
            String direction;
            Integer level;
            Boolean state;
        }

        @Override
        public void onCreate() {
            super.onCreate();
            Log.d(TAG, "VoiceCommandService creating");
            createNotificationChannel();
            startForeground(NOTIF_ID, buildNotification("Voice Agent active"));

            audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
            wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":wakelock");
            wakeLock.acquire(10 * 60 * 1000L);

            findTorchCamera();
            registerIntentActions();
            initTts();
            initRecognizer();

            // Boot receiver registration
            registerBootReceiver();
        }

        private void registerBootReceiver() {
            try {
                IntentFilter filter = new IntentFilter(Intent.ACTION_BOOT_COMPLETED);
                registerReceiver(new BootReceiver(), filter);
            } catch (Exception e) {
                Log.w(TAG, "Boot receiver registration failed", e);
            }
        }

        private void createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        CHAN_ID, "Voice Agent", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Voice Agent is listening");
                ch.setShowBadge(false);
                getSystemService(NotificationManager.class).createNotificationChannel(ch);
            }
        }

        private Notification buildNotification(String text) {
            Intent openIntent = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            return new NotificationCompat.Builder(this, CHAN_ID)
                    .setContentTitle("VoiceAgent")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
        }

        private void updateNotification(String text) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.notify(NOTIF_ID, buildNotification(text));
        }

        // ─── Find torch camera ─────────────────────────────────

        private void findTorchCamera() {
            try {
                for (String id : cameraManager.getCameraIdList()) {
                    CameraCharacteristics cc = cameraManager.getCameraCharacteristics(id);
                    Boolean flash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                    if (flash != null && flash
                            && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        torchCameraId = id;
                        return;
                    }
                }
                for (String id : cameraManager.getCameraIdList()) {
                    CameraCharacteristics cc = cameraManager.getCameraCharacteristics(id);
                    Boolean flash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (flash != null && flash) { torchCameraId = id; return; }
                }
            } catch (CameraAccessException e) {
                Log.e(TAG, "Camera init error", e);
            }
        }

        // ─── Intent Map ────────────────────────────────────────

        private void registerIntentActions() {
            intentMap.put("volume", p -> {
                if (p.level != null) {
                    int lvl = Math.max(0, Math.min(maxVolume, p.level * maxVolume / 100));
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, lvl, 0);
                    return "Volume " + (p.level > 50 ? "raised" : "lowered");
                }
                if ("up".equals(p.direction)) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_RAISE, 0);
                    return "Volume up";
                }
                if ("down".equals(p.direction)) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_LOWER, 0);
                    return "Volume down";
                }
                return "Done";
            });

            intentMap.put("torch", p -> {
                if (torchCameraId == null) return "No flash";
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        boolean on = p.state != null ? p.state : !torchOn;
                        cameraManager.setTorchMode(torchCameraId, on);
                        torchOn = on;
                        return on ? "Torch on" : "Torch off";
                    }
                    return "Not supported";
                } catch (Exception e) {
                    return "Flash error";
                }
            });

            intentMap.put("open_app", p -> {
                if (p.app == null) return "Which app?";
                Intent intent = resolveApp(p.app);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(intent); return "Opening " + p.app; }
                    catch (Exception e) { return "Cannot open " + p.app; }
                }
                String pkg = resolvePackage(p.app);
                if (pkg != null) {
                    Intent launcher = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (launcher != null) {
                        launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try { startActivity(launcher); return "Opening " + p.app; }
                        catch (Exception e) { return "Cannot open"; }
                    }
                }
                return "App not found";
            });

            intentMap.put("call", p -> {
                if (p.number != null) {
                    Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + p.number));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (checkCallPermission()) { startActivity(i); return "Calling"; }
                    Intent d = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + p.number));
                    d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(d);
                    return "Dialing";
                }
                if (p.contact != null) {
                    Intent d = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + p.contact));
                    d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(d);
                    return "Dialing " + p.contact;
                }
                return "Who to call?";
            });

            intentMap.put("sms", p -> {
                String to = p.number != null ? p.number : p.contact;
                if (to == null) return "Who to message?";
                Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + to));
                i.putExtra("sms_body", p.message != null ? p.message : "Hello from VoiceAgent");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return "Opening SMS";
            });

            intentMap.put("notifications", p -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    var active = notificationManager.getActiveNotifications();
                    if (active.length == 0) return "No notifications";
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.min(5, active.length); i++) {
                        String t = active[i].getNotification().extras
                                .getString(android.app.Notification.EXTRA_TITLE, "");
                        String txt = active[i].getNotification().extras
                                .getString(android.app.Notification.EXTRA_TEXT, "");
                        if (!t.isEmpty()) sb.append(t).append(". ");
                        if (!txt.isEmpty()) sb.append(txt).append(". ");
                    }
                    return sb.length() > 0 ? sb.toString() : "Notifications present";
                }
                return "Not supported";
            });

            intentMap.put("wifi", p -> {
                boolean on = p.state != null ? p.state : !wifiManager.isWifiEnabled();
                wifiManager.setWifiEnabled(on);
                return on ? "WiFi on" : "WiFi off";
            });

            intentMap.put("bluetooth", p -> {
                BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
                if (ba == null) return "No Bluetooth";
                boolean on = p.state != null ? p.state : !ba.isEnabled();
                if (on) ba.enable(); else ba.disable();
                return on ? "Bluetooth on" : "Bluetooth off";
            });

            intentMap.put("dnd", p -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "Not supported";
                int filter;
                if ("on".equals(p.direction) || Boolean.TRUE.equals(p.state)) {
                    filter = NotificationManager.INTERRUPTION_FILTER_PRIORITY;
                } else {
                    int cur = notificationManager.getCurrentInterruptionFilter();
                    filter = cur == NotificationManager.INTERRUPTION_FILTER_ALL
                            ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                            : NotificationManager.INTERRUPTION_FILTER_ALL;
                }
                if (notificationManager.isNotificationPolicyAccessGranted()) {
                    notificationManager.setInterruptionFilter(filter);
                    return filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
                            ? "Quiet on" : "Quiet off";
                }
                Intent i = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return "Allow DND access";
            });

            intentMap.put("screenshot", p -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Intent i = ((android.media.projection.MediaProjectionManager)
                            getSystemService(MEDIA_PROJECTION_SERVICE)).createScreenCaptureIntent();
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    return "Capturing";
                }
                return "Not supported";
            });

            intentMap.put("flash", p -> intentMap.get("torch").execute(p));
            intentMap.put("light", p -> intentMap.get("torch").execute(p));
            intentMap.put("photo", p -> {
                Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return "Camera opening";
            });
            intentMap.put("camera", p -> intentMap.get("photo").execute(p));
        }

        private Intent resolveApp(String name) {
            switch (name) {
                case "settings": case "mipangilio":
                    return new Intent(Settings.ACTION_SETTINGS);
                case "camera": case "kamera":
                    return new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                case "phone": case "dialer": case "simu":
                    return new Intent(Intent.ACTION_DIAL, Uri.parse("tel:"));
                case "browser": case "chrome":
                    return new Intent(Intent.ACTION_VIEW, Uri.parse("https://google.com"));
                case "calculator": case "kikokotoo": {
                    Intent ci = new Intent("android.intent.action.CALCULATOR");
                    ci.setPackage("com.android.calculator2");
                    return ci;
                }
                case "calendar": case "kalenda":
                    return new Intent(Intent.ACTION_VIEW, Uri.parse(
                            "content://com.android.calendar/time/"));
                case "maps": case "ramani":
                    return new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="));
                case "youtube":
                    Intent yt = new Intent(Intent.ACTION_VIEW);
                    yt.setPackage("com.google.android.youtube");
                    return yt;
                case "whatsapp": case "whatasapp":
                    Intent wa = new Intent(Intent.ACTION_VIEW);
                    wa.setPackage("com.whatsapp");
                    return wa;
                case "gallery": case "picha": {
                    Intent gi = new Intent(Intent.ACTION_VIEW);
                    gi.setType("image/*");
                    return gi;
                }
                default:
                    return null;
            }
        }

        private String resolvePackage(String name) {
            Map<String, String> m = new HashMap<>();
            m.put("settings", "com.android.settings");
            m.put("camera", "com.android.camera2");
            m.put("phone", "com.android.dialer");
            m.put("dialer", "com.android.dialer");
            m.put("chrome", "com.android.chrome");
            m.put("browser", "com.android.browser");
            m.put("calculator", "com.android.calculator2");
            m.put("calendar", "com.android.calendar");
            m.put("maps", "com.google.android.apps.maps");
            m.put("youtube", "com.google.android.youtube");
            m.put("whatsapp", "com.whatsapp");
            m.put("gallery", "com.android.gallery3d");
            return m.get(name);
        }

        private boolean checkCallPermission() {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                    == PackageManager.PERMISSION_GRANTED;
        }

        // ─── Text-to-Speech (concise, sharp) ──────────────────

        private void initTts() {
            tts = new TextToSpeech(this, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    tts.setLanguage(Locale.ENGLISH);
                    tts.setSpeechRate(1.05f);
                    tts.setPitch(1.0f);
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String uid) {
                            ttsSpeaking = true;
                            handler.postDelayed(() -> {
                                if (recognizer != null) {
                                    recognizer.stop();
                                }
                            }, 100);
                        }
                        @Override
                        public void onDone(String uid) {
                            ttsSpeaking = false;
                            handler.postDelayed(() -> startListening(), 200);
                        }
                        @Override
                        public void onError(String uid) {
                            ttsSpeaking = false;
                            handler.postDelayed(() -> startListening(), 200);
                        }
                    });
                    startListening();
                }
            });
        }

        private void speak(String text) {
            if (tts == null) return;
            String uid = UUID.randomUUID().toString();
            Bundle b = new Bundle();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, b, uid);
            updateNotification("Speaking...");
            Log.d(TAG, "TTS: " + text);
        }

        // ─── Speech Recognizer (always-on continuous) ─────────

        private void initRecognizer() {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle p) {
                    isListening = true;
                    updateNotification("Listening...");
                }

                @Override
                public void onBeginningOfSpeech() {
                    lastSpeechTime = System.currentTimeMillis();
                    updateNotification("Heard you...");
                }

                @Override
                public void onRmsChanged(float rms) {}

                @Override
                public void onBufferReceived(byte[] buf) {}

                @Override
                public void onEndOfSpeech() {
                    // User stopped talking — process immediately
                    isListening = false;
                    updateNotification("Processing...");
                }

                @Override
                public void onError(int code) {
                    isListening = false;
                    String err;
                    switch (code) {
                        case SpeechRecognizer.ERROR_NO_MATCH: err = "no match"; break;
                        case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: err = "timeout"; break;
                        case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: err = "busy"; break;
                        case SpeechRecognizer.ERROR_NETWORK: err = "network"; break;
                        default: err = "error " + code;
                    }
                    Log.d(TAG, "Recognizer: " + err);
                    // Restart immediately unless TTS is speaking
                    if (!ttsSpeaking) {
                        handler.postDelayed(() -> startListening(), 100);
                    }
                }

                @Override
                public void onResults(Bundle res) {
                    ArrayList<String> matches = res
                            .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        processTranscript(matches.get(0).toLowerCase().trim());
                    } else {
                        if (!ttsSpeaking) startListening();
                    }
                }

                @Override
                public void onPartialResults(Bundle res) {}

                @Override
                public void onEvent(int type, Bundle p) {}
            });
            startListening();
        }

        private void startListening() {
            if (ttsSpeaking || isListening) return;
            try {
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "sw-TZ");
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "sw-TZ,en-US");
                intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
                intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 200);
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600);
                recognizer.startListening(intent);
                isListening = true;
                updateNotification("Listening...");
            } catch (Exception e) {
                Log.e(TAG, "Start listening failed", e);
                handler.postDelayed(() -> startListening(), 1000);
            }
        }

        // ─── Transcript Processing (wake word + commands) ─────

        private void processTranscript(String text) {
            Log.d(TAG, "Transcript: " + text);
            if (text.isEmpty()) {
                if (!ttsSpeaking) startListening();
                return;
            }

            // Check for wake word "hello makoti"
            if (!wakeWordDetected && !commandMode) {
                if (text.contains("hello makoti") || text.contains("helo makoti")
                        || text.contains("hello macoti") || text.contains("makoti")
                        || text.contains("hello macotte") || text.contains("hello mkoti")) {
                    wakeWordDetected = true;
                    commandMode = true;
                    vibrate();
                    speak("Yes?");
                    setWakeTimeout();
                    return;
                }
                // Also check direct commands without wake word
                IntentParams p = parseIntent(text);
                if (p != null) {
                    commandMode = true;
                    executeAndRespond(p);
                    setWakeTimeout();
                    return;
                }
                if (!ttsSpeaking) startListening();
                return;
            }

            // Command mode — process as intent
            if (commandMode) {
                cancelWakeTimeout();
                IntentParams p = parseIntent(text);
                if (p != null) {
                    executeAndRespond(p);
                } else {
                    speak("Say again?");
                }
                handler.postDelayed(() -> {
                    commandMode = false;
                    wakeWordDetected = false;
                    if (!ttsSpeaking) startListening();
                }, 3000);
                return;
            }

            if (!ttsSpeaking) startListening();
        }

        private void setWakeTimeout() {
            cancelWakeTimeout();
            wakeTimeoutRunnable = () -> {
                commandMode = false;
                wakeWordDetected = false;
                if (!ttsSpeaking) startListening();
            };
            handler.postDelayed(wakeTimeoutRunnable, WAKE_WORD_TIMEOUT_MS);
        }

        private void cancelWakeTimeout() {
            if (wakeTimeoutRunnable != null) handler.removeCallbacks(wakeTimeoutRunnable);
        }

        private void vibrate() {
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100,
                            VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(100);
                }
            }
        }

        // ─── Semantic Intent Parsing (English + Kiswahili) ─────

        private IntentParams parseIntent(String text) {
            if (text == null || text.isEmpty()) return null;
            String t = text.toLowerCase().trim();

            IntentParams p = new IntentParams();
            p.rawText = t;

            // Volume
            if (matchesAny(t, "volume", "sauti", "paza", "nyamazisha",
                    "ongeza sauti", "punguza sauti", "kubaza", "teremsha")) {
                p.direction = matchesAny(t, "up", "ongeza", "paza", "kubaza", "juu") ? "up" : null;
                if (p.direction == null && matchesAny(t, "down", "punguza", "teremsha", "nyamazisha", "chini")) {
                    p.direction = "down";
                }
                p.level = extractNumber(t);
                if (matchesAny(t, "max", "full", "highest", "kamili", "juu kabisa")) p.level = 100;
                if (matchesAny(t, "min", "zero", "off", "sifuri", "chini kabisa")) p.level = 0;
                return executeIntent("volume", p);
            }

            // Torch / Flash / Light / Taa
            if (matchesAny(t, "torch", "flash", "flashlight", "taa", "tochi", "mwanga", "mwasha")) {
                p.state = matchesAny(t, "on", "washa", "enable", "fungua", "open", "mwasha");
                if (p.state == null && matchesAny(t, "off", "zima", "disable", "funga", "close")) {
                    p.state = false;
                }
                // "taa" alone = toggle
                if (t.matches(".*\\b(taa|tochi|mwanga)\\b.*") && p.state == null) {
                    p.state = !torchOn;
                }
                return executeIntent("torch", p);
            }

            // Open app
            if (matchesAny(t, "open", "fungua", "launch", "start", "go to")) {
                p.app = extractApp(t);
                if (p.app != null) return executeIntent("open_app", p);
            }
            // Direct app names
            p.app = extractDirectApp(t);
            if (p.app != null) return executeIntent("open_app", p);

            // Call
            if (matchesAny(t, "call", "dial", "piga simu", "piga", "mpigie", "simu")) {
                p.number = extractPhone(t);
                if (p.number == null) p.contact = extractAfterKeyword(t, "call", "dial", "piga simu", "piga", "mpigie");
                return executeIntent("call", p);
            }

            // SMS
            if (matchesAny(t, "send", "message", "sms", "text", "tuma ujumbe", "tuma sms", "ujumbe")) {
                p.number = extractPhone(t);
                p.message = "Message from VoiceAgent";
                if (p.number == null) {
                    String[] parts = t.split("(?:to|kwa|kwa namba)\\s+");
                    if (parts.length > 1) {
                        p.number = extractPhone(parts[1]);
                        if (p.number == null) p.contact = parts[1].split("\\s+")[0];
                    }
                }
                return executeIntent("sms", p);
            }

            // Notifications
            if (matchesAny(t, "notification", "taarifa", "arifa", "notifications",
                    "read notifications", "soma taarifa", "soma arifa",
                    "what are my notifications", "nini taarifa")) {
                return executeIntent("notifications", p);
            }

            // WiFi
            if (matchesAny(t, "wifi")) {
                p.state = matchesAny(t, "on", "washa", "enable", "fungua");
                if (p.state == null && matchesAny(t, "off", "zima", "disable", "funga")) p.state = false;
                return executeIntent("wifi", p);
            }

            // Bluetooth
            if (matchesAny(t, "bluetooth")) {
                p.state = matchesAny(t, "on", "washa", "enable", "fungua");
                if (p.state == null && matchesAny(t, "off", "zima", "disable", "funga")) p.state = false;
                return executeIntent("bluetooth", p);
            }

            // DND / Silent
            if (matchesAny(t, "do not disturb", "dnd", "silent", "quiet",
                    "usijali", "kimya", "hali ya kimya")) {
                p.direction = matchesAny(t, "on", "enable", "washa") ? "on" : "off";
                return executeIntent("dnd", p);
            }

            // Screenshot
            if (matchesAny(t, "screenshot", "capture screen", "piga screenshot",
                    "chukua skrini", "picha ya skrini")) {
                return executeIntent("screenshot", p);
            }

            // Photo
            if (matchesAny(t, "take picture", "take photo", "capture photo",
                    "piga picha", "chukua picha", "nas\u00e1 picha", "click photo")) {
                return executeIntent("photo", p);
            }

            return null;
        }

        private IntentParams executeIntent(String key, IntentParams p) {
            IntentAction action = intentMap.get(key);
            if (action != null) {
                p.rawText = key; // mark which intent matched
            }
            return p;
        }

        private void executeAndRespond(IntentParams p) {
            if (p == null || p.rawText == null) { speak("Say again?"); return; }
            String key = p.rawText;
            IntentAction action = intentMap.get(key);
            if (action == null) {
                // Re-parse to find key
                IntentParams parsed = parseIntent(p.rawText);
                if (parsed != null && parsed.rawText != null) {
                    action = intentMap.get(parsed.rawText);
                }
            }
            if (action != null) {
                String result = action.execute(p);
                speak(result);
                Log.d(TAG, "Action: " + key + " -> " + result);
            } else {
                speak("Say again?");
            }
        }

        // ─── Helpers ──────────────────────────────────────────

        private boolean matchesAny(String text, String... keywords) {
            for (String kw : keywords) {
                if (text.contains(kw)) return true;
            }
            return false;
        }

        private Integer extractNumber(String text) {
            var m = java.util.regex.Pattern.compile("(\\d+)").matcher(text);
            return m.find() ? Integer.parseInt(m.group(1)) : null;
        }

        private String extractPhone(String text) {
            var m = java.util.regex.Pattern.compile(
                    "(\\+?\\d[\\d\\s\\-\\(\\)]{4,14}\\d)").matcher(text);
            return m.find() ? m.group(1).replaceAll("[\\s\\-\\(\\)]", "") : null;
        }

        private String extractAfterKeyword(String text, String... keywords) {
            for (String kw : keywords) {
                int idx = text.indexOf(kw);
                if (idx >= 0) {
                    String after = text.substring(idx + kw.length()).trim();
                    if (!after.isEmpty()) return after.split("\\s+")[0];
                }
            }
            return null;
        }

        private String extractApp(String text) {
            String[] apps = {"settings", "camera", "phone", "dialer", "browser", "chrome",
                    "calculator", "calendar", "maps", "youtube", "whatsapp", "gallery",
                    "mipangilio", "kamera", "simu", "kikokotoo", "kalenda", "ramani",
                    "whatasapp", "picha"};
            for (String a : apps) {
                if (text.contains(a)) return a;
            }
            String[] tokens = text.split("\\s+");
            return tokens.length > 1 ? tokens[tokens.length - 1] : null;
        }

        private String extractDirectApp(String text) {
            if (text.matches(".*\\b(mipangilio|settings)\\b.*")) return "settings";
            if (text.matches(".*\\b(kamera|camera)\\b.*")) return "camera";
            if (text.matches(".*\\b(simu|phone|dialer)\\b.*")) return "phone";
            if (text.matches(".*\\b(calculator|kikokotoo)\\b.*")) return "calculator";
            if (text.matches(".*\\b(calendar|kalenda)\\b.*")) return "calendar";
            if (text.matches(".*\\b(maps|ramani)\\b.*")) return "maps";
            if (text.matches(".*\\b(youtube)\\b.*")) return "youtube";
            if (text.matches(".*\\b(whatsapp|whatasapp)\\b.*")) return "whatsapp";
            if (text.matches(".*\\b(gallery|picha|photos)\\b.*")) return "gallery";
            if (text.matches(".*\\b(chrome|browser)\\b.*")) return "browser";
            return null;
        }

        // ─── Service Lifecycle ────────────────────────────────

        @Nullable
        @Override
        public IBinder onBind(Intent intent) { return null; }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            return START_STICKY;
        }

        @Override
        public void onDestroy() {
            if (recognizer != null) {
                recognizer.destroy();
                recognizer = null;
            }
            if (tts != null) {
                tts.stop();
                tts.shutdown();
                tts = null;
            }
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            handler.removeCallbacksAndMessages(null);

            // Restart service if killed
            Intent restart = new Intent(this, VoiceCommandService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restart);
            } else {
                startService(restart);
            }

            super.onDestroy();
        }
    }
}
