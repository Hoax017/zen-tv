#!/usr/bin/env bash
# Zen TV — make an Android TV / Google TV faster and ad-free, safely and reversibly.
# Mac & Linux. Run:  ./zen.sh        (Windows: double-click zen.bat)
#
# Everything is reversible: apps are disabled (never uninstalled) and every change is logged
# in a backup folder that "Restore" uses to put the TV back exactly as it was.

set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
CONF="$ROOT/.zen-tv.conf"
BACKUPS="$ROOT/backups"
PROFILES="$ROOT/profiles"
APK="${ZEN_APK:-$ROOT/dist/zen-home.apk}"
SVC="com.zen.tvhome/com.zen.tvhome.HomeKeyService"
ADB=""
SERIAL=""

if [ -t 1 ]; then B=$'\e[1m'; G=$'\e[32m'; Y=$'\e[33m'; R=$'\e[31m'; D=$'\e[2m'; N=$'\e[0m'; else B=; G=; Y=; R=; D=; N=; fi
say()  { printf '%s\n' "$*"; }
ok()   { printf '%s✔ %s%s\n' "$G" "$*" "$N"; }
warn() { printf '%s! %s%s\n' "$Y" "$*" "$N"; }
err()  { printf '%s✘ %s%s\n' "$R" "$*" "$N"; }
title(){ printf '\n%s== %s ==%s\n' "$B" "$*" "$N"; }
ask()  { local a; read -r -p "$1 " a; printf '%s' "$a"; }
yes()  { local a; a=$(ask "$1 [y/N]"); case "$a" in y|Y|yes|o|O|oui) return 0;; *) return 1;; esac; }
pause(){ read -r -p "${D}Press Enter to continue…${N}" _; }

# adb never reads our stdin (it would swallow the rest of a loop's input).
adbc() { "$ADB" -s "$SERIAL" "$@" </dev/null; }
sh_()  { adbc shell "$@" | tr -d '\r'; }

# ---------------------------------------------------------------- adb

ensure_adb() {
  if [ -x "$ROOT/tools/platform-tools/adb" ]; then ADB="$ROOT/tools/platform-tools/adb"; return 0; fi
  if command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"; return 0; fi
  local os
  case "$(uname -s)" in Darwin) os=darwin;; Linux) os=linux;; *) err "Unsupported system"; exit 1;; esac
  warn "adb (Android's official TV/phone connection tool) is not installed."
  say "It can be downloaded from Google: https://dl.google.com/android/repository/platform-tools-latest-$os.zip (~10 MB)"
  yes "Download it into $ROOT/tools ?" || { err "adb is required."; exit 1; }
  mkdir -p "$ROOT/tools"
  curl -fL --progress-bar -o "$ROOT/tools/pt.zip" "https://dl.google.com/android/repository/platform-tools-latest-$os.zip" \
    && unzip -q -o "$ROOT/tools/pt.zip" -d "$ROOT/tools" && rm -f "$ROOT/tools/pt.zip" \
    || { err "Download failed."; exit 1; }
  ADB="$ROOT/tools/platform-tools/adb"
  ok "adb installed"
}

# ---------------------------------------------------------------- finding the TV

local_prefix() {
  local ip=""
  if [ "$(uname -s)" = Darwin ]; then
    local ifc; ifc=$(route -n get default 2>/dev/null | awk '/interface:/{print $2}')
    ip=$(ipconfig getifaddr "${ifc:-en0}" 2>/dev/null)
  else
    ip=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") print $(i+1)}')
  fi
  [ -n "$ip" ] && printf '%s' "${ip%.*}"
}

port_open() {
  if [ "$(uname -s)" = Darwin ]; then nc -z -G 1 "$1" 5555 >/dev/null 2>&1
  elif command -v timeout >/dev/null 2>&1; then timeout 1 bash -c "</dev/tcp/$1/5555" >/dev/null 2>&1
  else nc -z -w 1 "$1" 5555 >/dev/null 2>&1; fi
}

