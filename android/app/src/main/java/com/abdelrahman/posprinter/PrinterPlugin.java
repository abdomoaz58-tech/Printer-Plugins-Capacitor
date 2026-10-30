package com.abdelrahman.posprinter;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.core.app.ActivityCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(name = "Printer", permissions = {
    @Permission(alias = "printer_perms", strings = {
        Manifest.permission.BLUETOOTH, 
        Manifest.permission.BLUETOOTH_ADMIN, 
        Manifest.permission.BLUETOOTH_SCAN, 
        Manifest.permission.BLUETOOTH_CONNECT, 
        Manifest.permission.ACCESS_FINE_LOCATION
    })
})
public class PrinterPlugin extends Plugin {

    private PrinterHelper helper;

    @Override
    public void load() {
        helper = PrinterHelper.getInstance(getActivity().getApplicationContext());
    }

    @PluginMethod
    public void listBluetoothDevices(PluginCall call) {
        if (getPermissionState("printer_perms") != PermissionState.GRANTED) {
            requestPermissionForAlias("printer_perms", call, "deviceDiscoveryCallback");
        } else {
            doDiscovery(call);
        }
    }

    @PermissionCallback
    private void deviceDiscoveryCallback(PluginCall call) {
        if (getPermissionState("printer_perms") == PermissionState.GRANTED) {
            doDiscovery(call);
        } else {
            call.reject("يجب الموافقة على الصلاحيات للبحث عن الطابعات");
        }
    }

