package com.abdelrahman.posprinter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintJob;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MyPrintService extends PrintService {

    private static final String CHANNEL_ID = "PrintServiceChannel";
    private static final PrintAttributes.MediaSize ROLL_80 = new PrintAttributes.MediaSize("ROLL_80", "رول 80 مم (كبير)", 3150, 20000);
    private static final PrintAttributes.MediaSize ROLL_58 = new PrintAttributes.MediaSize("ROLL_58", "رول 58 مم (صغير)", 2280, 20000);

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, getNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(1, getNotification());
        }
        return START_STICKY;
    }

    @Override
    protected PrinterDiscoverySession onCreatePrinterDiscoverySession() {
        return new PrinterDiscoverySession() {
            @Override
            public void onStartPrinterDiscovery(List<PrinterId> priorityList) {
                List<PrinterInfo> printers = new ArrayList<>();
                PrinterId printerId = generatePrinterId("AlBaraka_Printer");
                PrinterInfo.Builder builder = new PrinterInfo.Builder(printerId, "طابعة البركة (Smart Roll) 🖨️", PrinterInfo.STATUS_IDLE);
                
                PrinterCapabilitiesInfo.Builder caps = new PrinterCapabilitiesInfo.Builder(printerId);
                caps.addMediaSize(ROLL_80, true);
                caps.addMediaSize(ROLL_58, false);
                caps.addMediaSize(PrintAttributes.MediaSize.ISO_A4, false);
                caps.setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME);
                caps.addResolution(new PrintAttributes.Resolution("R1", "203dpi", 203, 203), true);
                
                builder.setCapabilities(caps.build());
                printers.add(builder.build());
                addPrinters(printers);
            }
            @Override public void onStopPrinterDiscovery() {}
            @Override public void onValidatePrinters(List<PrinterId> printerIds) {}
            @Override public void onStartPrinterStateTracking(PrinterId printerId) {}
            @Override public void onStopPrinterStateTracking(PrinterId printerId) {}
            @Override public void onDestroy() {}
        };
    }

    @Override
    protected void onRequestCancelPrintJob(PrintJob printJob) {
        printJob.cancel();
    }

    @Override
    protected void onPrintJobQueued(final PrintJob printJob) {
        printJob.start();
        
        final PrintAttributes attrs = printJob.getInfo().getAttributes();
        final PrintAttributes.MediaSize selectedMedia = attrs.getMediaSize();
        final ParcelFileDescriptor pfd = printJob.getDocument().getData();
        
        if (pfd == null) { failPrintJob(printJob, "No data"); return; }

        new Thread(() -> {
            File tempFile = null;
            ParcelFileDescriptor seekablePfd = null;
            try {
                int targetWidth = 576; // الافتراضي 80مم
                if (selectedMedia != null && "ROLL_58".equals(selectedMedia.getId())) {
                    targetWidth = 384; 
                }

                tempFile = new File(getCacheDir(), "job_" + System.currentTimeMillis() + ".pdf");
                copyFdToFile(pfd.getFileDescriptor(), tempFile);
                seekablePfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY);

                // استرجاع الطابعة المحفوظة
                SharedPreferences configPrefs = getSharedPreferences("PrinterConfig", MODE_PRIVATE);
                String lastPrinterJson = configPrefs.getString("last_connected_printer", "");
                
                String address = "";
                String type = "USB"; 

                if (!lastPrinterJson.isEmpty()) {
                    try {
                        JSONObject obj = new JSONObject(lastPrinterJson);
                        address = obj.getString("address");
                        type = obj.getString("type");
                    } catch (Exception e) {}
                }

                if (address.isEmpty() && type.equals("USB")) address = "USB_DEVICE";

                if (address == null || address.isEmpty()) {
                    failPrintJob(printJob, "لم يتم العثور على طابعة مسجلة، افتح التطبيق واتصل أولاً");
                    return;
                }

                // الاتصال
                PrinterHelper helper = PrinterHelper.getInstance(getApplicationContext());
                boolean connected = helper.connectDirectSync(address, type);

                if (connected) {
                    helper.resetPrinter(); 
                    
                    PdfRenderer renderer = new PdfRenderer(seekablePfd);
                    int pageCount = renderer.getPageCount();

                    for (int i = 0; i < pageCount; i++) {
                        Bitmap finalBitmap = renderSmartPage(renderer, i, targetWidth);
                        if (finalBitmap != null) {
                            helper.printImageChunk(finalBitmap, targetWidth);
                            finalBitmap.recycle();
                            Thread.sleep(50);
                        }
                    }
                    renderer.close();
                    helper.finishJob();
                    
                    completePrintJob(printJob);
                } else {
                    failPrintJob(printJob, "فشل الاتصال بالطابعة");
                }

            } catch (Exception e) {
                Log.e("MyPrintService", "Error", e);
                failPrintJob(printJob, e.getMessage());
            } finally {
                try { pfd.close(); } catch (Exception e) {}
                try { if(seekablePfd!=null) seekablePfd.close(); } catch (Exception e) {}
                if (tempFile != null) tempFile.delete();
            }
        }).start();
    }

    private Bitmap renderSmartPage(PdfRenderer renderer, int index, int targetWidth) {
        try {
            PdfRenderer.Page page = renderer.openPage(index);
            
            // ✅✅ التصحيح هنا: التحويل إلى float لضمان دقة الحساب
            float scale = (float) targetWidth / page.getWidth();
            int renderH = (int) (page.getHeight() * scale);

            if (renderH <= 0) renderH = targetWidth; // حماية إضافية

            Bitmap tempBmp = Bitmap.createBitmap(targetWidth, renderH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(tempBmp);
            canvas.drawColor(Color.WHITE);
            page.render(tempBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            page.close();

            Bitmap croppedBmp = cropWhiteSpace(tempBmp);
            if (tempBmp != croppedBmp) tempBmp.recycle();

            return croppedBmp;
        } catch (Exception e) { return null; }
    }

    private Bitmap cropWhiteSpace(Bitmap bmp) {
        int width = bmp.getWidth();
        int height = bmp.getHeight();
        int minX = width, minY = height, maxX = -1, maxY = -1;

        for (int y = 0; y < height; y += 5) {
            for (int x = 0; x < width; x += 5) {
                if (bmp.getPixel(x, y) != Color.WHITE) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }

        if (maxX < minX) return bmp;

        int padding = 0; 
        minY = Math.max(0, minY - padding);
        maxY = Math.min(height, maxY + padding); 

        return Bitmap.createBitmap(bmp, 0, minY, width, (maxY - minY));
    }

    private void copyFdToFile(java.io.FileDescriptor src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }

    private void completePrintJob(PrintJob printJob) {
        new Handler(Looper.getMainLooper()).post(printJob::complete);
    }

    private void failPrintJob(PrintJob printJob, String error) {
        new Handler(Looper.getMainLooper()).post(() -> printJob.fail(error));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Printing Service", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification getNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("خدمة الطباعة تعمل")
                .setContentText("جاهز لاستقبال طلبات الطباعة")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .build();
    }
}