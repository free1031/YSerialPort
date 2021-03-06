package com.yujing.yserialport;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.serialport.SerialPort;
import android.serialport.SerialPortFinder;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 串口工具类，调用的此类的activity必须在onDestroy调用onDestroy方法
 * 默认50ms读取超时，读取长数据请设置读取长度和超时时间。
 * 读取未知长度，请增大读取长度，并且增加组包时间差，组包时间差要小于读取超时时间。
 *
 * @author yujing 2020年8月11日10:27:58
 */
/*
使用方法：
YSerialPort ySerialPort = new YSerialPort(this);
//设置串口,设置波特率,如果设置了默认可以不用设置
ySerialPort.setDevice("/dev/ttyS4", "9600");
//设置数据监听
ySerialPort.addDataListener(new YSerialPort.DataListener() {
    @Override
    public void onDataReceived(String hexString, byte[] bytes, int size) {
        //结果回调:haxString
        //结果回调:bytes
        //结果回调:size
    }
});

//设置自动组包，每次组包时长为40毫秒，如果40毫秒读取不到数据则返回结果
ySerialPort.setAutoPackage(true);
//ySerialPort.setPackageTime(40);

//或者,设置非自动组包，读取长度1000，超时时间为500毫秒。如果读取到1000立即返回，否则直到读取到超时为止
//ySerialPort.setAutoPackage(false);
//ySerialPort.setLengthAndTimeout(1000,500);

//启动
ySerialPort.start();

//发送文字
ySerialPort.send("你好".getBytes(Charset.forName("GB18030")));

//退出页面时候注销
@Override
protected void onDestroy() {
    super.onDestroy();
    ySerialPort.onDestroy();
}
 */
@SuppressWarnings("unused")
public class YSerialPort {
    private static String TAG = "YSerialPort";
    private OutputStream outputStream;
    private InputStream inputStream;
    private final Handler handler = new Handler();
    private Activity activity;
    private String device;//串口
    private String baudRate;//波特率
    private static final String DEVICE = "DEVICE";
    private static final String BAUD_RATE = "BAUD_RATE";
    private static final String SERIAL_PORT = "SERIAL_PORT";
    private static final String[] BAUD_RATE_LIST = new String[]{"50", "75", "110", "134", "150", "200", "300", "600", "1200", "1800", "2400", "4800", "9600", "19200", "38400", "57600", "115200", "230400", "460800", "500000", "576000", "921600", "1000000", "1152000", "1500000", "2000000", "2500000", "3000000", "3500000", "4000000"};
    private boolean autoPackage = true;//自动组包
    private int packageTime = -1;//组包时间差，毫秒
    private int readTimeout = -1;//读取超时时间
    private int readLength = -1;//读取长度
    private YReadInputStream readInputStream;
    //串口类
    private SerialPort serialPort;
    //串口查找列表类
    private static final SerialPortFinder mSerialPortFinder = new SerialPortFinder();

    //获取串口查找列表类
    public static SerialPortFinder getSerialPortFinder() {
        return mSerialPortFinder;
    }

    public static String[] getDevices() {
        return getSerialPortFinder().getAllDevicesPath();
    }

    //获取波特率列表
    public static String[] getBaudRates() {
        return BAUD_RATE_LIST;
    }

    //回调结果
    private final List<DataListener> dataListeners = new ArrayList<>();
    //错误回调
    private ErrorListener errorListener;
    //单例模式，全局只有一个串口通信使用
    private static YSerialPort instance;

    /**
     * 单例模式，调用此方法前必须先调用getInstance(String ip, int port)
     *
     * @param activity activity
     * @return YSerialPort
     */
    public static synchronized YSerialPort getInstance(Activity activity) {
        if (instance == null) {
            synchronized (YSerialPort.class) {
                if (instance == null) {
                    instance = new YSerialPort(activity);
                }
            }
        }
        return instance;
    }

