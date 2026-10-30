import { Printer } from './plugins/printer'; 
import { App } from '@capacitor/app';
import { Preferences } from '@capacitor/preferences';

// تعريف الواجهة لتفادي أخطاء TypeScript
interface PrinterDevice {
    address: string;
    name: string;
    type: string;
}

class PrinterApp {
    private savedAddressKey = 'printer_address';
    private savedPrinters: PrinterDevice[] = [];

    constructor() {
        this.init().catch(e => {
            console.error(e);
            alert("خطأ في التشغيل: " + e);
        });
    }

    private async init() {
        this.bindEvents();
        // 1. تحميل السجل
        await this.loadHistory();
        // 2. محاولة الاتصال التلقائي (الآن مدعومة بـ getStatus في الجافا)
        await this.autoConnect();
        // 3. تفعيل استقبال المشاركة
        this.setupShareListeners();
    }

    private bindEvents() {
        document.getElementById('btnConnectWifi')?.addEventListener('click', () => this.connectWifi());
        document.getElementById('btnListBT')?.addEventListener('click', () => this.listBT());
        document.getElementById('btnConnectBT')?.addEventListener('click', () => this.connectBT());
        document.getElementById('btnConnectUsb')?.addEventListener('click', () => this.connectUsb());
        document.getElementById('btnDisconnect')?.addEventListener('click', () => this.disconnect());
        
        // زر الطباعة (الفاتورة العامة)
        document.getElementById('btnPrintBill')?.addEventListener('click', () => this.printBill());
        
        document.getElementById('btnClear')?.addEventListener('click', () => this.clearBill());
    }

    // --- إدارة السجل ---
    private async loadHistory() {
        try {
            const result = await Printer.getSavedPrinters();
            if (result && result.printers) {
                const rawData = result.printers;
                // معالجة البيانات سواء جاءت كنص (JSON String) أو مصفوفة
                this.savedPrinters = (typeof rawData === 'string') 
                    ? JSON.parse(rawData) 
                    : rawData as unknown as PrinterDevice[];
                this.renderHistoryUI();
            }
        } catch (e) {
            console.error("Error loading history:", e);
        }
    }

    private renderHistoryUI() {
        const container = document.getElementById('savedPrintersList');
        if (!container) return;

        if (this.savedPrinters.length === 0) {
            container.innerHTML = '<p style="text-align:center; color:gray;">لا توجد طابعات محفوظة</p>';
            return;
        }

        container.innerHTML = ''; 
        this.savedPrinters.forEach(printer => {
            const div = document.createElement('div');
            div.className = 'printer-history-item';
            div.style.cssText = "display:flex; justify-content:space-between; align-items:center; padding:10px; border-bottom:1px solid #eee;";
            
            div.innerHTML = `
                <div>
                    <b style="color:#222;">${printer.name || 'Printer'}</b><br>
                    <small style="color:#666;">${printer.address} (${printer.type})</small>
                </div>
                <button class="reconnect-btn" style="background:#2196F3; color:white; border:none; padding:8px 12px; border-radius:4px; cursor:pointer;">اتصال ⚡</button>
            `;
            
            // استخدام Arrow Function لتمرير البيانات الصحيحة
            div.querySelector('.reconnect-btn')?.addEventListener('click', () => this.reconnect(printer));
            container.appendChild(div);
        });
    }

    // --- الاتصال الذكي ---
    
    private async autoConnect() {
        // قراءة آخر عنوان محفوظ
        const { value: lastAddress } = await Preferences.get({ key: this.savedAddressKey });
        
        // التحقق من حالة الاتصال الحالية من الجافا (بفضل دالة getStatus الجديدة)
        const status = await Printer.getStatus();
        if (status.connected) {
            this.updateUIStatus("متصل بالفعل ✅", true);
            return;
        }

        if (!lastAddress) {
            this.updateUIStatus("غير متصل ⚪", false);
            return;
        }

        // محاولة إيجاد الطابعة في السجل لمعرفة نوعها
        const targetPrinter = this.savedPrinters.find(p => p.address === lastAddress);

        if (targetPrinter) {
            console.log("Auto-connecting to:", targetPrinter);
            await this.reconnect(targetPrinter);
        } else {
            // محاولة افتراضية
            await this.reconnect({ name: 'Auto', address: lastAddress, type: 'BT' });
        }
    }

    private async reconnect(printer: PrinterDevice) {
        this.updateUIStatus(`جاري الاتصال بـ ${printer.name}...`, false);
        try {
            let res;
            if (printer.type === 'BT') {
                res = await Printer.connectBluetooth({ address: printer.address, name: printer.name });
            } else if (printer.type === 'WIFI') {
                res = await Printer.connectWifi({ ip: printer.address, port: 9100 });
            } else if (printer.type === 'USB') {
                res = await Printer.connectUsb();
            }

            if (res?.success) {
                await Preferences.set({ key: this.savedAddressKey, value: printer.address });
                this.updateUIStatus(`متصل بـ ${printer.name} ✅`, true);
                await this.loadHistory(); 
            }
        } catch (e: any) {
            console.error(e);
            this.updateUIStatus("فشل الاتصال ❌", false);
        }
    }

