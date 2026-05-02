# 📜 KIBOT TRINITY: MANUAL OPS RULES

## 🚫 GITHUB ACTIONS POLICY
- **TIDAK BOLEH** menggunakan GitHub Actions untuk deployment ke server produksi.
- GitHub Actions hanya digunakan untuk CI/Testing atau Auto-fix internal KiBot.
- AI (ANTIGRAVITY) dilarang keras mencoba trigger `.github/workflows` untuk update code di server.

## ⚡ MANUAL DEPLOYMENT PROTOCOL (GROUND TRUTH)
1. **Source of Truth**: File di lokal (`/Users/kiki/Documents/Web Develop/KiBot/...`) adalah satu-satunya referensi yang benar.
2. **Access Method**: Selalu gunakan SSH manual dengan Private Keys yang terdaftar di `ops/SERVERS.json`.
3. **Command**: Gunakan `python3 tools/deploy.py <file_path> <node_id|ALL>` untuk push update.
4. **Validation**: Setelah deploy, WAJIB cek status service dengan `sudo journalctl -u <service-name> -n 50`.

## 🛠️ TRINITY CLUSTER INVENTORY (v7.3.2)
| Node ID | Role | IP Address | Primary Service | Path |
|---------|------|------------|-----------------|------|
| **batam-manager** | **BRAIN** / Manager | 168.110.201.228 | `kibot-manager` | `/core/` |
| **tokyo-KiBot** | **SCANNER** / Lead-Lag | 152.69.218.198 | `ki-global-scanner-mesh` | `/server/` |
| **singapore-KiBot** | **EXECUTOR** / Engine | 213.35.118.26 | `kibot-executor-indodax` | `/server/` |

## 🛡️ STABILITY GUARANTEE
- **Node Purity**: Node Scanner & Executor dilarang menjalankan service Manager/Auditor/Guardian untuk menghemat RAM (OOM Prevention).
- **Consensus**: Setiap update logika `_can_enter` harus dideploy ke Batam.
- **Tunnels**: Koneksi antar node wajib lewat SSH Tunnel (Port 8787/8788/9998/9999).
