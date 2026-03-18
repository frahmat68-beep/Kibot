# Assumptions

- Single private owner account only.
- One Indodax account dedicated to the bot.
- Spot trading only.
- No withdraw permission on API key.
- Android is the default active engine.
- Mac is the default standby engine.
- Live trading exists, but runtime should remain `OFF` until health checks pass.
- Daily equity resets at `00:00 Asia/Jakarta`.
- Hard daily loss stop is 25% of opening daily equity.
- More than one position is technically allowed, but risk engine may constrain it to fewer in practice.
- Data retention for high-volume operational data is 90 days rolling.

