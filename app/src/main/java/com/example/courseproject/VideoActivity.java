package com.example.courseproject;

import android.Manifest;
import android.annotation.SuppressLint;

import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;


public class VideoActivity extends AppCompatActivity  {
    private static final int REQUEST_PERMISSIONS = 1;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Button btnStream;
    private Button back;
    private TextView statusText;
    private MjpegHttpServer mjpegHttpServer;
    private PreviewView surfaceView;
    private String destinationIP;
    private String currentStudentId;   // 成员变量
    @SuppressLint("UnsafeOptInUsageError")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.forvideo);
        destinationIP="10.243.105.168";
        surfaceView = findViewById(R.id.viewFinder);
        btnStream = findViewById(R.id.startVideoButton);
        statusText = findViewById(R.id.VideoStatusText);
        statusText.setText("                 ");
        back=findViewById(R.id.cameraBack_video);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });
        // 检查并请求权限
        requestPermissionsIfNeeded();
        btnStream.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAttendance();
            }
        });
    }
    private void startAttendance() {
            String  ip=NetworkUtil.getDeviceIpAddress(this);
        Button takePhotoButton = findViewById(R.id.startVideoButton);
        takePhotoButton.setEnabled(false);
 //调用 POST
        String url = "http://"+ip+":8080/video";
        String destination="http://"+destinationIP+":1521";
        JSONObject json = new JSONObject();
        try {
            json.put("source",url);
        } catch (JSONException ignored) {}
        MjpegHttpServer.HttpHelper.sendPost(
                destination+"/recognize/?source="+url,

                startListener
        );
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
        //Toast.makeText(this, "IP is"+ip, Toast.LENGTH_SHORT).show();
    }

    // 申请权限检测成功后调用：
    @androidx.camera.core.ExperimentalGetImage
    @SuppressLint("UnsafeOptInUsageError")
    private void startCamera() throws IOException {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(this).get();

                // Preview binding
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(surfaceView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor(), imageProxy -> {
                    Image img = imageProxy.getImage();
                    if (img != null && img.getFormat() == ImageFormat.YUV_420_888) {
                        // 取出这帧的旋转角度
                        int rotation = imageProxy.getImageInfo().getRotationDegrees();
                        // 调用带角度的 imageToJpeg
                        byte[] jpeg = imageToJpeg(img, rotation);
                        if (jpeg != null && mjpegHttpServer != null) {
                            mjpegHttpServer.updateFrame(jpeg);
                        }
                    }
                    imageProxy.close();
                });


                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));


    }
    @SuppressLint("UnsafeOptInUsageError")
    private void requestPermissionsIfNeeded() {
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.ACCESS_WIFI_STATE,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    }, REQUEST_PERMISSIONS);
        } else {
            try {
                startCamera();
                startHttpServer();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
// 在 onCreate 方法里权限检查通过后调用此函数替代 initRtsp()

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }


    // 采集YUV420转NV21
    private byte[] yuv420ToNv21(Image image) {
        Image.Plane[] planes = image.getPlanes();
        int width  = image.getWidth();
        int height = image.getHeight();
        int ySize  = width * height;
        int uvSize = width * height / 4;
        byte[] nv21 = new byte[ySize + uvSize * 2];

        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        int yRowStride    = planes[0].getRowStride();
        int uvRowStride   = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        // 1) 拷贝 Y 平面
        int pos = 0;
        for (int row = 0; row < height; row++) {
            int yRowStart = row * yRowStride;
            for (int col = 0; col < width; col++) {
                nv21[pos++] = yBuffer.get(yRowStart + col);
            }
        }

        // 2) 拷贝交错的 VU 平面 (NV21 格式要求先 V 后 U)
        int uvPos = ySize;
        for (int row = 0; row < height / 2; row++) {
            int uRowStart = row * uvRowStride;
            int vRowStart = row * planes[2].getRowStride();
            for (int col = 0; col < width / 2; col++) {
                nv21[uvPos++] = vBuffer.get(vRowStart + col * planes[2].getPixelStride());
                nv21[uvPos++] = uBuffer.get(uRowStart + col * uvPixelStride);
            }
        }

        return nv21;
    }



    /**
     * 最终调用：把 Image 直接转成 JPEG，用于 HTTP 发送
     */
    /**
     * 把 Image 转成 JPEG，并按 rotationDegrees 旋转到正确方向
     */
    private byte[] imageToJpeg(Image image, int rotationDegrees) {
        // 1) YUV->NV21
        byte[] nv21 = yuv420ToNv21(image);

        // 2) NV21->JPEG bytes
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21,
                image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        yuv.compressToJpeg(
                new Rect(0, 0, image.getWidth(), image.getHeight()),
                80,
                baos
        );
        byte[] jpegBytes = baos.toByteArray();

        // 3) 解码成 Bitmap
        Bitmap bmp = BitmapFactory.decodeByteArray(
                jpegBytes, 0, jpegBytes.length
        );

        // 4) 创建旋转矩阵并旋转
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        Bitmap rotated = Bitmap.createBitmap(
                bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true
        );
        bmp.recycle();

        // 5) 再次压缩成 JPEG
        ByteArrayOutputStream rotatedBaos = new ByteArrayOutputStream();
        rotated.compress(Bitmap.CompressFormat.JPEG, 80, rotatedBaos);
        byte[] out = rotatedBaos.toByteArray();
        rotated.recycle();
        return out;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mjpegHttpServer != null) {
            mjpegHttpServer.stop();
            mjpegHttpServer = null;
        }
    }

    // 先实现一个 listener
    ResponseListener startListener = new ResponseListener() {
        @Override
        public void onSuccess(int code, String body) {
            //Log.d("http", "start code=" + code + ", rawBody=" + body);
            runOnUiThread(() -> {
                try {
                    JSONObject resp = new JSONObject(body);
                    String taskID = resp.getString("task_id");
                    statusText.setText("任务已启动，taskID=" + taskID);
                   // System.out.println("*********************Task ID is"+taskID);
                    pollJobStatus(taskID);
                } catch (JSONException e) {
                    e.printStackTrace();
                    statusText.setText("启动失败，无法解析 taskId");
                }
            });
        }
        @Override
        public void onError(Exception e) {
            statusText.setText("网络错误: " + e.getMessage());
        }
    };

    // 2) 轮询状态的监听器，只关心 status
    private void pollJobStatus(String task_id) {
        String url = "http://"+destinationIP+":1521/result/"+task_id;//destination
        Map<String,String> params = Collections.singletonMap("task_id", task_id);

        MjpegHttpServer.HttpHelper.sendGet(
                url,
                new ResponseListener() {
                    @Override
                    public void onSuccess(int code, String body) {
                       // Log.d("Http", "poll code=" + code + ", rawBody=" + body);
                        runOnUiThread(() -> {
                            try {
                                JSONObject resp = new JSONObject(body);
//                                JSONObject sent=resp.getJSONObject("sent");
//                                String studentId = sent.getString("studentId");
                                //System.out.println(resp.get("sent"));
                                String studentId=new String();

                                //****返回json开始******
                                String destination="http://"+destinationIP+":8000";

                                //****返回json结束******
                                if(resp.has("sent")){
                                    JSONObject sent=resp.getJSONObject("sent");
                                    studentId = sent.getString("studentId");
                                    System.out.println(resp.get("sent"));
                                }
                                String status = resp.getString("status");
                                System.out.println("Status is "+status);
                                if("done".equals(status)){
                                  
                                    statusText.setText("              完成:"+studentId);
                                    currentStudentId=studentId;
                                    Button takePhotoButton = findViewById(R.id.startVideoButton);
                                    takePhotoButton.setEnabled(true);
//                                    runOnUiThread(new Runnable() {
//                                        @Override
//                                        public void run() {
//                                            AlertDialog.Builder builder = new AlertDialog.Builder(VideoActivity.this);
//                                            builder.setMessage("当前学号："+currentStudentId)
//                                                    .setCancelable(false)  // 禁止点击外部关闭
//                                                    .setPositiveButton("确认", new DialogInterface.OnClickListener() {
//                                                        @Override
//                                                        public void onClick(DialogInterface dialog, int id) {
//                                                            // 点击确认时执行的逻辑
//                                                            MjpegHttpServer.HttpHelper.sendGet(
//                                                                    "http://"+destinationIP+":1521/face/recognize/result/"+currentStudentId,
//                                                                    new ResponseListener() {
//                                                                        @Override
//                                                                        public void onSuccess(int code, String body) {
//                                                                            System.out.println(code);
//                                                                            System.out.println(body);
//                                                                        }
//
//                                                                        @Override
//                                                                        public void onError(Exception e) {
//                                                                            System.out.println(e);
//                                                                        }
//                                                                    }
//
//                                                            );
//
//                                                            Toast.makeText(VideoActivity.this, "id is"+currentStudentId, Toast.LENGTH_SHORT).show();
//                                                        }
//                                                    })
//                                                    .setNegativeButton("取消", new DialogInterface.OnClickListener() {
//                                                        @Override
//                                                        public void onClick(DialogInterface dialog, int id) {
//                                                            // 点击取消时的操作（如果有需要）
//                                                            Toast.makeText(VideoActivity.this, "已取消", Toast.LENGTH_SHORT).show();
//                                                        }
//                                                    });
//
//                                            // 显示对话框
//                                            AlertDialog alert = builder.create();
//                                            alert.show();
//                                        }
//                                    });
                                }else if("pending".equals(status)){
                                    statusText.setText("             处理中…");
                                    mainHandler.postDelayed(() -> pollJobStatus(task_id), 5000);
                                }else if("none".equals(status)){
                                    statusText.setText("         还未开始考勤！");
                                }else{
                                    statusText.setText("         处理错误，请重试");
                                }

                            } catch (JSONException e) {
                                e.printStackTrace();
                                statusText.setText("解析返回失败");
                            }
                        });
                    }
                    @Override
                    public void onError(Exception e) {
                        e.printStackTrace();
                        runOnUiThread(() ->
                                statusText.setText("网络错误: " + e.getMessage())
                        );
                    }
                }
        );
    }

}