device_name() {  # "<manufacturer> <model>" or a status word
  local state; state=$("$ADB" -s "$1" get-state 2>&1 </dev/null)
  case "$state" in
    device) "$ADB" -s "$1" shell 'echo "$(getprop ro.product.manufacturer) $(getprop ro.product.model) (Android $(getprop ro.build.version.release))"' </dev/null | tr -d '\r';;
    *unauthorized*) printf 'waiting for approval on the TV';;
    *) printf 'not reachable';;
  esac
}

connect_to() {
  local target="$1" out
  case "$target" in *:*) ;; *) target="$target:5555";; esac
  out=$("$ADB" connect "$target" 2>&1 </dev/null)
  if printf '%s' "$out" | grep -qi "no route to host"; then
    # macOS sometimes blocks a stale adb server from the local network: a fresh one fixes it.
    "$ADB" kill-server >/dev/null 2>&1; "$ADB" start-server >/dev/null 2>&1
    out=$("$ADB" connect "$target" 2>&1 </dev/null)
  fi
  sleep 1
  local state; state=$("$ADB" -s "$target" get-state 2>&1 </dev/null)
  if printf '%s' "$state" | grep -q unauthorized; then
    warn "Look at your TV: a window asks to allow USB debugging. Tick \"Always allow\" and press OK."
    local i; for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do
      sleep 2; state=$("$ADB" -s "$target" get-state 2>&1 </dev/null)
      [ "$state" = device ] && break
    done
  fi
  if [ "$state" = device ]; then
    SERIAL="$target"; printf 'SERIAL=%s\n' "$SERIAL" > "$CONF"
    ok "Connected to $(device_name "$SERIAL") at $SERIAL"
    return 0
  fi
  err "Could not connect to $target ($out)"
  return 1
}

help_enable_debugging() {
  title "Turn on network debugging on the TV (one time)"
  say "1. Settings → System → About (or Device Preferences → About)."
  say "2. Click \"Android TV OS build\" (or \"Build\") 7 times: \"You are now a developer\"."
  say "3. Back to Settings → System → Developer options:"
  say "   turn on \"USB debugging\" and, if present, \"Network debugging\" / \"Wireless debugging\"."
  say "4. The TV and this computer must be on the same Wi-Fi / network."
}

