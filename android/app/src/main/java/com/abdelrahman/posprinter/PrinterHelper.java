package com.abdelrahman.posprinter;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PrinterHelper {
    private static volatile PrinterHelper instance;
    private Context context;

    // متغيرات الاتصال
    private BluetoothSocket bluetoothSocket;
    private Socket wifiSocket;
    private OutputStream outputStream;
    // متغيرات USB
    private UsbDeviceConnection usbConnection;
    private UsbEndpoint usbEndpoint;
    private UsbInterface usbInterface;
    
    private static final String ACTION_USB_PERMISSION = "com.abdelrahman.posprinter.USB_PERMISSION";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final UUID UUID_SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final Object lock = new Object();

    // أوامر الطابعة
    private static final byte[] INIT_PRINTER = {0x1B, 0x40}; 
    private static final byte[] ALIGN_LEFT = {0x1B, 0x61, 0x00}; 
    private static final byte[] CUT_PAPER = {0x1D, 0x56, 0x42, 0x00}; 
    private static final byte[] FEED_PAPER_AND_CUT = {0x1B, 0x4A, (byte)0xFF}; 

    private PrinterHelper(Context context) {
        this.context = context;
    }

    public static PrinterHelper getInstance(Context context) {
        if (instance == null) {
            synchronized (PrinterHelper.class) {
                if (instance == null) instance = new PrinterHelper(context);
            }
        }
        return instance;
    }

    public interface ConnectCallback { void onResult(boolean isSuccess, String message); }

    public boolean isConnected() {
        synchronized (lock) {
            return (bluetoothSocket != null && bluetoothSocket.isConnected()) ||
                   (wifiSocket != null && wifiSocket.isConnected()) ||
                   (usbConnection != null);
        }
    }

    // --- الاتصال ---
    @SuppressLint("MissingPermission")
    public boolean connectDirectSync(String address, String type) {
        closeInternal(); // إغلاق أي اتصال سابق
        try {
            if ("WIFI".equals(type)) {
                Socket tempSocket = new Socket();
                int port = 9100; 
                tempSocket.connect(new InetSocketAddress(address, port), 4000);
                synchronized (lock) {
                    wifiSocket = tempSocket;
                    outputStream = wifiSocket.getOutputStream();
                }
                return true;
            } else if ("USB".equals(type)) {
                return connectUsbSync();
            } else { 
                BluetoothDevice dev = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
                BluetoothSocket tempSocket = dev.createRfcommSocketToServiceRecord(UUID_SPP);
                tempSocket.connect();
                synchronized (lock) {
                    bluetoothSocket = tempSocket;
                    outputStream = bluetoothSocket.getOutputStream();
                }
                return true;
            }
        } catch (Exception e) {
            Log.e("PrinterHelper", "Connection Failed: " + e.getMessage());
            return false;
        }
    }

    private boolean connectUsbSync() {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager.getDeviceList().isEmpty()) return false;
        UsbDevice device = manager.getDeviceList().values().iterator().next();
        
        if (!manager.hasPermission(device)) {
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), 
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ? PendingIntent.FLAG_IMMUTABLE : 0);
            manager.requestPermission(device, pi);
            return false;
        }

        try {
            UsbDeviceConnection tempConn = manager.openDevice(device);
            if (tempConn == null) return false;

            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface iface = device.getInterface(i);
                for (int j = 0; j < iface.getEndpointCount(); j++) {
                    UsbEndpoint ep = iface.getEndpoint(j);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                        if (tempConn.claimInterface(iface, true)) {
                            synchronized (lock) {
                                usbConnection = tempConn;
                                usbEndpoint = ep;
                                usbInterface = iface;
                            }
                            return true;
                        }
                    }
                }
            }
            tempConn.close();
        } catch (Exception ignored) {}
        return false;
    }

    // فحص الاتصال فقط لتجنب التعليق
    public boolean checkPrinterStatus() {
        return isConnected();
    }

    // --- الطباعة ---
    public void resetPrinter() throws IOException {
        sendRawData(INIT_PRINTER);
    }

    public void printBitmapSync(Bitmap bitmap, int printerWidth) throws IOException {
        if (!checkPrinterStatus()) throw new IOException("الطابعة غير متصلة");

        sendRawData(INIT_PRINTER);
        try { Thread.sleep(50); } catch (Exception e) {} 

        sendRawData(ALIGN_LEFT);
        printImageChunk(bitmap, printerWidth);
        
        finishJob();
    }

    public void printBitmapSync(Bitmap bitmap) throws IOException {
        printBitmapSync(bitmap, 576);
    }

    public void printImageChunk(Bitmap bitmap, int printerWidth) throws IOException {
        byte[] data = decodeBitmapToGSv0(bitmap, printerWidth);
        sendRawData(data);
    }

    public void finishJob() throws IOException {
        try { Thread.sleep(100); } catch (InterruptedException e) {}
        sendRawData(FEED_PAPER_AND_CUT); 
        try { Thread.sleep(100); } catch (InterruptedException e) {}
        sendRawData(CUT_PAPER);
    }

    private void sendRawData(byte[] data) throws IOException {
        int offset = 0;
        int chunkSize;
        long sleepTime;

        // ضبط السرعة
        synchronized (lock) {
            if (usbConnection != null) {
                chunkSize = 16384; 
                sleepTime = 0; 
            } else if (wifiSocket != null) {
                chunkSize = 4096; 
                sleepTime = 5; 
            } else {
                chunkSize = 1024; 
                sleepTime = 20; 
            }
        }
        
        while (offset < data.length) {
            int length = Math.min(chunkSize, data.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(data, offset, chunk, 0, length);
            
            synchronized (lock) {
                if (outputStream == null && usbConnection == null) throw new IOException("انقطع الاتصال");

                if (outputStream != null) {
                    outputStream.write(chunk);
                    outputStream.flush();
                } else if (usbConnection != null && usbEndpoint != null) {
                    usbConnection.bulkTransfer(usbEndpoint, chunk, length, 5000);
                }
            }
            offset += length;
            
            if (sleepTime > 0) {
                try { Thread.sleep(sleepTime); } catch (Exception ignored) {}
            }
        }
    }

    private byte[] decodeBitmapToGSv0(Bitmap bmp, int targetWidth) {
        if (targetWidth <= 0) targetWidth = 576;
        int widthBytes = targetWidth / 8;
        int targetHeight = (int) (bmp.getHeight() * (targetWidth / (float) bmp.getWidth()));
        
        Bitmap scaledBmp = Bitmap.createScaledBitmap(bmp, targetWidth, targetHeight, false);
        int[] pixels = new int[targetWidth * targetHeight];
        scaledBmp.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight);

        byte[] data = new byte[targetHeight * widthBytes + 8];
        data[0] = 0x1D; data[1] = 0x76; data[2] = 0x30; data[3] = 0x00;
        data[4] = (byte) widthBytes;   data[5] = 0; 
        data[6] = (byte) (targetHeight % 256); data[7] = (byte) (targetHeight / 256);

        int k = 8;
        for (int i = 0; i < targetHeight; i++) {
            for (int j = 0; j < widthBytes; j++) {
                byte b = 0;
                for (int p = 0; p < 8; p++) {
                    int x = j * 8 + p;
                    if (x >= targetWidth) continue;
                    
                    int pixel = pixels[i * targetWidth + x];
                    if (((pixel >> 24) & 0xff) < 128) continue; 

                    int r = (pixel >> 16) & 0xff;
                    int g = (pixel >> 8) & 0xff;
                    int bl = pixel & 0xff;
                    int luminance = (int) (0.299 * r + 0.587 * g + 0.114 * bl);
                    
                    if (luminance < 200) { 
                        b |= (1 << (7 - p)); 
                    }
                }
                data[k++] = b;
            }
        }
        if (scaledBmp != bmp) scaledBmp.recycle();
        return data;
    }

    public void closeInternal() {
        synchronized (lock) {
            try {
                if (outputStream != null) outputStream.close();
                if (bluetoothSocket != null) bluetoothSocket.close();
                if (wifiSocket != null) wifiSocket.close();
                if (usbConnection != null) {
                    if (usbInterface != null) usbConnection.releaseInterface(usbInterface);
                    usbConnection.close();
                }
            } catch (Exception ignored) {}
            outputStream = null; bluetoothSocket = null; wifiSocket = null;
            usbConnection = null; usbInterface = null; usbEndpoint = null;
        }
    }
    
    // Wrappers (هنا تم التصحيح: استخدام lambdas بدلاً من الكود المفكك)
    public void connectUsb(ConnectCallback callback) { 
        executor.execute(() -> { 
            closeInternal();
            if(connectUsbSync()) callback.onResult(true, "تم الاتصال (USB)"); 
            else callback.onResult(false, "فشل الاتصال (USB)"); 
        }); 
    }
    
    public void connectBluetooth(String address, ConnectCallback callback) { 
        executor.execute(() -> { 
            closeInternal();
            if(connectDirectSync(address, "BT")) callback.onResult(true, "تم الاتصال (BT)"); 
            else callback.onResult(false, "فشل الاتصال (BT)"); 
        }); 
    }

    public void connectWifi(String ip, int port, ConnectCallback callback) {
        executor.execute(() -> {
            closeInternal();
            if (connectDirectSync(ip, "WIFI")) callback.onResult(true, "تم الاتصال (WiFi)");
            else callback.onResult(false, "فشل الاتصال (WiFi)");
        });
    }

    public void printBitmap(final Bitmap inputBitmap, ConnectCallback callback) { 
        executor.execute(() -> { 
            try { 
                printBitmapSync(inputBitmap); 
                if(callback != null) callback.onResult(true, "تمت الطباعة بنجاح"); 
            } catch (Exception e) { 
                if(callback != null) callback.onResult(false, "خطأ: " + e.getMessage()); 
            } 
        }); 
    }
    
    public void disconnect(ConnectCallback cb) { 
        executor.execute(() -> { 
            closeInternal(); 
            if(cb != null) cb.onResult(true, "تم الفصل"); 
        }); 
    }
}