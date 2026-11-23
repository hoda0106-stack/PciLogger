package com.pcilogger;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * مراقب الأبراج لراوتر TP-Link Archer MR505.
 * يفتح صفحة الراوتر داخل التطبيق، وبعد أن يسجّل المستخدم الدخول بنفسه
 * يقرأ معلومات الخلية (PCI, EARFCN, Band, eNB, RSRP, RSRQ, SNR, CQI) كل بضع ثوانٍ
 * من نفس الواجهة الداخلية التي يستخدمها الراوتر (block 151 = LTE_SERVING_CELL_INFO).
 * التطبيق للقراءة فقط: لا يغيّر أي إعداد في الراوتر.
 */
public class MainActivity extends Activity {

    private static final String ROUTER_URL = "https://192.168.1.1/";
    private static final String CSV_FILE = "pci_log.csv";
    private static final String CSV_HEADER =
            "time,cell,band,pci,earfcn,cell_id,enb,tac,rsrp_dbm,rsrq_db,snr_db,cqi,ri,dl_bw_mhz\n";
    private static final int REQ_EXPORT = 7;
    private static final int[] INTERVALS = {3, 5, 10, 30};

    private WebView web;
    private TextView statusView, currentView, statsView, countView;
    private Button startBtn, intervalBtn, routerBtn;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private int intervalIdx = 1;
    private int samples = 0;
    private final Map<String, Stat> stats = new LinkedHashMap<>();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    /** إحصائيات لكل برج (PCI + EARFCN). */
    private static class Stat {
        String band, pci, earfcn, enb;
        int n;
        double rsrp, rsrq, snr, cqi;
        double bestSnr = -999;
    }

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if (!running) return;
            poll();
            handler.postDelayed(this, INTERVALS[intervalIdx] * 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        setupWebView();
        loadSavedStats();
        web.loadUrl(ROUTER_URL);
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(Color.parseColor("#F4F6F8"));
        int pad = dp(10);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("مراقب الأبراج — Archer MR505");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#1B3A57"));
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("سجّل الدخول إلى الراوتر في الصفحة بالأسفل، ثم اضغط «ابدأ».");
        statusView.setTextColor(Color.parseColor("#555555"));
        statusView.setPadding(0, dp(4), 0, dp(6));
        root.addView(statusView);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        startBtn = button("ابدأ", v -> toggleRunning());
        intervalBtn = button("كل " + INTERVALS[intervalIdx] + " ث", v -> cycleInterval());
        routerBtn = button("إخفاء الراوتر", v -> toggleRouter());
        row1.addView(startBtn, weight());
        row1.addView(intervalBtn, weight());
        row1.addView(routerBtn, weight());
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(button("تصدير CSV", v -> exportCsv()), weight());
        row2.addView(button("مسح السجل", v -> clearLog()), weight());
        root.addView(row2);

        ScrollView scroll = new ScrollView(this);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);

        currentView = card("—");
        countView = new TextView(this);
        countView.setTextColor(Color.parseColor("#777777"));
        statsView = card("لا توجد بيانات بعد.");

        info.addView(header("البرج الحالي"));
        info.addView(currentView);
        info.addView(header("ترتيب الأبراج (الأفضل أولاً حسب SNR)"));
        info.addView(countView);
        info.addView(statsView);
        scroll.addView(info);