find_tv() {
  title "Finding your TV"
  if [ -f "$CONF" ]; then
    # shellcheck disable=SC1090
    . "$CONF"
    if [ -n "${SERIAL:-}" ] && connect_to "$SERIAL" 2>/dev/null; then return 0; fi
    SERIAL=""
  fi
  local prefix; prefix=$(local_prefix)
  local found=""
  if [ -n "$prefix" ]; then
    say "Scanning $prefix.1-254 for TVs with debugging turned on…"
    local tmp; tmp=$(mktemp -d)
    local i; for i in $(seq 1 254); do ( port_open "$prefix.$i" && touch "$tmp/$prefix.$i" ) & done; wait
    found=$(ls "$tmp" 2>/dev/null | sort -t. -k4 -n); rm -rf "$tmp"
  fi
  local list=() ip
  for ip in $found; do list+=("$ip"); done
  if [ ${#list[@]} -eq 0 ]; then
    warn "No TV found automatically."
    help_enable_debugging
    local manual; manual=$(ask "Type the TV's IP address (Settings → Network), or press Enter to quit:")
    [ -z "$manual" ] && exit 1
    case "$manual" in
      *:*) if yes "Is this a \"Wireless debugging\" pairing address (Android 11+ with a pairing code)?"; then
             local code; code=$(ask "Pairing code shown on the TV:")
             "$ADB" pair "$manual" "$code" </dev/null
             manual=$(ask "Now type the IP:port shown under \"IP address & port\" on the TV:")
           fi;;
    esac
    connect_to "$manual" || exit 1
    return 0
  fi
  local n=1
  for ip in "${list[@]}"; do
    "$ADB" connect "$ip:5555" >/dev/null 2>&1 </dev/null
    printf '  %s%d)%s %-16s %s\n' "$B" "$n" "$N" "$ip" "$(device_name "$ip:5555")"
    n=$((n + 1))
  done
  local pick=1
  [ ${#list[@]} -gt 1 ] && pick=$(ask "Which one is your TV? [1-${#list[@]}]")
  case "$pick" in ''|*[!0-9]*) pick=1;; esac
  connect_to "${list[$((pick - 1))]}" || exit 1
}

# ---------------------------------------------------------------- backup

device_dir() { printf '%s/%s' "$BACKUPS" "$(printf '%s' "$SERIAL" | tr ':/' '__')"; }
latest_backup() { local d; d="$(device_dir)"; [ -f "$d/LATEST" ] && printf '%s/%s' "$d" "$(cat "$d/LATEST")"; }

backup() {
  title "Backup"
  local d; d="$(device_dir)"; local name; name=$(date +%Y%m%d-%H%M%S)
  local B_="$d/$name"; mkdir -p "$B_"
  sh_ getprop > "$B_/getprop.txt"
  sh_ pm list packages -d | sed 's/package://' | sort > "$B_/packages_disabled.txt"
  sh_ pm list packages -e | sed 's/package://' | sort > "$B_/packages_enabled.txt"
  sh_ pm list packages -s | sed 's/package://' | sort > "$B_/packages_system.txt"
  sh_ pm list packages -3 | sed 's/package://' | sort > "$B_/packages_user.txt"
  local ns; for ns in global secure system; do sh_ settings list "$ns" > "$B_/settings_$ns.txt"; done
  sh_ cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -1 > "$B_/home_activity.txt"
  : > "$B_/disabled_by_us.txt"; : > "$B_/suspended_by_us.txt"
  adbc exec-out screencap -p > "$B_/screen_before.png" 2>/dev/null
  printf '%s' "$name" > "$d/LATEST"
  ok "Saved in $B_"
}

need_backup() {
  [ -n "$(latest_backup)" ] && return 0
  warn "No backup yet for this TV: making one first."
  backup
}

# ---------------------------------------------------------------- debloat

is_protected() {
  grep -v '^#' "$PROFILES/protected.txt" | grep -v '^~' | grep -qx "$1"
}

scan_blocked() {  # advanced scan: package name contains a "~word"
  local w; for w in $(grep '^~' "$PROFILES/protected.txt" | cut -c2-); do
    case "$1" in *"$w"*) return 0;; esac
  done
  return 1
}

trim() { local s="$1"; s="${s#"${s%%[![:space:]]*}"}"; printf '%s' "${s%"${s##*[![:space:]]}"}"; }

disable_pkg() {  # $1 package, $2 "suspend" or ""
  local B_; B_=$(latest_backup)
  local out; out=$(sh_ pm disable-user --user 0 "$1" 2>&1)
  if printf '%s' "$out" | grep -q "disabled-user"; then
    grep -qx "$1" "$B_/disabled_by_us.txt" || printf '%s\n' "$1" >> "$B_/disabled_by_us.txt"
    if [ "$2" = suspend ]; then
      sh_ pm suspend --user 0 "$1" >/dev/null 2>&1 && { grep -qx "$1" "$B_/suspended_by_us.txt" || printf '%s\n' "$1" >> "$B_/suspended_by_us.txt"; }
    fi
    ok "Disabled $1"
  else
    warn "Skipped $1 ($out)"
  fi
}

debloat() {
  need_backup
  title "Clean up apps"
  local manuf; manuf=$(sh_ getprop ro.product.manufacturer | tr '[:upper:]' '[:lower:]' | tr -d ' ')
  local enabled; enabled=$(sh_ pm list packages -e | sed 's/package://')
  local files=("$PROFILES/common.txt")
  [ -f "$PROFILES/$manuf.txt" ] && files+=("$PROFILES/$manuf.txt")
  [ -f "$PROFILES/$manuf.txt" ] || warn "No tested list for \"$manuf\" yet: only the generic Google list is used. (The advanced scan can find brand apps.)"

  local rec=() rec_s=() opt=() opt_s=() opt_l=() line pkg level label desc
  local f
  for f in "${files[@]}"; do
    while IFS='|' read -r pkg level label desc; do
      pkg=$(trim "$pkg"); level=$(trim "${level:-}"); label=$(trim "${label:-}"); desc=$(trim "${desc:-}")
      case "$pkg" in ''|\#*) continue;; esac
      printf '%s\n' "$enabled" | grep -qx "$pkg" || continue
      is_protected "$pkg" && continue
      local susp=""; case "$level" in *+suspend) susp=suspend;; esac
      case "$level" in
        recommended*) rec+=("$pkg"); rec_s+=("$susp"); say "  ${B}${label}${N} — $desc ${D}($pkg)${N}";;
        optional*) opt+=("$pkg"); opt_s+=("$susp"); opt_l+=("$label — $desc");;
      esac
    done < "$f"
  done

  local chosen=() chosen_s=() i
  if [ ${#rec[@]} -gt 0 ]; then
    say ""; say "Above: ${#rec[@]} ads / telemetry / junk apps."
    local a; a=$(ask "Disable them? [A]ll / [C]hoose one by one / [N]one:")
    case "$a" in
      a|A|"") for i in "${!rec[@]}"; do chosen+=("${rec[$i]}"); chosen_s+=("${rec_s[$i]}"); done;;
      c|C) for i in "${!rec[@]}"; do yes "  Disable ${rec[$i]}?" && { chosen+=("${rec[$i]}"); chosen_s+=("${rec_s[$i]}"); }; done;;
    esac
  else
    ok "No known ads / telemetry apps are active."
  fi

  if [ ${#opt[@]} -gt 0 ]; then
    title "Features you may use (default: keep)"
    for i in "${!opt[@]}"; do
      yes "  Disable ${B}${opt_l[$i]}${N}?" && { chosen+=("${opt[$i]}"); chosen_s+=("${opt_s[$i]}"); }
    done
  fi

  if yes "Advanced: also review other $manuf system apps not in the lists (for experienced users)?"; then
    local sysp; sysp=$(sh_ pm list packages -s -e | sed 's/package://' | sort)
    local known; known=$(cat "${files[@]}" | grep -v '^#' | cut -d'|' -f1 | tr -d ' ')
    for pkg in $sysp; do
      case "$pkg" in com.android.*|com.google.*|android*) continue;; esac
      printf '%s\n' "$known" | grep -qx "$pkg" && continue
      is_protected "$pkg" && continue
      scan_blocked "$pkg" && continue
      yes "  Disable ${pkg}?" && { chosen+=("$pkg"); chosen_s+=(""); }
    done
  fi

  [ ${#chosen[@]} -eq 0 ] && { ok "Nothing to change."; return; }
  title "Applying"
  for i in "${!chosen[@]}"; do disable_pkg "${chosen[$i]}" "${chosen_s[$i]}"; done
  ok "Done. Everything can be undone with \"Restore\"."
}

reapply() {
  local B_; B_=$(latest_backup); [ -z "$B_" ] && { warn "Nothing applied yet."; return; }
  title "Re-applying after a TV update / reboot"
  local enabled; enabled=$(sh_ pm list packages -e | sed 's/package://')
  local pkg n=0
  for pkg in $(cat "$B_/disabled_by_us.txt"); do
    if printf '%s\n' "$enabled" | grep -qx "$pkg"; then
      n=$((n + 1)); warn "$pkg came back: disabling it again and suspending it"
      disable_pkg "$pkg" suspend
    fi
  done
  [ $n -eq 0 ] && ok "All disabled apps are still disabled."
}

# ---------------------------------------------------------------- launcher

install_launcher() {
  need_backup
  title "Install Zen Home"
  [ -f "$APK" ] || { err "dist/zen-home.apk is missing. Download the release zip, or build it: ./launcher/build.sh"; return; }
  adbc install -r "$APK" | tail -1
  local cur; cur=$(sh_ settings get secure enabled_accessibility_services)
  case "$cur" in
    *"$SVC"*) ;;
    ""|null) sh_ settings put secure enabled_accessibility_services "$SVC";;
    *) sh_ settings put secure enabled_accessibility_services "${cur}:${SVC}";;
  esac
  sh_ settings put secure accessibility_enabled 1
  sh_ am start -n com.zen.tvhome/.HomeActivity >/dev/null
  ok "Zen Home installed. The Home button of the remote now opens it."
  say "${D}To give the Home button back to the TV without a computer: Settings → Accessibility → \"Zen Home – Home button\" → off.${N}"
}

