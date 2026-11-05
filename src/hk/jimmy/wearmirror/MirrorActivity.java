package hk.jimmy.wearmirror;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public final class MirrorActivity extends Activity {
    private static final int CAPTURE = 1;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean requestingConsent;
    private TextView title;
    private TextView status;
    private TextView address;
    private Button action;
    private ImageView qrCode;
    private int qrSide;
    private String displayedAddress = "";

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            String url = MirrorService.address;
            if (!displayedAddress.equals(url)) {
                displayedAddress = url;
                address.setText(url);
                address.setVisibility(url.isEmpty() ? View.GONE : View.VISIBLE);
                updateQrCode(url);
            }
            boolean hasQrCode = qrCode.getVisibility() == View.VISIBLE;
            title.setVisibility(hasQrCode ? View.GONE : View.VISIBLE);
            String message = hasQrCode ? "掃 QR Code 開啟投影" : MirrorService.status;
            if (!TextUtils.equals(status.getText(), message)) status.setText(message);
            action.setEnabled(!requestingConsent);
            String buttonText = MirrorService.active ? "停止投影" : "開始投影";
            if (!TextUtils.equals(action.getText(), buttonText)) action.setText(buttonText);
            main.postDelayed(this, 300);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestingConsent = state != null && state.getBoolean("requestingConsent");
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(15, 16, 27));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        float density = getResources().getDisplayMetrics().density;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int side = Math.round(screenWidth * .13f);
        int vertical = Math.round(18 * density);
        panel.setPadding(side, vertical, side, vertical);
        scroll.addView(panel);
        title = label("Wear Mirror", 20, 0xffdfd5ff);
        panel.addView(title);
        status = label("", 13, 0xffeeeeff);
        panel.addView(status);
        qrSide = Math.round(screenWidth * .56f);
        qrCode = new ImageView(this);
        qrCode.setContentDescription("投影網址 QR Code");
        qrCode.setScaleType(ImageView.ScaleType.CENTER);
        qrCode.setVisibility(View.GONE);
        LinearLayout.LayoutParams qrLayout = new LinearLayout.LayoutParams(qrSide, qrSide);
        qrLayout.topMargin = Math.round(4 * density);
        panel.addView(qrCode, qrLayout);
        action = new Button(this);
        panel.addView(action);
        address = label("", 12, 0xffbbddff);
        address.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        address.setVisibility(View.GONE);
        panel.addView(address);
        action.setOnClickListener(view -> {
            if (MirrorService.active) {
                stopService(new Intent(this, MirrorService.class));
            } else {
                requestProjection();
            }
        });
        setContentView(scroll);
    }

    private void updateQrCode(String url) {
        qrCode.setImageDrawable(null);
        qrCode.setVisibility(View.GONE);
        if (url.isEmpty()) return;

        try {
            BitMatrix code = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, qrSide, qrSide);
            int[] pixels = new int[qrSide * qrSide];
            for (int y = 0; y < qrSide; y++) {
                for (int x = 0; x < qrSide; x++) {
                    pixels[y * qrSide + x] = code.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(pixels, qrSide, qrSide, Bitmap.Config.ARGB_8888);
            bitmap.setDensity(Bitmap.DENSITY_NONE);
            qrCode.setImageBitmap(bitmap);
            qrCode.setVisibility(View.VISIBLE);
        } catch (WriterException ex) {
            // The URL is still shown for manual entry.
        }
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private void requestProjection() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        try {
            if (manager == null) {
                throw new ActivityNotFoundException();
            }
            Intent consent = Build.VERSION.SDK_INT >= 34
                    ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    : manager.createScreenCaptureIntent();
            requestingConsent = true;
            action.setEnabled(false);
            startActivityForResult(consent, CAPTURE);
        } catch (ActivityNotFoundException | SecurityException | UnsupportedOperationException ex) {
            requestingConsent = false;
            MirrorService.status = "此手錶未能開啟擷取授權。";
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CAPTURE) return;
        requestingConsent = false;
        if (result != RESULT_OK || data == null) {
            MirrorService.status = "未獲螢幕擷取授權。";
            return;
        }
        android.graphics.Rect bounds = getWindowManager().getMaximumWindowMetrics().getBounds();
        Intent start = new Intent(this, MirrorService.class)
                .putExtra(MirrorService.CONSENT, data)
                .putExtra("width", bounds.width())
                .putExtra("height", bounds.height())
                .putExtra("density", getResources().getDisplayMetrics().densityDpi);
        try {
            MirrorService.status = "正在啟動投影…";
            startForegroundService(start);
        } catch (IllegalStateException | SecurityException ex) {
            MirrorService.status = "未能啟動投影。請留在此畫面再試。";
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("requestingConsent", requestingConsent);
        super.onSaveInstanceState(state);
    }

    @Override protected void onStart() {
        super.onStart();
        main.post(refresh);
    }

    @Override protected void onStop() {
        main.removeCallbacks(refresh);
        super.onStop();
    }
}
