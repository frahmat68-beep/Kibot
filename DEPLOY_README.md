# 🚀 DEPLOY KIBOT - SUPER SIMPLE VERSION

## ✅ CARA JALAN:

```bash
cd /Users/kiki/Documents/Web\ Develop/KiBot
chmod +x deploy-kibot-final.sh
./deploy-kibot-final.sh
```

**Itu aja!** ☕

---

## 📋 Script akan:

1. ✅ Setup SSH key permissions
2. ✅ Test SSH ke Indodax
3. ✅ Test SSH ke Binance  
4. ✅ Transfer file KiBot ke Binance
5. ✅ Setup service KiBot di Binance
6. ✅ Stop KiBot di Indodax
7. ✅ Verifikasi semua jalan

---

## ⏱️ Perkiraan waktu: 2-3 menit

---

## 🎯 Hasil akhir:

```
Indodax (213.35.118.26):
  KiDax:      active ✅
  KiBot:      inactive ✅
  RAM bebas:  700MB

Binance (152.69.218.198):
  Kinance:    active ✅
  KiBot:      active ✅
  RAM bebas:  750MB
```

---

## ❌ Kalau error:

Biasanya error ini:

### `Connection timed out`
```
→ IP salah, firewall block, atau server mati
→ Cek: apakah 213.35.118.26 dan 152.69.218.198 correct?
```

### `Permission denied (publickey)`
```
→ Key tidak cocok atau belum di-install di server
→ Fix: Minta orang yang setup server add public key
```

### `No such file or directory` (saat transfer file)
```
→ Path ke file salah
→ Pastikan lu jalanin dari folder: /Users/kiki/Documents/Web\ Develop/KiBot
```

---

## 🔧 Kalau perlu debug:

Jalanin command ini satu-satu:

```bash
# Test SSH ke Indodax
chmod 600 SSH_INDODAX/ssh-key-2026-03-22.key
ssh -i SSH_INDODAX/ssh-key-2026-03-22.key ubuntu@213.35.118.26 "echo OK"

# Test SSH ke Binance
chmod 600 SSH_BINANCE/ssh-key-2026-03-27.key
ssh -i SSH_BINANCE/ssh-key-2026-03-27.key ubuntu@152.69.218.198 "echo OK"
```

Kalau dua ini jalan, deploy script pasti jalan juga.

---

**Ready? Jalanin:** `./deploy-kibot-final.sh` ☕
