#!/system/bin/sh
# Minimal root-only fallback for the one allow-listed Glyph brightness node.
DIR='/data/adb/glyph_breathe'
BACKUP="$DIR/originals"
NODE='/sys/class/leds/aw210xx_led/all_white_leds_br'

case "$(id)" in *'uid=0'*) ;; *) echo 'Run as root: su -c "sh /sdcard/Download/glyph-breathe.sh"'; exit 1;; esac
[ -f "$NODE" ] || { echo "Unsupported/missing Glyph node: $NODE"; exit 1; }

max=255
if [ -r /sys/class/leds/aw210xx_led/max_brightness ]; then
  reported=$(cat /sys/class/leds/aw210xx_led/max_brightness 2>/dev/null)
  case "$reported" in ''|*[!0-9]*|0) ;; *) max="$reported";; esac
fi
[ "$max" -le 65535 ] 2>/dev/null || max=65535

backup_original() {
  current=$(cat "$NODE" 2>/dev/null)
  case "$current" in ''|*[!0-9]*) echo 'Could not read original brightness'; return 1;; esac
  [ "${#current}" -le 5 ] || { echo 'Original value is outside supported range'; return 1; }
  if [ -s "$BACKUP" ]; then
    saved_node=''
    saved_value=''
    IFS='|' read -r saved_node saved_value < "$BACKUP"
    [ "$saved_node" = "$NODE" ] || { echo 'Unexpected backup contents; refusing to write'; return 1; }
    case "$saved_value" in ''|*[!0-9]*) echo 'Invalid saved backup; refusing to write'; return 1;; esac
    return 0
  fi
  umask 077
  mkdir -p "$DIR" && chmod 700 "$DIR" || return 1
  printf '%s|%s\n' "$NODE" "$current" > "$BACKUP.tmp" || return 1
  chmod 600 "$BACKUP.tmp" && mv "$BACKUP.tmp" "$BACKUP" || { rm -f "$BACKUP.tmp"; return 1; }
}

write_value() {
  value="$1"
  case "$value" in ''|*[!0-9]*) echo 'Enter a whole number'; return 1;; esac
  [ "${#value}" -le 5 ] || { echo 'Value too large'; return 1; }
  [ "$value" -le "$max" ] 2>/dev/null || { echo "Value must be between 0 and $max"; return 1; }
  backup_original || return 1
  printf '%s\n' "$value" > "$NODE" || { echo 'Glyph write failed'; return 1; }
  return 0
}

echo "Glyph Breathe root fallback"
echo "Target: $NODE (max $max)"
while :; do
  printf '\nCurrent: '; cat "$NODE" 2>/dev/null
  echo '1) Set brightness'
  echo '2) Turn off'
  echo '3) Breathe (Ctrl+C stops, then restore)'
  echo '4) Restore original'
  echo '5) Quit'
  printf 'Choose: '
  read choice || choice=5
  case "$choice" in
    1)
      printf "Brightness (0-$max): "
      read value || continue
      write_value "$value" && echo 'Brightness changed; original value backed up.'
      ;;
    2)
      write_value 0 && echo 'Glyph off; original value backed up.'
      ;;
    3)
      backup_original || continue
      printf "Peak brightness (1-$max, default 25): "
      read peak || peak=25
      case "$peak" in ''|*[!0-9]*) peak=25;; esac
      [ "${#peak}" -le 5 ] || peak=$max
      [ "$peak" -le "$max" ] 2>/dev/null || peak=$max
      [ "$peak" -ge 1 ] 2>/dev/null || peak=1
      step=$((peak / 25)); [ "$step" -lt 1 ] && step=1
      trap 'sh /sdcard/Download/restore-glyph.sh; trap - INT TERM HUP; exit 130' INT TERM HUP
      while :; do
        i=1
        while [ "$i" -le "$peak" ]; do printf '%s\n' "$i" > "$NODE" || break; sleep 0.05; i=$((i + step)); done
        printf '%s\n' "$peak" > "$NODE" 2>/dev/null
        i=$peak
        while [ "$i" -ge 1 ]; do printf '%s\n' "$i" > "$NODE" 2>/dev/null; sleep 0.05; i=$((i - step)); done
        printf '0\n' > "$NODE" 2>/dev/null
        sleep 2
      done
      ;;
    4)
      sh /sdcard/Download/restore-glyph.sh
      ;;
    5) echo 'Exiting. If changed, restore with restore-glyph.sh before uninstalling/ending use.'; exit 0;;
    *) echo 'Choose 1–5.';;
  esac
done
