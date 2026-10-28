package pl.superdarco.paneltv;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Panel TV v1 - usluga w tle.
 * HTTP na porcie 7980 (tylko siec lokalna):
 *   POST /show   {"url": "...", "width": 820, "height": 480, "x": 48, "y": 48, "gravity": "top_right"}
 *   POST /close
 *   POST /token  {"token": "..."}   - klucz HA dla stron (window.PanelTV.token())
 *   GET  /state
 * Okienko to przezroczysty WebView nad kazda aplikacja. Gdy jest otwarte, przejmuje pilota
 * (strzalki / OK trafiaja do strony), WSTECZ zamyka. Strona moze zamknac sie sama: PanelTV.close().
 */
public class PanelService extends Service {
    static final int PORT = 7980;
    static final String WERSJA = "1.0";

    private WindowManager wm;
    private Handler ui;
    private PanelWeb web;
    private boolean widoczne = false;
    private String ostatniUrl = "";
    private SharedPreferences prefs;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        ui = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences("panel", MODE_PRIVATE);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("panel", "Panel TV", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "panel")
                .setContentTitle("Panel TV")
                .setContentText("Czeka na polecenia z Home Assistant")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();
        startForeground(1, n);
        new Thread(this::serwer, "panel-http").start();
    }

    @Override
    public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }

    // ---------------- HTTP ----------------
    private void serwer() {
        try (ServerSocket ss = new ServerSocket(PORT)) {
            while (true) {
                Socket s = ss.accept();
                new Thread(() -> obsluz(s)).start();
            }
        } catch (Exception e) {
            ui.postDelayed(() -> new Thread(this::serwer, "panel-http").start(), 3000);
        }
    }

    private void obsluz(Socket s) {
        try {
            s.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String linia = in.readLine();
            if (linia == null) { s.close(); return; }
            String[] cz = linia.split(" ");
            String metoda = cz[0], sciezka = cz.length > 1 ? cz[1] : "/";
            int dl = 0;
            String h;
            while ((h = in.readLine()) != null && !h.isEmpty()) {
                if (h.toLowerCase().startsWith("content-length:")) dl = Integer.parseInt(h.substring(15).trim());
            }
            char[] buf = new char[dl];
            int r = 0;
            while (r < dl) { int k = in.read(buf, r, dl - r); if (k < 0) break; r += k; }
            JSONObject body = dl > 0 ? new JSONObject(new String(buf, 0, r)) : new JSONObject();
            JSONObject odp = new JSONObject();
            odp.put("ok", true);
            if (sciezka.startsWith("/show") && metoda.equals("POST")) {
                final JSONObject b = body;
                ui.post(() -> pokaz(b));
            } else if (sciezka.startsWith("/close")) {
                ui.post(this::zamknij);
            } else if (sciezka.startsWith("/token") && metoda.equals("POST")) {
                prefs.edit().putString("token", body.optString("token", "")).apply();
            } else if (sciezka.startsWith("/state")) {
                odp.put("app", "PanelTV");
                odp.put("version", WERSJA);
                odp.put("visible", widoczne);
                odp.put("url", ostatniUrl);
                odp.put("token", !prefs.getString("token", "").isEmpty());
            } else {
                odp.put("ok", false);
                odp.put("error", "unknown path");
            }
            byte[] dane = odp.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + dane.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(dane);
            out.flush();
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    // ---------------- okienko ----------------
    @SuppressLint("SetJavaScriptEnabled")
    private void pokaz(JSONObject b) {
        String url = b.optString("url", "");
        if (url.isEmpty()) return;
        int szer = b.optInt("width", 820), wys = b.optInt("height", 480);
        int x = b.optInt("x", 48), y = b.optInt("y", 48);
        String grav = b.optString("gravity", "top_right");
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(szer, wys,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        switch (grav) {
            case "top_left": lp.gravity = Gravity.TOP | Gravity.START; break;
            case "bottom_right": lp.gravity = Gravity.BOTTOM | Gravity.END; break;
            case "bottom_left": lp.gravity = Gravity.BOTTOM | Gravity.START; break;
            case "center": lp.gravity = Gravity.CENTER; break;
            default: lp.gravity = Gravity.TOP | Gravity.END;
        }
        lp.x = x; lp.y = y;
        if (web == null) {
            web = new PanelWeb(this);
            web.setBackgroundColor(Color.TRANSPARENT);
            WebSettings ws = web.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setMediaPlaybackRequiresUserGesture(false);
            web.setWebViewClient(new WebViewClient());
            web.addJavascriptInterface(new Most(), "PanelTV");
            web.setFocusable(true);
            web.setFocusableInTouchMode(true);
        }
        if (widoczne) {
            wm.updateViewLayout(web, lp);
        } else {
            wm.addView(web, lp);
            widoczne = true;
        }
        if (!url.equals(ostatniUrl) || b.optBoolean("reload", false)) web.loadUrl(url);
        ostatniUrl = url;
        web.requestFocus();
    }

    private void zamknij() {
        if (web != null && widoczne) {
            try { wm.removeView(web); } catch (Exception ignored) { }
            widoczne = false;
            web.loadUrl("about:blank");
            ostatniUrl = "";
        }
    }

    /** Most dla strony: window.PanelTV.close(), window.PanelTV.token() */
    class Most {
        @JavascriptInterface public void close() { ui.post(PanelService.this::zamknij); }
        @JavascriptInterface public String token() { return prefs.getString("token", ""); }
        @JavascriptInterface public String version() { return WERSJA; }
    }

    /** WebView, w ktorym WSTECZ zamyka okienko, a reszta klawiszy idzie do strony. */
    class PanelWeb extends WebView {
        PanelWeb(Context c) { super(c); }
        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                if (e.getAction() == KeyEvent.ACTION_UP) zamknij();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
    }
}