    // --- أزرار الاتصال اليدوي ---

    private async connectBT() {
        const select = document.getElementById('btSelect') as HTMLSelectElement;
        if (!select || !select.value) { alert("اختر جهازاً أولاً"); return; }
        
        const selectedOption = select.options[select.selectedIndex];
        const name = selectedOption.text.split('(')[0].trim();

        await this.reconnect({ name: name, address: select.value, type: 'BT' });
    }

    private async connectWifi() {
        const ipInput = document.getElementById('ipInput') as HTMLInputElement;
        if (!ipInput?.value) { alert("اكتب الـ IP"); return; }

        await this.reconnect({ name: `WiFi(${ipInput.value})`, address: ipInput.value, type: 'WIFI' });
    }

    private async connectUsb() {
        await this.reconnect({ name: "USB Printer", address: "USB_DEVICE", type: 'USB' });
    }

    private async listBT() {
        try {
            this.updateUIStatus("جاري البحث...", false);
            const res = await Printer.listBluetoothDevices();
            const select = document.getElementById('btSelect') as HTMLSelectElement;
            if (select) {
                select.innerHTML = '<option value="">اختر طابعة...</option>';
                res.devices.forEach((d: any) => {
                    select.innerHTML += `<option value="${d.address}">${d.name || 'Unknown'} (${d.address})</option>`;
                });
                this.updateUIStatus("تم التحديث", false);
                alert(`تم العثور على ${res.devices.length} جهاز`);
            }
        } catch (e: any) { this.showError(e); }
    }

    private async disconnect() {
        try {
            await Printer.disconnect();
            await Preferences.remove({ key: this.savedAddressKey });
            this.updateUIStatus("غير متصل ⚪", false);
        } catch (e) { console.error(e); }
    }

    // --- الطباعة ---

    private async printBill() {
        const status = await Printer.getStatus();
        if (!status.connected) {
            alert("⚠️ الطابعة غير متصلة! يرجى الاتصال أولاً.");
            return;
        }

        const storeElement = document.getElementById('storeName') as HTMLInputElement;
        const contentElement = document.getElementById('invoiceContent') as HTMLTextAreaElement;
        
        const storeName = storeElement?.value || "My Store";
        const billContent = contentElement?.value || "";
        
        if (!billContent.trim()) { alert("الفاتورة فارغة!"); return; }

        const formattedContent = billContent.replace(/\n/g, '<br>');
        
        const htmlBody = `
            <div style="direction:rtl; font-family: monospace; width: 570px; padding: 10px; color: black; font-weight: bold;">
                <h2 style="text-align:center; margin:0; font-size: 30px;">${storeName}</h2>
                <hr style="border-top: 2px dashed #000; margin: 15px 0;">
                <div style="font-size: 24px; font-weight: bold; text-align: right; line-height: 1.5; white-space: pre-wrap;">
                    ${formattedContent}
                </div>
                <hr style="border-top: 2px dashed #000; margin: 15px 0;">
                <p style="text-align:center; font-size: 20px;">شكراً لزيارتكم</p>
                <div style="height: 50px;"></div>
            </div>
        `;

        try {
            await Printer.printHTML({ html: htmlBody, width: 576 });
        } catch (e: any) { this.showError(e); }
    }

    private setupShareListeners() {
        window.addEventListener('onSharedText', (data: any) => {
            const text = data.detail ? (JSON.parse(data.detail).data) : (data.data || data);
            const contentElement = document.getElementById('invoiceContent') as HTMLTextAreaElement;
            if (contentElement && text) {
                contentElement.value = text;
                setTimeout(() => this.printBill(), 1000);
            }
        });

        window.addEventListener('onSharedImage', async (data: any) => {
            try {
                const base64 = data.detail ? (JSON.parse(data.detail).data) : (data.data || data);
                if(base64){
                    await Printer.printBase64({ base64: base64 });
                    setTimeout(async () => { await App.minimizeApp(); }, 1500);
                }
            } catch (e: any) { alert("فشل الطباعة: " + e.message); }
        });
    }

    private clearBill() {
        const ta = document.getElementById('invoiceContent') as HTMLTextAreaElement;
        if (ta) ta.value = '';
    }

    private updateUIStatus(text: string, isOnline: boolean) {
        const el = document.getElementById('status');
        if (el) {
            el.innerText = "الحالة: " + text;
            el.className = "status-badge " + (isOnline ? "online" : "");
            el.style.backgroundColor = isOnline ? "#d4edda" : "#f8d7da";
        }
    }

    private showError(e: any) {
        console.error(e);
        alert("خطأ: " + (e.message || JSON.stringify(e)));
    }
}

new PrinterApp();