        web = new WebView(this);

        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.2f));
        setContentView(root);
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private TextView header(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(Color.parseColor("#1B3A57"));
        v.setPadding(0, dp(10), 0, dp(4));
        return v;
    }

    private TextView card(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(14);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextDirection(View.TEXT_DIRECTION_LTR);
        v.setGravity(Gravity.START);
        v.setBackgroundColor(Color.WHITE);
        v.setTextColor(Color.parseColor("#222222"));
        int p = dp(10);
        v.setPadding(p, p, p, p);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toggleRunning() {
        running = !running;
        startBtn.setText(running ? "إيقاف" : "ابدأ");
        handler.removeCallbacks(poller);
        if (running) handler.post(poller);
        else setStatus("متوقف.");
    }

    private void cycleInterval() {
        intervalIdx = (intervalIdx + 1) % INTERVALS.length;
        intervalBtn.setText("كل " + INTERVALS[intervalIdx] + " ث");
    }

    private void toggleRouter() {
        boolean show = web.getVisibility() != View.VISIBLE;
        web.setVisibility(show ? View.VISIBLE : View.GONE);
        routerBtn.setText(show ? "إخفاء الراوتر" : "إظهار الراوتر");
    }

    private void setStatus(String s) {
        statusView.setText(s);
    }

    // ---------------------------------------------------------- WebView

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        web.addJavascriptInterface(new Bridge(), "PCI");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler h, SslError error) {
                // الراوتر يستخدم شهادة ذاتية التوقيع. نقبلها فقط لعنوان الراوتر.
                String url = error.getUrl();
                Uri u = url == null ? null : Uri.parse(url);
                String host = u == null ? "" : String.valueOf(u.getHost());
                if (host.equals("192.168.1.1") || host.equals("tplinkmodem.net")) h.proceed();
                else h.cancel();
            }
        });
    }

    private static final String POLL_JS =
            "(function(){try{" +
            "var a=window.$&&$.su&&$.su.serviceManager&&$.su.serviceManager.get('ajax');" +
            "var p=a&&a.proxyMap&&a.proxyMap.getLteStatusProxy;" +
            "if(!p){PCI.onError('nologin');return;}" +
            "p.read({blocks:[151]," +
            "success:function(d){PCI.onData(JSON.stringify(d));}," +
            "fail:function(){PCI.onError('fail');}," +
            "error:function(){PCI.onError('error');}});" +
            "}catch(e){PCI.onError('nologin');}})();";

    private void poll() {
        web.evaluateJavascript(POLL_JS, null);
    }

    private class Bridge {
        @JavascriptInterface
        public void onData(String json) {
            handler.post(() -> handleData(json));
        }

        @JavascriptInterface
        public void onError(String code) {
            handler.post(() -> {
                if ("nologin".equals(code))
                    setStatus("لم يتم تسجيل الدخول بعد — سجّل الدخول في صفحة الراوتر بالأسفل.");
                else
                    setStatus("تعذّرت القراءة من الراوتر (" + code + ") — سأحاول مرة أخرى.");
            });
        }
    }

    // ---------------------------------------------------------- Data

    private static double num(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return Double.NaN; }
    }

    private void handleData(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject block = root.has("servingCellList") ? root : root.optJSONObject("151");
            if (block == null) { setStatus("ردّ غير متوقّع من الراوتر."); return; }
            JSONArray list = block.optJSONArray("servingCellList");
            if (list == null) { setStatus("لا توجد بيانات خلايا."); return; }

            String now = timeFmt.format(System.currentTimeMillis());
            StringBuilder cur = new StringBuilder();
            StringBuilder csv = new StringBuilder();
            int cellNo = 0;

            for (int i = 0; i < list.length(); i++) {
                JSONObject c = list.getJSONObject(i);
                String pci = c.optString("PCI", "").trim();
                double rsrp = num(c.optString("RSRP", ""));
                // نتجاهل خلايا 3G القديمة والخلايا بدون PCI أو بدون قياس
                if (pci.isEmpty() || Double.isNaN(rsrp) || rsrp == 0) continue;
                cellNo++;

                String band = "B" + c.optString("band", "?");
                String earfcn = c.optString("ARFCN", "");
                String cid = c.optString("cid", "");
                double cidNum = num(cid);
                String enb = Double.isNaN(cidNum) ? "" : String.valueOf((long) cidNum / 256);
                double rsrq = num(c.optString("RSRQ", ""));
                double snr = num(c.optString("SNR", "")) / 10.0; // الراوتر يرسل SNR × 10
                double cqi = num(c.optString("CQI", ""));
                String ri = c.optString("RI", "");
                double bw = num(c.optString("downBandWidth", "")) / 1000.0;
                String tac = c.optString("TAC", "");

                String role = cellNo == 1 ? "الرئيسية (PCC)" : "مجمّعة (SCC)";
                cur.append(String.format(Locale.US,
                        "%s  %s\nPCI %s   EARFCN %s   eNB %s\nRSRP %.0f dBm   RSRQ %.0f dB\nSNR %.1f dB   CQI %.0f   %s MHz\n\n",
                        role, band, pci, earfcn, enb, rsrp, rsrq, snr, cqi,
                        Double.isNaN(bw) ? "?" : String.format(Locale.US, "%.0f", bw)));

                csv.append(now).append(',').append(cellNo).append(',').append(band).append(',')
                        .append(pci).append(',').append(earfcn).append(',').append(cid).append(',')
                        .append(enb).append(',').append(tac).append(',')
                        .append(fmt(rsrp)).append(',').append(fmt(rsrq)).append(',')
                        .append(fmt(snr)).append(',').append(fmt(cqi)).append(',')
                        .append(ri).append(',').append(fmt(bw)).append('\n');

                addStat(band, pci, earfcn, enb, rsrp, rsrq, snr, cqi);
            }

            if (cellNo == 0) {
                setStatus("متصل لكن لا توجد خلية 4G الآن (ربما على 3G).");
                currentView.setText("—");
                return;
            }
            samples++;
            appendCsv(csv.toString());
            currentView.setText(cur.toString().trim());
            renderStats();
            setStatus("آخر قراءة: " + now.substring(11) + "  •  " + (running ? "يعمل" : "متوقف"));
        } catch (Exception e) {
            setStatus("خطأ في قراءة البيانات: " + e.getMessage());
        }
    }

    private static String fmt(double d) {
        return Double.isNaN(d) ? "" : String.format(Locale.US, "%.1f", d);
    }

    private void addStat(String band, String pci, String earfcn, String enb,
                         double rsrp, double rsrq, double snr, double cqi) {
        String key = pci + "|" + earfcn;
        Stat s = stats.get(key);
        if (s == null) {
            s = new Stat();
            s.band = band; s.pci = pci; s.earfcn = earfcn; s.enb = enb;
            stats.put(key, s);
        }
        s.n++;
        s.rsrp += rsrp;
        s.rsrq += Double.isNaN(rsrq) ? 0 : rsrq;
        s.snr += Double.isNaN(snr) ? 0 : snr;
        s.cqi += Double.isNaN(cqi) ? 0 : cqi;
        if (!Double.isNaN(snr) && snr > s.bestSnr) s.bestSnr = snr;
    }

    private void renderStats() {
        List<Stat> list = new ArrayList<>(stats.values());
        Collections.sort(list, (a, b) -> Double.compare(b.snr / b.n, a.snr / a.n));
        StringBuilder sb = new StringBuilder();
        int rank = 0;
        for (Stat s : list) {
            rank++;
            sb.append(String.format(Locale.US,
                    "%d) %s  PCI %s  EARFCN %s  eNB %s\n   متوسط: SNR %.1f  RSRP %.0f  RSRQ %.0f  CQI %.1f\n   أفضل SNR %.1f  •  %d قراءة\n\n",
                    rank, s.band, s.pci, s.earfcn, s.enb,
                    s.snr / s.n, s.rsrp / s.n, s.rsrq / s.n, s.cqi / s.n, s.bestSnr, s.n));
        }
        statsView.setText(sb.length() == 0 ? "لا توجد بيانات بعد." : sb.toString().trim());
        countView.setText("عدد القراءات: " + samples + "  •  عدد الأبراج: " + stats.size());
    }

    // ---------------------------------------------------------- CSV

    private File csvFile() {
        return new File(getFilesDir(), CSV_FILE);
    }

    private void appendCsv(String lines) {
        try {
            File f = csvFile();
            boolean isNew = !f.exists();
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                if (isNew) out.write(CSV_HEADER.getBytes(StandardCharsets.UTF_8));
                out.write(lines.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    /** إعادة بناء الإحصائيات من السجل المحفوظ عند فتح التطبيق. */
    private void loadSavedStats() {
        File f = csvFile();
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int read = 0;
            while (read < data.length) {
                int r = in.read(data, read, data.length - read);
                if (r < 0) break;
                read += r;
            }
            String[] lines = new String(data, 0, read, StandardCharsets.UTF_8).split("\n");
            String lastTime = null;
            for (int i = 1; i < lines.length; i++) {
                String[] p = lines[i].split(",", -1);
                if (p.length < 12) continue;
                if (!p[0].equals(lastTime)) { samples++; lastTime = p[0]; }
                addStat(p[2], p[3], p[4], p[6], num(p[8]), num(p[9]), num(p[10]), num(p[11]));
            }
            renderStats();
        } catch (Exception ignored) { }
    }

    private void exportCsv() {
        if (!csvFile().exists()) {
            Toast.makeText(this, "لا يوجد سجل بعد", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "pci_log_" +
                new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(System.currentTimeMillis()) + ".csv");
        startActivityForResult(i, REQ_EXPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try (OutputStream out = getContentResolver().openOutputStream(data.getData());
             FileInputStream in = new FileInputStream(csvFile())) {
            if (out == null) throw new Exception("no stream");
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            Toast.makeText(this, "تم حفظ الملف", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "فشل الحفظ: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void clearLog() {
        //noinspection ResultOfMethodCallIgnored
        csvFile().delete();
        stats.clear();
        samples = 0;
        currentView.setText("—");
        renderStats();
        Toast.makeText(this, "تم مسح السجل", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (web.getVisibility() == View.VISIBLE && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        running = false;
        handler.removeCallbacks(poller);
        web.destroy();
        super.onDestroy();
    }
}
