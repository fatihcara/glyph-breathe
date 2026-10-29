#!/system/bin/sh
# Restore the one saved Glyph brightness value. Safe to run after app uninstall.
DIR='/data/adb/glyph_breathe'
BACKUP="$DIR/originals"
NODE='/sys/class/leds/aw210xx_led/all_white_leds_br'

case "$(id)" in *'uid=0'*) ;; *) echo 'ERROR: run as root (su -c ...).'; exit 1;; esac
[ -r "$BACKUP" ] || { echo "No readable backup at $BACKUP"; exit 1; }
[ -s "$BACKUP" ] || { echo 'Backup is empty; no value to restore.'; exit 1; }
[ -e "$NODE" ] || { echo "Glyph node is absent; backup retained: $BACKUP"; exit 1; }

count=0
failed=0
while IFS='|' read -r node value; do
  [ -n "$node" ] || continue
  if [ "$node" != "$NODE" ]; then
    echo "REFUSED: unsupported path in backup: $node"
    failed=1
    continue
  fi
  case "$value" in ''|*[!0-9]*) echo 'REFUSED: invalid saved brightness'; failed=1; continue;; esac
  [ "${#value}" -le 5 ] || { echo 'REFUSED: saved value too large'; failed=1; continue; }
  max=65535
  if [ -r /sys/class/leds/aw210xx_led/max_brightness ]; then
    observed=$(cat /sys/class/leds/aw210xx_led/max_brightness 2>/dev/null)
    case "$observed" in ''|*[!0-9]*|0) ;; *) max="$observed";; esac
  fi
  [ "$value" -le "$max" ] 2>/dev/null || { echo "REFUSED: value exceeds reported maximum ($max)"; failed=1; continue; }
  count=$((count + 1))
  if printf '%s\n' "$value" > "$NODE" 2>/dev/null; then
    echo "RESTORED: $NODE = $value"
  else
    echo "FAILED: could not write Glyph node; backup retained"
    failed=1
  fi
done < "$BACKUP"

if [ "$count" -gt 0 ] && [ "$failed" -eq 0 ]; then
  rm -f "$BACKUP" "$BACKUP.tmp"
  rmdir "$DIR" 2>/dev/null
  echo 'Restore completed; saved backup removed.'
  exit 0
fi
echo 'Restore incomplete. Backup retained for inspection/retry.'
exit 1
