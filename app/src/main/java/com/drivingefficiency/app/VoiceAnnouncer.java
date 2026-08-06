package com.drivingefficiency.app;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/**
 * Thin singleton wrapper around Android's TextToSpeech so customer
 * instructions can be read aloud hands-free ("Zero Interaction While
 * Driving" -- report section 4: Message Intelligence).
 */
public final class VoiceAnnouncer {

    private static TextToSpeech tts;
    private static volatile boolean ready = false;
    private static Context appContext;
    // Logged once, not on every dropped speak() call -- if TTS genuinely
    // never initializes, every single announcement in the whole app
    // would otherwise spam an identical log line repeatedly.
    private static volatile boolean loggedNotReadyWarning = false;

    private VoiceAnnouncer() {}

    public static synchronized void init(Context context) {
        if (tts != null) {
            return;
        }
        appContext = context.getApplicationContext();
        tts = new TextToSpeech(appContext, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.getDefault());
                ready = true;
            } else {
                // Previously a completely silent failure -- if TTS never
                // initializes (missing/disabled TTS engine, genuinely
                // happens on some devices), nothing anywhere indicated
                // why every spoken announcement in the app was silently
                // doing nothing.
                logInitFailure(status);
            }
        });
    }

    private static void logInitFailure(int status) {
        String message = "TextToSpeech failed to initialize (status=" + status
                + ") -- every spoken announcement in the app will silently do nothing "
                + "until this is resolved (check that a TTS engine is installed and enabled).";
        try {
            com.chaquo.python.PyObject engine = PythonBridge.getEngine(appContext);
            engine.callAttr("log_diagnostic", "TTS", message);
        } catch (RuntimeException e) { // covers PyException too
            FallbackLogger.log(appContext, "TTS", message + " (engine unavailable: "
                    + android.util.Log.getStackTraceString(e) + ")");
        }
    }

    public static void speak(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (tts == null || !ready) {
            if (!loggedNotReadyWarning && appContext != null) {
                loggedNotReadyWarning = true;
                String message = "speak() called but TTS is not ready -- this announcement "
                        + "(and likely all future ones) will be silently dropped.";
                try {
                    com.chaquo.python.PyObject engine = PythonBridge.getEngine(appContext);
                    engine.callAttr("log_diagnostic", "TTS", message);
                } catch (RuntimeException e) { // covers PyException too
                    FallbackLogger.log(appContext, "TTS", message);
                }
            }
            return;
        }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "dasher_monitor_utterance");
    }

    /**
     * MessageIntelligence.extract_instruction() in drive_monitor.py returns
     * category-tagged strings like "delivery_note: Please leave it at the
     * back door" for storage/summary purposes. This strips that tag so TTS
     * speaks naturally ("Please leave it at the back door") instead of
     * reading the internal category name aloud.
     */
    public static String stripCategoryPrefix(String instruction) {
        if (instruction == null) {
            return null;
        }
        int idx = instruction.indexOf(": ");
        if (idx == -1) {
            return instruction;
        }
        String prefix = instruction.substring(0, idx);
        if (prefix.equals("delivery_note") || prefix.equals("address_correction")
                || prefix.equals("eta_adjustment")) {
            return instruction.substring(idx + 2);
        }
        return instruction;
    }

    /**
     * Same category tags, but formatted for the post-trip summary dialog
     * (report/reading context, not speech) -- e.g. "Delivery note: ..."
     * instead of the raw "delivery_note: ...".
     */
    public static String friendlyCategoryLabel(String instruction) {
        if (instruction == null) {
            return null;
        }
        if (instruction.startsWith("delivery_note: ")) {
            return "Delivery note: " + instruction.substring("delivery_note: ".length());
        }
        if (instruction.startsWith("address_correction: ")) {
            return "Address correction: " + instruction.substring("address_correction: ".length());
        }
        if (instruction.startsWith("eta_adjustment: ")) {
            return "ETA update: " + instruction.substring("eta_adjustment: ".length());
        }
        return instruction;
    }
}
