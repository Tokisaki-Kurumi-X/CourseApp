package com.example.courseproject;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class PhotoActivity extends AppCompatActivity {
    String wsUrl = "ws://10.243.105.168:1521/ws/photo";
    String backUrl="ws://10.243.105.168:1521/ws/photo/result";
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private WebSocket webSocket;
    private OkHttpClient httpClient;
    private MjpegHttpServer mjpegHttpServer;
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.forphoto);
        // 请求相机权限
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, CameraActivity.Configuration.REQUIRED_PERMISSIONS,
                    CameraActivity.Configuration.REQUEST_CODE_PERMISSIONS);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    1001);
        }
        try {
            startHttpServer();
        } catch (IOException e) {
            e.printStackTrace();
        }
        Button backToMain=findViewById(R.id.cameraBack);
        Button takePhoto=findViewById(R.id.camera_capture_button);
        backToMain.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });
        takePhoto.setOnClickListener(v -> takePhoto());
        cameraExecutor = Executors.newSingleThreadExecutor();
    }
    // System.out.println("*************************************");
    private void initWebSocket() {
        httpClient = new OkHttpClient();
        //replace address
        Request request = new Request.Builder().url(wsUrl).build();
        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
               // System.out.println("*************open connection************************");
               // Log.d("WebSocket", "连接已打开");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
               System.out.println("**************Recieve back***********************");
               System.out.println("接收到消息: " + text);
                webSocket.send(text);
                JSONArray jsonArray = null;
                try {
                    jsonArray = new JSONArray(text);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                StringBuilder studentIds = new StringBuilder();
               try {


                   for(int i=0;i<jsonArray.length();i++){
                       JSONObject student = jsonArray.getJSONObject(i);
                       String studentId = student.getString("studentId");
                       studentIds.append(studentId).append("\n");
                   }
               }catch (JSONException e){
                   e.printStackTrace();
               }
                final String result = studentIds.toString();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PhotoActivity.this, "学号列表:\n" + result, Toast.LENGTH_LONG).show();
                    }
                });



            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(code, reason);
               // System.out.println("*************************************");
              //  Log.d("WebSocket", "连接正在关闭: " + reason);
             //   System.out.println("*************************************");
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
             //   System.out.println("*************************************");
              //  Log.e("WebSocket", "连接失败", t);
              //  System.out.println("*************************************");
            }
        });
    }
    private  void  startHttpServer() throws IOException {
        // Start HTTP server
        if (mjpegHttpServer == null) {
            mjpegHttpServer = new MjpegHttpServer(8080);
            try {
                mjpegHttpServer.start();
                Toast.makeText(this, "HTTP server started on port 8080", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                e.printStackTrace();
                Toast.makeText(this, "Failed to start HTTP server", Toast.LENGTH_SHORT).show();
            }
        }
        String ip= NetworkUtil.getDeviceIpAddress(this);
        Toast.makeText(this, "IP is"+ip, Toast.LENGTH_SHORT).show();
    }
    private void showImageDialog(Bitmap bitmap) {
        // 加载自定义布局
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_image_preview, null);
        ImageView imageView = dialogView.findViewById(R.id.imagePreview);
        imageView.setImageBitmap(bitmap);

        // 构建弹窗
        new AlertDialog.Builder(this)
                .setTitle("Base64 解码图片预览")
                .setView(dialogView)
                .setPositiveButton("确定", null)
                .show();
    }
    private  void ensureCode(String encodedImage){
        byte[] decodedBytes = Base64.decode(encodedImage, Base64.NO_WRAP);
        Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
        if (bitmap != null) {
            // 弹窗显示图片
            showImageDialog(bitmap);
        } else {
            Log.e("PhotoActivity", "Bitmap 解码失败");
        }
    }
    // System.out.println("*********************Base64********************");
    private void takePhoto() {
        // 禁用按钮，防止重复点击
       // System.out.println("************InitWS*********");
        initWebSocket();

        Button takePhotoButton = findViewById(R.id.camera_capture_button);
        takePhotoButton.setEnabled(false);

        if (imageCapture == null) {
            Log.e("PhotoActivity", "ImageCapture 未初始化");
            takePhotoButton.setEnabled(true);
            return;
        }
        imageCapture.takePicture(
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(@NonNull ImageProxy image) {
                        try {
                            // 此处默认 image 为 JPEG 格式，如果 ImageCapture 初始化时指定了 JPEG 输出格式
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);
                          //  Log.d("PhotoSize", "Captured image byte size: " + bytes.length);

                            // 将 JPEG 数据转换为 Base64 字符串
                            String encodedImage = Base64.encodeToString(bytes, Base64.NO_WRAP);
                          //  System.out.println("*********************Base64********************");
                          //  Log.d("PhotoActivity", "Encoded Base64 Image: " + encodedImage);
                          //  System.out.println("*********************Base64********************");
                            // 这里可以进一步处理 Base64 数据，比如上传服务器或展示预览
                            //***********验证*****************
                          //  ensureCode(encodedImage);//验证通过
                            //***************验证******************
                            //***************处理数据***************
                            JSONObject jsonObject = new JSONObject();
                            try {

                                jsonObject.put("text_header","OK");
                                jsonObject.put("image_base64", encodedImage);


                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                            if (webSocket != null) {
                                webSocket.send(jsonObject.toString());
                              //  System.out.println("************Sending data*********************");
                              //  Log.d("PhotoActivity", "通过 WebSocket 发送数据: " + jsonObject.toString());
                              //  System.out.println("************close connection*********************");
                               // webSocket.close(1000,"已完成传输");
                            } else {
                               // System.out.println("*************************************");
                              //  Log.e("PhotoActivity", "WebSocket 未连接");
                            }
                            //***************处理数据***************

                        } catch (Exception e) {
                            Log.e("PhotoActivity", "Base64 转换出错", e);
                        } finally {
                            image.close();
                            // 拍照完成后恢复按钮可用状态
                            runOnUiThread(() -> takePhotoButton.setEnabled(true));
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e("PhotoActivity", "拍照失败: " + exception.getMessage(), exception);
                        runOnUiThread(() -> {
                            Toast.makeText(PhotoActivity.this, "拍照失败", Toast.LENGTH_SHORT).show();
                            takePhotoButton.setEnabled(true);
                        });
                    }
                }
        );
    }




    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                PreviewView viewFinder = findViewById(R.id.viewFinder);
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                // 选择后置摄像头并创建ImageCapture实例
                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                imageCapture = new ImageCapture.Builder().build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(PhotoActivity.this, cameraSelector, preview, imageCapture);
            } catch (Exception e) {
                Log.e("PhotoActivity", "Failed to bind use cases", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }


    private boolean allPermissionsGranted() {
        for (String permission : CameraActivity.Configuration.REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
    private File getOutputDirectory() {
        // 获取Pictures目录（公共存储）
        File mediaDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "CourseProject");

        if (!mediaDir.exists()) {
            mediaDir.mkdirs();  // 如果目录不存在，创建它
        }
        return mediaDir;
    }



    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        // 关闭 WebSocket 连接
        if (webSocket != null) {
            webSocket.close(1000, "Activity destroyed");
        }
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
        }
    }

    static class Configuration {
        public static final String TAG = "CameraxBasic";
        public static final String FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS";
        public static final int REQUEST_CODE_PERMISSIONS = 10;
        public static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CameraActivity.Configuration.REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {// 申请权限通过
                startCamera();
            } else {// 申请权限失败
                Toast.makeText(this, "用户拒绝授予权限！", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
}
