package com.drivingefficiency.app;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Two independent floating overlay elements, kept separate so they can be
 * shown at the same time without interfering with each other:
 *
 * 1. The text bubble (showMessage/clear) -- arrival instructions and the
 *    Smart Score badge, both centered near the top of the screen.
 * 2. The status dot (showStatusDot) -- a plain colored circle at the very
 *    top-center of the screen: solid green while monitoring is active,
 *    solid red while idle, always visible while the always-on
 *    TripForegroundService exists. Deliberately no text label and no
 *    animation -- color only, per explicit preference. Sized larger
 *    (32dp) than an earlier 16dp version to stay easily visible at a
 *    glance without a label or animation to help identify it.
 *
 * Requires the SYSTEM_ALERT_WINDOW permission -- see MainActivity's
 * "Enable Overlay Permission" button. If that permission was never
 * granted, this dot silently never appears at all -- see the
 * troubleshooting note in the README if it seems to be missing.
 */
public final class OverlayHelper {

    private static TextView overlayView;
    private static final long DEFAULT_AUTO_DISMISS_MS = 8000;
    private static final int DEFAULT_COLOR = Color.parseColor("#CC1565C0");

    private static View statusDotView;

    private OverlayHelper() {}

    public static boolean hasPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(context);
    }

    /** Arrival-style bubble: default color, auto-dismisses after 8s. */
    public static void showMessage(Context context, String message) {
        showMessage(context, message, DEFAULT_AUTO_DISMISS_MS, DEFAULT_COLOR);
    }

    /**
     * durationMs &lt;= 0 means "stay up until clear() is called" -- used for
     * the Smart Score badge, since offer decision windows vary in length.
     */
    public static void showMessage(Context context, String message, long durationMs, int backgroundColor) {
        showMessage(context, message, durationMs, backgroundColor, null);
    }

    /**
     * Overload with an optional tap action -- kept SEPARATE from the
     * 3-arg version above so every existing caller stays exactly as
     * non-touchable as before; only becomes touchable when a tap action
     * is actually provided. Used for tap-to-expand the Smart Score
     * badge's full 6-factor breakdown, without changing anything about
     * the other (non-expandable) uses of this same method.
     */
    public static void showMessage(Context context, String message, long durationMs, int backgroundColor,
                                    Runnable onTapAction) {
        if (!hasPermission(context) || message == null || message.isEmpty()) {
            return;
        }
        Context appContext = context.getApplicationContext();
        WindowManager windowManager =
                (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return;
        }

        removeExisting(windowManager);

        overlayView = new TextView(appContext);
        overlayView.setText(message);
        overlayView.setTextColor(Color.WHITE);
        overlayView.setBackgroundColor(backgroundColor);
        overlayView.setPadding(32, 24, 32, 24);
        overlayView.setTextSize(16f);
        if (onTapAction != null) {
            overlayView.setOnClickListener(v -> onTapAction.run());
        }

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                // Only non-touchable when there's nothing to tap --
                // matches every existing caller's behavior exactly when
                // onTapAction is null.
                onTapAction != null
                        ? WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 24;
        params.y = 450;

        windowManager.addView(overlayView, params);

        if (durationMs > 0) {
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> removeExisting(windowManager), durationMs);
        }
    }

    /** Explicitly dismiss (e.g. the offer screen went away). Safe to call anytime. */
    public static void clear(Context context) {
        Context appContext = context.getApplicationContext();
        WindowManager windowManager =
                (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager != null) {
            removeExisting(windowManager);
        }
    }

    private static View instructionOverlayView;

    /**
     * A fully SEPARATE, independent overlay from showMessage()/clear()
     * above -- deliberately does not share state with the Smart Score
     * badge or arrival-announcement overlay. Per explicit request: shown
     * while APPROACHING a stop with a pending delivery instruction, and
     * must NOT auto-clear for any reason (not a timer, not arrival, not
     * another overlay event elsewhere in the app) -- the only thing that
     * ever dismisses it is the user tapping it, since the delivery may
     * not actually be complete yet even after arriving. Touchable
     * (unlike every other overlay in this app, which are deliberately
     * non-interactive) specifically so a tap can dismiss it.
     */
    public static boolean showPersistentTappableMessage(Context context, String message, java.util.List<String> cannedReplies) {
        return showPersistentTappableMessage(context, message, cannedReplies, null);
    }

    /**
     * onAcknowledged: called exactly once, the moment the driver taps to
     * dismiss THIS message -- never called if it's cleared some other way
     * (a newer persistent message replacing it, or clearPersistentMessage()
     * called directly). Null is fine for callers that don't need to know.
     * Returns whether the overlay was actually shown (false if overlay
     * permission isn't granted) -- callers that pair this with
     * startAcknowledgeReminder() below must check this first, since
     * starting a repeat-until-acknowledged voice reminder with nothing
     * shown to actually tap would nag forever with no way to stop it.
     */
    public static boolean showPersistentTappableMessage(Context context, String message,
            java.util.List<String> cannedReplies, Runnable onAcknowledged) {
        if (!hasPermission(context) || message == null || message.isEmpty()) {
            return false;
        }
        Context appContext = context.getApplicationContext();
        WindowManager windowManager =
                (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return false;
        }

        clearPersistentMessage(context);

        android.widget.LinearLayout container = new android.widget.LinearLayout(appContext);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.parseColor("#E6C62828"));
        container.setPadding(32, 24, 32, 24);

        TextView messageView = new TextView(appContext);
        messageView.setText(message + (cannedReplies == null || cannedReplies.isEmpty()
                ? "\n\n(tap to dismiss)" : ""));
        messageView.setTextColor(Color.WHITE);
        messageView.setTextSize(16f);
        messageView.setOnClickListener(v -> {
            clearPersistentMessage(context);
            if (onAcknowledged != null) {
                onAcknowledged.run();
            }
        });
        container.addView(messageView);

        // Canned-reply buttons -- deliberately copy to clipboard, NOT an
        // automated SMS send or anything typed into another app directly.
        // A reply might need to go through real SMS, Dasher's own chat, or
        // somewhere else entirely -- clipboard-copy works regardless of
        // which channel it actually belongs in, and never automates input
        // into another app. Each copy is independent of dismissing the
        // whole overlay, so more than one reply can be tried if needed.
        if (cannedReplies != null && !cannedReplies.isEmpty()) {
            for (String reply : cannedReplies) {
                android.widget.Button replyButton = new android.widget.Button(appContext);
                replyButton.setText(reply);
                replyButton.setAllCaps(false);
                replyButton.setTextSize(13f);
                replyButton.setOnClickListener(v -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                            appContext.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Quick Reply", reply));
                        Toast.makeText(appContext, "Copied: \"" + reply + "\"", Toast.LENGTH_SHORT).show();
                    }
                });
                container.addView(replyButton);
            }
            TextView dismissHint = new TextView(appContext);
            dismissHint.setText("(tap message text to dismiss)");
            dismissHint.setTextColor(Color.parseColor("#CCFFFFFF"));
            dismissHint.setTextSize(11f);
            container.addView(dismissHint);
        }

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // Deliberately NOT FLAG_NOT_TOUCHABLE -- this is the one overlay
        // in the app that needs to actually receive a tap.
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.START;
        params.x = 24;
        params.y = 150;

        windowManager.addView(container, params);
        instructionOverlayView = container;
        // No auto-dismiss timer at all, unlike showMessage() above --
        // this stays up indefinitely until clearPersistentMessage() is
        // called, which only ever happens from the tap listener itself
        // (or a newer persistent message replacing this one).
        return true;
    }

    // Driver-requested (2026-09-03, docs/driver_backlog_2026_09_03/PRD.md
    // #4): "force me to acknowledge" -- a persistent overlay you could
    // simply not notice (phone mounted, eyes on the road) doesn't actually
    // force anything on its own. Pairs with showPersistentTappableMessage:
    // re-speaks the same text on an interval for as long as the overlay
    // this reminder is tied to is STILL showing, stopping itself the
    // moment it's been acknowledged (or replaced/cleared some other way) --
    // never speaks into a void with nothing left to tap.
    private static Handler reminderHandler;
    private static Runnable reminderRunnable;

    public static void startAcknowledgeReminder(String spokenText, long intervalMs) {
        if (reminderHandler == null) {
            reminderHandler = new Handler(Looper.getMainLooper());
        }
        if (reminderRunnable != null) {
            reminderHandler.removeCallbacks(reminderRunnable);
        }
        reminderRunnable = new Runnable() {
            @Override
            public void run() {
                if (instructionOverlayView == null) {
                    return; // already acknowledged (or cleared some other way) -- stop repeating
                }
                VoiceAnnouncer.speak(spokenText);
                reminderHandler.postDelayed(this, intervalMs);
            }
        };
        // First REPEAT fires after intervalMs, not immediately -- the
        // caller already spoke it once when first showing the overlay.
        reminderHandler.postDelayed(reminderRunnable, intervalMs);
    }

    public static void clearPersistentMessage(Context context) {
        Context appContext = context.getApplicationContext();
        WindowManager windowManager =
                (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager != null && instructionOverlayView != null) {
            try {
                windowManager.removeView(instructionOverlayView);
            } catch (IllegalArgumentException ignored) {
                // View was already removed.
            }
            instructionOverlayView = null;
        }
        // Stops any in-progress acknowledge-reminder too -- instructionOverlayView
        // being null is exactly what startAcknowledgeReminder's own runnable
        // checks to know it's done, but cancelling the pending callback here
        // as well avoids one harmless-but-pointless extra tick before it
        // notices on its own.
        if (reminderHandler != null && reminderRunnable != null) {
            reminderHandler.removeCallbacks(reminderRunnable);
        }
    }

    private static void removeExisting(WindowManager windowManager) {
        if (overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (IllegalArgumentException ignored) {
                // View was already removed (e.g. Activity/Service torn down).
            }
            overlayView = null;
        }
    }

    /**
     * Three states that each mean something actionable -- deliberately
     * NOT a 4th "solid red = idle" state, since that communicated nothing
     * you'd need to act on. Idle with Dasher closed now shows no dot at
     * all (see clearStatusDot) -- "nothing shown" becomes its own
     * meaningful signal ("nothing to see here") instead of a state that
     * exists just to confirm the dot is alive.
     */
    public enum DotState {
        /** Not monitoring, but Dasher IS in the foreground right now -- a real warning: you're dashing without tracking. */
        RED_FLASHING,
        /** Monitoring active, but accessibility access has been revoked -- offer detection and Accept/Decline tracking are silently not working. Distinct from RED_FLASHING (a different problem), so it needs its own color, not a shared one. */
        BLUE_FLASHING,
        /** Monitoring active, but in GENERAL mode (not currently on an active delivery). */
        YELLOW,
        /** Monitoring active AND in DASHER mode -- the "everything working as intended" state. */
        GREEN,
        /** DASHER mode, near an unmatched stop but not yet arrived, moving at walking pace -- e.g. parked, walking up to the door. */
        WALKING
    }

    private static ObjectAnimator statusDotAnimator;

    /**
     * Plain colored circle, top-center of the screen. No text -- explicitly
     * requested plain color only. Only shown for the states that mean
     * something (see DotState) -- idle with Dasher closed shows nothing at
     * all instead of an unremarkable solid color (call clearStatusDot for
     * that case). RED_FLASHING is the one state that uses animation --
     * specifically because it's a real warning worth the extra visual
     * attention, unlike the others which stay solid per the original
     * no-animation preference.
     */
    private static final String DOT_POSITION_PREFS = "status_dot_position_prefs";

    public static void showStatusDot(Context context, DotState state) {
        if (!hasPermission(context)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        WindowManager windowManager =
                (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return;
        }

        removeStatusDot(windowManager);

        View dot = new View(appContext);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        int color;
        switch (state) {
            case GREEN:
                color = Color.parseColor("#E62E7D32");
                break;
            case YELLOW:
                color = Color.parseColor("#E6F9A825");
                break;
            case WALKING:
                color = Color.parseColor("#E68E24AA");
                break;
            case BLUE_FLASHING:
                color = Color.parseColor("#E61565C0");
                break;
            default: // RED_FLASHING
                color = Color.parseColor("#E6C62828");
                break;
        }
        shape.setColor(color);
        dot.setBackground(shape);

        int sizePx = (int) (32 * appContext.getResources().getDisplayMetrics().density);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // Draggable -- per explicit request, after repositioning this
        // twice based on guessing what might avoid colliding with
        // Dasher's own UI. Deliberately NOT FLAG_NOT_TOUCHABLE (unlike
        // every other overlay in this app except the ones meant to be
        // tapped) -- this one needs to receive touch events to be
        // dragged, though it still does nothing on a simple tap, only a
        // drag.
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                sizePx, sizePx, overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        // Absolute TOP|START coordinates for BOTH the default position
        // and any saved/dragged position -- simpler drag math than
        // gravity-relative positioning (CENTER_VERTICAL, etc.), since x/y
        // then directly correspond to real screen pixels from the
        // top-left corner.
        params.gravity = Gravity.TOP | Gravity.START;
        android.content.SharedPreferences positionPrefs =
                appContext.getSharedPreferences(DOT_POSITION_PREFS, Context.MODE_PRIVATE);
        int screenHeight = appContext.getResources().getDisplayMetrics().heightPixels;
        params.x = positionPrefs.getInt("dot_x", 24);
        params.y = positionPrefs.getInt("dot_y", screenHeight / 2); // default: left-middle, matching the previous position

        dot.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean didDrag = false;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        didDrag = false;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        didDrag = true;
                        windowManager.updateViewLayout(dot, params);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        if (didDrag) {
                            positionPrefs.edit().putInt("dot_x", params.x).putInt("dot_y", params.y).apply();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        windowManager.addView(dot, params);
        statusDotView = dot;

        if (state == DotState.RED_FLASHING || state == DotState.BLUE_FLASHING) {
            statusDotAnimator = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.3f);
            statusDotAnimator.setDuration(500);
            statusDotAnimator.setRepeatMode(ValueAnimator.REVERSE);
            statusDotAnimator.setRepeatCount(ValueAnimator.INFINITE);
            statusDotAnimator.start();
        }
        // All other states: solid, no animation, stays up indefinitely
        // (no auto-dismiss) until showStatusDot or clearStatusDot is
        // called again.
    }

    /** Fully removes the status dot (e.g. the service itself was torn down). */
    public static void clearStatusDot(Context context) {
        Context appContext = context.getApplicationContext();
        WindowManager windowManager =
                (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager != null) {
            removeStatusDot(windowManager);
        }
    }

    private static void removeStatusDot(WindowManager windowManager) {
        if (statusDotAnimator != null) {
            statusDotAnimator.cancel();
            statusDotAnimator = null;
        }
        if (statusDotView != null) {
            try {
                windowManager.removeView(statusDotView);
            } catch (IllegalArgumentException ignored) {
                // View was already removed.
            }
            statusDotView = null;
        }
    }

    private static View navigationIconView;

    /**
     * A small, TAPPABLE icon (unlike the status dot/message bubble, which
     * are deliberately non-touchable) -- appears while approaching an
     * unmatched delivery stop (see TripManager.APPROACHING_RADIUS_METERS),
     * tapping it runs onTapAction (opening RoadWarrior/maps with that
     * stop's address). Positioned top-right so it doesn't overlap the
     * status dot (top-center) or the message bubble.
     */
    public static void showNavigationIcon(Context context, Runnable onTapAction) {
        if (!hasPermission(context)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        WindowManager windowManager =
                (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return;
        }

        removeNavigationIcon(windowManager);

        TextView icon = new TextView(appContext);
        icon.setText("\uD83D\uDCCD"); // pin emoji -- simple, no extra drawable needed
        icon.setTextSize(20f);
        icon.setGravity(Gravity.CENTER);

        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(Color.parseColor("#E61565C0")); // brand blue
        icon.setBackground(shape);
        icon.setOnClickListener(v -> onTapAction.run());

        int sizePx = (int) (48 * appContext.getResources().getDisplayMetrics().density);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // Deliberately NOT FLAG_NOT_TOUCHABLE -- this one needs to
        // actually receive the tap, unlike the status dot.
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                sizePx, sizePx, overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 24;
        params.y = 60;

        windowManager.addView(icon, params);
        navigationIconView = icon;
    }

    /** Removes the navigation icon -- called once the stop is matched/arrived, or no longer active. */
    public static void clearNavigationIcon(Context context) {
        Context appContext = context.getApplicationContext();
        WindowManager windowManager =
                (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager != null) {
            removeNavigationIcon(windowManager);
        }
    }

    private static View returnToSweetSpotIconView;

    /**
     * A dedicated overlay, deliberately separate from showNavigationIcon
     * above (not sharing its static view field) to avoid any conflict
     * between the two -- shown once ALL deliveries in a trip are
     * complete, if you're far enough from your real, learned pickup
     * hotspot to plausibly be in unfamiliar territory. Tap navigates
     * straight there. Stays up until tapped -- doesn't auto-clear, since
     * there's no natural "no longer relevant" moment for this the way
     * there is for the RoadWarrior icon (matched/arrived).
     */
    public static void showReturnToSweetSpotIcon(Context context, Runnable onTapAction) {
        if (!hasPermission(context)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        WindowManager windowManager =
                (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return;
        }
        clearReturnToSweetSpotIcon(context);

        TextView icon = new TextView(appContext);
        icon.setText("\uD83C\uDFE0"); // house emoji -- visually distinct from the pin used for delivery stops
        icon.setTextSize(20f);
        icon.setGravity(Gravity.CENTER);

        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(Color.parseColor("#E62E7D32")); // distinct green, not the RoadWarrior blue
        icon.setBackground(shape);
        icon.setOnClickListener(v -> onTapAction.run());

        int sizePx = (int) (48 * appContext.getResources().getDisplayMetrics().density);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                sizePx, sizePx, overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.x = 24;
        params.y = 220;

        windowManager.addView(icon, params);
        returnToSweetSpotIconView = icon;
    }

    public static void clearReturnToSweetSpotIcon(Context context) {
        Context appContext = context.getApplicationContext();
        WindowManager windowManager =
                (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager != null && returnToSweetSpotIconView != null) {
            try {
                windowManager.removeView(returnToSweetSpotIconView);
            } catch (IllegalArgumentException ignored) {
                // View was already removed.
            }
            returnToSweetSpotIconView = null;
        }
    }

    private static void removeNavigationIcon(WindowManager windowManager) {
        if (navigationIconView != null) {
            try {
                windowManager.removeView(navigationIconView);
            } catch (IllegalArgumentException ignored) {
                // View was already removed.
            }
            navigationIconView = null;
        }
    }
}
