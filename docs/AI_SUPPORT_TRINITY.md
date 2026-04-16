# KiBot Trinity: AI Legion Reference

Sistem KiBot v7.0 "Trinity" menggunakan integrasi **Multi-AI Legion**, sebuah koordinator yang menggabungkan 9+ otak AI berbeda (berbayar & gratis) untuk mendukung keputusan perdagangan.

## 🧠 Daftar Pasukan AI (Providers)

Sistem secara otomatis melakukan failover dan konsensus di antara provider berikut:

### 1. Premium Providers (Membutuhkan Key)
| Provider | Peran | Model Utama |
| :--- | :--- | :--- |
| **Google Gemini** | Auditor Utama | `gemini-2.0-flash-lite` |
| **NVIDIA Build** | High-Speed Veto | `meta/llama-3.1-70b` |
| **Groq Cloud** | Real-time Analysis | `llama3-70b-8192` |
| **OpenRouter** | Multi-Model Gateway | Berbagai model (Auto) |
| **Cohere** | Risk Manager | `command-r-plus` |
| **Jina AI** | Market News Intelligence | `jina-reader` / `embeddings` |

### 2. Public Fallback (No-Key Required)
| Provider | Peran | Sumber |
| :--- | :--- | :--- |
| **Pollinations** | Zero-Config Fallback | `text.pollinations.ai` |
| **HuggingFace** | Community Model Access | `api-inference.huggingface.co` |
| **DuckDuckGo AI** | Global Logic Wrapper | `duckduckgo.com/aichat` |

## 🛠️ Cara Kerja Integrasi Hybrid

Sistem ini dirancang agar **AI adalah pembantu, bukan penguasa**. Logika perdagangan tetap berakar pada matematika engine:

1.  **Mathematica Engine (70%)**: Menentukan shortlist berdasarkan RSI, Bollinger, Lead-Lag, dan Volume.
2.  **AI Legion (30%)**: Memberikan "Bias Support" (0.00 - 0.08) atau "Bias Caution" (0.00 - 0.06).
3.  **Veto Power**: Jika AI mendeteksi manipulasi (perubahan drastis orderbook atau berita negatif mendadak), AI bisa menaikkan `cautionBias` yang akan menghentikan eksekusi trade meskipun angka matematikanya bagus.

## 📂 Lokasi Kode
- Interface: `packages/ai-support/.../AiProvider.kt`
- Koordinator: `packages/ai-support/.../MultiAIClient.kt`
- Konfigurasi: `.secrets/binance-ai.env` dan `.env.kibot`

## 🔐 Keamanan & Redundansi
Jika satu AI (misalnya Gemini) terkena *Rate Limit*, koordinator akan otomatis mencoba provider berikutnya (Groq atau NVIDIA) sampai mendapatkan jawaban atau kembali ke logika matematika murni jika semua AI gagal "nyaut".
