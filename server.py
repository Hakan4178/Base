#!/usr/bin/env python3
"""
Benim Gamepad/Mouse UDP Server
Tek dosyada tüm sistem - Linux Wayland/X11
"""

import os
import sys
import socket
import subprocess
import shutil
import signal
import threading
import struct
import time
from datetime import datetime
from abc import ABC, abstractmethod

# ═══════════════════════════════════════════════════════════════
# VERSİYON
# ═══════════════════════════════════════════════════════════════
VERSION = "1.0.2"

# ═══════════════════════════════════════════════════════════════
# CONFIG
# ═══════════════════════════════════════════════════════════════
class Config:
    DEBUG_MODE = False
    UDP_HOST = '0.0.0.0'
    UDP_PORT = 26760
    
    PACKET_PING = 0x7F
    PACKET_JOYSTICK = 0x01
    PACKET_MOUSE_MOVE = 0x02
    PACKET_MOUSE_BUTTON = 0x03
    PACKET_MOUSE_WHEEL = 0x04
    PACKET_GYRO = 0x0D
    
    MOUSE_SENSITIVITY = 1.6
    SCROLL_SENSITIVITY = 2
    JOYSTICK_DEADZONE = 10
    JOYSTICK_AS_MOUSE = False
    
    GAMEPAD_BUTTONS = {
        0x01: ("A", "BTN_A"),
        0x02: ("B", "BTN_B"),
        0x04: ("X", "BTN_X"),
        0x08: ("Y", "BTN_Y"),
        0x10: ("START", "BTN_START"),
        0x20: ("SELECT", "BTN_SELECT"),
        0x40: ("L1", "BTN_TL"),
        0x80: ("R1", "BTN_TR"),
    }
    
    BACKEND = "auto"
    LOG_FILE = "gamepad_server.log"
    LOG_PACKETS = False
    LOG_MOUSE_MOVE = False
    LOG_RAW_BYTES = False
    
    @classmethod
    def enable_debug(cls):
        cls.DEBUG_MODE = True
        cls.LOG_PACKETS = True
        cls.LOG_MOUSE_MOVE = True
        cls.LOG_RAW_BYTES = True

# ═══════════════════════════════════════════════════════════════
# BACKEND BASE
# ═══════════════════════════════════════════════════════════════
class InputBackend(ABC):
    name = "abstract"
    method = "unknown"
    library = "none"
    
    @abstractmethod
    def mouse_move(self, dx, dy): pass
    @abstractmethod
    def mouse_button(self, button, pressed): pass
    @abstractmethod
    def mouse_scroll(self, delta): pass
    
    def gamepad_buttons_update(self, buttons, prev): pass
    def gamepad_axes(self, x, y): pass
    def close(self): pass
    
    def get_info(self):
        return {"name": self.name, "method": self.method, "library": self.library}

