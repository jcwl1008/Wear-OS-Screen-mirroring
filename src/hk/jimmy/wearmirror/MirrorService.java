package hk.jimmy.wearmirror;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

public final class MirrorService extends Service {
    static final String CONSENT = "consent";
    static final String READY = "同一 Wi-Fi 連接手錶同 PC。";
    static volatile String status = READY;
    static volatile String address = "";
    static volatile boolean active;
    private static final String STOP = "hk.jimmy.wearmirror.STOP";
    private static final String CHANNEL = "mirror";
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread captureThread;
    private Handler capture;
    private Bitmap bitmap;
    private byte[] pixels;
    private boolean frameQueued;
    private long nextFrameTime;
    private volatile byte[] latestFrame;
    private volatile MirrorServer server;
    private boolean networkRegistered;
    private Network wifi;
    private InetAddress wifiAddress;
    private byte[] page;
    private String token;
    private String endMessage = READY;
    private final MediaProjection.Callback projectionEvents = new MediaProjection.Callback() {
        @Override public void onStop() {
            endMessage = "螢幕擷取已停止。可重新開始。";
            stopSelf();
        }
    };
    private final ConnectivityManager.NetworkCallback wifiEvents = new ConnectivityManager.NetworkCallback() {
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
            if (!active) return;
            for (LinkAddress link : properties.getLinkAddresses()) {
                if (link.getAddress() instanceof Inet4Address) {
                    openViewer(network, link.getAddress());
                    return;
                }
            }
            if (network.equals(wifi)) clearViewer();
        }

        @Override public void onLost(Network network) {
            if (network.equals(wifi)) clearViewer();
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (active) return START_NOT_STICKY;
        try {
            beginForeground();
            startProjection(intent);
        } catch (IOException | RuntimeException ex) {
            endMessage = "未能擷取螢幕。請檢查手錶嘅螢幕分享支援。";
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void beginForeground() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(
                new NotificationChannel(CHANNEL, "Wear Mirror", NotificationManager.IMPORTANCE_LOW));
        int pendingFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MirrorActivity.class), pendingFlags);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, MirrorService.class).setAction(STOP), pendingFlags);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Wear Mirror")
                .setContentText("正在擷取手錶畫面")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止投影", stop).build())
                .build();
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    private void startProjection(Intent intent) throws IOException {
        Intent consent = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(CONSENT, Intent.class) : intent.getParcelableExtra(CONSENT);
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        projection = manager.getMediaProjection(Activity.RESULT_OK, consent);
        if (projection == null) throw new IllegalStateException("Missing projection");
        projection.registerCallback(projectionEvents, main);

        int width = intent.getIntExtra("width", 0);
        int height = intent.getIntExtra("height", 0);
        int density = intent.getIntExtra("density", 0);
        captureThread = new HandlerThread("mirror-capture");
        captureThread.start();
        capture = new Handler(captureThread.getLooper());
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::scheduleFrame, capture);
        display = projection.createVirtualDisplay("Wear Mirror", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);
        if (display == null) throw new IllegalStateException("Missing capture display");

        try (InputStream input = getAssets().open("viewer.html")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int count;
            while ((count = input.read(chunk)) != -1) bytes.write(chunk, 0, count);
            page = bytes.toByteArray();
        }
        byte[] random = new byte[12];
        new SecureRandom().nextBytes(random);
        token = android.util.Base64.encodeToString(random,
                android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);
        active = true;
        status = "等候 Wi-Fi。請確認手錶已連線。";
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        getSystemService(ConnectivityManager.class).requestNetwork(request, wifiEvents, main);
        networkRegistered = true;
    }

    private void scheduleFrame(ImageReader source) {
        if (frameQueued) return;
        frameQueued = true;
        capture.postAtTime(() -> {
            frameQueued = false;
            nextFrameTime = SystemClock.uptimeMillis() + 125;
            encodeFrame(source);
        }, Math.max(nextFrameTime, SystemClock.uptimeMillis()));
    }

    private void encodeFrame(ImageReader source) {
        try (Image image = source.acquireLatestImage()) {
            if (image == null) return;
            int width = image.getWidth();
            int height = image.getHeight();
            int rowBytes = width * 4;
            if (bitmap == null) {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                pixels = new byte[rowBytes * height];
            }
            Image.Plane plane = image.getPlanes()[0];
            if (plane.getPixelStride() != 4) return;
            ByteBuffer rgba = plane.getBuffer();
            for (int row = 0; row < height; row++) {
                rgba.position(row * plane.getRowStride());
                rgba.get(pixels, row * rowBytes, rowBytes);
            }
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 75, jpeg)) {
                latestFrame = jpeg.toByteArray();
            }
        } catch (IllegalStateException | java.nio.BufferUnderflowException ignored) {
            // A frame can be interrupted while projection is ending.
        }
    }

    private void openViewer(Network network, InetAddress ip) {
        if (network.equals(wifi) && ip.equals(wifiAddress)) return;
        clearViewer();
        try {
            MirrorServer viewer = new MirrorServer(ip, token, page, () -> latestFrame);
            server = viewer;
            wifi = network;
            wifiAddress = ip;
            address = viewer.url();
            status = "用 PC 瀏覽器開啟以下網址";
        } catch (IOException ex) {
            status = "未能開啟連線。請重新開始投影。";
        }
    }

    private void clearViewer() {
        MirrorServer viewer = server;
        server = null;
        if (viewer != null) viewer.close();
        address = "";
        wifi = null;
        wifiAddress = null;
        if (active) status = "等候 Wi-Fi。請確認手錶已連線。";
    }

    @Override public void onDestroy() {
        active = false;
        clearViewer();
        if (networkRegistered) {
            getSystemService(ConnectivityManager.class).unregisterNetworkCallback(wifiEvents);
        }
        if (projection != null) projection.unregisterCallback(projectionEvents);
        if (display != null) display.release();
        if (projection != null) projection.stop();
        if (reader != null) reader.setOnImageAvailableListener(null, null);
        if (capture != null) {
            capture.removeCallbacksAndMessages(null);
            capture.post(() -> {
                if (reader != null) reader.close();
                if (bitmap != null) bitmap.recycle();
            });
            captureThread.quitSafely();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        status = endMessage;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
