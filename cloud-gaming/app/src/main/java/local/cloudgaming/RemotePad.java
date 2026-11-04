package local.cloudgaming;

import android.view.KeyEvent;
import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Collections;

/**
 * The TV remote as an Xbox controller for Xbox's web player and its menus: a virtual standard gamepad, added to
 * the page's Gamepad API, whose buttons follow the remote. Real controllers take over whenever one is connected.
 */
final class RemotePad {

    // Standard gamepad button numbers.
    static final int A = 0, B = 1, LB = 4, RB = 5, VIEW = 8, MENU = 9, UP = 12, DOWN = 13, LEFT = 14, RIGHT = 15;

    private static final String SCRIPT = "(function(){if(window.__otvPad)return;"
            + "var pad={id:'OpenTV Remote (Xbox Wireless Controller)',index:0,connected:true,mapping:'standard',"
            + "timestamp:performance.now(),axes:[0,0,0,0],vibrationActuator:null,hapticActuators:[],buttons:[]};"
            + "for(var i=0;i<17;i++)pad.buttons.push({pressed:false,touched:false,value:0});"
            + "window.__otvPad=pad;window.__otvPadOn=false;"
            + "var real=navigator.getGamepads?navigator.getGamepads.bind(navigator):function(){return[]};"
            + "navigator.getGamepads=function(){var r=Array.prototype.slice.call(real()||[]);"
            + "for(var i=0;i<r.length;i++)if(r[i]&&r[i].connected)return r;"
            + "return window.__otvPadOn?[pad,null,null,null]:[null,null,null,null];};"
            + "window.__otvSet=function(b,down){var x=pad.buttons[b];x.pressed=down;x.touched=down;x.value=down?1:0;"
            + "pad.timestamp=performance.now();if(!window.__otvPadOn){window.__otvPadOn=true;"
            + "var e=new Event('gamepadconnected');e.gamepad=pad;window.dispatchEvent(e);}};"
            + "})();";

    private RemotePad() {
    }

    /** Adds the virtual controller to every Xbox page before the page's own scripts run (where supported). */
    static void install(WebView web) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, SCRIPT, Collections.singleton("https://www.xbox.com"));
            installed = true;
        }
    }

    private static boolean installed;

    /** Older WebViews: add it as the page starts loading instead. */
    static void injectIfNeeded(WebView web) {
        if (!installed) web.evaluateJavascript(SCRIPT, null);
    }

    static int buttonFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return A;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MENU: return MENU;
            case KeyEvent.KEYCODE_MEDIA_REWIND: return LB;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return RB;
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_GUIDE: return VIEW;
            default: return -1;
        }
    }

    private static final long[] downAt = new long[17];
    /** Xbox reads the controller once a frame; a press must last a few frames to be seen, however quick the tap. */
    private static final long MIN_PRESS_MS = 90;

    static void set(WebView web, int button, boolean down) {
        long now = android.os.SystemClock.uptimeMillis();
        if (down) {
            downAt[button] = now;
            send(web, button, true);
        } else {
            long wait = MIN_PRESS_MS - (now - downAt[button]);
            if (wait > 0) web.postDelayed(() -> send(web, button, false), wait);
            else send(web, button, false);
        }
    }

    private static void send(WebView web, int button, boolean down) {
        web.evaluateJavascript("window.__otvSet&&window.__otvSet(" + button + "," + down + ")", null);
    }

    /** A quick press and release (long enough for a 60 Hz game to see it). */
    static void tap(WebView web, int button) {
        set(web, button, true);
        set(web, button, false);
    }
}