# ═══════════════════════════════════════════════════════════════
# EVDEV BACKEND
# ═══════════════════════════════════════════════════════════════
class EvdevBackend(InputBackend):
    name = "evdev"
    method = "Kernel uinput (sanal cihaz)"
    library = "python-evdev"
    
    def __init__(self):
        import evdev
        from evdev import UInput, ecodes, AbsInfo
        self.ecodes = ecodes
        
        if not os.path.exists("/dev/uinput"):
            raise FileNotFoundError("/dev/uinput bulunamadı")
        
        mouse_cap = {
            ecodes.EV_REL: [ecodes.REL_X, ecodes.REL_Y, ecodes.REL_WHEEL],
            ecodes.EV_KEY: [ecodes.BTN_LEFT, ecodes.BTN_RIGHT, ecodes.BTN_MIDDLE],
        }
        self.mouse = UInput(mouse_cap, name="Benim Virtual Mouse")
        
        gamepad_cap = {
            ecodes.EV_KEY: [
                ecodes.BTN_A, ecodes.BTN_B, ecodes.BTN_X, ecodes.BTN_Y,
                ecodes.BTN_TL, ecodes.BTN_TR, ecodes.BTN_SELECT, ecodes.BTN_START,
            ],
            ecodes.EV_ABS: [
                (ecodes.ABS_X, AbsInfo(0, -127, 127, 0, 15, 0)),
                (ecodes.ABS_Y, AbsInfo(0, -127, 127, 0, 15, 0)),
                # ────────────── GYRO EKLEMESİ ────────────── Yemek molası backend kaldı
                (ecodes.ABS_RX, AbsInfo(0, -32767, 32767, 0, 0, 0)),  # roll
                (ecodes.ABS_RY, AbsInfo(0, -32767, 32767, 0, 0, 0)),  # pitch
                (ecodes.ABS_RZ, AbsInfo(0, -32767, 32767, 0, 0, 0)),  # yaw
            ],
        }
        self.gamepad = UInput(gamepad_cap, name="Benim Virtual Gamepad")
        
        self.mouse_btns = {0: ecodes.BTN_LEFT, 1: ecodes.BTN_RIGHT, 2: ecodes.BTN_MIDDLE}
        self.gpad_btns = {
            0x01: ecodes.BTN_A, 0x02: ecodes.BTN_B, 0x04: ecodes.BTN_X, 0x08: ecodes.BTN_Y,
            0x10: ecodes.BTN_START, 0x20: ecodes.BTN_SELECT, 0x40: ecodes.BTN_TL, 0x80: ecodes.BTN_TR,
        }
        self.library = f"python-evdev"
    
    def mouse_move(self, dx, dy):
        self.mouse.write(self.ecodes.EV_REL, self.ecodes.REL_X, dx)
        self.mouse.write(self.ecodes.EV_REL, self.ecodes.REL_Y, dy)
        self.mouse.syn()
    
    def mouse_button(self, button, pressed):
        btn = self.mouse_btns.get(button, self.ecodes.BTN_LEFT)
        self.mouse.write(self.ecodes.EV_KEY, btn, 1 if pressed else 0)
        self.mouse.syn()
    
    def mouse_scroll(self, delta):
        self.mouse.write(self.ecodes.EV_REL, self.ecodes.REL_WHEEL, delta)
        self.mouse.syn()
    
    def gamepad_buttons_update(self, buttons, prev):
        for mask, btn in self.gpad_btns.items():
            if bool(buttons & mask) != bool(prev & mask):
                self.gamepad.write(self.ecodes.EV_KEY, btn, 1 if buttons & mask else 0)
        self.gamepad.syn()
    
    def gamepad_axes(self, x, y):
        self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_X, x)
        self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_Y, y)
        self.gamepad.syn()

    def gamepad_gyro(self, rx, ry, rz):
        self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_RX, rx)
        self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_RY, ry)
        self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_RZ, rz)
        self.gamepad.syn()
    
    def close(self):
        try:
            self.mouse.close()
            self.gamepad.close()
        except: pass

# ═══════════════════════════════════════════════════════════════
# PYNPUT BACKEND
# ═══════════════════════════════════════════════════════════════
class PynputBackend(InputBackend):
    name = "pynput"
    method = "X11 API (python)"
    library = "pynput"
    
    def __init__(self):
        if os.environ.get('XDG_SESSION_TYPE') == 'wayland':
            raise RuntimeError("Wayland desteklenmiyor")
        from pynput.mouse import Controller, Button
        self.ctrl = Controller()
        self.Button = Button
        self.btns = {0: Button.left, 1: Button.right, 2: Button.middle}
    
    def mouse_move(self, dx, dy):
        self.ctrl.move(dx, dy)
    
    def mouse_button(self, button, pressed):
        btn = self.btns.get(button, self.Button.left)
        self.ctrl.press(btn) if pressed else self.ctrl.release(btn)
    
    def mouse_scroll(self, delta):
        self.ctrl.scroll(0, delta)

