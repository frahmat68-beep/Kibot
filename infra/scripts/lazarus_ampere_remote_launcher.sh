#!/usr/bin/env bash

set -e

mkdir -p "$HOME/ampere-hunt" "$HOME/logs" "$HOME/.ssh"
head -n 1 "$HOME/.ssh/authorized_keys" > "$HOME/.ssh/lazarus_ampere.pub"
chmod 600 "$HOME/.ssh/lazarus_ampere.pub"

install -m 755 /tmp/lazarus_ampere.sh "$HOME/ampere-hunt/lazarus_ampere.sh"

pkill -f lazarus_ampere.sh || true
tmux kill-session -t lazarus-ampere 2>/dev/null || true

export PATH="$HOME/bin:$HOME/.local/bin:$PATH"
export KIBOT_TELEGRAM_BOT_TOKEN="${KIBOT_TELEGRAM_BOT_TOKEN:-}"
export KIBOT_TELEGRAM_CHAT_ID="${KIBOT_TELEGRAM_CHAT_ID:-}"
tmux new-session -d -s lazarus-ampere "cd '$HOME/ampere-hunt' && ./lazarus_ampere.sh >> '$HOME/logs/lazarus_ampere.log' 2>&1"
sleep 5
tmux has-session -t lazarus-ampere
printf 'SESSION_OK\n'
tail -n 20 "$HOME/logs/lazarus_ampere.log" || true
