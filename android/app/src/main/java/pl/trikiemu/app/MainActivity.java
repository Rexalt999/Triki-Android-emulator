package pl.trikiemu.app;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * TrikiEmu na Androida: telefon udaje kapsel Zabka Triki (BLE peripheral). Zappke uruchom na
 * DRUGIM telefonie. Zrodlo ruchu: czujniki telefonu, spoczynek, sterowanie reczne, wzorce, plik CSV.
 */
public class MainActivity extends Activity implements TrikiPeripheral.Listener {

    private static final int REQ_PERMS = 1;
    private static final int REQ_ENABLE_BT = 2;
    private static final int REQ_CSV = 3;

    private static final int SRC_PHONE = 0, SRC_REST = 1, SRC_MANUAL = 2, SRC_SPIN = 3, SRC_TILT = 4, SRC_CSV = 5;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final ArrayDeque<String> logLines = new ArrayDeque<>();

    private TrikiPeripheral engine;
    private PhoneMotion phone;
    private final RestMotion rest = new RestMotion();
    private final ManualMotion manual = new ManualMotion();
    private final PatternMotion spin = new PatternMotion(PatternMotion.SPIN);
    private final PatternMotion tilt = new PatternMotion(PatternMotion.TILT);
    private volatile CsvMotion csv;
    private int sourceIdx = SRC_PHONE;
    private boolean resumed = false;

