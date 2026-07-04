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
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
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
import android.provider.ContactsContract;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final String TAG = "VoiceAgent";
    private static final int REQ_PERMISSIONS = 1001;

    private static final String[] PERMS = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
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
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.SYSTEM_ALERT_WINDOW,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SET_ALARM,
            Manifest.permission.USE_SIP,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE,
    };

    private TextView logView;
    private TextView statusView;
    private static final int REQ_VOICE = 2001;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private void sendTextCommand(String cmd) {
        Intent svc = new Intent(this, VoiceCommandService.class);
        svc.putExtra("text_command", cmd);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> matches = data.getStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS);
            if (matches != null && !matches.isEmpty()) {
                String text = matches.get(0);
                VoiceCommandService.addLog("Google Voice: \"" + text + "\"");
                sendTextCommand(text);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        logView = findViewById(R.id.logView);
        statusView = findViewById(R.id.statusView);

        EditText inputText = findViewById(R.id.inputText);
        Button sendBtn = findViewById(R.id.sendBtn);

        // Mic button — launches Google's voice recognition activity directly
        Button micBtn = findViewById(R.id.micBtn);
        micBtn.setOnClickListener(v -> {
            Intent voiceIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            voiceIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            voiceIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            voiceIntent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a command...");
            try {
                startActivityForResult(voiceIntent, REQ_VOICE);
                VoiceCommandService.addLog("Google voice UI launched");
            } catch (Exception e) {
                VoiceCommandService.addLog("Cannot launch voice: " + e.getMessage());
            }
        });

        sendBtn.setOnClickListener(v -> {
            String cmd = inputText.getText().toString().trim();
            if (!cmd.isEmpty()) {
                inputText.setText("");
                sendTextCommand(cmd);
            }
        });

        // Enter key sends
        inputText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendBtn.performClick();
                return true;
            }
            return false;
        });

        // Poll logs from service
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                for (String line : VoiceCommandService.debugLog) {
                    sb.append(line).append("\n");
                }
                logView.setText(sb.toString());
                // Auto-scroll
                int scroll = logView.getLineCount() * logView.getLineHeight();
                if (scroll > logView.getHeight()) {
                    logView.scrollTo(0, scroll - logView.getHeight());
                }
                uiHandler.postDelayed(this, 400);
            }
        });

        // Poll status
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                String status = "Not listening";
                String notifText = "No notification";
                try {
                    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null && Build.VERSION.SDK_INT >= 23) {
                        var active = nm.getActiveNotifications();
                        for (var n : active) {
                            if (n.getPackageName().equals(getPackageName())) {
                                notifText = n.getNotification().extras
                                    .getString(android.app.Notification.EXTRA_TEXT, "");
                                break;
                            }
                        }
                    }
                } catch (Exception e) {}
                if (notifText.contains("Listening")) status = "Listening for wake word...";
                else if (notifText.contains("Speaking")) status = "Speaking...";
                else if (notifText.contains("Heard")) status = "Heard you! Processing...";
                else if (notifText.contains("Active")) status = "Active";
                statusView.setText("Status: " + status);
                uiHandler.postDelayed(this, 1000);
            }
        });

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
        VoiceCommandService.addLog("Service started. Say \"hello makoti\" to activate");
        Toast.makeText(this, "VoiceAgent active. Say \"hello makoti\"", Toast.LENGTH_LONG).show();
    }

    // ──────────────── BOOT RECEIVER ────────────────

    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                Log.d(TAG, "Boot – starting VoiceAgent");
                Intent svc = new Intent(ctx, VoiceCommandService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(svc);
                } else {
                    ctx.startService(svc);
                }
            }
        }
    }

    // ──────────────── SEMANTIC NLP ENGINE ────────────────

    static class SemanticEngine {

        // Character n-gram set for similarity computation
        static Set<String> charNGrams(String s, int n) {
            Set<String> ngrams = new HashSet<>();
            String padded = " " + s.toLowerCase().trim() + " ";
            for (int i = 0; i + n <= padded.length(); i++) {
                ngrams.add(padded.substring(i, i + n));
            }
            return ngrams;
        }

        static double jaccard(Set<String> a, Set<String> b) {
            if (a.isEmpty() && b.isEmpty()) return 0;
            Set<String> inter = new HashSet<>(a);
            inter.retainAll(b);
            Set<String> union = new HashSet<>(a);
            union.addAll(b);
            return (double) inter.size() / union.size();
        }

        // n-gram similarity at character level (language agnostic)
        static double charNGramSimilarity(String s1, String s2) {
            if (s1 == null || s2 == null) return 0;
            String t1 = s1.toLowerCase().trim();
            String t2 = s2.toLowerCase().trim();
            if (t1.equals(t2)) return 1.0;
            if (t1.isEmpty() || t2.isEmpty()) return 0;
            // Weighted combination of 2-gram and 3-gram
            double sim2 = jaccard(charNGrams(t1, 2), charNGrams(t2, 2));
            double sim3 = jaccard(charNGrams(t1, 3), charNGrams(t2, 3));
            double sim4 = jaccard(charNGrams(t1, 4), charNGrams(t2, 4));
            return 0.2 * sim2 + 0.5 * sim3 + 0.3 * sim4;
        }

        // Intent templates with semantic fields (NOT hardcoded keywords)
        // Each template has: id, a list of example phrases, and required semantic markers
        static class IntentTemplate {
            String id;
            List<String> examples;
            double threshold;

            IntentTemplate(String id, List<String> examples, double threshold) {
                this.id = id;
                this.examples = examples;
                this.threshold = threshold;
            }
        }

        static final List<IntentTemplate> TEMPLATES = Arrays.asList(
            new IntentTemplate("torch_on", Arrays.asList(
                "turn on the flashlight", "switch on the torch", "i need light",
                "washa taa", "mwasha taa", "fungua taa", "taa washa",
                "turn on the flash", "flashlight on", "light please"
            ), 0.35),
            new IntentTemplate("torch_off", Arrays.asList(
                "turn off the flashlight", "switch off the torch", "no more light",
                "zima taa", "funga taa", "taa zima", "flashlight off"
            ), 0.35),
            new IntentTemplate("volume_up", Arrays.asList(
                "increase volume", "make it louder", "turn it up",
                "ongeza sauti", "paza sauti", "kubaza sauti", "sauti juu",
                "raise the volume", "volume up"
            ), 0.30),
            new IntentTemplate("volume_down", Arrays.asList(
                "decrease volume", "make it quieter", "turn it down",
                "punguza sauti", "teremsha sauti", "nyamazisha", "sauti chini",
                "lower the volume", "volume down"
            ), 0.30),
            new IntentTemplate("volume_set", Arrays.asList(
                "set volume to", "volume", "change volume to",
                "weka sauti", "sauti"
            ), 0.25),
            new IntentTemplate("open_app", Arrays.asList(
                "open app", "launch", "start", "open",
                "fungua", "zindua", "anzisha"
            ), 0.20),
            new IntentTemplate("call", Arrays.asList(
                "call", "dial", "phone", "make a call",
                "piga simu", "piga", "mpigie", "simu"
            ), 0.20),
            new IntentTemplate("sms", Arrays.asList(
                "send message", "send text", "text", "message",
                "tuma ujumbe", "tuma sms", "sms", "ujumbe"
            ), 0.25),
            new IntentTemplate("read_notifications", Arrays.asList(
                "read notifications", "check notifications", "what are my notifications",
                "soma taarifa", "soma arifa", "taarifa zangu", "arifa",
                "show notifications", "notification"
            ), 0.30),
            new IntentTemplate("find_contact", Arrays.asList(
                "find contact", "search contact", "lookup", "find",
                "tafuta", "tafuta anwani", "anwani ya", "number for",
                "phone number of", "contact"
            ), 0.25),
            new IntentTemplate("read_contacts", Arrays.asList(
                "show my contacts", "list contacts", "all contacts",
                "onyesha anwani", "orodhesha anwani", "anwani zangu"
            ), 0.30),
            new IntentTemplate("reply_message", Arrays.asList(
                "reply", "reply to", "reply message", "reply text",
                "jibu", "jibu ujumbe", "jibu sms"
            ), 0.30),
            new IntentTemplate("screenshot", Arrays.asList(
                "take screenshot", "capture screen", "screenshot",
                "piga screenshot", "picha ya skrini", "chukua skrini"
            ), 0.30),
            new IntentTemplate("wifi_on", Arrays.asList(
                "turn on wifi", "enable wifi", "wifi on",
                "washa wifi", "fungua wifi", "wifi washa"
            ), 0.30),
            new IntentTemplate("wifi_off", Arrays.asList(
                "turn off wifi", "disable wifi", "wifi off",
                "zima wifi", "funga wifi", "wifi zima"
            ), 0.30),
            new IntentTemplate("bluetooth_on", Arrays.asList(
                "turn on bluetooth", "enable bluetooth",
                "washa bluetooth", "fungua bluetooth"
            ), 0.30),
            new IntentTemplate("bluetooth_off", Arrays.asList(
                "turn off bluetooth", "disable bluetooth",
                "zima bluetooth", "funga bluetooth"
            ), 0.30),
            new IntentTemplate("dnd_on", Arrays.asList(
                "do not disturb", "silent mode", "quiet mode",
                "usijali", "kimya", "hali ya kimya",
                "turn on silent", "dnd on"
            ), 0.25),
            new IntentTemplate("dnd_off", Arrays.asList(
                "turn off silent", "turn off do not disturb",
                "zima kimya", "dnd off"
            ), 0.25),
            new IntentTemplate("take_photo", Arrays.asList(
                "take a picture", "take photo", "capture photo",
                "piga picha", "chukua picha", "picha",
                "open camera", "camera"
            ), 0.25),
            new IntentTemplate("alarm", Arrays.asList(
                "set alarm", "set an alarm", "alarm",
                "weka alarmu", "alarmu", "kengele"
            ), 0.25),
            new IntentTemplate("greeting", Arrays.asList(
                "hello", "hi", "hey", "good morning", "good evening",
                "jambo", "habari", "sasa", "mambo", "vipi",
                "how are you", "what's up", "niaje"
            ), 0.30),
            new IntentTemplate("thanks", Arrays.asList(
                "thank you", "thanks", "thanks a lot",
                "asante", "asante sana", "shukran"
            ), 0.35),
            new IntentTemplate("goodbye", Arrays.asList(
                "goodbye", "bye", "see you", "see you later",
                "kwa heri", "baadaye", "tuonane"
            ), 0.35),
            new IntentTemplate("time", Arrays.asList(
                "what time is it", "tell me the time", "current time",
                "saa ngapi", "saa", "wakati"
            ), 0.30),
            new IntentTemplate("date", Arrays.asList(
                "what date is it", "what day is it", "today's date",
                "tarehe gani", "tarehe", "siku gani"
            ), 0.30),
            new IntentTemplate("battery", Arrays.asList(
                "battery level", "check battery", "battery percentage",
                "betri", "nguvu ya betri", "asilimia ya betri",
                "charge level"
            ), 0.30)
        );

        static class IntentResult {
            String id;
            double score;
            String rawText;

            IntentResult(String id, double score, String rawText) {
                this.id = id;
                this.score = score;
                this.rawText = rawText;
            }
        }

        // Find best intent using n-gram similarity against templates
        static IntentResult resolve(String text) {
            if (text == null || text.trim().isEmpty()) return null;
            String input = text.toLowerCase().trim();

            IntentResult best = null;
            for (IntentTemplate tpl : TEMPLATES) {
                double maxSim = 0;
                for (String ex : tpl.examples) {
                    double sim = charNGramSimilarity(input, ex);
                    // Bonus for word overlap
                    String[] inputWords = input.split("\\s+");
                    String[] exWords = ex.toLowerCase().split("\\s+");
                    int overlap = 0;
                    for (String iw : inputWords) {
                        for (String ew : exWords) {
                            if (iw.length() > 2 && ew.length() > 2 &&
                                    (iw.contains(ew) || ew.contains(iw) ||
                                     charNGramSimilarity(iw, ew) > 0.6)) {
                                overlap++;
                                break;
                            }
                        }
                    }
                    double wordOverlap = exWords.length > 0
                            ? (double) overlap / exWords.length : 0;
                    double combined = 0.6 * sim + 0.4 * wordOverlap;
                    if (combined > maxSim) maxSim = combined;
                }
                if (maxSim >= tpl.threshold && (best == null || maxSim > best.score)) {
                    best = new IntentResult(tpl.id, maxSim, input);
                }
            }
            return best;
        }
    }

    // ──────────────── CONVERSATION MANAGER ────────────────

    static class ConversationManager {
        String lastIntent;
        String lastResponse;
        String lastNumber;
        String lastContact;
        String lastMessage;
        boolean awaitingConfirmation;
        boolean awaitingContactName;
        boolean awaitingMessageText;
        boolean awaitingNumber;
        boolean awaitingAppName;
        boolean isGreeted;
        int turnCount;
        final Random random = new Random();

        final String[] greetings = {
            "Hey! What can I help you with?",
            "Yes? I'm listening.",
            "Hi! What do you need?",
            "Hello! Tell me what to do.",
            "Habari! How can I help?",
            "Nisaidieje? What can I do for you?"
        };

        final String[] thanksReplies = {
            "You're welcome!", "Anytime!", "Happy to help!",
            "Welcome!", "Karibu!", "No problem!"
        };

        final String[] goodbyeReplies = {
            "Goodbye!", "See you later!", "Bye!",
            "Kwa heri!", "Baadaye!", "Take care!"
        };

        final String[] fallbacks = {
            "I'm not sure what you mean. Can you say it differently?",
            "I didn't understand. Try again?",
            "Say that again? I want to help.",
            "Sijaelewa. Tafadhali sema tena.",
            "I'm still learning. Can you rephrase that?",
            "Tell me more clearly what you need."
        };

        String getGreeting() {
            isGreeted = true;
            return greetings[random.nextInt(greetings.length)];
        }

        String getThanks() {
            return thanksReplies[random.nextInt(thanksReplies.length)];
        }

        String getGoodbye() {
            return goodbyeReplies[random.nextInt(goodbyeReplies.length)];
        }

        String getFallback() {
            return fallbacks[random.nextInt(fallbacks.length)];
        }

        void reset() {
            lastIntent = null;
            lastResponse = null;
            lastNumber = null;
            lastContact = null;
            lastMessage = null;
            awaitingConfirmation = false;
            awaitingContactName = false;
            awaitingMessageText = false;
            awaitingNumber = false;
            awaitingAppName = false;
        }

        void startFresh() {
            reset();
            isGreeted = false;
            turnCount = 0;
        }
    }

    // ──────────────── FOREGROUND VOICE SERVICE ────────────────

    public static class VoiceCommandService extends Service {

        private static final String CHAN_ID = "voiceagent_channel";
        private static final int NOTIF_ID = 9001;
        private static final long WAKE_TIMEOUT_MS = 60000;

        // Debug log shared with the activity
        public static final java.util.ArrayList<String> debugLog = new java.util.ArrayList<>();
        public static void addLog(String msg) {
            String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ENGLISH).format(new java.util.Date());
            String line = "[" + ts + "] " + msg;
            debugLog.add(line);
            if (debugLog.size() > 300) debugLog.remove(0);
            android.util.Log.d(TAG, msg);
        }

        private SpeechRecognizer recognizer;
        private TextToSpeech tts;
        private AudioManager audioManager;
        private CameraManager cameraManager;
        private WifiManager wifiManager;
        private NotificationManager notificationManager;
        private Vibrator vibrator;
        private PowerManager.WakeLock wakeLock;
        private ContentResolver contentResolver;

        private String torchCameraId;
        private boolean torchOn;
        private boolean isListening = false;
        private boolean wakeWordDetected = false;
        private boolean commandMode = false;
        private boolean ttsSpeaking = false;
        private boolean recognizerError = false;
        private boolean useCloudSTT = false;
        private int errorCount = 0;
        private int maxVolume;

        // Cloud AI speech-to-text
        private AudioRecord audioRecord;
        private volatile boolean aiListening = false;
        private Thread aiThread;
        private String apiKey = "";
        private static final int SAMPLE_RATE = 16000;

        private final Handler handler = new Handler(Looper.getMainLooper());
        private Runnable wakeTimeoutRunnable;
        private final ConversationManager conv = new ConversationManager();

        @Override
        public void onCreate() {
            super.onCreate();
            addLog("Service starting...");
            try {
                createChannel();
                startForeground(NOTIF_ID, buildNotif("Active"));

                audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
                wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                contentResolver = getContentResolver();
                if (audioManager != null) maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":lock");
                    wakeLock.acquire(10 * 60 * 1000L);
                }

                findTorch();
                initTts();
                loadApiKey();
                if (!apiKey.isEmpty()) {
                    addLog("Cloud AI STT configured. Starting continuous listening...");
                    useCloudSTT = true;
                    startCloudListening();
                } else {
                    addLog("No AI API key set. Type: set api key YOUR_KEY");
                    addLog("Use 🎤 button for voice, or type commands below");
                    initRecognizer();
                }
                registerBootReceiver();
                addLog("Service ready. Say \"hello makoti\" or type a command");
            } catch (Exception e) {
                Log.e(TAG, "Service init error", e);
                addLog("Init error: " + e.getMessage());
                updateNotif("Init failed: " + e.getMessage());
            }
        }

        private void registerBootReceiver() {
            try {
                registerReceiver(new BootReceiver(), new IntentFilter(Intent.ACTION_BOOT_COMPLETED));
            } catch (Exception e) { Log.w(TAG, "Boot reg fail", e); }
        }

        private void createChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        CHAN_ID, "Voice Agent", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                getSystemService(NotificationManager.class).createNotificationChannel(ch);
            }
        }

        private Notification buildNotif(String text) {
            Intent open = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(
                    this, 0, open,
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

        private void updateNotif(String text) {
            getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotif(text));
        }

        // ─── Torch ──────────────────────────────────────────

        private void findTorch() {
            try {
                for (String id : cameraManager.getCameraIdList()) {
                    CameraCharacteristics cc = cameraManager.getCameraCharacteristics(id);
                    Boolean flash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                    if (flash != null && flash && facing != null
                            && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        torchCameraId = id; return;
                    }
                }
                for (String id : cameraManager.getCameraIdList()) {
                    CameraCharacteristics cc = cameraManager.getCameraCharacteristics(id);
                    Boolean flash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (flash != null && flash) { torchCameraId = id; return; }
                }
            } catch (CameraAccessException e) { Log.e(TAG, "Camera error", e); }
        }

        // ─── TTS ────────────────────────────────────────────

        private void initTts() {
            try {
                tts = new TextToSpeech(this, status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        tts.setLanguage(Locale.ENGLISH);
                        tts.setSpeechRate(0.90f);
                        tts.setPitch(1.3f);
                        // Try to pick a female voice
                        try {
                            for (android.speech.tts.Voice v : tts.getVoices()) {
                                String vn = v.getName().toLowerCase();
                                if (vn.contains("female") || vn.contains("woman") || vn.contains("girl")
                                    || vn.contains("samantha") || vn.contains("zira")) {
                                    tts.setVoice(v);
                                    addLog("Selected voice: " + v.getName());
                                    break;
                                }
                            }
                        } catch (Exception e) { /* use default */ }
                        addLog("TTS ready");
                        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override public void onStart(String uid) {
                                ttsSpeaking = true;
                                updateNotif("Speaking...");
                                if (recognizer != null) { try { recognizer.stopListening(); } catch (Exception e) {} }
                            }
                            @Override public void onDone(String uid) {
                                ttsSpeaking = false;
                                updateNotif("Listening...");
                                handler.postDelayed(() -> startListening(), 150);
                            }
                            @Override public void onError(String uid) {
                                ttsSpeaking = false;
                                updateNotif("Listening...");
                                handler.postDelayed(() -> startListening(), 150);
                            }
                        });
                        startListening();
                    } else {
                        Log.w(TAG, "TTS init failed: " + status);
                        updateNotif("TTS unavailable");
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "TTS error", e);
                updateNotif("TTS error");
            }
        }

        private void speak(String text) {
            if (tts == null || text == null || text.isEmpty()) return;
            String uid = UUID.randomUUID().toString();
            Bundle b = new Bundle();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, b, uid);
            conv.lastResponse = text;
            Log.d(TAG, "TTS: " + text);
        }

        // ─── Speech Recognizer ─────────────────────────────

        private void initRecognizer() {
            // Diagnose available recognition services
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                Intent recIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                java.util.List<android.content.pm.ResolveInfo> recServices =
                    pm.queryIntentServices(recIntent, 0);
                addLog("Found " + recServices.size() + " recognition services:");
                for (android.content.pm.ResolveInfo ri : recServices) {
                    addLog("  - " + ri.serviceInfo.packageName + "/" + ri.serviceInfo.name);
                }
            } catch (Exception e) {
                addLog("Query rec services error: " + e.getMessage());
            }

            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                addLog("Speech recognition NOT available");
                addLog("Install Google app or Google Play Services for voice input");
                updateNotif("Voice unavailable — type commands");
                recognizerError = true;
                return;
            }
            addLog("Speech recognition IS available");
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            if (recognizer == null) {
                addLog("Failed to create SpeechRecognizer (null)");
                addLog("Try: Settings → Apps → Google → Clear cache, then reboot");
                updateNotif("Voice unavailable — type commands");
                recognizerError = true;
                return;
            }
            addLog("SpeechRecognizer created successfully");
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle p) { isListening = true; updateNotif("Listening..."); }
                @Override public void onBeginningOfSpeech() { updateNotif("Heard you"); addLog("Heard speech"); }
                @Override public void onRmsChanged(float rms) {}
                @Override public void onBufferReceived(byte[] buf) {}
                @Override public void onEndOfSpeech() { isListening = false; updateNotif("Processing..."); addLog("Speech ended"); }
                @Override public void onError(int code) {
                    isListening = false;
                    String err = "code " + code;
                    if (code == SpeechRecognizer.ERROR_NO_MATCH) err = "ERROR_NO_MATCH — can't hear speech";
                    else if (code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) err = "SPEECH_TIMEOUT — waited but no speech";
                    else if (code == SpeechRecognizer.ERROR_NETWORK) err = "NETWORK — check internet";
                    else if (code == SpeechRecognizer.ERROR_CLIENT) err = "CLIENT — app-side error";
                    else if (code == SpeechRecognizer.ERROR_AUDIO) err = "AUDIO — mic issue";
                    else if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) err = "NO_PERMISSION";
                    else if (code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) err = "LANGUAGE not supported";
                    else if (code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) err = "LANGUAGE model not downloaded";
                    else if (code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) err = "BUSY";
                    errorCount++;
                    if (errorCount == 1) {
                        addLog("Speech error: " + err);
                        addLog("Make sure Google app has microphone permission and is the default voice input");
                    } else {
                        addLog("Retry #" + errorCount + ": " + err);
                    }
                    if (errorCount > 10) {
                        addLog("Stopping voice retry. Use text commands instead.");
                        updateNotif("Voice unavailable — type commands below");
                        recognizerError = true;
                        return;
                    }
                    long delay = Math.min(errorCount * 1000, 5000);
                    if (!ttsSpeaking) handler.postDelayed(() -> startListening(), delay);
                }
                @Override public void onResults(Bundle res) {
                    ArrayList<String> matches = res.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String text = matches.get(0).toLowerCase().trim();
                        addLog("Recognized: " + text);
                        processTranscript(text);
                    } else {
                        if (!ttsSpeaking) startListening();
                    }
                }
                @Override public void onPartialResults(Bundle res) {}
                @Override public void onEvent(int type, Bundle p) {}
            });
            startListening();
        }

        private void startListening() {
            if (ttsSpeaking || isListening || recognizer == null || recognizerError) return;
            try {
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US,sw-TZ");
                intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
                intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800);
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500);
                recognizer.startListening(intent);
                isListening = true;
                updateNotif("Listening...");
            } catch (Exception e) {
                Log.e(TAG, "Start fail", e);
                addLog("Listen start error: " + e.getMessage());
                handler.postDelayed(() -> startListening(), 2000);
            }
        }

        // ─── MAIN PROCESSING ────────────────────────────────

        private void processTranscript(String text) {
            Log.d(TAG, "Text: " + text);
            if (text.isEmpty()) { if (!ttsSpeaking) startListening(); return; }

            conv.turnCount++;
            addLog("Processing: \"" + text + "\"");

            // ── Wake word detection ──
            if (!wakeWordDetected && !commandMode) {
                SemanticEngine.IntentResult sem = SemanticEngine.resolve(text);
                boolean isGreeting = sem != null && "greeting".equals(sem.id) && sem.score > 0.4;

                // Wake word check
                boolean hasWakeWord = text.contains("hello makoti") || text.contains("helo makoti")
                        || text.contains("makoti") || text.contains("hello macoti")
                        || text.contains("helo macoti") || text.contains("hello mkoti")
                        || text.contains("makoti") && text.length() < 20;

                if (hasWakeWord || (isGreeting && !conv.isGreeted)) {
                    wakeWordDetected = true;
                    commandMode = true;
                    vibrate();
                    if (isGreeting && !conv.isGreeted) {
                        speak(conv.getGreeting());
                    } else {
                        speak("Yes? I'm ready.");
                    }
                    setWakeTimeout();
                    return;
                }

                // Try direct intent without wake word
                if (sem != null && sem.score > 0.35) {
                    wakeWordDetected = true;
                    commandMode = true;
                    executeIntent(sem.id, text);
                    setWakeTimeout();
                    return;
                }

                if (!ttsSpeaking) startListening();
                return;
            }

            // ── Command mode or follow-up ──
            cancelWakeTimeout();

            // Check if we're waiting for specific info
            if (conv.awaitingContactName) {
                conv.awaitingContactName = false;
                String contact = text;
                List<String> numbers = findContactNumber(contact);
                if (!numbers.isEmpty()) {
                    conv.lastContact = contact;
                    conv.lastNumber = numbers.get(0);
                    speak("Found " + contact + ". What should I do? Call or message?");
                } else {
                    speak("I couldn't find " + contact + ". Try another name.");
                }
                handler.postDelayed(() -> endCommandMode(), 4000);
                return;
            }

            if (conv.awaitingNumber) {
                conv.awaitingNumber = false;
                String num = extractNumber(text);
                if (num != null) {
                    conv.lastNumber = num;
                    speak("Number noted. Call or message?");
                } else {
                    conv.lastNumber = text.replaceAll("\\s+", "");
                    speak("Got it. Call or message?");
                }
                handler.postDelayed(() -> endCommandMode(), 4000);
                return;
            }

            if (conv.awaitingMessageText) {
                conv.awaitingMessageText = false;
                String msg = text;
                conv.lastMessage = msg;
                if (conv.lastNumber != null) {
                    sendSms(conv.lastNumber, msg);
                    speak("Message sent to " + (conv.lastContact != null ? conv.lastContact : conv.lastNumber));
                } else if (conv.lastContact != null) {
                    List<String> nums = findContactNumber(conv.lastContact);
                    if (!nums.isEmpty()) {
                        sendSms(nums.get(0), msg);
                        speak("Message sent to " + conv.lastContact);
                    } else {
                        speak("I don't have a number for " + conv.lastContact);
                    }
                } else {
                    speak("Who should I send it to?");
                    conv.awaitingContactName = true;
                }
                handler.postDelayed(() -> endCommandMode(), 4000);
                return;
            }

            if (conv.awaitingAppName) {
                conv.awaitingAppName = false;
                openAnyApp(text);
                handler.postDelayed(() -> endCommandMode(), 3000);
                return;
            }

            // ── Resolve intent semantically ──
            SemanticEngine.IntentResult sem = SemanticEngine.resolve(text);
            if (sem != null && sem.score > 0.25) {
                executeIntent(sem.id, text);
            } else {
                // Fallback: extract any numbers or known patterns
                String num = extractNumber(text);
                if (num != null && conv.lastIntent != null) {
                    conv.lastNumber = num;
                    if ("call".equals(conv.lastIntent)) {
                        makeCall(num);
                    } else if ("sms".equals(conv.lastIntent)) {
                        speak("What should I say?");
                        conv.awaitingMessageText = true;
                        return;
                    } else {
                        makeCall(num);
                    }
                } else {
                    // Try as an app name
                    if (openAnyApp(text)) {
                        // app opened
                    } else {
                        speak(conv.getFallback());
                    }
                }
            }
            handler.postDelayed(() -> endCommandMode(), 4000);
        }

        private void endCommandMode() {
            commandMode = false;
            wakeWordDetected = false;
            if (!ttsSpeaking) startListening();
        }

        private void setWakeTimeout() {
            cancelWakeTimeout();
            wakeTimeoutRunnable = () -> {
                commandMode = false;
                wakeWordDetected = false;
                wakeTimeoutRunnable = null;
                addLog("Back to background listening (1min timeout)");
                if (!ttsSpeaking) startListening();
            };
            handler.postDelayed(wakeTimeoutRunnable, WAKE_TIMEOUT_MS);
        }

        private void cancelWakeTimeout() {
            if (wakeTimeoutRunnable != null) {
                handler.removeCallbacks(wakeTimeoutRunnable);
                wakeTimeoutRunnable = null;
            }
        }

        private void vibrate() {
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
                } else { vibrator.vibrate(100); }
            }
        }

        // ─── INTENT EXECUTION ────────────────────────────────

        private void executeIntent(String intentId, String rawText) {
            conv.lastIntent = intentId;
            Log.d(TAG, "Intent: " + intentId + " (" + rawText + ")");
            addLog("Intent: " + intentId);

            switch (intentId) {
                case "torch_on":
                    setTorch(true);
                    break;
                case "torch_off":
                    setTorch(false);
                    break;
                case "volume_up":
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0);
                    speak("Volume up");
                    break;
                case "volume_down":
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0);
                    speak("Volume down");
                    break;
                case "volume_set": {
                    String lvlStr = extractNumber(rawText);
                    if (lvlStr != null) {
                        int lvl = Integer.parseInt(lvlStr);
                        int mapped = Math.max(0, Math.min(maxVolume, lvl * maxVolume / 100));
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, mapped, 0);
                        speak("Volume set to " + lvl);
                    } else {
                        speak("To what level?");
                    }
                    break;
                }
                case "open_app": {
                    String app = extractAppName(rawText);
                    if (app != null && !openAnyApp(app)) {
                        speak("I couldn't find " + app);
                    } else if (app == null) {
                        speak("Which app should I open?");
                        conv.awaitingAppName = true;
                    }
                    break;
                }
                case "call": {
                    String num = extractNumber(rawText);
                    if (num != null) {
                        makeCall(num);
                    } else {
                        String contact = extractName(rawText);
                        if (contact != null) {
                            List<String> nums = findContactNumber(contact);
                            if (!nums.isEmpty()) {
                                makeCall(nums.get(0));
                                conv.lastContact = contact;
                            } else {
                                speak("I don't have a number for " + contact);
                            }
                        } else {
                            speak("Who should I call? Tell me the name or number.");
                            conv.awaitingNumber = true;
                        }
                    }
                    break;
                }
                case "sms": {
                    String num = extractNumber(rawText);
                    if (num != null) {
                        conv.lastNumber = num;
                        speak("What should I say?");
                        conv.awaitingMessageText = true;
                    } else {
                        String contact = extractName(rawText);
                        if (contact != null) {
                            List<String> nums = findContactNumber(contact);
                            if (!nums.isEmpty()) {
                                conv.lastNumber = nums.get(0);
                                conv.lastContact = contact;
                                speak("What should I say to " + contact + "?");
                                conv.awaitingMessageText = true;
                            } else {
                                speak("I don't have a number for " + contact + ". Tell me the number.");
                                conv.awaitingNumber = true;
                            }
                        } else {
                            speak("Who should I message? Name or number?");
                            conv.awaitingContactName = true;
                        }
                    }
                    break;
                }
                case "read_notifications": {
                    readNotifications();
                    break;
                }
                case "find_contact": {
                    String name = extractName(rawText);
                    if (name != null) {
                        List<String> nums = findContactNumber(name);
                        if (!nums.isEmpty()) {
                            conv.lastContact = name;
                            conv.lastNumber = nums.get(0);
                            speak(name + " is at " + nums.get(0) + ". Call or message?");
                        } else {
                            speak("No contact found for " + name);
                        }
                    } else {
                        speak("What name should I search for?");
                        conv.awaitingContactName = true;
                    }
                    break;
                }
                case "read_contacts": {
                    listAllContacts();
                    break;
                }
                case "reply_message": {
                    replyToLastNotification(rawText);
                    break;
                }
                case "screenshot": {
                    takeScreenshot();
                    break;
                }
                case "wifi_on":
                    wifiManager.setWifiEnabled(true);
                    speak("WiFi on");
                    break;
                case "wifi_off":
                    wifiManager.setWifiEnabled(false);
                    speak("WiFi off");
                    break;
                case "bluetooth_on": {
                    BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
                    if (ba != null) { ba.enable(); speak("Bluetooth on"); }
                    else speak("No Bluetooth");
                    break;
                }
                case "bluetooth_off": {
                    BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
                    if (ba != null) { ba.disable(); speak("Bluetooth off"); }
                    else speak("No Bluetooth");
                    break;
                }
                case "dnd_on": case "dnd_off": {
                    toggleDnd("dnd_on".equals(intentId));
                    break;
                }
                case "take_photo": {
                    Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(i); speak("Opening camera"); }
                    catch (Exception e) { speak("Cannot open camera"); }
                    break;
                }
                case "alarm": {
                    String numStr = extractNumber(rawText);
                    if (numStr != null) {
                        int num = Integer.parseInt(numStr);
                        Intent i = new Intent(android.provider.AlarmClock.ACTION_SET_ALARM);
                        i.putExtra(android.provider.AlarmClock.EXTRA_HOUR, num);
                        i.putExtra(android.provider.AlarmClock.EXTRA_MINUTES, 0);
                        i.putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try { startActivity(i); speak("Setting alarm"); }
                        catch (Exception e) { speak("Opening alarm"); }
                    } else {
                        Intent i = new Intent(android.provider.AlarmClock.ACTION_SET_ALARM);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try { startActivity(i); }
                        catch (Exception e) { speak("Alarm app not found"); }
                    }
                    break;
                }
                case "greeting":
                    speak(conv.getGreeting());
                    commandMode = true;
                    setWakeTimeout();
                    break;
                case "thanks":
                    speak(conv.getThanks());
                    break;
                case "goodbye":
                    speak(conv.getGoodbye());
                    conv.startFresh();
                    break;
                case "time": {
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("h:mm a", Locale.ENGLISH);
                    speak("It's " + sdf.format(new java.util.Date()));
                    break;
                }
                case "date": {
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("EEEE, MMMM d", Locale.ENGLISH);
                    speak("Today is " + sdf.format(new java.util.Date()));
                    break;
                }
                case "battery": {
                    IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                    Intent batteryStatus = registerReceiver(null, ifilter);
                    if (batteryStatus != null) {
                        int level = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                        int scale = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                        int pct = (int) ((float) level / scale * 100);
                        speak("Battery is at " + pct + " percent");
                    } else { speak("Could not read battery"); }
                    break;
                }
                default:
                    speak(conv.getFallback());
            }
        }

        // ─── ACTIONS ────────────────────────────────────────

        private void setTorch(boolean on) {
            if (torchCameraId == null) { speak("No flash available"); return; }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cameraManager.setTorchMode(torchCameraId, on);
                    torchOn = on;
                    speak(on ? "Torch on" : "Torch off");
                }
            } catch (Exception e) { speak("Flash error"); }
        }

        private void makeCall(String number) {
            if (number == null) { speak("No number"); return; }
            Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (checkCallPerm()) {
                try { startActivity(i); speak("Calling"); }
                catch (Exception e) { dialNumber(number); }
            } else { dialNumber(number); }
        }

        private void dialNumber(String number) {
            Intent d = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number));
            d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(d); speak("Dialing"); }
            catch (Exception e) { speak("Cannot dial"); }
        }

        private void sendSms(String number, String message) {
            if (number == null) return;
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number));
            i.putExtra("sms_body", message != null ? message : "Hello from VoiceAgent");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); }
            catch (Exception e) { Log.e(TAG, "SMS fail", e); }
        }

        private boolean openAnyApp(String name) {
            if (name == null || name.isEmpty()) return false;
            String lower = name.toLowerCase().trim();
            addLog("Opening app: " + lower);

            // Direct known apps
            Intent known = resolveKnownApp(lower);
            if (known != null) {
                known.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(known); speak("Opening"); addLog("Opened via known intent"); return true; }
                catch (Exception e) { addLog("Known intent failed: " + e.getMessage()); }
            }

            // Find by package
            String pkg = resolveAppPackage(lower);
            if (pkg != null) {
                Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(launch); speak("Opening"); addLog("Opened package: " + pkg); return true; }
                    catch (Exception e) { addLog("Package launch failed: " + e.getMessage()); }
                }
            }

            // Search all installed apps
            Intent mainIntent = new Intent(Intent.ACTION_MAIN);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> apps = getPackageManager().queryIntentActivities(mainIntent, 0);
            addLog("Searching " + apps.size() + " installed apps");
            double bestScore = 0.18;
            String bestPkg = null;
            String bestLabel = null;
            for (ResolveInfo ri : apps) {
                String label = ri.loadLabel(getPackageManager()).toString().toLowerCase();
                double sim = SemanticEngine.charNGramSimilarity(lower, label);
                if (sim > bestScore) {
                    bestScore = sim;
                    bestPkg = ri.activityInfo.packageName;
                    bestLabel = label;
                }
            }
            if (bestPkg != null) {
                addLog("Best match: " + bestLabel + " (" + bestPkg + ") score=" + String.format("%.2f", bestScore));
                Intent launch = getPackageManager().getLaunchIntentForPackage(bestPkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(launch); speak("Done"); return true; }
                    catch (Exception e) { addLog("Launch failed: " + e.getMessage()); return false; }
                }
            }
            addLog("No app found for: " + lower);
            return false;
        }

        private Intent resolveKnownApp(String name) {
            if (name.contains("settings") || name.contains("seting") || name.contains("mipangilio"))
                return new Intent(Settings.ACTION_SETTINGS);
            if (name.contains("camera") || name.contains("kamera"))
                return new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            if (name.contains("dialer") || name.contains("phone") || name.contains("simu"))
                return new Intent(Intent.ACTION_DIAL, Uri.parse("tel:"));
            if (name.contains("calculator") || name.contains("kikokotoo") || name.contains("calc"))
                return new Intent("android.intent.action.CALCULATOR");
            if (name.contains("calendar") || name.contains("kalenda"))
                return new Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/"));
            if (name.contains("maps") || name.contains("ramani"))
                return new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="));
            if (name.contains("gallery") || name.contains("picha"))
                return new Intent(Intent.ACTION_VIEW).setType("image/*");
            return null;
        }

        private String resolveAppPackage(String name) {
            Map<String, String> map = new HashMap<>();
            map.put("settings", "com.android.settings");
            map.put("camera", "com.android.camera2");
            map.put("phone", "com.android.dialer");
            map.put("dialer", "com.android.dialer");
            map.put("chrome", "com.android.chrome");
            map.put("browser", "com.android.browser");
            map.put("calculator", "com.android.calculator2");
            map.put("calendar", "com.android.calendar");
            map.put("maps", "com.google.android.apps.maps");
            map.put("youtube", "com.google.android.youtube");
            map.put("whatsapp", "com.whatsapp");
            map.put("gallery", "com.android.gallery3d");
            map.put("twitter", "com.twitter.android");
            map.put("facebook", "com.facebook.katana");
            map.put("snapchat", "com.snapchat.android");
            map.put("instagram", "com.instagram.android");
            map.put("tiktok", "com.zhiliaoapp.musically");
            map.put("telegram", "org.telegram.messenger");
            map.put("gmail", "com.google.android.gm");
            map.put("email", "com.google.android.gm");
            map.put("play store", "com.android.vending");
            map.put("playstore", "com.android.vending");
            map.put("clock", "com.google.android.deskclock");
            map.put("alarm", "com.google.android.deskclock");
            map.put("contacts", "com.google.android.contacts");
            map.put("files", "com.android.documentsui");
            map.put("spotify", "com.spotify.music");
            map.put("music", "com.google.android.music");
            map.put("photos", "com.google.android.apps.photos");
            map.put("drive", "com.google.android.apps.docs");
            for (Map.Entry<String, String> e : map.entrySet()) {
                if (name.contains(e.getKey())) return e.getValue();
            }
            return null;
        }

        // ─── Contacts ───────────────────────────────────────

        private List<String> findContactNumber(String name) {
            List<String> numbers = new ArrayList<>();
            if (name == null || name.isEmpty()) return numbers;
            try {
                String[] projection = {ContactsContract.CommonDataKinds.Phone.NUMBER};
                String selection = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                        + " LIKE ?";
                String[] args = {"%" + name + "%"};
                Cursor cursor = contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        projection, selection, args, null);
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        numbers.add(cursor.getString(0));
                    }
                    cursor.close();
                }
            } catch (Exception e) { Log.e(TAG, "Contacts error", e); }
            return numbers;
        }

        private void listAllContacts() {
            try {
                String[] projection = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER};
                Cursor cursor = contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        projection, null, null,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC LIMIT 20");
                StringBuilder sb = new StringBuilder();
                if (cursor != null) {
                    int count = 0;
                    while (cursor.moveToNext() && count < 20) {
                        String name = cursor.getString(0);
                        String num = cursor.getString(1);
                        if (name != null) {
                            sb.append(name).append(", ");
                            count++;
                        }
                    }
                    cursor.close();
                    if (sb.length() > 2) {
                        sb.setLength(sb.length() - 2);
                        speak("Your contacts: " + sb.toString());
                    } else {
                        speak("No contacts found");
                    }
                } else { speak("Cannot read contacts"); }
            } catch (Exception e) { speak("Contacts error"); }
        }

        // ─── Notifications ──────────────────────────────────

        private void readNotifications() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    var active = notificationManager.getActiveNotifications();
                    if (active.length == 0) { speak("No notifications"); return; }
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.min(3, active.length); i++) {
                        String t = active[i].getNotification().extras
                                .getString(android.app.Notification.EXTRA_TITLE, "");
                        String txt = active[i].getNotification().extras
                                .getString(android.app.Notification.EXTRA_TEXT, "");
                        if (!t.isEmpty()) sb.append(t).append(". ");
                        if (!txt.isEmpty()) sb.append(txt).append(". ");
                    }
                    speak(sb.length() > 0 ? sb.toString() : "Notifications present");
                } catch (Exception e) { speak("Cannot read notifications"); }
            } else { speak("Not supported"); }
        }

        private void replyToLastNotification(String rawText) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    var active = notificationManager.getActiveNotifications();
                    if (active.length == 0) { speak("No notifications to reply to"); return; }
                    // Use the most recent notification that supports reply
                    for (int i = 0; i < active.length; i++) {
                        Notification notif = active[i].getNotification();
                        if (notif.actions != null) {
                            for (Notification.Action act : notif.actions) {
                                if (act.actionIntent != null && act.title != null
                                        && (act.title.toString().toLowerCase().contains("reply")
                                            || act.title.toString().toLowerCase().contains("jibu"))) {
                                    try { act.actionIntent.send(); speak("Replied"); return; }
                                    catch (Exception e) { /* fall through */ }
                                }
                            }
                        }
                    }
                    // Fallback: open the app that sent the last notification
                    if (active.length > 0) {
                        String pkg = active[0].getPackageName();
                        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                        if (launch != null) {
                            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(launch);
                            speak("Opening app");
                        } else { speak("Cannot reply"); }
                    }
                } catch (Exception e) { speak("Cannot reply"); }
            } else { speak("Not supported"); }
        }

        // ─── Screenshot ─────────────────────────────────────

        private void takeScreenshot() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    android.media.projection.MediaProjectionManager mpm =
                        (android.media.projection.MediaProjectionManager)
                            getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                    Intent i = mpm.createScreenCaptureIntent();
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    speak("Capturing");
                } catch (Exception e) { speak("Screenshot error"); }
            } else { speak("Not supported"); }
        }

        // ─── DND ────────────────────────────────────────────

        private void toggleDnd(boolean on) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) { speak("Not supported"); return; }
            int filter = on ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                            : NotificationManager.INTERRUPTION_FILTER_ALL;
            if (notificationManager.isNotificationPolicyAccessGranted()) {
                notificationManager.setInterruptionFilter(filter);
                speak(on ? "Quiet on" : "Quiet off");
            } else {
                Intent i = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                speak("Allow DND access");
            }
        }

        // ─── Helpers ────────────────────────────────────────

        private boolean checkCallPerm() {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                    == PackageManager.PERMISSION_GRANTED;
        }

        private String extractNumber(String text) {
            if (text == null) return null;
            var m = java.util.regex.Pattern.compile("(\\+?\\d[\\d\\s\\-\\(\\)]{4,14}\\d)").matcher(text);
            return m.find() ? m.group(1).replaceAll("[\\s\\-\\(\\)]", "") : null;
        }

        private String extractName(String text) {
            if (text == null) return null;
            String t = text.toLowerCase().trim();
            String[] triggers = {"call", "dial", "message", "text", "find", "contact",
                    "piga", "simu", "mpigie", "ujumbe", "tafuta", "anwani", "number for",
                    "phone number of", "send message to", "send to", "tell"};
            for (String trig : triggers) {
                int idx = t.indexOf(trig);
                if (idx >= 0) {
                    String after = t.substring(idx + trig.length()).trim();
                    if (!after.isEmpty()) {
                        // Remove trailing words that aren't names
                        String clean = after.replaceAll("\\b(please|now|quick|fast|urgent|sasa|tafadhali)\\b", "").trim();
                        if (!clean.isEmpty()) return clean;
                    }
                }
            }
            // If text contains "to" use what follows
            String[] parts = t.split("\\b(to|kwa|kwa namba)\\b");
            if (parts.length > 1) {
                String after = parts[parts.length - 1].trim();
                if (!after.isEmpty()) return after;
            }
            // If it's just 1-3 words, it might be a name
            String[] words = t.split("\\s+");
            if (words.length <= 3 && words.length >= 1) return t;
            return null;
        }

        private String extractAppName(String text) {
            if (text == null) return null;
            String t = text.toLowerCase().trim();
            String[] triggers = {"open", "launch", "start", "run", "go to",
                    "fungua", "zindua", "anzisha", "fungua"};
            for (String trig : triggers) {
                int idx = t.indexOf(trig);
                if (idx >= 0) {
                    String after = t.substring(idx + trig.length()).trim();
                    if (!after.isEmpty()) return after;
                }
            }
            return null;
        }

        // ─── Service Lifecycle ─────────────────────────────

        @Nullable
        @Override
        public IBinder onBind(Intent intent) { return null; }

        // ─── Cloud AI Speech-To-Text ──────────────────────

        private void loadApiKey() {
            try {
                apiKey = getSharedPreferences("voiceagent", MODE_PRIVATE).getString("api_key", "");
                if (!apiKey.isEmpty()) addLog("Cloud AI key loaded");
            } catch (Exception e) { addLog("Load key error: " + e.getMessage()); }
        }

        private void saveApiKey(String key) {
            apiKey = key.trim();
            try {
                getSharedPreferences("voiceagent", MODE_PRIVATE).edit().putString("api_key", apiKey).apply();
                addLog("AI API key saved. Restarting with cloud STT...");
                useCloudSTT = true;
                recognizerError = false;
                startCloudListening();
            } catch (Exception e) { addLog("Save key error: " + e.getMessage()); }
        }

        private void startCloudListening() {
            if (apiKey.isEmpty()) {
                addLog("Cannot start: no API key. Type: set api key YOUR_GOOGLE_API_KEY");
                return;
            }
            aiListening = true;
            aiThread = new Thread(this::audioLoop);
            aiThread.setDaemon(true);
            aiThread.start();
            addLog("Cloud AI listening started (Google Cloud STT)");
        }

        private void stopCloudListening() {
            aiListening = false;
            if (audioRecord != null) {
                try { audioRecord.stop(); } catch (Exception e) {}
                audioRecord.release();
                audioRecord = null;
            }
            aiThread = null;
        }

        private void audioLoop() {
            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            bufferSize = Math.max(bufferSize, SAMPLE_RATE * 4);

            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                addLog("AudioRecord failed. Check mic permission.");
                return;
            }

            audioRecord.startRecording();
            addLog("🎤 Microphone active (" + SAMPLE_RATE + "Hz)");

            byte[] chunk = new byte[SAMPLE_RATE / 4]; // 250ms
            ByteArrayOutputStream speechBuf = new ByteArrayOutputStream();
            boolean inSpeech = false;
            int silenceCount = 0;
            int maxSilence = 8; // ~2 sec silence = end of speech
            int maxBytes = SAMPLE_RATE * 15 * 2; // 15 sec max

            while (aiListening && !recognizerError) {
                int read = audioRecord.read(chunk, 0, chunk.length);
                if (read <= 0) continue;

                double energy = calculateRMS(chunk, read);

                if (energy > 0.018) { // Speech detected
                    speechBuf.write(chunk, 0, read);
                    if (!inSpeech) {
                        inSpeech = true;
                        addLog("Voice detected");
                    }
                    silenceCount = 0;
                    if (speechBuf.size() > maxBytes) {
                        processAndSend(speechBuf.toByteArray());
                        speechBuf.reset();
                        inSpeech = false;
                    }
                } else { // Silence
                    if (inSpeech) {
                        speechBuf.write(chunk, 0, read);
                        silenceCount++;
                        if (silenceCount >= maxSilence) {
                            addLog("End of speech (" + (speechBuf.size() / 32) + "ms)");
                            byte[] audioData = speechBuf.toByteArray();
                            speechBuf.reset();
                            inSpeech = false;
                            if (audioData.length > SAMPLE_RATE) {
                                processAndSend(audioData);
                            }
                        }
                    }
                }
            }

            stopCloudListening();
        }

        private double calculateRMS(byte[] audio, int len) {
            double sum = 0;
            int samples = len / 2;
            for (int i = 0; i < len - 1; i += 2) {
                short s = (short) ((audio[i + 1] << 8) | (audio[i] & 0xFF));
                sum += s * s;
            }
            return Math.sqrt(sum / samples) / 32768.0;
        }

        private void processAndSend(byte[] audioData) {
            addLog("Sending " + (audioData.length / 32) + "ms to Google Cloud STT...");
            new Thread(() -> {
                String text = googleCloudSTT(audioData);
                if (text != null && !text.isEmpty()) {
                    addLog("STT result: \"" + text + "\"");
                    String finalText = text.toLowerCase().trim();
                    handler.post(() -> processTranscript(finalText));
                } else {
                    addLog("STT: no speech recognized");
                }
            }).start();
        }

        private String googleCloudSTT(byte[] audioData) {
            try {
                String b64 = android.util.Base64.encodeToString(audioData, android.util.Base64.NO_WRAP);

                JSONObject config = new JSONObject();
                config.put("encoding", "LINEAR16");
                config.put("sampleRateHertz", SAMPLE_RATE);
                config.put("languageCode", "en-US");
                config.put("model", "latest_short");

                JSONObject audio = new JSONObject();
                audio.put("content", b64);

                JSONObject body = new JSONObject();
                body.put("config", config);
                body.put("audio", audio);

                URL url = new URL("https://speech.googleapis.com/v1/speech:recognize?key=" + apiKey);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes());
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    try (InputStream is = conn.getInputStream()) {
                        String resp = new java.util.Scanner(is).useDelimiter("\\A").next();
                        JSONObject json = new JSONObject(resp);
                        JSONArray results = json.optJSONArray("results");
                        if (results != null && results.length() > 0) {
                            JSONArray alts = results.getJSONObject(0).optJSONArray("alternatives");
                            if (alts != null && alts.length() > 0) {
                                return alts.getJSONObject(0).optString("transcript", null);
                            }
                        }
                    }
                } else {
                    try (InputStream es = conn.getErrorStream()) {
                        String err = es != null ? new java.util.Scanner(es).useDelimiter("\\A").next() : "HTTP " + code;
                        addLog("STT API error (" + code + "): " + err);
                    }
                }
            } catch (Exception e) {
                addLog("STT error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return null;
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (intent != null && intent.hasExtra("text_command")) {
                String cmd = intent.getStringExtra("text_command").trim();
                addLog("Text command: " + cmd);

                // Handle special commands
                if (cmd.toLowerCase().startsWith("set api key ")) {
                    String key = cmd.substring("set api key ".length()).trim();
                    if (!key.isEmpty()) {
                        saveApiKey(key);
                        speak("API key saved");
                    }
                    return START_STICKY;
                }
                if (cmd.toLowerCase().startsWith("api key ")) {
                    String key = cmd.substring("api key ".length()).trim();
                    if (!key.isEmpty()) {
                        saveApiKey(key);
                        speak("API key saved");
                    }
                    return START_STICKY;
                }
                if (cmd.toLowerCase().equals("stop listening") || cmd.toLowerCase().equals("stop ai")) {
                    stopCloudListening();
                    addLog("Cloud listening stopped");
                    return START_STICKY;
                }
                if (cmd.toLowerCase().equals("start ai") || cmd.toLowerCase().equals("start listening")) {
                    if (!apiKey.isEmpty()) {
                        startCloudListening();
                    } else {
                        addLog("Set API key first: set api key YOUR_KEY");
                    }
                    return START_STICKY;
                }

                // If awake and in command mode, process directly
                if (commandMode || wakeWordDetected) {
                    cancelWakeTimeout();
                    processTranscript(cmd);
                    handler.postDelayed(() -> endCommandMode(), 4000);
                } else {
                    // Treat as direct command (wake word not needed for typed commands)
                    wakeWordDetected = true;
                    commandMode = true;
                    processTranscript(cmd);
                    setWakeTimeout();
                }
            }
            return START_STICKY;
        }

        @Override
        public void onDestroy() {
            stopCloudListening();
            if (recognizer != null) { recognizer.destroy(); recognizer = null; }
            if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
            if (wakeLock != null && wakeLock.isHeld()) { wakeLock.release(); }
            handler.removeCallbacksAndMessages(null);
            Intent restart = new Intent(this, VoiceCommandService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { startForegroundService(restart); }
            else { startService(restart); }
            super.onDestroy();
        }
    }
}
