package com.example.gesturectrl;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import static android.view.View.GONE;

public class DeviceListActivity extends Activity {
  // 调试用
  private static final String TAG = "DeviceListActivity";
  private static final boolean D = true;

  // 返回时数据标签
  public static String EXTRA_DEVICE_ADDRESS = "设备地址";

  // 成员域
  private BluetoothAdapter mBtAdapter;
  private ArrayAdapter<String> mPairedDevicesArrayAdapter;
  private ArrayAdapter<String> mNewDevicesArrayAdapter;
  // 查找到设备和搜索完成action监听器
  private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      String action = intent.getAction();
      Log.e(TAG, action);
      // 查找到设备action
      if (BluetoothDevice.ACTION_FOUND.equals(action)) {// 得到蓝牙设备
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        // 如果是已配对的则略过，已得到显示，其余的在添加到列表中进行显示
        if (device.getBondState() != BluetoothDevice.BOND_BONDED && //未绑定
                mNewDevicesArrayAdapter.getPosition(device.getName() + "\n" + device.getAddress()) == -1) {//避免重复添加
          mNewDevicesArrayAdapter.add(device.getName() + "\n" + device.getAddress());
        }
        if (device.getBondState() == BluetoothDevice.BOND_BONDED &&  //已绑定
                mPairedDevicesArrayAdapter.getPosition(device.getName() + "\n" + device.getAddress()) == -1) {//避免重复添加
          mPairedDevicesArrayAdapter.add(device.getName() + "\n" + device.getAddress());
        }
        Log.e(TAG, device.getName() + "---" + device.getAddress());
        // 搜索完成action
      } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
        setProgressBarIndeterminateVisibility(false);
        setTitle("选择要连接的设备");
        if (mNewDevicesArrayAdapter.getCount() == 0) {
          String noDevices = "没有找到新设备";
          mNewDevicesArrayAdapter.add(noDevices);
        }
      }
    }
  };
  // 选择设备响应函数
  private OnItemClickListener mDeviceClickListener = new OnItemClickListener() {
    public void onItemClick(AdapterView<?> av, View v, int arg2, long arg3) {
      // 准备连接设备，关闭服务查找
      mBtAdapter.cancelDiscovery();

      // 得到mac地址
      String info = ((TextView) v).getText().toString();
      String address = info.substring(info.length() - 17);

      // 设置返回数据
      Intent intent = new Intent();
      intent.putExtra(EXTRA_DEVICE_ADDRESS, address);

      // 设置返回值并结束程序
      setResult(Activity.RESULT_OK, intent);
      finish();
    }
  };

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // 创建并显示窗口
    requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);  //设置窗口显示模式为窗口方式
    setContentView(R.layout.device_list);

    // 设定默认返回值为取消
    setResult(Activity.RESULT_CANCELED);

    // 设定扫描按键响应
    Button scanButton = findViewById(R.id.button_scan);
    scanButton.setOnClickListener(v -> {
      doDiscovery();
      v.setVisibility(GONE);
    });

    // 初使化设备存储数组
    mPairedDevicesArrayAdapter = new ArrayAdapter<>(this, R.layout.device_name);
    mNewDevicesArrayAdapter = new ArrayAdapter<>(this, R.layout.device_name);

    // 设置已配队设备列表
    ListView pairedListView = findViewById(R.id.paired_devices);
    pairedListView.setAdapter(mPairedDevicesArrayAdapter);
    pairedListView.setOnItemClickListener(mDeviceClickListener);

    // 设置新查找设备列表
    ListView newDevicesListView = findViewById(R.id.new_devices);
    newDevicesListView.setAdapter(mNewDevicesArrayAdapter);
    newDevicesListView.setOnItemClickListener(mDeviceClickListener);

    // 注册接收查找到设备action接收器
    //IntentFilter filter = ;
    this.registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));

    // 注册查找结束action接收器
    //filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
    this.registerReceiver(mReceiver, new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED));

    this.registerReceiver(mReceiver, new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_STARTED));

    // 得到本地蓝牙句柄
    mBtAdapter = BluetoothAdapter.getDefaultAdapter();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();

    // 关闭服务查找
    if (mBtAdapter != null) {
      mBtAdapter.cancelDiscovery();
    }

    // 注销action接收器
    this.unregisterReceiver(mReceiver);
  }

  public void OnCancel(View v) {
    finish();
  }

  /**
   * 开始服务和设备查找
   */
  private void doDiscovery() {
    //已绑定设备添加到列表
    for (BluetoothDevice pairedDevice : mBtAdapter.getBondedDevices()) {
      mPairedDevicesArrayAdapter.add(pairedDevice.getName() + "\n" + pairedDevice.getAddress());
    }
    if (D) Log.d(TAG, "doDiscovery()");

    // 在窗口显示查找中信息
    setProgressBarIndeterminateVisibility(true);
    setTitle("查找设备中...");

    // 显示其它设备（未配对设备）列表
    findViewById(R.id.title_new_devices).setVisibility(View.VISIBLE);

    // 关闭再进行的服务查找
    if (mBtAdapter.isDiscovering()) {
      mBtAdapter.cancelDiscovery();
    }

    mBtAdapter.startDiscovery();
    Log.e(TAG, "startDiscovery()");
  }
}