    private TextView statusView, logView, csvInfo, stepLabel, flapLabel, throwLabel;
    private EditText nameEdit;
    private Button bleBtn;
    private CheckBox loopCheck;
    private LinearLayout manualPanel, csvPanel;
    private RadioGroup sourceGroup;
    private final RadioButton[] sourceButtons = new RadioButton[6];
    private int throwCount = 5;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateStatus();
            ui.postDelayed(this, 250);
        }
    };

    // =====================================================================
    // Cykl zycia
    // =====================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        phone = new PhoneMotion(this);
        engine = new TrikiPeripheral(this, this);
        engine.setSource(phone);
        buildUi();
        if (hasBtPerms()) engine.restoreSavedName();
        if (!phone.hasAccel()) log("Brak akcelerometru - źródło \"czujniki telefonu\" nadaje spoczynek.");
        if (!phone.hasGyro()) log("Brak żyroskopu - gyro z czujników telefonu = 0.");
        log("Gotowe. Wpisz nazwę i włącz BLE, potem połącz się Żappką z DRUGIEGO telefonu.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (sourceIdx == SRC_PHONE) phone.start();
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        resumed = false;
        phone.stop();
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        phone.stop();
        engine.destroy();
        super.onDestroy();
    }

    // =====================================================================
    // Budowa UI (programowo, bez XML)
    // =====================================================================

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, final Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private void addWeighted(LinearLayout row, View v) {
        v.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(v);
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(text("TrikiEmu", 24, true));
        TextView note = text("Telefon udaje kapsel Żabka Triki (BLE peripheral). Żappkę uruchom na DRUGIM telefonie "
                + "- telefon nie połączy się sam ze sobą. Zostaw tę aplikację na pierwszym planie z włączonym ekranem. "
                + "Android nie pozwala ustawić adresu MAC reklamy, a Żappka może rozpoznawać kapsel po MAC (zob. android/README.md).", 12, false);
        note.setPadding(0, dp(4), 0, dp(8));
        root.addView(note);

        statusView = text("", 13, false);
        statusView.setTypeface(Typeface.MONOSPACE);
        statusView.setPadding(0, dp(4), 0, dp(8));
        root.addView(statusView);

        root.addView(text("Nazwa urządzenia (max 20 bajtów; na czas działania zmienia nazwę Bluetooth telefonu):", 12, false));
        nameEdit = new EditText(this);
        nameEdit.setSingleLine(true);
        nameEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        nameEdit.setText(Protocol.DEFAULT_DEVICE_NAME);
        nameEdit.setFilters(new InputFilter[] {new InputFilter.LengthFilter(Protocol.MAX_NAME_LEN)});
        root.addView(nameEdit);

        bleBtn = button("Włącz BLE (reklama)", new Runnable() {
            @Override public void run() { onBleClick(); }
        });
        root.addView(bleBtn);

        TextView srcTitle = text("Źródło ruchu", 16, true);
        srcTitle.setPadding(0, dp(12), 0, dp(4));
        root.addView(srcTitle);

        String[] names = {
                "Czujniki telefonu (żyroskop + akcelerometr)",
                "Spoczynek (accel 1 g)",
                "Sterowanie ręczne (obrót, FLAP, rzut)",
                "Wzorzec: spin (obrót wokół Z)",
                "Wzorzec: tilt (przechył)",
                "Plik CSV (ruch z pliku)"};
        sourceGroup = new RadioGroup(this);
        sourceGroup.setOrientation(RadioGroup.VERTICAL);
        final int[] ids = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(names[i]);
            rb.setId(View.generateViewId());
            ids[i] = rb.getId();
            sourceButtons[i] = rb;
            sourceGroup.addView(rb);
        }
        sourceGroup.check(ids[SRC_PHONE]);
        sourceGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup group, int checkedId) {
                for (int i = 0; i < ids.length; i++) {
                    if (ids[i] == checkedId) { selectSource(i); return; }
                }
            }
        });
        root.addView(sourceGroup);

        buildCsvPanel(root);
        buildManualPanel(root);

        TextView logTitle = text("Dziennik", 16, true);
        logTitle.setPadding(0, dp(16), 0, dp(4));
        root.addView(logTitle);
        logView = text("", 11, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        setContentView(sv);
        selectSource(SRC_PHONE);
    }

    private void buildCsvPanel(LinearLayout root) {
        csvPanel = new LinearLayout(this);
        csvPanel.setOrientation(LinearLayout.VERTICAL);
        csvPanel.addView(button("Wybierz plik CSV…", new Runnable() {
            @Override public void run() { pickCsv(); }
        }));
        loopCheck = new CheckBox(this);
        loopCheck.setText("Zapętl (bez zapętlenia zostaje ostatnia próbka)");
        loopCheck.setChecked(true);
        loopCheck.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CsvMotion c = csv;
                if (c != null) c.setLoop(loopCheck.isChecked());
            }
        });
        csvPanel.addView(loopCheck);
        csvInfo = text("Brak wczytanego pliku. Format: t,gx,gy,gz,ax,ay,az (keyframe'y) lub gx,gy,gz,ax,ay,az; '#' = komentarz.", 12, false);
        csvPanel.addView(csvInfo);
        root.addView(csvPanel);
    }

    private void buildManualPanel(LinearLayout root) {
        manualPanel = new LinearLayout(this);
        manualPanel.setOrientation(LinearLayout.VERTICAL);

        LinearLayout r1 = row();
        addWeighted(r1, button("◀ LEWO (q)", new Runnable() { @Override public void run() { manual.turnLeft(); } }));
        addWeighted(r1, button("PRAWO (e) ▶", new Runnable() { @Override public void run() { manual.turnRight(); } }));
        manualPanel.addView(r1);

        LinearLayout r2 = row();
        addWeighted(r2, button("X− (a)", new Runnable() { @Override public void run() { manual.xMinus(); } }));
        addWeighted(r2, button("X+ (d)", new Runnable() { @Override public void run() { manual.xPlus(); } }));
        addWeighted(r2, button("Y+ (w)", new Runnable() { @Override public void run() { manual.yPlus(); } }));
        addWeighted(r2, button("Y− (s)", new Runnable() { @Override public void run() { manual.yMinus(); } }));
        manualPanel.addView(r2);

        LinearLayout r3 = row();
        addWeighted(r3, button("FLAP (f)", new Runnable() { @Override public void run() { manual.flap(); } }));
        addWeighted(r3, button("ZERUJ", new Runnable() { @Override public void run() { manual.zero(); } }));
        manualPanel.addView(r3);

        stepLabel = text("Siła obrotu: 15 °/s na kliknięcie", 12, false);
        manualPanel.addView(stepLabel);
        SeekBar stepBar = new SeekBar(this);
        stepBar.setMax(119);                    // 1..120
        stepBar.setProgress(14);
        stepBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override void changed(int p) {
                manual.setStep(p + 1);
                stepLabel.setText("Siła obrotu: " + (p + 1) + " °/s na kliknięcie");
            }
        });
        manualPanel.addView(stepBar);

        flapLabel = text("Siła flapa: 10 g", 12, false);
        manualPanel.addView(flapLabel);
        SeekBar flapBar = new SeekBar(this);
        flapBar.setMax(29);                     // 1..30
        flapBar.setProgress(9);
        flapBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override void changed(int p) {
                manual.setFlapPeak(p + 1);
                flapLabel.setText("Siła flapa: " + (p + 1) + " g");
            }
        });
        manualPanel.addView(flapBar);

        throwLabel = text("Rzut: 5 obr.", 12, false);
        manualPanel.addView(throwLabel);
        SeekBar throwBar = new SeekBar(this);
        throwBar.setMax(ManualMotion.MAX_THROW - 1);   // 1..20
        throwBar.setProgress(4);
        throwBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override void changed(int p) {
                throwCount = p + 1;
                throwLabel.setText("Rzut: " + throwCount + " obr.");
            }
        });
        manualPanel.addView(throwBar);
        Button throwBtn = button("RZUĆ (podrzut + obroty)", new Runnable() {
            @Override public void run() { manual.throwIt(throwCount); }
        });
        manualPanel.addView(throwBtn);

        root.addView(manualPanel);
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        abstract void changed(int progress);
        @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { changed(p); }
        @Override public void onStartTrackingTouch(SeekBar s) { }
        @Override public void onStopTrackingTouch(SeekBar s) { }
    }

    // =====================================================================
    // Zrodlo ruchu
    // =====================================================================

    private void selectSource(int idx) {
        MotionSource src;
        switch (idx) {
            case SRC_PHONE:  src = phone; break;
            case SRC_MANUAL: src = manual; break;
            case SRC_SPIN:   src = spin; break;
            case SRC_TILT:   src = tilt; break;
            case SRC_CSV:
                CsvMotion c = csv;
                if (c != null) { c.rewind(); src = c; } else { src = rest; }
                break;
            default:         src = rest; break;
        }
        sourceIdx = idx;
        if (idx == SRC_PHONE) { if (resumed) phone.start(); } else { phone.stop(); }
        engine.setSource(src);
        manualPanel.setVisibility(idx == SRC_MANUAL ? View.VISIBLE : View.GONE);
        csvPanel.setVisibility(idx == SRC_CSV ? View.VISIBLE : View.GONE);
        if (idx == SRC_CSV && csv == null) log("Wybierz plik CSV - do tego czasu nadawany jest spoczynek.");
    }

    private void pickCsv() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_CSV);
        } catch (RuntimeException e) {
            log("Nie można otworzyć selektora plików: " + e.getMessage());
        }
    }

    private void loadCsv(final Uri uri) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new java.io.IOException("brak strumienia");
                    final CsvMotion parsed;
                    try {
                        parsed = CsvMotion.parse(in);
                    } finally {
                        in.close();
                    }
                    ui.post(new Runnable() {
                        @Override public void run() {
                            parsed.setLoop(loopCheck.isChecked());
                            csv = parsed;
                            csvInfo.setText(String.format(Locale.US, "Wczytano %d próbek (%.1f s @ 100 Hz).",
                                    parsed.sampleCount(), parsed.durationSeconds()));
                            log("CSV wczytany: " + parsed.sampleCount() + " próbek.");
                            if (sourceIdx == SRC_CSV) { parsed.rewind(); engine.setSource(parsed); }
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            csvInfo.setText("Błąd pliku: " + e.getMessage());
                            log("Błąd CSV: " + e.getMessage());
                        }
                    });
                }
            }
        }, "triki-csv").start();
    }

    // =====================================================================
    // BLE: uprawnienia i start/stop
    // =====================================================================

    private boolean hasBtPerms() {
        if (Build.VERSION.SDK_INT < 31) return true;       // <=30: uprawnienia instalacyjne (BLUETOOTH, BLUETOOTH_ADMIN)
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
    }

    private void onBleClick() {
        if (engine.isWantAdvertising()) {
            engine.stop();
            return;
        }
        if (!engine.hasBluetooth()) {
            log("To urządzenie nie ma Bluetooth.");
            return;
        }
        if (!hasBtPerms()) {
            requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE}, REQ_PERMS);
            return;
        }
        if (!engine.isBluetoothEnabled()) {
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT);
            } catch (RuntimeException e) {
                log("Włącz Bluetooth ręcznie i spróbuj ponownie.");
            }
            return;
        }
        startEngine();
    }

    private void startEngine() {
        String name = nameEdit.getText().toString().trim();
        if (name.isEmpty()) name = Protocol.DEFAULT_DEVICE_NAME;
        if (name.getBytes(StandardCharsets.UTF_8).length > Protocol.MAX_NAME_LEN) {
            log("Nazwa za długa (max " + Protocol.MAX_NAME_LEN + " bajtów) - skróć ją.");
            return;
        }
        nameEdit.setText(name);
        engine.start(name);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        if (hasBtPerms()) {
            engine.restoreSavedName();
            onBleClick();
        } else {
            log("Brak uprawnień Bluetooth (połączone urządzenia / reklamowanie) - bez nich BLE nie ruszy.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENABLE_BT) {
            if (resultCode == RESULT_OK) startEngine(); else log("Bluetooth nie został włączony.");
        } else if (requestCode == REQ_CSV) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) loadCsv(data.getData());
        }
    }

    // =====================================================================
    // TrikiPeripheral.Listener (wolane z watku BLE)
    // =====================================================================

    @Override
    public void onLog(final String msg) {
        ui.post(new Runnable() {
            @Override public void run() { log(msg); }
        });
    }

    @Override
    public void onChanged() {
        ui.post(new Runnable() {
            @Override public void run() { updateStatus(); }
        });
    }

    // =====================================================================
    // Status i dziennik (tylko watek UI)
    // =====================================================================

    private void log(String msg) {
        logLines.addLast(timeFmt.format(new Date()) + "  " + msg);
        while (logLines.size() > 60) logLines.removeFirst();
        if (logView == null) return;
        StringBuilder sb = new StringBuilder();
        for (String s : logLines) sb.append(s).append('\n');
        logView.setText(sb.toString());
    }

    private void updateStatus() {
        if (statusView == null) return;
        String state;
        if (!engine.isWantAdvertising()) state = "WYŁĄCZONE";
        else if (engine.isStreaming()) state = "STRUMIEŃ";
        else if (engine.isConnected()) state = "połączony (czekam na START)";
        else if (engine.isAdvertising()) state = "reklama…";
        else state = "uruchamianie…";

        StringBuilder sb = new StringBuilder();
        sb.append("BLE:    ").append(state).append('\n');
        if (engine.isWantAdvertising()) {
            sb.append("Nazwa:  ").append(engine.getAdvName()).append('\n');
        }
        if (engine.isConnected()) {
            sb.append("Central: ").append(engine.getCentralAddr())
              .append("  MTU ").append(engine.getMtu())
              .append("  TX-notify ").append(engine.isTxSubscribed() ? "tak" : "nie").append('\n');
        }
        if (engine.isStreaming()) {
            sb.append("Ramki:  ").append(engine.getFrames())
              .append("  pominięte cykle: ").append(engine.getDropped()).append('\n');
        }
        float[] m = engine.getLastMotion();
        sb.append(String.format(Locale.US, "gyro  %7.1f %7.1f %7.1f °/s%n", m[0], m[1], m[2]));
        sb.append(String.format(Locale.US, "accel %7.3f %7.3f %7.3f g", m[3], m[4], m[5]));
        statusView.setText(sb.toString());

        bleBtn.setText(engine.isWantAdvertising() ? "Wyłącz BLE" : "Włącz BLE (reklama)");
        nameEdit.setEnabled(!engine.isWantAdvertising());
    }
}
