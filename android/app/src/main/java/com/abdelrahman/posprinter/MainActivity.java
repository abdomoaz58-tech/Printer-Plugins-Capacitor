package com.abdelrahman.posprinter;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.JSObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // 1. تسجيل الـ Plugin عشان يشتغل مع Capacitor
        registerPlugin(PrinterPlugin.class);
        super.onCreate(savedInstanceState);

        // 2. معالجة أي مشاركة (Share) جاية للتطبيق أول ما يفتح
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 3. معالجة المشاركة لو التطبيق كان مفتوح بالفعل
        handleIntent(intent);
    }

    // --- دالة التوجيه (Router) ---
    private void handleIntent(Intent intent) {
        String action = intent.getAction();
        String type = intent.getType();

        if (type == null) return;

        // أ. مشاركة نصوص
        if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(type)) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null) sendToJS("onSharedText", sharedText);
        }
        // ب. مشاركة صور
        else if (type.startsWith("image/")) {
            Uri imageUri = (Intent.ACTION_SEND.equals(action)) ? (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM) : intent.getData();
            if (imageUri != null) processImage(imageUri);
        }
        // ج. مشاركة PDF (اختياري - بيحول أول صفحة لصورة)
        else if (type.equals("application/pdf")) {
            Uri pdfUri = (Intent.ACTION_SEND.equals(action)) ? (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM) : intent.getData();
            if (pdfUri != null) processPdf(pdfUri);
        }
    }

    // --- معالجة PDF (تحويله لصورة وإرساله) ---
    private void processPdf(Uri pdfUri) {
        try {
            ParcelFileDescriptor fileDescriptor = getContentResolver().openFileDescriptor(pdfUri, "r");
            if (fileDescriptor != null) {
                PdfRenderer renderer = new PdfRenderer(fileDescriptor);
                int pageCount = renderer.getPageCount();
                int width = 576; // عرض الورقة الحرارية
                ArrayList<Bitmap> pageBitmaps = new ArrayList<>();
                int totalHeight = 0;

                // تحويل كل الصفحات لصور
                for (int i = 0; i < pageCount; i++) {
                    PdfRenderer.Page page = renderer.openPage(i);
                    Bitmap tempBmp = Bitmap.createBitmap(page.getWidth() * 2, page.getHeight() * 2, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(tempBmp);
                    canvas.drawColor(Color.WHITE);
                    page.render(tempBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

                    // قص الهوامش البيضاء الزيادة
                    Bitmap croppedBmp = cropBitmapMargins(tempBmp);
                    int finalHeight = (int) ((float) width / croppedBmp.getWidth() * croppedBmp.getHeight());
                    Bitmap finalBmp = Bitmap.createScaledBitmap(croppedBmp, width, finalHeight, true);

                    totalHeight += finalHeight;
                    pageBitmaps.add(finalBmp);
                    page.close();
                    tempBmp.recycle();
                }

                // دمج الصفحات في صورة واحدة طويلة
                Bitmap stitchedBitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888);
                Canvas stitchedCanvas = new Canvas(stitchedBitmap);
                float currentY = 0;
                for (Bitmap bmp : pageBitmaps) {
                    stitchedCanvas.drawBitmap(bmp, 0, currentY, null);
                    currentY += bmp.getHeight();
                }

                sendToJS("onSharedImage", convertBitmapToBase64(stitchedBitmap));
                renderer.close();
                fileDescriptor.close();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    // --- قص الهوامش البيضاء (لتحسين شكل الفاتورة) ---
    private Bitmap cropBitmapMargins(Bitmap bmp) {
        int minX = bmp.getWidth(), minY = bmp.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < bmp.getHeight(); y += 5) {
            for (int x = 0; x < bmp.getWidth(); x += 5) {
                if (bmp.getPixel(x, y) != Color.WHITE) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) return bmp;
        return Bitmap.createBitmap(bmp,
                Math.max(0, minX-10), Math.max(0, minY-10),
                Math.min(bmp.getWidth()-minX, (maxX-minX)+20),
                Math.min(bmp.getHeight()-minY, (maxY-minY)+20));
    }

    // --- معالجة الصور العادية ---
    private void processImage(Uri imageUri) {
        try {
            InputStream imageStream = getContentResolver().openInputStream(imageUri);
            Bitmap originalBitmap = BitmapFactory.decodeStream(imageStream);
            if (originalBitmap != null) {
                // إضافة خلفية بيضاء (عشان الصور الشفافة PNG متطلعش سوداء)
                Bitmap whiteBgBitmap = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(whiteBgBitmap);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(originalBitmap, 0, 0, null);
                
                sendToJS("onSharedImage", convertBitmapToBase64(whiteBgBitmap));
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    // --- أدوات مساعدة ---
    private String convertBitmapToBase64(Bitmap bitmap) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        return Base64.encodeToString(byteArray, Base64.DEFAULT);
    }

    // إرسال البيانات للـ Frontend (TypeScript)
    private void sendToJS(String eventName, String data) {
        JSObject ret = new JSObject();
        ret.put("data", data);
        new android.os.Handler().postDelayed(() -> {
            if (getBridge() != null) {
                getBridge().triggerWindowJSEvent(eventName, ret.toString());
            }
        }, 1500); // تأخير بسيط لضمان تحميل الصفحة
    }
}