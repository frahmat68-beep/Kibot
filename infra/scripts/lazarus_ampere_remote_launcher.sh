#!/usr/bin/env bash

set -e

mkdir -p "$HOME/ampere-hunt" "$HOME/logs" "$HOME/.ssh"
head -n 1 "$HOME/.ssh/authorized_keys" > "$HOME/.ssh/lazarus_ampere.pub"
chmod 600 "$HOME/.ssh/lazarus_ampere.pub"

install -m 755 /tmp/lazarus_ampere.sh "$HOME/ampere-hunt/lazarus_ampere.sh"

pkill -f lazarus_ampere.sh || true
tmux kill-session -t lazarus-ampere 2>/dev/null || true

cat > "$HOME/ampere-hunt/run_lazarus_ampere.sh" <<'EOF'
#!/usr/bin/env bash
export PATH="$HOME/bin:$HOME/.local/bin:$PATH"
export KICRYP_TELEGRAM_BOT_TOKEN="8583424689:AAHRe8drD2hmuyN48RoFv9Me0oXwcXnSoSE"
export KICRYP_TELEGRAM_CHAT_ID="1346696386"
cd "$HOME/ampere-hunt"
./lazarus_ampere.sh >> "$HOME/logs/lazarus_ampere.log" 2>&1
EOF

chmod 755 "$HOME/ampere-hunt/run_lazarus_ampere.sh"
tmux new-session -d -s lazarus-ampere "$HOME/ampere-hunt/run_lazarus_ampere.sh"
sleep 5
tmux has-session -t lazarus-ampere
printf 'SESSION_OK\n'
tail -n 20 "$HOME/logs/lazarus_ampere.log" || true
