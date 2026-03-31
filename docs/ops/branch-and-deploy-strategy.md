# Branch And Deploy Strategy

## Source Of Truth
- Keep one main source branch for now.
- Do not split `KiDax`, `Kinance`, and `KiBot` into separate long-lived branches.
- Shared trading logic stays aligned by editing one code path, then selecting runtime behavior with profile/env/service separation.

## Why
- The two trading bots must stay one voice in aggression, target pursuit, rotation, and introspection.
- The exchange runtime must differ, but the mission must not drift.
- Separate deployment paths are safer than separate branches for this stage.

## Deployment Split
- `deploy-kidax.yml` -> KiDax / Indodax server only
- `deploy-kinance.yml` -> Kinance / Binance server only
- Future: `deploy-kibot.yml` -> reporting/orchestration brain only

## Identity Rules
### KiDax
- `BOT_ID=kidax`
- `BOT_PROFILE_KEY=kidax`
- `KIBOT_EXCHANGE_KIND=INDODAX`
- root `/home/ubuntu/KiDax`
- port `8787`

### Kinance
- `BOT_ID=kinance`
- `BOT_PROFILE_KEY=kinance`
- `KIBOT_EXCHANGE_KIND=BINANCE_SPOT`
- root `/home/ubuntu/Kinance`
- port `8788`

### KiBot
- reporting/orchestration brain only
- no direct reuse of exchange trading env files

## Guardrail
- Never deploy a runtime/service/env bundle to the wrong root.
- Never reuse SSH folders across exchanges.
- If a change is Binance-only, keep KiDax deploy path ignored unless intentional.