    /**
     * 单例模式
     *
     * @param activity activity
     * @param device   串口
     * @param baudRate 波特率
     * @return YSerialPort
     */
    public static YSerialPort getInstance(Activity activity, String device, String baudRate) {
        if (instance == null) {
            synchronized (YSerialPort.class) {
                if (instance == null) {
                    instance = new YSerialPort(activity, device, baudRate);
                }
            }
        }
        instance.setActivity(activity);
        instance.setDevice(device, baudRate);
        return instance;
    }

    /**
     * 构造函数
     *
     * @param activity activity
     */
    public YSerialPort(Activity activity) {
        this.activity = activity;
    }

    /**
     * 构造函数
     *
     * @param activity activity
     * @param device   串口
     * @param baudRate 波特率
     */
    public YSerialPort(Activity activity, String device, String baudRate) {
        this.activity = activity;
        this.device = device;
        this.baudRate = baudRate;
    }

    /**
     * 开始读取串口
     */
    public void start() {
        if (readInputStream != null) {
            readInputStream.stop();
        }
        if (serialPort != null) {
            serialPort.close();
            serialPort = null;
        }
        try {
            serialPort = buildSerialPort();
            outputStream = serialPort.getOutputStream();
            inputStream = serialPort.getInputStream();
            readInputStream = new YReadInputStream(inputStream, bytes -> activity.runOnUiThread(() -> {
                for (DataListener item : dataListeners) {
                    item.onDataReceived(bytesToHexString(bytes), bytes, bytes.length);
                }
            }));
            readInputStream.setLengthAndTimeout(readLength, readTimeout);
            if (packageTime == -1) setPackageTimeDefault();//设置默认组包时间
            readInputStream.setAutoPackage(autoPackage);
            readInputStream.setPackageTime(packageTime);
            readInputStream.start();
        } catch (SecurityException e) {
            DisplayError("您对串行端口没有读/写权限。");
        } catch (IOException e) {
            DisplayError("由于未知原因，无法打开串行端口。");
        } catch (InvalidParameterException e) {
            DisplayError("请先配置你的串口。");
        }
    }

    /**
     * 重启
     */
    public void reStart() {
        stop();
        start();
    }

    /**
     * 重启
     *
     * @param device   串口
     * @param baudRate 波特率
     */
    public void reStart(String device, String baudRate) {
        setDevice(device, baudRate);
        stop();
        start();
    }

    /**
     * 构建SerialPort类
     *
     * @return SerialPort
     * @throws SecurityException         串行端口权限
     * @throws IOException               IO异常
     * @throws InvalidParameterException 未配置串口
     */
    public SerialPort buildSerialPort() throws SecurityException, IOException, InvalidParameterException {
        if (device == null || baudRate == null) {
            if (readDevice(activity) == null || readBaudRate(activity) == null || (readDevice(activity).length() == 0) || (readBaudRate(activity).length() == 0)) {
                throw new InvalidParameterException();
            }
            device = readDevice(activity);
            baudRate = readBaudRate(activity);
        }
        return SerialPort.newBuilder(new File(device), Integer.parseInt(baudRate)).build();
    }

    /**
     * 发送
     *
     * @param bytes 数据
     */
    public void send(byte[] bytes) {
        send(bytes, null);
    }

    /**
     * 发送
     *
     * @param bytes    数据
     * @param listener 状态，成功回调true，失败false
     */
    public void send(byte[] bytes, YListener<Boolean> listener) {
        send(bytes, listener, null);
    }