# ---------------------------------------------------------------- restore

restore() {
  local d; d="$(device_dir)"
  local list; list=$(ls -1 "$d" 2>/dev/null | grep -v LATEST)
  [ -z "$list" ] && { warn "No backup for this TV."; return; }
  title "Restore"
  local B_; B_=$(latest_backup)
  say "Latest backup: $B_"
  yes "Put the TV back exactly as in this backup?" || return
  local pkg
  for pkg in $(cat "$B_/disabled_by_us.txt"); do say "enable $pkg: $(sh_ pm enable --user 0 "$pkg")"; done
  for pkg in $(cat "$B_/suspended_by_us.txt"); do sh_ pm unsuspend --user 0 "$pkg" >/dev/null; done
  local k v
  for k in enabled_accessibility_services accessibility_enabled; do
    v=$(grep "^$k=" "$B_/settings_secure.txt" | cut -d= -f2-)
    if [ -n "$v" ]; then sh_ settings put secure "$k" "$v"; else sh_ settings delete secure "$k" >/dev/null; fi
  done
  local home; home=$(grep / "$B_/home_activity.txt" | tail -1)
  [ -n "$home" ] && sh_ cmd package set-home-activity "$home" >/dev/null
  sh_ pm list packages | grep -qx 'package:com.zen.tvhome' && sh_ pm uninstall com.zen.tvhome >/dev/null
  sh_ pm list packages -d | sed 's/package://' | sort > "$B_/packages_disabled_after_restore.txt"
  if diff -q "$B_/packages_disabled.txt" "$B_/packages_disabled_after_restore.txt" >/dev/null; then
    ok "The TV is back to its original state."
  else
    warn "Differences with the backup (< before, > now):"; diff "$B_/packages_disabled.txt" "$B_/packages_disabled_after_restore.txt"
  fi
  : > "$B_/disabled_by_us.txt"; : > "$B_/suspended_by_us.txt"
  sh_ input keyevent KEYCODE_HOME
}

