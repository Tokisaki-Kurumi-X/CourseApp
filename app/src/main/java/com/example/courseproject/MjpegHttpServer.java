package com.example.courseproject;

import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;

public class MjpegHttpServer extends NanoHTTPD {
    private ServerSocket serverSocket;
    private Thread   serverThread;
    private ScheduledExecutorService scheduler;
    private VideoTask defaultTask = new VideoTask();
    // 任务管理，key=taskID，value=任务实例
    private Map<String, VideoTask> taskMap = new ConcurrentHashMap<>();

    public MjpegHttpServer(int port) throws IOException {
        super(port);

    }

    /** HTTP 工具：基于 HttpURLConnection */
    public static class HttpHelper {
        private static final int TIMEOUT = 5000;  // 毫秒
        private static final Handler mainHandler = new Handler(Looper.getMainLooper());

        /**
         * 普通 GET，会把整个响应读完再回调 onSuccess。
         * 对于 MJPEG 流（multipart/x-mixed-replace），你可以改成 setReadTimeout(0)
         * 并在外部自行处理流式解析。
         */
        public static void sendGet(String baseUrl,

                                   ResponseListener listener) {
            new Thread(() -> {
                HttpURLConnection conn = null;
                InputStream is = null;
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try {
                    // 拼 URL

                    URL url = new URL(baseUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(TIMEOUT);
                    // 对于长连接流式场景，请设置为 0（无限等待）
                    conn.setReadTimeout(0);

                    int code = conn.getResponseCode();
                    is = code >= 200 && code < 300
                            ? conn.getInputStream()
                            : conn.getErrorStream();

                    // 直接按字节读取，不用 BufferedReader.readLine()
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, len);
                    }

                    final String body = baos.toString("UTF-8");
                    mainHandler.post(() -> listener.onSuccess(code, body));

                } catch (Exception ex) {
                    mainHandler.post(() -> listener.onError(ex));
                } finally {
                    try { if (is != null) is.close(); } catch (IOException ignored) {}
                    if (conn != null) conn.disconnect();
                }
            }).start();
        }

        /**
         * POST（JSON）请求
         */
        public static void sendPost(String urlStr,

                                        ResponseListener listener) {
            new Thread(() -> {
                HttpURLConnection conn = null;
                try {
                    // 1. 打开连接（参数已经包含在 urlStr 里）
                    URL url = new URL(urlStr);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");      // 强制使用 POST
                    conn.setConnectTimeout(TIMEOUT);
                    conn.setReadTimeout(TIMEOUT);
                    // 不调用 conn.setDoOutput(true)，不写请求体

                    // 2. 发起请求并读响应
                    int code = conn.getResponseCode();
                    InputStream is = (code >= 200 && code < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(is, "UTF-8")
                    )) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line);
                        }
                    }
                    String body = sb.toString();

                    // 3. 回调到主线程
                    mainHandler.post(() -> listener.onSuccess(code, body));

                } catch (Exception ex) {
                    mainHandler.post(() -> listener.onError(ex));
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }).start();
        }
    }

    // 创建新任务，返回 taskID
    public String createTask() {
        String taskID = UUID.randomUUID().toString();
        VideoTask task = new VideoTask();
        taskMap.put(taskID, task);
        return taskID;
    }

    // 更新所有任务的 JPEG 图像数据
    public void updateFrame(byte[] jpegData) {
        for (VideoTask t : taskMap.values()) {
            t.updateFrame(jpegData);
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();

        if (uri.equals("/start")) {
            // 外部请求开始接口，返回 JSON taskID
            String newTaskID = createTask();
            JSONObject json = new JSONObject();
            try {
                json.put("taskID", newTaskID);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString());

        } else if(uri.equals("/video")){
            // 解析 query 参数
            Map<String, String> params = session.getParms();
            String taskID = params.get("taskID");
            VideoTask task;
            // 如果没传 taskID，就新建
            if (taskID == null) {
                taskID = createTask();
                System.out.println("*******************No ID**************");
                task = taskMap.get(taskID);
            } else {
                // 如果传了就去找
                task = taskMap.get(taskID);
                if (task == null) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND,
                            "text/plain",
                            "Task not found: " + taskID);
                }
            }

            // 准备 MJPEG 流
            Response resp = newChunkedResponse(Response.Status.OK,
                    "multipart/x-mixed-replace; boundary=myboundary",
                    task.getMJpegInputStream());
            // 返回给客户端一个 header，让它知道当前的 taskID
            resp.addHeader("X-TaskID", taskID);
            // 保持长连接
            resp.addHeader("Connection", "keep-alive");
            return resp;
        }else if (uri.equals("/stream")) {
            Map<String, String> params = session.getParms();
            String taskID = params.get("taskID");
            if (taskID == null) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing taskID parameter");
            }
            VideoTask task = taskMap.get(taskID);
            if (task == null) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Task not found");
            }
            // 设置 MJPEG 流响应头，调用对应任务的InputStream
            return newChunkedResponse(Response.Status.OK,
                    "multipart/x-mixed-replace; boundary=frame",
                    task.getMJpegInputStream());

        } else {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Unknown path");
        }
    }
    // 启动时直接调用父类的 start()
    @Override
    public void start() throws IOException {
        super.start();    // 这行就会在指定端口打开 Socket
    }
    // 停止时调用父类的 stop()
    @Override
    public void stop() {
        super.stop();
    }
    // 内部类，管理每个task生成的流
    class VideoTask {
        private volatile byte[] latestFrame;
        private int frameIntervalMs = 1000 / 100; // 100fps
        private final String boundary = "myboundary";
        public void updateFrame(byte[] jpegData) {
            this.latestFrame = jpegData;
        }

        public InputStream getMJpegInputStream() {
            return new InputStream() {
                private int index = 0;
                private byte[] msg = null;
                private  int pos=0;
                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    int r = read(one, 0, 1);
                    return r == -1 ? -1 : (one[0] & 0xFF);
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    // 等待首帧就绪
                    while (latestFrame == null) {
                        try { Thread.sleep(10); } catch (InterruptedException e) { }
                    }
                    byte[] frame = latestFrame;

                    // 拼 header
                    String header = "--" + boundary + "\r\n"
                            + "Content-Type: image/jpeg\r\n"
                            + "Content-Length: " + frame.length + "\r\n\r\n";
                    byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);

                    int totalLen = headerBytes.length + frame.length + 2;

                    // 只有第一次或上一帧已经完全发送完才重建 msg
                    if (msg == null || msg.length != totalLen) {
                        msg = new byte[totalLen];
                        int p = 0;
                        System.arraycopy(headerBytes, 0, msg, p, headerBytes.length);
                        p += headerBytes.length;
                        System.arraycopy(frame, 0, msg, p, frame.length);
                        p += frame.length;
                        msg[p++] = '\r';
                        msg[p  ] = '\n';
                        pos = 0;               // ← 仅此处重置
                    }

                    // 从 pos 处拷 len 或剩余部分
                    int toCopy = Math.min(len, msg.length - pos);
                    System.arraycopy(msg, pos, buf, off, toCopy);
                    pos += toCopy;

                    // 如果本帧已经完全发送，暂停、然后丢弃 msg 以便下次重建新帧
                    if (pos >= msg.length) {
                        try { Thread.sleep(frameIntervalMs); } catch (InterruptedException e) { }
                        msg = null;           // ← 标记下次需要重建 header+新 frame
                    }

                    return toCopy;
                }


                @Override
                public int available() {
                    return (msg == null ? 0 : msg.length - pos);
                }
            };
        }
    }
}