    /**
     * 发送
     *
     * @param bytes            数据
     * @param listener         状态，成功回调true，失败false
     * @param progressListener 进度监听，返回已经发送长度
     */
    public void send(final byte[] bytes, final YListener<Boolean> listener, final YListener<Integer> progressListener) {
        try {
            if (serialPort != null) outputStream = serialPort.getOutputStream();
            final int sendLength = 1024;//每次写入长度
            if (bytes.length > sendLength) {
                new Thread(() -> {
                    try {
                        int i = 0;//第几次写入
                        int count = 0;//统计已经发送长度
                        while (true) {
                            //剩余长度
                            int sy = bytes.length - (i * sendLength);
                            //如果剩余长度小于等于0，说明发送完成
                            if (sy <= 0) break;
                            //如果剩余长度大于每次写入长度，就写入对应长度，如果不大于就写入剩余长度
                            byte[] current = new byte[Math.min(sy, sendLength)];
                            //数组copy
                            System.arraycopy(bytes, i * sendLength, current, 0, current.length);
                            //写入
                            outputStream.write(current);
                            //统计已经发送长度
                            count += current.length;
                            //回调进度
                            if (progressListener != null) {
                                final int finalCount = count;
                                activity.runOnUiThread(() -> progressListener.value(finalCount));
                            }
                            i++;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "发送失败", e);
                        if (listener != null) activity.runOnUiThread(() -> listener.value(false));
                    }
                }).start();
            } else {
                outputStream.write(bytes);
                if (progressListener != null) {
                    activity.runOnUiThread(() -> progressListener.value(bytes.length));
                }
            }
            if (listener != null) activity.runOnUiThread(() -> listener.value(true));
        } catch (Exception e) {
            Log.e(TAG, "发送失败", e);
            if (listener != null) activity.runOnUiThread(() -> listener.value(false));
        }
    }

