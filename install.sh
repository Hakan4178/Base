#!/bin/bash
# Benim Gamepad Server - Tek Komut Kurulum
# Kullanım: chmod +x install.sh && ./install.sh

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

VENV_DIR="venv"
DIR="$(cd "$(dirname "$0")" && pwd)"

echo -e "${CYAN}"
cat << "EOF"
═══════════════════════════════════════════════════════════
  🎮 Benim Gamepad/Mouse Server - Kurulum
═══════════════════════════════════════════════════════════
EOF
echo -e "${NC}"

# ─────────────────────────────────────────────────────────────
# 1. SİSTEM KONTROLÜ
# ─────────────────────────────────────────────────────────────
echo -e "${CYAN}[1/4]${NC} Sistem kontrol ediliyor..."

# Python3
if ! command -v python3 &> /dev/null; then
    echo -e "${RED}❌ Python3 bulunamadı!${NC}"
    echo "  → sudo apt install python3 python3-venv"
    exit 1
fi
echo -e "${GREEN}  ✓ Python3${NC}"

# python3-venv
if ! python3 -c "import venv" 2>/dev/null; then
    echo -e "${YELLOW}  → python3-venv kuruluyor...${NC}"
    sudo apt-get update -qq && sudo apt-get install -y python3-venv python3-full
fi
echo -e "${GREEN}  ✓ python3-venv${NC}"

# Display server
if [ "$XDG_SESSION_TYPE" = "wayland" ]; then
    echo -e "${CYAN}  → Wayland tespit edildi${NC}"
    DISPLAY_TYPE="wayland"
else
    echo -e "${CYAN}  → X11 tespit edildi${NC}"
    DISPLAY_TYPE="x11"
fi

# ─────────────────────────────────────────────────────────────
# 2. SİSTEM ARAÇLARI
# ─────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}[2/4]${NC} Sistem araçları kuruluyor..."

# xdotool (X11)
if ! command -v xdotool &> /dev/null; then
    echo -e "${YELLOW}  → xdotool kuruluyor...${NC}"
    sudo apt-get install -y xdotool 2>/dev/null || true
fi
[ -x "$(command -v xdotool)" ] && echo -e "${GREEN}  ✓ xdotool${NC}"

# ydotool (Wayland)
if ! command -v ydotool &> /dev/null; then
    echo -e "${YELLOW}  → ydotool kuruluyor...${NC}"
    sudo apt-get install -y ydotool 2>/dev/null || true
fi
[ -x "$(command -v ydotool)" ] && echo -e "${GREEN}  ✓ ydotool${NC}"

# uinput modülü
UINPUT_OK=false
if [ -c "/dev/uinput" ]; then
    UINPUT_OK=true
    echo -e "${GREEN}  ✓ /dev/uinput${NC}"
else
    if sudo modprobe uinput 2>/dev/null; then
        echo "uinput" | sudo tee /etc/modules-load.d/uinput.conf > /dev/null 2>&1
        UINPUT_OK=true
        echo -e "${GREEN}  ✓ uinput modülü yüklendi${NC}"
    else
        echo -e "${YELLOW}  ⚠ uinput yok (xdotool/ydotool kullanılacak)${NC}"
    fi
fi

# uinput izinleri
if [ "$UINPUT_OK" = true ]; then
    if [ ! -f "/etc/udev/rules.d/99-uinput.rules" ]; then
        echo 'KERNEL=="uinput", MODE="0660", GROUP="input"' | sudo tee /etc/udev/rules.d/99-uinput.rules > /dev/null
        sudo udevadm control --reload-rules 2>/dev/null || true
    fi
    groups | grep -q input || sudo usermod -aG input "$USER" 2>/dev/null || true
fi

# ─────────────────────────────────────────────────────────────
# 3. PYTHON VENV
# ─────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}[3/4]${NC} Python ortamı hazırlanıyor..."

cd "$DIR"
[ -d "$VENV_DIR" ] && rm -rf "$VENV_DIR"
python3 -m venv "$VENV_DIR"
echo -e "${GREEN}  ✓ venv oluşturuldu${NC}"

"$VENV_DIR/bin/pip" install --upgrade pip -q

# evdev (uinput için)
if [ "$UINPUT_OK" = true ]; then
    "$VENV_DIR/bin/pip" install evdev -q && echo -e "${GREEN}  ✓ evdev${NC}"
fi

# pynput (X11 için)
"$VENV_DIR/bin/pip" install pynput -q 2>/dev/null && echo -e "${GREEN}  ✓ pynput${NC}" || true

# ─────────────────────────────────────────────────────────────
# 4. RUN SCRIPT
# ─────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}[4/4]${NC} Çalıştırma scripti oluşturuluyor..."

cat > "$DIR/run.sh" << 'RUNEOF'
#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

# ydotoold gerekiyorsa başlat
if command -v ydotool &> /dev/null && ! pgrep -x ydotoold > /dev/null; then
    echo "🔧 ydotoold başlatılıyor..."
    sudo ydotoold &
    sleep 1
fi

# Çalıştır
if [ -f "venv/bin/python" ]; then
    sudo venv/bin/python server.py "$@"
else
    sudo python3 server.py "$@"
fi
RUNEOF

chmod +x "$DIR/run.sh"
echo -e "${GREEN}  ✓ run.sh oluşturuldu${NC}"

# ─────────────────────────────────────────────────────────────
# ÖZET
# ─────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}✅ Kurulum tamamlandı!${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "Kullanım:"
echo -e "  ${GREEN}./run.sh${NC}           Normal başlat"
echo -e "  ${GREEN}./run.sh -d${NC}        Debug modunda başlat"
echo -e "  ${GREEN}./run.sh -h${NC}        Yardım"
echo ""
echo -e "Backend: ${CYAN}$DISPLAY_TYPE${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
