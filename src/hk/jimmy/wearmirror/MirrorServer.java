package hk.jimmy.wearmirror;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class MirrorServer implements AutoCloseable {
    private final ServerSocket listener = new ServerSocket();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(4));
    private final String viewerPath;
    private final byte[] viewerPage;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final Supplier<byte[]> frames;

    MirrorServer(InetAddress wifiAddress, String token, byte[] viewerPage,
            Supplier<byte[]> frames) throws IOException {
        this.viewerPage = viewerPage;
        this.frames = frames;
        viewerPath = "/" + token + "/";
        try {
            listener.bind(new InetSocketAddress(wifiAddress, 0));
        } catch (IOException ex) {
            listener.close();
            throw ex;
        }
        new Thread(this::accept, "mirror-http").start();
    }

    String url() {
        return "http://" + listener.getInetAddress().getHostAddress() + ":"
                + listener.getLocalPort() + viewerPath;
    }

    private void accept() {
        while (!listener.isClosed()) {
            try {
                Socket client = listener.accept();
                clients.add(client);
                try {
                    workers.execute(() -> serve(client));
                } catch (RejectedExecutionException ex) {
                    clients.remove(client);
                    client.close();
                }
            } catch (IOException ex) {
                close();
            }
        }
    }

    private void serve(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(2000);
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            String request = readLine(input);
            for (int i = 0; !readLine(input).isEmpty(); i++) {
                if (i >= 32) throw new IOException("Too many request headers");
            }
            String[] parts = request.split(" ");
            if (parts.length != 3 || !parts[0].equals("GET")) {
                respond(socket, "400 Bad Request", "text/plain", new byte[0]);
                return;
            }
            if (parts[1].equals(viewerPath)) {
                respond(socket, "200 OK", "text/html; charset=utf-8", viewerPage);
            } else if (parts[1].equals(viewerPath + "frame.jpg")) {
                byte[] frame = frames.get();
                respond(socket, frame == null ? "503 Service Unavailable" : "200 OK",
                        "image/jpeg", frame == null ? new byte[0] : frame);
            } else {
                respond(socket, "404 Not Found", "text/plain", new byte[0]);
            }
        } catch (IOException ignored) {
            // A closed browser tab or interrupted Wi-Fi connection can end a request.
        } finally {
            clients.remove(client);
        }
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < 2048; i++) {
            int value = input.read();
            if (value == -1 || value == '\n') {
                return new String(bytes.toByteArray(), StandardCharsets.US_ASCII).trim();
            }
            bytes.write(value);
        }
        throw new IOException("Request line too long");
    }

    private static void respond(Socket socket, String status, String type, byte[] body)
            throws IOException {
        OutputStream output = socket.getOutputStream();
        String headers = "HTTP/1.1 " + status + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\nCache-Control: no-store"
                + "\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
    }

    @Override public void close() {
        try {
            listener.close();
        } catch (IOException ignored) {
            // The listener is already closing.
        }
        workers.shutdownNow();
        for (Socket client : clients) {
            try {
                client.close();
            } catch (IOException ignored) {
                // The peer may have disconnected.
            }
        }
        clients.clear();
    }
}