    //保存串口
    public static void saveDevice(Context context, String device) {
        SharedPreferences sp = context.getSharedPreferences(SERIAL_PORT, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(DEVICE, device);
        editor.apply();
    }

    //读取上面方法保存的串口
    public static String readDevice(Context context) {
        SharedPreferences sp = context.getSharedPreferences(SERIAL_PORT, Context.MODE_PRIVATE);
        return sp.getString(DEVICE, null);// null为默认值
    }

    //保存波特率
    public static void saveBaudRate(Context context, String device) {
        SharedPreferences sp = context.getSharedPreferences(SERIAL_PORT, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(BAUD_RATE, device);
        editor.apply();
    }

    //读取上面方法保存的波特率
    public static String readBaudRate(Context context) {
        SharedPreferences sp = context.getSharedPreferences(SERIAL_PORT, Context.MODE_PRIVATE);
        return sp.getString(BAUD_RATE, null);// null为默认值
    }

    /**
     * 添加回调函数
     *
     * @param dataListener 数据监听回调
     */
    public void addDataListener(DataListener dataListener) {
        if (!dataListeners.contains(dataListener))
            dataListeners.add(dataListener);
    }

    /**
     * 删除回调函数
     *
     * @param dataListener 数据监听回调
     */
    public void removeDataListener(DataListener dataListener) {
        dataListeners.remove(dataListener);
    }

    /**
     * 删除全部回调函数
     */
    public void clearDataListener() {
        dataListeners.clear();
    }

    /**
     * 设置串口和波特率
     *
     * @param device   串口
     * @param baudRate 波特率
     */
    public void setDevice(String device, String baudRate) {
        this.device = device;
        this.baudRate = baudRate;
    }

    /**
     * 获取当前串口
     *
     * @return 串口名
     */
    public String getDevice() {
        return device;
    }

    /**
     * 设置当前串口
     *
     * @param device 串口名
     */
    public void setDevice(String device) {
        this.device = device;
    }

    /**
     * 获取当前波特率
     *
     * @return 波特率
     */
    public String getBaudRate() {
        return baudRate;
    }

    /**
     * 设置当前波特率
     *
     * @param baudRate 波特率
     */
    public void setBaudRate(String baudRate) {
        this.baudRate = baudRate;
    }

    /**
     * 获取activity
     *
     * @return Activity
     */
    public Activity getActivity() {
        return activity;
    }

    /**
     * 设置activity
     *
     * @param activity activity
     */
    public void setActivity(Activity activity) {
        this.activity = activity;
    }

    /**
     * 设置错误回调
     *
     * @param errorListener errorListener
     */
    public void setErrorListener(ErrorListener errorListener) {
        this.errorListener = errorListener;
    }

    /**
     * 获取组包最小时间差
     *
     * @return 毫秒
     */
    public int getPackageTime() {
        return packageTime;
    }

    /**
     * 设置组包最小时间差  方法互斥 setReadTimeOutAndLength
     *
     * @param packageTime 组包最小时间差,毫秒
     */
    public void setPackageTime(int packageTime) {
        this.packageTime = packageTime;
        if (readInputStream != null) {
            readInputStream.setPackageTime(packageTime);
            setAutoPackage(true);
        }
    }

    /**
     * 获取SerialPort对象
     *
     * @return SerialPort
     */
    public SerialPort getSerialPort() {
        return serialPort;
    }

    /**
     * 获取输出流
     *
     * @return OutputStream
     */
    public OutputStream getOutputStream() {
        return serialPort.getOutputStream();
    }

    /**
     * 获取输入流
     *
     * @return InputStream
     */
    public InputStream getInputStream() {
        return serialPort.getInputStream();
    }

    private void setPackageTimeDefault() {
        if (baudRate != null) {
            int intBaudRate = Integer.parseInt(baudRate);
            packageTime = Math.round((5f / (intBaudRate / 115200f)) + 0.4999f);//向上取整
        }
    }

    /**
     * 设置读取超时时间和读取最小长度 方法互斥 setGroupPackageTime
     *
     * @param readTimeout 读取超时时间
     * @param readLength  读取最小长度
     */
    public void setLengthAndTimeout(int readLength, int readTimeout) {
        this.readTimeout = readTimeout;
        this.readLength = readLength;
        if (readInputStream != null) {
            readInputStream.setLengthAndTimeout(readLength, readTimeout);
            setAutoPackage(false);
        }
    }

    public boolean isAutoPackage() {
        return autoPackage;
    }

    public void setAutoPackage(boolean autoPackage) {
        this.autoPackage = autoPackage;
        if (readInputStream != null) readInputStream.setAutoPackage(autoPackage);
    }

    /**
     * bytesToHexString
     *
     * @param bArray bytes
     * @return HexString
     */
    public static String bytesToHexString(byte[] bArray) {
        StringBuilder sb = new StringBuilder(bArray.length);
        String sTemp;
        for (byte aBArray : bArray) {
            sTemp = Integer.toHexString(0xFF & aBArray);
            if (sTemp.length() < 2)
                sb.append(0);
            sb.append(sTemp.toUpperCase(Locale.US));
        }
        return sb.toString();
    }

    /**
     * 错误处理
     *
     * @param error 错误消息
     */
    private void DisplayError(String error) {
        if (errorListener != null) {
            errorListener.error(error);
        } else {
            activity.runOnUiThread(() -> {
                AlertDialog.Builder b = new AlertDialog.Builder(activity);
                b.setTitle("错误");
                b.setMessage(error);
                b.setPositiveButton("确定", null);
                b.show();
            });
        }
    }

    /**
     * 结果回调
     */
    public interface DataListener {
        void onDataReceived(String hexString, byte[] bytes, int size);
    }

    /**
     * 错误回调
     */
    public interface ErrorListener {
        void error(String error);
    }

    /**
     * 关闭串口释放资源
     */
    private void stop() {
        try {
            if (readInputStream != null) {
                readInputStream.stop();
            }
            if (inputStream != null) {
                inputStream.close();
                outputStream = null;
            }
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
        } catch (Throwable e) {
            Log.e(TAG, "stop异常", e);
        } finally {
            if (serialPort != null) {
                try {
                    serialPort.close();
                } catch (Throwable ignored) {
                }
                serialPort = null;
            }
        }
    }

    /**
     * onDestroy,调用的此类的activity必须在onDestroy调用此方法
     */
    public void onDestroy() {
        Log.i(TAG, "调用onDestroy");
        stop();
        clearDataListener();
    }
}