screenshot() {
  mkdir -p "$ROOT/screenshots"
  local f; f="$ROOT/screenshots/tv-$(date +%Y%m%d-%H%M%S).png"
  adbc exec-out screencap -p > "$f" && ok "Saved $f"
}

# ---------------------------------------------------------------- menu

main() {
  printf '%s\n' "${B}Zen TV${N} — faster, ad-free Android TV / Google TV. Nothing is uninstalled; everything can be undone."
  ensure_adb
  find_tv
  while true; do
    title "What do you want to do?  ${D}($(device_name "$SERIAL"))${N}"
    say "  1) Back up the TV (done automatically before any change)"
    say "  2) Clean up: ads, telemetry, useless apps (asks before each feature)"
    say "  3) Install the Zen Home screen"
    say "  4) Re-apply after a TV update (if removed apps came back)"
    say "  5) Restore the TV to how it was"
    say "  6) Take a screenshot of the TV"
    say "  7) Connect to another TV"
    say "  0) Quit"
    case "$(ask '>')" in
      1) backup;; 2) debloat;; 3) install_launcher;; 4) reapply;; 5) restore;; 6) screenshot;;
      7) rm -f "$CONF"; SERIAL=""; find_tv;;
      0|q|Q) exit 0;;
    esac
  done
}

if [ "${1:-}" = "--restore" ]; then ensure_adb; find_tv; restore; exit; fi
main
