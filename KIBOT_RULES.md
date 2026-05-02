# 📜 KIBOT PROJECT RULES (TRINITY ARCHITECTURE)

Sistem ini beroperasi di bawah filosofi **"Autonomous Trading"** dengan sinkronisasi ketat antara **Mac (Lokal)** dan **Batam (Server)**.

## 🔄 PROSEDUR SINKRONISASI (WAJIB)
Setiap ada perubahan kode di **Mac**, asisten/operator WAJIB melakukan:
1.  **PULL**: Ambil update terbaru dari GitHub (`git pull origin main`) untuk menghindari konflik sejarah commit.
2.  **SYNC**: Kirim perubahan ke server Batam via SSH/SCP.
3.  **PUSH**: Setelah tes lokal & server aman, wajib `git push` ke GitHub agar Cloud tetap terupdate.

---

## 📂 STRUKTUR FOLDER & ATURAN KHUSUS

### 1. `Batam/Brain_Control/`
*   **Isi**: Trinity Governor, Manager Utama, dan Logika Pusat.
*   **Aturan**: Perubahan di sini sensitif. Jika berubah, wajib restart service: `sudo systemctl restart kibot-trinity-governor`.

### 2. `Batam/Telegram Notification/`
*   **Isi**: Notifier, Monitor, dan Gateway Chat.
*   **Aturan**: Pastikan `TELEGRAM_CHAT_ID` selalu merujuk ke ID lu yang aktif (**1346696386**). Jangan ganti ID tanpa izin tertulis dari Bos.

### 3. `Batam/Learning System/`
*   **Isi**: AI Learning Engine, Trade Stats.
*   **Aturan**: File `.state/` diabaikan oleh Git. Jangan paksa push file state ke GitHub.

### 4. `Batam/Security & Watchdog/`
*   **Isi**: AI Healer, Guardian.
*   **Aturan**: Jalur "Self-Healing" harus selalu aktif. Jika watchdog mati, sistem dianggap kritis.

---

## 🛡️ FILOSOFI OPERASIONAL
1.  **Autonomous First**: Sistem harus bisa memperbaiki diri sendiri lewat jalur Trinity Governor.
2.  **Little by Little**: Fokus pada kestabilan jangka panjang. Tekan kerugian, maksimalkan probabilitas.
3.  **No Ghost Process**: Jangan biarkan ada skrip manual yang jalan tanpa pengawasan `systemd`.

**DISETUJUI OLEH: KIBOT GOVERNOR & ANTIGRAVITY**
