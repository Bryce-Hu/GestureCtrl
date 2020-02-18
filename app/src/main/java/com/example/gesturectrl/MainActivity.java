package com.example.gesturectrl;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.mediapipe.components.CameraHelper;
import com.google.mediapipe.components.CameraXPreviewHelper;
import com.google.mediapipe.components.ExternalTextureConverter;
import com.google.mediapipe.components.FrameProcessor;
import com.google.mediapipe.components.PermissionHelper;
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark;
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList;
import com.google.mediapipe.framework.AndroidAssetUtil;
import com.google.mediapipe.framework.PacketGetter;
import com.google.mediapipe.glutil.EglManager;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.util.Objects;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
  private static final String TAG = "MainActivity";
  private static final String BINARY_GRAPH_NAME = "handtrackinggpu.binarypb";
  private static final String INPUT_VIDEO_STREAM_NAME = "input_video";
  private static final String OUTPUT_VIDEO_STREAM_NAME = "output_video";
  private static final String OUTPUT_HAND_PRESENCE_STREAM_NAME = "hand_presence";
  private static final String OUTPUT_LANDMARKS_STREAM_NAME = "hand_landmarks";
  private static final CameraHelper.CameraFacing CAMERA_FACING = CameraHelper.CameraFacing.FRONT;
  // 垂直翻转相机预览帧，然后将它们发送到FrameProcessor中以在MediaPipe图形中进行处理，
  // 并在处理后的帧显示时将它们翻转回来。这是必需的，因为OpenGL表示图像，假设图像原点
  // 在左下角，而MediaPipe通常假设图像原点在左上角。
  private static final boolean FLIP_FRAMES_VERTICALLY = true;
  /*---------------------------------------蓝牙---------------------------------------------------*/
  private final static int REQUEST_CONNECT_DEVICE = 1;    //宏定义查询设备句柄
  private final static String MY_UUID = "00001101-0000-1000-8000-00805F9B34FB";   //SPP服务UUID号
  private final static String noneHandDataString = //没有手掌数据
          "[0]:(0.0000,0.0000)\n" +
                  "[1]:(0.0000,0.0000)\n" +
                  "[2]:(0.0000,0.0000)\n" +
                  "[3]:(0.0000,0.0000)\n" +
                  "[4]:(0.0000,0.0000)\n" +
                  "[5]:(0.0000,0.0000)\n" +
                  "[6]:(0.0000,0.0000)\n" +
                  "[7]:(0.0000,0.0000)\n" +
                  "[8]:(0.0000,0.0000)\n" +
                  "[9]:(0.0000,0.0000)\n" +
                  "[10]:(0.0000,0.0000)\n" +
                  "[11]:(0.0000,0.0000)\n" +
                  "[12]:(0.0000,0.0000)\n" +
                  "[13]:(0.0000,0.0000)\n" +
                  "[14]:(0.0000,0.0000)\n" +
                  "[15]:(0.0000,0.0000)\n" +
                  "[16]:(0.0000,0.0000)\n" +
                  "[17]:(0.0000,0.0000)\n" +
                  "[18]:(0.0000,0.0000)\n" +
                  "[19]:(0.0000,0.0000)\n" +
                  "[20]:(0.0000,0.0000)\n";
  private static String fLandmarkString = "";  //发送数据缓存
  private static String fLandmarkString_Last = "";  //上次发送数据缓存

  static {
    // 加载必要的本地库
    System.loadLibrary("mediapipe_jni");
    System.loadLibrary("opencv_java3");
  }

  final int MY_PERMISSION_ACCESS_COARSE_LOCATION = 11;
  final int MY_PERMISSION_ACCESS_FINE_LOCATION = 12;
  BluetoothDevice _device = null;     //蓝牙设备
  BluetoothSocket _socket = null;      //蓝牙通信socket
  volatile boolean bRun = true;
  boolean bThread = false;
  //发送数据线程
  Thread sendThread = new Thread() {
    public void run() {
      while (_socket != null) {
        if (!fLandmarkString.equals(noneHandDataString)) {  //检测到了手掌
          if (!fLandmarkString.equals(fLandmarkString_Last)) {//检测到了数据变化
            sendHandData();
            fLandmarkString_Last = fLandmarkString; //缓存上次发送数据
          }
        }
        try {
          Thread.sleep(200);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }
  };
  // 访问相机预览帧。
  private SurfaceTexture previewFrameTexture;
  // 显示由 MediaPipe 图形处理的相机预览帧。
  private SurfaceView previewDisplayView;
  // 创建和管理一个 EGL 管理器，是 OpenGL ES 渲染 API 和本地窗口系统之间的一个中间接口层。
  private EglManager eglManager;
  // 将相机预览帧发送到 MediaPipe 图形中进行处理，并将处理后的帧显示到 Surface 上。
  private FrameProcessor processor;
  // 将相机的 GL_TEXTURE_EXTERNAL_OES 纹理转换为 FrameProcessor 和底层 MediaPipe 图形使用的规则纹理。
  private ExternalTextureConverter converter;
  // CameraX 是新增库。利用该库，可以更轻松地向应用添加相机功能。
  private CameraXPreviewHelper cameraHelper;
  private InputStream is;    //输入流，用来接收蓝牙数据
  private String smsg = "";    //显示用数据缓存
  //消息处理队列
  @SuppressLint("HandlerLeak")
  Handler handler = new Handler() {
    public void handleMessage(@NonNull Message msg) {
      super.handleMessage(msg);
      TextView gesture = findViewById(R.id.gesture);
      gesture.setText(smsg);   //显示数据
      smsg = "";
    }
  };
  //接收数据线程
  Thread readThread = new Thread() {
    public void run() {
      int i, n, num;
      byte[] buffer = new byte[1024];
      byte[] buffer_new = new byte[1024];
      bRun = true;
      //接收线程
      while (true) {
        try {
          while (0 == is.available()) {
            do {
              if (bRun) break;
            } while (true);
          }
          do {//短时间没有数据才跳出进行显示
            if (!bThread) return;//跳出循环
            num = is.read(buffer);         //读入数据
            n = 0;
            for (i = 0; i < num; i++) {
              if ((buffer[i] == 0x0d) && (buffer[i + 1] == 0x0a)) {
                buffer_new[n] = 0x0a;
                i++;
              } else {
                buffer_new[n] = buffer[i];
              }
              n++;
            }
            String str = new String(buffer_new, 0, n);
            smsg = smsg + str;   //写入接收缓存
            try {
              Thread.sleep(10);
            } catch (InterruptedException e) {
              e.printStackTrace();
            }
          } while (is.available() != 0);
          handler.sendMessage(handler.obtainMessage());//发送显示消息，进行显示刷新
        } catch (IOException ignored) {
        }
      }
    }
  };
  //获取蓝牙适配器
  private BluetoothAdapter _bluetooth = BluetoothAdapter.getDefaultAdapter();

  // 字符串格式化，输出日志，用于调试
  private static String getLandmarksDebugString(NormalizedLandmarkList landmarks) {
    int landmarkIndex = 0;
    String landmarksString = "";
    fLandmarkString = "";
    DecimalFormat df = new DecimalFormat("#0.0000"); //保留四位小数
    for (NormalizedLandmark landmark : landmarks.getLandmarkList()) {
      if (landmarkIndex < 10) {
        landmarksString +=
                "Landmark[0"
                        + landmarkIndex
                        + "]: ("
                        + df.format(landmark.getX())
                        + ", "
                        + df.format(landmark.getY())
                        + ")\n";
      } else {
        landmarksString +=
                "Landmark["
                        + landmarkIndex
                        + "]: ("
                        + df.format(landmark.getX())
                        + ", "
                        + df.format(landmark.getY())
                        + ")\n";
      }
      fLandmarkString += "(" + df.format(landmark.getX()) + "," + df.format(landmark.getY()) + ")";
      ++landmarkIndex;
    }
    fLandmarkString = "#" + fLandmarkString + "!\n";
    return landmarksString;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState); //内置参数初始化
    setContentView(R.layout.activity_main); //显示布局界面

    //动态申请蓝牙权限，解决兼容性问题，6.0以上使用新的API
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {   //如果 API level 是大于等于 23(Android 6.0) 时
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this,
                new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION}, MY_PERMISSION_ACCESS_COARSE_LOCATION);
      }
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this,
                new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, MY_PERMISSION_ACCESS_FINE_LOCATION);
      }
    }
    // 创建线程，处于就绪状态，用于打开蓝牙
    new Thread() {
      public void run() {
        if (!_bluetooth.isEnabled()) _bluetooth.enable();
      }
    }.start();
    //监听连接按键
    ImageView bleConnect = findViewById(R.id.btnBLE);
    bleConnect.setOnClickListener(v -> bleConnecting());
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (PermissionHelper.cameraPermissionsGranted(this)) {  //如果已获取相机权限
      startCamera();    //打开相机
    }
    gestureTracking();//手势跟踪
    converter = new ExternalTextureConverter(eglManager.getContext());  //创建图形外部转换器
    converter.setFlipY(FLIP_FRAMES_VERTICALLY); //垂直翻转帧
    converter.setConsumer(processor);   //指定处理图形
  }

  @Override
  protected void onPause() {
    super.onPause();
    converter.close();  //停止转换
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    converter.close();  //停止转换
    if (_socket != null)  //关闭连接socket
      try {
        _socket.close();
      } catch (IOException ignored) {
      }
//    _bluetooth.disable();  //关闭蓝牙服务
  }

  @Override // 权限申请结果回调函数
  public void onRequestPermissionsResult(
          int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    PermissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults);
  }

  // 裁剪预览图形
  private void setupPreviewDisplayView() {
    previewDisplayView.setVisibility(View.GONE);    //设置视图不可见，不占布局空间
    ViewGroup viewGroup = findViewById(R.id.preview_display_layout);    //获取控件对象id
    viewGroup.addView(previewDisplayView);  //添加视图

    previewDisplayView
            .getHolder()
            .addCallback(
                    new SurfaceHolder.Callback() {
                      @Override
                      public void surfaceCreated(SurfaceHolder holder) {
                        processor.getVideoSurfaceOutput().setSurface(holder.getSurface());
                      }

                      @Override
                      public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                        // (重新)计算相机预览显示的理想尺寸(相机预览帧渲染到的区域，
                        // 可能通过缩放和旋转)基于包含显示的表面视图的尺寸。
                        Size viewSize = new Size(width, height);
                        Size displaySize = cameraHelper.computeDisplaySizeFromViewSize(viewSize);

                        // 将转换器连接到相机预览帧作为其输入(通过previewFrameTexture)，
                        // 并将输出宽度和高度配置为计算出的显示大小。
                        converter.setSurfaceTextureAndAttachToGLContext(
                                previewFrameTexture, displaySize.getWidth(), displaySize.getHeight());
                      }

                      @Override
                      public void surfaceDestroyed(SurfaceHolder holder) {
                        processor.getVideoSurfaceOutput().setSurface(null);
                      }
                    });
  }

  // 打开相机
  private void startCamera() {
    cameraHelper = new CameraXPreviewHelper();
    cameraHelper.setOnCameraStartedListener(
            surfaceTexture -> {
              previewFrameTexture = surfaceTexture;
              // 使显示视图可见，以开始显示预览。这就触发了SurfaceHolder。回调添加到previewDisplayView。
              previewDisplayView.setVisibility(View.VISIBLE);
            });
    cameraHelper.startCamera(this, CAMERA_FACING, /*surfaceTexture=*/ null);
  }

  //手势跟踪
  private void gestureTracking() {
    previewDisplayView = new SurfaceView(this); //创建 SurfaceView 界面，主要用作视频输出
    setupPreviewDisplayView();  //裁剪预览图形

    // 初始化 asset 管理器，以便 MediaPipe 本地库可以访问应用的 assets 文件夹内容,例如 binary 图
    AndroidAssetUtil.initializeNativeAssetManager(this);

    //创建 EGL 管理器，作为 OpenGL 和原生窗口系统之间的桥梁，控制 GPU 渲染视频
    eglManager = new EglManager(null);
    processor = new FrameProcessor(this,    //创建视频帧处理
            eglManager.getNativeContext(),
            BINARY_GRAPH_NAME,
            INPUT_VIDEO_STREAM_NAME,
            OUTPUT_VIDEO_STREAM_NAME);
    processor.getVideoSurfaceOutput().setFlipY(FLIP_FRAMES_VERTICALLY); //垂直翻转帧

    processor.addPacketCallback(    //添加'手掌存在'数据流回调
            OUTPUT_HAND_PRESENCE_STREAM_NAME,
            (packet) -> {
              boolean handPresence = PacketGetter.getBool(packet);
              if (!handPresence) {
                Log.d(TAG, "[TS:" + packet.getTimestamp() + "] Hand presence is false, no hands detected.");
              }
            });

    processor.addPacketCallback(    //添加'手掌标记'数据流回调
            OUTPUT_LANDMARKS_STREAM_NAME,
            (packet) -> {
              byte[] landmarksRaw = PacketGetter.getProtoBytes(packet);
              try {
                NormalizedLandmarkList landmarks = NormalizedLandmarkList.parseFrom(landmarksRaw);
                if (landmarks == null) {
                  Log.d(TAG, "[TS:" + packet.getTimestamp() + "] No hand landmarks.");
                  return;
                }
                // Note: If hand_presence is false, these landmarks are useless.
                Log.d(TAG, "[TS:" + packet.getTimestamp() + "] #Landmarks for hand: " + landmarks.getLandmarkCount());
                Log.d(TAG, getLandmarksDebugString(landmarks)); //输出XYZ数据

                TextView landmarksData = findViewById(R.id.landmarksData);
                landmarksData.setText(getLandmarksDebugString(landmarks));  //屏幕显示标点数据
                //gesture.setText("Gesture");  //屏幕显示手势
              } catch (InvalidProtocolBufferException e) {
                Log.e(TAG, "Couldn't Exception received - " + e);
              }
            });

    PermissionHelper.checkAndRequestCameraPermissions(this);    //检查和申请相机权限
  }

  //连接蓝牙
  private void bleConnecting() {
    if (!_bluetooth.isEnabled()) {  //如果蓝牙服务不可用则提示
      Toast.makeText(this, " 请打开蓝牙！", Toast.LENGTH_LONG).show();
      return;
    }
    //如未连接设备则打开DeviceListActivity进行设备搜索
    if (_socket == null) {
      Intent serverIntent = new Intent(this, DeviceListActivity.class); //跳转程序设置
      startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);  //设置返回宏定义
    } else { //关闭连接socket
      try {
        bRun = false;
        Thread.sleep(2000);
        is.close();
        _socket.close();
        _socket = null;
      } catch (IOException ignored) {
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

  //接收活动结果，响应startActivityForResult()
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQUEST_CONNECT_DEVICE) {// 响应返回结果
      if (resultCode == Activity.RESULT_OK) {   //连接成功，由DeviceListActivity设置返回
        // MAC地址，由DeviceListActivity设置返回
        String address = Objects.requireNonNull(data.getExtras())
                .getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
        // 得到蓝牙设备句柄
        _device = _bluetooth.getRemoteDevice(address);
        // 用服务号得到socket
        try {
          _socket = _device.createRfcommSocketToServiceRecord(UUID.fromString(MY_UUID));
        } catch (IOException e) {
          Toast.makeText(this, "创建Socket失败！", Toast.LENGTH_SHORT).show();
        }
        //socket通信
        try {
          _socket.connect();
          Toast.makeText(this, "连接" + _device.getName() + "成功！", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
          try {
            Toast.makeText(this, "Socket通信失败！", Toast.LENGTH_SHORT).show();
            _socket.close();
            _socket = null;
          } catch (IOException ee) {
            Toast.makeText(this, "Socket关闭失败！", Toast.LENGTH_SHORT).show();
          }
          return;
        }

        //打开接收线程
        try {
          is = _socket.getInputStream();   //得到蓝牙数据输入流
        } catch (IOException e) {
          Toast.makeText(this, "接收数据失败！", Toast.LENGTH_SHORT).show();
          return;
        }
        if (!bThread) {
          sendThread.start();
          readThread.start();
          bThread = true;
        } else {
          bRun = true;
        }
      }
    }
  }

  //发送数据
  private void sendHandData() {
    int i;
    int n = 0;
    try {
      OutputStream os = _socket.getOutputStream();   //蓝牙连接输出流
      byte[] bos = fLandmarkString.getBytes();
      /*-***手机中的换行0x0a，变为回车换行0x0d，0x0a***-*/
      for (i = 0; i < bos.length; i++) {
        if (bos[i] == 0x0a) n++;
      }
      byte[] bos_new = new byte[bos.length + n];
      n = 0;
      for (i = 0; i < bos.length; i++) { //手机中换行为0a,将其改为0d 0a后再发送
        if (bos[i] == 0x0a) {
          bos_new[n] = 0x0d;
          n++;
          bos_new[n] = 0x0a;
        } else {
          bos_new[n] = bos[i];
        }
        n++;
      }
      /*-**********************************************-*/
      os.write(bos_new);
    } catch (IOException ignored) {
    }
  }
}
