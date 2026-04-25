# GitHub Actions Three-Node Direction

## Workflows

- `deploy-kidax.yml`
- `deploy-kinance.yml`
- monitoring / Batam workflow can be added separately if remote deploy is automated later

## Reality

Saat ini Batam masih paling aman di-deploy manual/SSH karena ia memegang:

- Ollama config
- gateway config
- Polymarket runtime

Perubahan di Batam sering menyentuh unit file + env + model runtime, jadi rollout manual lebih aman dibanding auto-push buta.

## Principle

- SG workflow hanya untuk `kidax-engine` dan `kinance-engine` stack
- Batam jangan diikat ke workflow yang sama dengan SG
- perubahan AI brain/gateway/polymarket harus lolos soak test khusus
