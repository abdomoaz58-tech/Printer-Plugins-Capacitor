import type { PermissionState } from '@capacitor/core';

export interface PrinterPlugin {
  /**
   * البحث عن أجهزة البلوتوث المقترنة (Bonded Devices)
   */
  listBluetoothDevices(): Promise<{ devices: { name: string; address: string }[] }>;

  /**
   * الاتصال بالطابعة عبر الواي فاي باستخدام الـ IP والـ Port
   */
  connectWifi(options: { ip: string; port?: number }): Promise<{ success: boolean; message: string }>;

  /**
   * الاتصال بالطابعة عبر الـ USB (تلقائياً لأول جهاز متصل)
   */
  connectUsb(): Promise<{ success: boolean; message: string }>;

  /**
   * الاتصال بالطابعة عبر البلوتوث باستخدام الـ MAC Address والاسم
   */
  connectBluetooth(options: { address: string; name?: string }): Promise<{ success: boolean; message: string }>;

  /**
   * قطع الاتصال بالطابعة الحالية لتحرير المنفذ
   */
  disconnect(): Promise<{ success: boolean; message: string }>;

  /**
   * 🔥 معرفة حالة الاتصال الحالية وتفاصيل الطابعة المتصلة
   */
  getStatus(): Promise<{ 
      connected: boolean; 
      message: string; 
      connectionType?: string; 
      device?: { name?: string; address?: string; type?: string } 
  }>;

  /**
   * طباعة محتوى HTML (الأفضل للفواتير الجاهزة)
   */
  printHTML(options: { html: string; width?: number }): Promise<{ success: boolean; message: string }>;

  /**
   * طباعة صورة بصيغة Base64
   */
  printBase64(options: { base64: string }): Promise<{ success: boolean; message: string }>;

  /**
   * طباعة نمط اختبار (Test Pattern) للتأكد من كفاءة الطابعة
   */
  printTestPattern(): Promise<{ success: boolean; message: string }>;

  /**
   * فحص حالة صلاحيات البلوتوث والموقع
   */
  checkPermissions(): Promise<{ printer_perms: PermissionState }>;

  /**
   * طلب الصلاحيات اللازمة من المستخدم
   */
  requestPermissions(): Promise<{ printer_perms: PermissionState }>;

  /**
   * ✅ جلب قائمة الطابعات المحفوظة مسبقاً (تم تعديل النوع ليكون مصفوفة جاهزة)
   */
  getSavedPrinters(): Promise<{ printers: { name: string; address: string; type: string }[] }>;
}