# ═══════════════════════════════════════════════════════════════
# XDOTOOL BACKEND
# ═══════════════════════════════════════════════════════════════
class XdotoolBackend(InputBackend):
    name = "xdotool"
    method = "X11 CLI"
    library = "xdotool"
    
    def __init__(self):
        if os.environ.get('XDG_SESSION_TYPE') == 'wayland':
            raise RuntimeError("Wayland desteklenmiyor")
        if not shutil.which("xdotool"):
            raise FileNotFoundError("xdotool bulunamadı")
    
    def _run(self, *args):
        try:
            subprocess.run(["xdotool"] + list(args), capture_output=True, timeout=1)
        except: pass
    
    def mouse_move(self, dx, dy):
        self._run("mousemove_relative", "--", str(dx), str(dy))
    
    def mouse_button(self, button, pressed):
        btn = {0: "1", 1: "3", 2: "2"}.get(button, "1")
        self._run("mousedown" if pressed else "mouseup", btn)
    
    def mouse_scroll(self, delta):
        btn = "4" if delta > 0 else "5"
        for _ in range(abs(delta)):
            self._run("click", btn)

# ═══════════════════════════════════════════════════════════════
# YDOTOOL BACKEND
# ═══════════════════════════════════════════════════════════════
class YdotoolBackend(InputBackend):
    name = "ydotool"
    method = "Wayland uinput CLI"
    library = "ydotool"
    
    def __init__(self):
        if not shutil.which("ydotool"):
            raise FileNotFoundError("ydotool bulunamadı")
        # ydotoold kontrol
        result = subprocess.run(["pgrep", "-x", "ydotoold"], capture_output=True)
        if result.returncode != 0:
            subprocess.Popen(["sudo", "ydotoold"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            time.sleep(1)
    
    def _run(self, *args):
        try:
            subprocess.run(["ydotool"] + list(args), capture_output=True, timeout=1)
        except: pass
    
    def mouse_move(self, dx, dy):
        self._run("mousemove", "-x", str(dx), "-y", str(dy))
    
    def mouse_button(self, button, pressed):
        code = {0: "0x00", 1: "0x01", 2: "0x02"}.get(button, "0x00")
        self._run("click", "-d" if pressed else "-u", code)
    
    def mouse_scroll(self, delta):
        self._run("mousemove", "-w", str(delta))

# ═══════════════════════════════════════════════════════════════
# BACKEND FACTORY
# ═══════════════════════════════════════════════════════════════
def create_backend(backend_type="auto"):
    errors = []
    is_wayland = os.environ.get('XDG_SESSION_TYPE') == 'wayland'
    
    if backend_type == "auto":
        for BackendClass in [EvdevBackend, YdotoolBackend if is_wayland else PynputBackend, XdotoolBackend]:
            try:
                return BackendClass()
            except Exception as e:
                errors.append(f"{BackendClass.name}: {e}")
        raise RuntimeError("Backend başlatılamadı!\n" + "\n".join(f"  • {e}" for e in errors))
    
    backends = {"evdev": EvdevBackend, "pynput": PynputBackend, "xdotool": XdotoolBackend, "ydotool": YdotoolBackend}
    return backends[backend_type]()

# ═══════════════════════════════════════════════════════════════
# UDP SERVER
# ═══════════════════════════════════════════════════════════════
class UdpServer:
    def __init__(self, port=None):
        self.port = port or Config.UDP_PORT
        self.running = False
        self.backend = None
        self.sock = None
        self.prev_buttons = {}
        self.last_activity = {}
        self.stats = {'packets': 0, 'pings': 0, 'mouse_moves': 0, 'clicks': 0, 'gamepad': 0}
    
    def _get_ip(self):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except:
            return "127.0.0.1"
    
    def _print_banner(self):
        ip = self._get_ip()
        display = os.environ.get('XDG_SESSION_TYPE', 'x11').upper()
        
        print()
        print("═" * 62)
        print("  🎮 Benim Gamepad/Mouse Server")
        print("═" * 62)
        print(f"  📌 Versiyon  : {VERSION}")
        print(f"  🌐 IP        : {ip}")
        print(f"  🔌 Port      : {self.port}")
        print(f"  🖥️  Display   : {display}")
        print("─" * 62)
        
        if self.backend:
            info = self.backend.get_info()
            print(f"  🔧 Backend   : {info['name']}")
            print(f"  📦 Kütüphane : {info['library']}")
            print(f"  ⚙️  Yöntem    : {info['method']}")
        
        print("─" * 62)
        print("  🎮 Gamepad Tuşları (8 adet):")
        buttons = list(Config.GAMEPAD_BUTTONS.items())
        for i in range(0, len(buttons), 2):
            left = buttons[i]
            right = buttons[i+1] if i+1 < len(buttons) else None
            left_str = f"     0x{left[0]:02X} = {left[1][0]:6s}"
            right_str = f"0x{right[0]:02X} = {right[1][0]}" if right else ""
            print(f"{left_str}    {right_str}")
        
        print("─" * 62)
        print(f"  📁 Log       : {Config.LOG_FILE}")
        print(f"  🐛 Debug     : {'AÇIK' if Config.DEBUG_MODE else 'KAPALI'}")
        print("═" * 62)
    
    def log(self, msg, client=None, level="INFO"):
        ts = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        client_str = f" [{client}]" if client else ""
        emoji = {"INFO": "ℹ️", "OK": "✅", "WARN": "⚠️", "ERROR": "❌", 
                 "PING": "📶", "MOUSE": "🖱️", "GAMEPAD": "🎮", "DEBUG": "🔧"}.get(level, "")
        print(f"{ts}{client_str} {emoji} {msg}")
        try:
            with open(Config.LOG_FILE, "a") as f:
                f.write(f"{datetime.now().isoformat()} [{level}]{client_str} {msg}\n")
        except: pass
    
    def _signed(self, b):
        return b if b < 128 else b - 256
    
    def handle_ping(self, data, addr):
        if len(data) >= 9:
            self.sock.sendto(data, addr)
            self.stats['pings'] += 1
            if Config.LOG_PACKETS:
                self.log("Ping echo", addr[0], "PING")
    
    def handle_joystick(self, data, addr):
        if len(data) < 5 or not self.backend:
            return
        ip = addr[0]
        buttons, raw_x, raw_y = data[1], data[2], data[3]
        x, y = self._signed(raw_x), self._signed(raw_y)
        
        if Config.LOG_RAW_BYTES:
            self.log(f"JOY: btn=0x{buttons:02X} x={x:4d} y={y:4d}", ip, "DEBUG")
        
        prev = self.prev_buttons.get(ip, 0)
        if buttons != prev:
            self.backend.gamepad_buttons_update(buttons, prev)
            self.prev_buttons[ip] = buttons
            self.stats['gamepad'] += 1
            if Config.LOG_PACKETS or buttons > 0:
                pressed = [name for mask, (name, _) in Config.GAMEPAD_BUTTONS.items() if buttons & mask]
                if pressed:
                    self.log(f"Tuşlar: {', '.join(pressed)}", ip, "GAMEPAD")
        
        if abs(x) < Config.JOYSTICK_DEADZONE: x = 0
        if abs(y) < Config.JOYSTICK_DEADZONE: y = 0
        self.backend.gamepad_axes(x, y)
        
        if Config.JOYSTICK_AS_MOUSE and (x or y):
            self.backend.mouse_move(int(x * Config.MOUSE_SENSITIVITY / 20),
                                    int(y * Config.MOUSE_SENSITIVITY / 20))
    
    def handle_mouse_move(self, data, addr):
        if len(data) < 3 or not self.backend:
            return
        raw_dx, raw_dy = data[1], data[2]
        dx, dy = self._signed(raw_dx), self._signed(raw_dy)
        
        if Config.LOG_RAW_BYTES:
            self.log(f"RAW: [{raw_dx:3d},{raw_dy:3d}] → [{dx:4d},{dy:4d}]", addr[0], "DEBUG")
        
        final_dx = int(dx * Config.MOUSE_SENSITIVITY)
        final_dy = int(dy * Config.MOUSE_SENSITIVITY)
        
        if final_dx or final_dy:
            self.backend.mouse_move(final_dx, final_dy)
            self.stats['mouse_moves'] += 1
            if Config.LOG_MOUSE_MOVE:
                self.log(f"Move: ({final_dx:4d},{final_dy:4d})", addr[0], "MOUSE")
    
    def handle_mouse_button(self, data, addr):
        if len(data) < 3 or not self.backend:
            return
        button, pressed = data[1], data[2] == 1
        self.backend.mouse_button(button, pressed)
        self.stats['clicks'] += 1
        btn_name = {0: "Sol", 1: "Sağ", 2: "Orta"}.get(button, str(button))
        self.log(f"{btn_name} tık {'▼' if pressed else '▲'}", addr[0], "MOUSE")
    
    def handle_mouse_wheel(self, data, addr):
        if len(data) < 2 or not self.backend:
            return
        delta = self._signed(data[1])
        scroll = delta * Config.SCROLL_SENSITIVITY // 10
        if scroll == 0 and delta:
            scroll = 1 if delta > 0 else -1
        self.backend.mouse_scroll(scroll)
        self.log(f"Scroll {'↑' if delta > 0 else '↓'} ({delta})", addr[0], "MOUSE")

    def handle_gyro(self, data, addr):
        if len(data) < 13 or not self.backend:
            return
        
        # Paket: 0x0D + float32 x + float32 y + float32 z (little endian)
        try:
            gx, gy, gz = struct.unpack('<fff', data[1:13])
            
            # Ham rad/s → derece/saniye
            gx_deg = gx * (180 / 3.1415926535)
            gy_deg = gy * (180 / 3.1415926535)
            gz_deg = gz * (180 / 3.1415926535)
            
            # Ölçekleme: ±500 derece/s = ±32767 (çok hızlı dönüş için yetmesi lazım)
            scale = 32767 / 500.0
            
            rx = int(gx_deg * scale)
            ry = int(gy_deg * scale)
            rz = int(gz_deg * scale)
            
            # Clamp
            rx = max(min(rx, 32767), -32767)
            ry = max(min(ry, 32767), -32767)
            rz = max(min(rz, 32767), -32767)
            
            self.backend.gamepad_gyro(rx, ry, rz)
            
            if Config.LOG_PACKETS:
                self.log(f"Gyro: {rx:6d}, {ry:6d}, {rz:6d}  (raw rad/s: {gx:.3f}, {gy:.3f}, {gz:.3f})", addr[0], "GYRO")
                
        except struct.error:
            self.log("Gyro paketlerinden vergi aldılar :( ", addr[0], "ERROR")
    
    def handle_discovery(self, data, addr):
        try:
            if b"DISCOVER" in data:
                self.sock.sendto(b"I_AM_SERVER", addr)
                self.log("Discovery yanıtı gönderildi", addr[0], "OK")
        except: pass
    
    def process_packet(self, data, addr):
        self.last_activity[addr[0]] = time.time()
        self.stats['packets'] += 1
        
        if not data:
            return
        
        if data.startswith(b"DISCOVER"):
            self.handle_discovery(data, addr)
            return
        
        ptype = data[0]
        handlers = {
            Config.PACKET_PING: self.handle_ping,
            Config.PACKET_JOYSTICK: self.handle_joystick,
            Config.PACKET_MOUSE_MOVE: self.handle_mouse_move,
            Config.PACKET_MOUSE_BUTTON: self.handle_mouse_button,
            Config.PACKET_MOUSE_WHEEL: self.handle_mouse_wheel,
            Config.PACKET_GYRO: self.handle_gyro,
        }
        handler = handlers.get(ptype)
        if handler:
            handler(data, addr)
        elif Config.LOG_PACKETS:
            self.log(f"Bilinmeyen: 0x{ptype:02X}", addr[0], "WARN")
    
    def start(self):
        """Sunucuyu başlat"""
        self.running = True
        
        # Backend
        print("\n🔧 Input backend başlatılıyor...")
        try:
            self.backend = create_backend(Config.BACKEND)
            print(f"  ✅ {self.backend.name} backend başarılı")
        except Exception as e:
            print(f"\n❌ Backend hatası: {e}")
            print("\n💡 Çözüm: ./install.sh çalıştırın")
            return
        
        self._print_banner()
        
        # Socket
        try:
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            self.sock.bind((Config.UDP_HOST, self.port))
            self.sock.settimeout(1.0)
        except Exception as e:
            self.log(f"Socket hatası: {e}", level="ERROR")
            return
        
        print()
        self.log("Sunucu başlatıldı", level="OK")
        self.log("Android'de 'Discover' butonuna tıklayın")
        self.log("Durdurmak için: Ctrl+C")
        print("─" * 62)
        
        # Cleanup thread
        threading.Thread(target=self._cleanup_loop, daemon=True).start()
        
        # Ana döngü
        while self.running:
            try:
                data, addr = self.sock.recvfrom(1024)
                self.process_packet(data, addr)
            except socket.timeout:
                continue
            except KeyboardInterrupt:
                break
            except Exception as e:
                if self.running:
                    self.log(f"Hata: {e}", level="ERROR")
        
        self.stop()
    
    def stop(self):
        """Sunucuyu durdur"""
        self.running = False
        if self.backend:
            self.backend.close()
        if self.sock:
            try:
                self.sock.close()
            except: pass
        
        print()
        print("─" * 62)
        self.log("Sunucu durduruldu", level="OK")
        print()
        print("📊 İstatistikler:")
        print(f"   Paket: {self.stats['packets']:,}")
        print(f"   Ping:  {self.stats['pings']:,}")
        print(f"   Mouse: {self.stats['mouse_moves']:,} hareket, {self.stats['clicks']:,} tık")
        print(f"   Gamepad: {self.stats['gamepad']:,} buton")
        print("─" * 62)
    
    def _cleanup_loop(self):
        """Eski bağlantıları temizle"""
        while self.running:
            time.sleep(30)
            now = time.time()
            expired = [ip for ip, t in self.last_activity.items() if now - t > 60]
            for ip in expired:
                del self.last_activity[ip]
                self.prev_buttons.pop(ip, None)
                self.log("Zaman aşımı", ip, "WARN")

# ═══════════════════════════════════════════════════════════════
# MAIN
# ═══════════════════════════════════════════════════════════════
def main():
    import argparse
    
    parser = argparse.ArgumentParser(
        description="🎮 Benim Gamepad/Mouse Server",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Örnekler:
  ./run.sh              Normal başlat
  ./run.sh -d           Debug modu
  ./run.sh -p 5000      Farklı port
  ./run.sh -b ydotool   Belirli backend
        """
    )
    parser.add_argument("-p", "--port", type=int, default=26760, help="UDP port")
    parser.add_argument("-d", "--debug", action="store_true", help="Debug modu")
    parser.add_argument("-b", "--backend", choices=["auto", "evdev", "pynput", "xdotool", "ydotool"],
                       default="auto", help="Input backend")
    parser.add_argument("-v", "--version", action="version", version=f"v{VERSION}")
    args = parser.parse_args()
    
    if args.debug:
        Config.enable_debug()
    
    Config.BACKEND = args.backend
    
    server = UdpServer(port=args.port)
    
    def sig_handler(sig, frame):
        print("\n\n🛑 Kapatılıyor...")
        server.stop()
        sys.exit(0)
    
    signal.signal(signal.SIGINT, sig_handler)
    signal.signal(signal.SIGTERM, sig_handler)
    
    server.start()

if __name__ == "__main__":
    main()
