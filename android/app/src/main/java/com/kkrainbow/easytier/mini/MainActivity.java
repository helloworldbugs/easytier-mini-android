package com.kkrainbow.easytier.mini;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Single-screen UI: pick a config, start/stop the core, watch peers and logs. */
public class MainActivity extends Activity {

    private static final int REQ_STORAGE = 1001;
    private static final int REQ_PICK_CONFIG = 2002;
    private static final long POLL_MS = 3000L;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Core.Listener coreListener = new Core.Listener() {
        @Override
        public void onCoreChanged() {
            ui.post(MainActivity.this::syncState);
        }
    };

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            pollPeers();
        }
    };

    private Prefs prefs;
    private TextView statusView;
    private TextView configPathView;
    private Spinner configSpinner;
    private ArrayAdapter<String> configAdapter;
    private ArrayAdapter<String> peerAdapter;
    private TextView logView;
    private CheckBox autoRunBox;
    private Button runBtn;

    private final List<File> configs = new ArrayList<>();
    private boolean polling;
    // Transient UI hint while a start is in flight (the service is async and only
    // syncState() posts 400 ms later). Set in startCore()/stopCore(), cleared
    // exclusively in syncState() once the core reports a real state — it is never
    // a second source of truth for "running", only for "starting".
    private boolean startPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        buildUi();
        Core.get().addListener(coreListener);
        askStorageIfNeeded();
        reloadConfigs();
        syncState();
        maybeAutoRun();
    }

    @Override
    protected void onResume() {
        super.onResume();
        polling = true;
        ui.removeCallbacks(pollTask);
        ui.post(pollTask);
    }

    @Override
    protected void onPause() {
        polling = false;
        ui.removeCallbacks(pollTask);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(pollTask);
        Core.get().removeListener(coreListener);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI build

    private void buildUi() {
        int pad = dp(10);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(statusView, matchWrap());

        root.addView(label("配置文件（放到 /sdcard/EasyTier/ 或本应用的外部目录）"), matchWrap());

        configSpinner = new Spinner(this);
        configAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<String>());
        configAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        configSpinner.setAdapter(configAdapter);
        configSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < configs.size()) {
                    File f = configs.get(position);
                    configPathView.setText(f.getAbsolutePath());
                    prefs.setLastConfig(f.getAbsolutePath());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // nothing to do
            }
        });
        root.addView(configSpinner, matchWrap());

        configPathView = new TextView(this);
        configPathView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        root.addView(configPathView, matchWrap());

        LinearLayout configRow = new LinearLayout(this);
        configRow.setOrientation(LinearLayout.HORIZONTAL);
        Button pickBtn = new Button(this);
        pickBtn.setText("选择配置文件");
        pickBtn.setOnClickListener(v -> pickConfig());
        Button reloadBtn = new Button(this);
        reloadBtn.setText("刷新配置");
        reloadBtn.setOnClickListener(v -> reloadConfigs());
        configRow.addView(pickBtn, weight1());
        configRow.addView(reloadBtn, weight1());
        root.addView(configRow, matchWrap());

        LinearLayout runRow = new LinearLayout(this);
        runRow.setOrientation(LinearLayout.HORIZONTAL);
        runBtn = new Button(this);
        runBtn.setText("启动");
        runBtn.setEnabled(false); // enabled for real in syncState()
        runBtn.setOnClickListener(v -> {
            // The core state decides the action; the label only mirrors it.
            if (Core.get().isRunning()) {
                stopCore();
            } else {
                startSelected();
            }
        });
        runRow.addView(runBtn, weight1());
        root.addView(runRow, matchWrap());

        autoRunBox = new CheckBox(this);
        autoRunBox.setText("打开 App 时自动运行上次配置");
        // Reflect the persisted value BEFORE the listener is attached: setChecked()
        // would otherwise fire onCheckedChange and write the same value back at startup.
        autoRunBox.setChecked(prefs.autoRun());
        autoRunBox.setOnCheckedChangeListener((v, checked) -> prefs.setAutoRun(checked));
        root.addView(autoRunBox, matchWrap());

        root.addView(label("连接设备（peer）"), matchWrap());
        ListView peerList = new ListView(this);
        peerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        peerList.setAdapter(peerAdapter);
        root.addView(peerList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout logHeader = new LinearLayout(this);
        logHeader.setOrientation(LinearLayout.HORIZONTAL);
        logHeader.addView(label("运行日志"), weight1());
        Button copyLogBtn = new Button(this);
        copyLogBtn.setText("复制日志");
        copyLogBtn.setOnClickListener(v -> copyLog());
        logHeader.addView(copyLogBtn, wrapWrap());
        root.addView(logHeader, matchWrap());

        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setMovementMethod(new ScrollingMovementMethod());
        ScrollView logScroll = new ScrollView(this);
        logScroll.addView(logView, matchWrap());
        root.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(130)));

        setContentView(root);
    }

    private TextView label(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        return v;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ actions

    private void startSelected() {
        int pos = configSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= configs.size()) {
            toast("没有可用的配置文件");
            return;
        }
        startCore(configs.get(pos));
    }

    private void startCore(File cfg) {
        startPending = true;
        prefs.setLastConfig(cfg.getAbsolutePath());
        Intent intent = new Intent(this, CoreService.class);
        intent.setAction(CoreService.ACTION_START);
        intent.putExtra(CoreService.EXTRA_CONFIG, cfg.getAbsolutePath());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        ui.postDelayed(this::syncState, 400L);
    }

    private void stopCore() {
        startPending = false;
        Intent intent = new Intent(this, CoreService.class);
        intent.setAction(CoreService.ACTION_STOP);
        startService(intent);
        ui.postDelayed(this::syncState, 400L);
    }

    private void maybeAutoRun() {
        if (!prefs.autoRun() || Core.get().isRunning()) {
            return;
        }
        File target = null;
        String last = prefs.lastConfig();
        if (last != null) {
            File f = new File(last);
            if (f.isFile()) {
                target = f;
            }
        }
        if (target == null && !configs.isEmpty()) {
            target = configs.get(0);
        }
        if (target != null) {
            startCore(target);
        }
    }

    private void reloadConfigs() {
        configs.clear();
        configs.addAll(ConfigStore.list(this));

        configAdapter.clear();
        for (File f : configs) {
            configAdapter.add(f.getName());
        }
        configAdapter.notifyDataSetChanged();

        int sel = configs.isEmpty() ? -1 : 0;
        String last = prefs.lastConfig();
        if (last != null) {
            for (int i = 0; i < configs.size(); i++) {
                if (configs.get(i).getAbsolutePath().equals(last)) {
                    sel = i;
                    break;
                }
            }
        }
        if (sel >= 0) {
            configSpinner.setSelection(sel);
            configPathView.setText(configs.get(sel).getAbsolutePath());
        } else {
            configPathView.setText("未找到配置文件。可点「选择配置文件」导入，或放到：\n" + dirsText());
        }
        syncState();
    }

    private String dirsText() {
        StringBuilder b = new StringBuilder();
        for (File d : ConfigStore.displayDirs(this)) {
            b.append("  ").append(d.getAbsolutePath()).append('\n');
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- rendering

    private void pollPeers() {
        if (!polling) {
            return;
        }
        if (Core.get().isRunning()) {
            new Thread(() -> {
                List<String> rows;
                try {
                    File cli = Binaries.resolve(MainActivity.this, "easytier-cli");
                    String out = Cli.execPeers(MainActivity.this, cli, Core.RPC_PORTAL);
                    rows = Cli.parsePeers(out);
                    if (rows.isEmpty()) {
                        rows = Collections.singletonList("（暂无节点数据）");
                    }
                } catch (Throwable t) {
                    rows = Collections.singletonList("peer 查询失败: " + t.getMessage());
                }
                final List<String> result = rows;
                ui.post(() -> {
                    peerAdapter.clear();
                    peerAdapter.addAll(result);
                    peerAdapter.notifyDataSetChanged();
                });
            }, "peer-poll").start();
        } else {
            peerAdapter.clear();
            peerAdapter.notifyDataSetChanged();
        }
        syncState();
        ui.removeCallbacks(pollTask);
        ui.postDelayed(pollTask, POLL_MS);
    }

    private void syncState() {
        Core core = Core.get();
        boolean running = core.isRunning();

        // The core has reached a real state (running, or failed with an error), so
        // the "starting" hint is done. Cleared here — the only place that reads it.
        if (running || core.lastError() != null) {
            startPending = false;
        }

        if (running) {
            long seconds = core.startedAt() > 0
                    ? (System.currentTimeMillis() - core.startedAt()) / 1000L : 0L;
            statusView.setText("● 运行中 · " + core.configName() + " · " + seconds + "s");
            statusView.setTextColor(0xFF2E7D32);
        } else if (core.lastError() != null) {
            statusView.setText("● 已停止（启动失败：" + core.lastError() + "）");
            statusView.setTextColor(0xFFC62828);
        } else {
            statusView.setText("● 已停止");
            statusView.setTextColor(0xFF616161);
        }

        // One button, driven purely by the core state plus the transient start hint.
        if (running) {
            runBtn.setText("停止");
            runBtn.setEnabled(true);
        } else if (startPending) {
            runBtn.setText("启动中…");
            runBtn.setEnabled(false);
        } else {
            runBtn.setText("启动");
            runBtn.setEnabled(!configs.isEmpty());
        }
        logView.setText(core.logs().text());
    }

    // -------------------------------------------------------------- permissions

    private void askStorageIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            reloadConfigs();
        }
    }

    /**
     * Opens the system file picker. SAF first, then the legacy GET_CONTENT picker;
     * bare head units sometimes ship neither, so that case is reported instead of
     * crashing with ActivityNotFoundException.
     */
    private void pickConfig() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_CONFIG);
            return;
        } catch (ActivityNotFoundException ignored) {
            // fall through to the legacy picker
        }

        Intent legacy = new Intent(Intent.ACTION_GET_CONTENT);
        legacy.addCategory(Intent.CATEGORY_OPENABLE);
        legacy.setType("*/*");
        legacy.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(legacy, REQ_PICK_CONFIG);
        } catch (ActivityNotFoundException e) {
            toast("本机没有文件选择器，请把配置文件拷到：\n" + dirsText());
        }
    }

    /** Copies the picked document into the app's own config dir, then selects it. */
    private void importConfigFrom(final Uri uri) {
        final String name = ConfigStore.displayName(this, uri);
        new Thread(() -> {
            try {
                final File dst = ConfigStore.importConfig(MainActivity.this, uri, name);
                ui.post(() -> {
                    prefs.setLastConfig(dst.getAbsolutePath());
                    reloadConfigs();
                    toast("已导入并选中：\n" + dst.getAbsolutePath());
                });
            } catch (final Throwable t) {
                ui.post(() -> toast("导入失败：" + t.getMessage()));
            }
        }, "config-import").start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_CONFIG && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            importConfigFrom(data.getData());
        }
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) {
            toast("剪贴板不可用");
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("easytier-log", Core.get().logs().text()));
        toast("日志已复制到剪贴板");
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