    private void doDiscovery(PluginCall call) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                call.reject("لا توجد صلاحية بلوتوث (Android 12+)");
                return;
            }
        }
        
        JSArray list = new JSArray();
        try {
            if (adapter != null && adapter.getBondedDevices() != null) {
                for (BluetoothDevice d : adapter.getBondedDevices()) {
                    JSObject obj = new JSObject();
                    obj.put("name", d.getName());
                    obj.put("address", d.getAddress());
                    list.put(obj);
                }
            }
        } catch (Exception e) {}
        
        JSObject ret = new JSObject();
        ret.put("devices", list);
        call.resolve(ret);
    }

    @PluginMethod
    public void connectWifi(PluginCall call) {
        String ip = call.getString("ip");
        int port = call.getInt("port", 9100);
        String name = "WiFi Printer (" + ip + ")";

        helper.connectWifi(ip, port, (success, msg) -> {
            if (success) {
                PrinterStorage storage = new PrinterStorage(getContext());
                storage.savePrinter(name, ip, "WIFI");
            }
            resolveCall(call, success, msg);
        });
    }

    @PluginMethod
    public void connectUsb(PluginCall call) {
        helper.connectUsb((success, msg) -> {
            if (success) {
                PrinterStorage storage = new PrinterStorage(getContext());
                storage.savePrinter("USB Printer", "USB_DEVICE", "USB");
            }
            resolveCall(call, success, msg);
        });
    }

    @PluginMethod
    public void connectBluetooth(PluginCall call) {
        String address = call.getString("address");
        String name = call.getString("name", "Thermal Printer");

        if (getPermissionState("printer_perms") != PermissionState.GRANTED) {
            call.save(); 
            requestPermissionForAlias("printer_perms", call, "connectBtCallback");
        } else {
            helper.connectBluetooth(address, (success, msg) -> {
                if (success) {
                    PrinterStorage storage = new PrinterStorage(getContext());
                    storage.savePrinter(name, address, "BT");
                }
                resolveCall(call, success, msg);
            });
        }
    }

    @PermissionCallback
    private void connectBtCallback(PluginCall call) {
        if (getPermissionState("printer_perms") == PermissionState.GRANTED) {
            String address = call.getString("address");
            String name = call.getString("name", "Thermal Printer");
            helper.connectBluetooth(address, (success, msg) -> {
                if (success) {
                    PrinterStorage storage = new PrinterStorage(getContext());
                    storage.savePrinter(name, address, "BT");
                }
                resolveCall(call, success, msg);
            });
        } else {
            call.reject("تم رفض الصلاحية للاتصال");
        }
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        helper.disconnect((success, msg) -> resolveCall(call, success, msg));
    }

    @PluginMethod
    public void getSavedPrinters(PluginCall call) {
        PrinterStorage storage = new PrinterStorage(getContext());
        JSObject ret = new JSObject();
        ret.put("printers", storage.getSavedPrintersJson());
        call.resolve(ret);
    }

    // 🔥🔥 هذه الدالة كانت ناقصة في الكود اللي بعته، وضفتها هنا عشان main.ts يشتغل
    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject ret = new JSObject();
        boolean connected = helper.isConnected();
        ret.put("connected", connected);
        ret.put("message", connected ? "Printer is Connected" : "Printer is Disconnected");
        
        if(connected) {
             PrinterStorage storage = new PrinterStorage(getContext());
             try {
                 org.json.JSONObject last = storage.getLastPrinter();
                 if(last != null) {
                     JSObject dev = new JSObject();
                     dev.put("name", last.optString("name"));
                     ret.put("device", dev);
                 }
             } catch(Exception e){}
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void printBase64(PluginCall call) {
        String b64 = call.getString("base64");
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp != null) {
                helper.printBitmap(bmp, (success, msg) -> resolveCall(call, success, msg));
            } else {
                call.reject("Failed to decode image");
            }
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void printHTML(PluginCall call) {
        String htmlInput = call.getString("html");
        int printerWidth = 576; // 80mm

        getActivity().runOnUiThread(() -> {
            ViewGroup rootView = (ViewGroup) getActivity().getWindow().getDecorView().findViewById(android.R.id.content);
            WebView wv = new WebView(getActivity());

            try {
                wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                wv.setBackgroundColor(Color.WHITE);

                WebSettings ws = wv.getSettings();
                ws.setJavaScriptEnabled(true);
                ws.setLoadWithOverviewMode(false);
                ws.setUseWideViewPort(false);
                ws.setTextZoom(100); 
                ws.setDefaultFontSize(22); 
                ws.setDefaultTextEncodingName("utf-8");

                String styledHtml = "<html><head><style>" +
                        "body { background-color: #ffffff; margin: 0; padding: 0; width: " + printerWidth + "px; color: #000000 !important; font-weight: bold !important; -webkit-font-smoothing: none; }" +
                        "* { font-family: monospace; }" +
                        ".spacer { height: 150px; width: 100%; display: block; }" + 
                        "</style></head><body>" + htmlInput + "<div class='spacer'></div></body></html>";

                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(printerWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
                wv.setVisibility(View.INVISIBLE);
                rootView.addView(wv, params);

                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                int specWidth = View.MeasureSpec.makeMeasureSpec(printerWidth, View.MeasureSpec.EXACTLY);
                                int specHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
                                view.measure(specWidth, specHeight);

                                int measuredHeight = view.getMeasuredHeight();
                                if (measuredHeight <= 0) measuredHeight = 400;
                                int fullHeight = measuredHeight + 100; 

                                view.layout(0, 0, printerWidth, fullHeight);
                                Bitmap bmp = Bitmap.createBitmap(printerWidth, fullHeight, Bitmap.Config.ARGB_8888);
                                Canvas canvas = new Canvas(bmp);
                                canvas.drawColor(Color.WHITE);
                                view.draw(canvas);

                                helper.printBitmap(bmp, (success, msg) -> resolveCall(call, success, msg));
                                rootView.removeView(wv);

                            } catch (Exception e) {
                                rootView.removeView(wv);
                                call.reject("Render Error: " + e.getMessage());
                            }
                        }, 1500);
                    }
                });

                wv.loadDataWithBaseURL(null, styledHtml, "text/html", "UTF-8", null);
            } catch (Exception e) {
                call.reject("Error: " + e.getMessage());
            }
        });
    }

    @PluginMethod
    public void printTestPattern(PluginCall call) {
        try {
            int width = 576;
            int height = 150;
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.drawColor(Color.WHITE);
            android.graphics.Paint paint = new android.graphics.Paint();
            paint.setColor(Color.BLACK);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            canvas.drawRect(10, 10, width - 10, height - 10, paint);

            helper.printBitmap(bmp, (success, msg) -> resolveCall(call, success, msg));
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    private void resolveCall(PluginCall call, boolean success, String msg) {
        if (success) {
            JSObject ret = new JSObject();
            ret.put("success", true);
            ret.put("message", msg);
            call.resolve(ret);
        } else {
            call.reject(msg);
        }
    }
}