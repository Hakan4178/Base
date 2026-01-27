#!/usr/bin/env python3
"""
Benim Gamepad/Mouse UDP Server
Tek dosyada tüm sistem - Linux Wayland/X11
12-Byte Gamepad Paketi + XOR Checksum
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
VERSION = "1.1.7"

# ═══════════════════════════════════════════════════════════════
# CONFIG
# ═══════════════════════════════════════════════════════════════
class Config:
    DEBUG_MODE = False
    UDP_HOST = '0.0.0.0'
    UDP_PORT = 26760
    
    # Paket Tipleri
    PACKET_PING = 0x7F
    PACKET_GAMEPAD = 0x01
    PACKET_MOUSE_MOVE = 0x02
    PACKET_MOUSE_BUTTON = 0x03
    PACKET_MOUSE_WHEEL = 0x04
    PACKET_GYRO = 0x0D
    
    # Hassasiyet
    MOUSE_SENSITIVITY = 1.6
    SCROLL_SENSITIVITY = 2
    JOYSTICK_DEADZONE = 10
    TRIGGER_DEADZONE = 20
    JOYSTICK_AS_MOUSE = False
    
    # 32-BIT BUTON MAPPING
    GAMEPAD_BUTTONS = {
        0x00000001: ("A", "BTN_A"),
        0x00000002: ("B", "BTN_B"),
        0x00000004: ("X", "BTN_X"),
        0x00000008: ("Y", "BTN_Y"),
        0x00000010: ("L1", "BTN_TL"),
        0x00000020: ("R1", "BTN_TR"),
        0x00000040: ("L2", "BTN_TL2"),
        0x00000080: ("R2", "BTN_TR2"),
        0x00000100: ("SELECT", "BTN_SELECT"),
        0x00000200: ("START", "BTN_START"),
        0x00000400: ("HOME", "BTN_MODE"),
        0x00000800: ("L3", "BTN_THUMBL"),
        0x00001000: ("R3", "BTN_THUMBR"),
        0x00002000: ("DPAD_UP", "DPAD"),
        0x00004000: ("DPAD_DOWN", "DPAD"),
        0x00008000: ("DPAD_LEFT", "DPAD"),
        0x00010000: ("DPAD_RIGHT", "DPAD"),
    }
    
    BACKEND = "auto"
    LOG_FILE = "gamepad_server.log"
    
    # Ayrı log bayrakları
    LOG_PACKETS = False
    LOG_MOUSE_MOVE = False
    LOG_RAW_BYTES = False
    LOG_GYRO = False          # Gyro için ayrı flag
    LOG_BUTTONS = True        # Buton değişikliklerini her zaman logla
    
    VERIFY_CHECKSUM = True
    
    @classmethod
    def enable_debug(cls):
        """Tüm logları aç"""
        cls.DEBUG_MODE = True
        cls.LOG_PACKETS = True
        cls.LOG_MOUSE_MOVE = True
        cls.LOG_RAW_BYTES = True
        cls.LOG_GYRO = True       # Gyro logunu da aç!
        cls.LOG_BUTTONS = True

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
    
    def gamepad_buttons(self, buttons, prev): pass
    def gamepad_left_stick(self, x, y): pass
    def gamepad_right_stick(self, x, y): pass
    def gamepad_triggers(self, l2, r2): pass
    def gamepad_gyro(self, rx, ry, rz): pass
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
        
        # MOUSE
        mouse_cap = {
            ecodes.EV_REL: [ecodes.REL_X, ecodes.REL_Y, ecodes.REL_WHEEL],
            ecodes.EV_KEY: [ecodes.BTN_LEFT, ecodes.BTN_RIGHT, ecodes.BTN_MIDDLE],
        }
        self.mouse = UInput(mouse_cap, name="Benim Virtual Mouse")
        
        # GAMEPAD - STANDART XBOX ARALIKLARI
        gamepad_cap = {
            ecodes.EV_KEY: [
                ecodes.BTN_A, ecodes.BTN_B, ecodes.BTN_X, ecodes.BTN_Y,
                ecodes.BTN_TL, ecodes.BTN_TR, ecodes.BTN_TL2, ecodes.BTN_TR2,
                ecodes.BTN_SELECT, ecodes.BTN_START, ecodes.BTN_MODE,
                ecodes.BTN_THUMBL, ecodes.BTN_THUMBR,
            ],
            ecodes.EV_ABS: [
                # Joystick: -32767 ~ +32767
                (ecodes.ABS_X,  AbsInfo(0, -32767, 32767, 16, 128, 0)),
                (ecodes.ABS_Y,  AbsInfo(0, -32767, 32767, 16, 128, 0)),
                (ecodes.ABS_RX, AbsInfo(0, -32767, 32767, 16, 128, 0)),
                (ecodes.ABS_RY, AbsInfo(0, -32767, 32767, 16, 128, 0)),
                # Tetikler: 0 ~ 255
                (ecodes.ABS_Z,  AbsInfo(0, 0, 255, 0, 0, 0)),
                (ecodes.ABS_RZ, AbsInfo(0, 0, 255, 0, 0, 0)),
                # D-Pad: -1, 0, +1
                (ecodes.ABS_HAT0X, AbsInfo(0, -1, 1, 0, 0, 0)),
                (ecodes.ABS_HAT0Y, AbsInfo(0, -1, 1, 0, 0, 0)),
            ],
        }
        self.gamepad = UInput(
            gamepad_cap, 
            name="Benim Virtual Gamepad",
            vendor=0x045e,
            product=0x028e,
            version=0x0110
        )
        
        # Mapping
        self.mouse_btns = {0: ecodes.BTN_LEFT, 1: ecodes.BTN_RIGHT, 2: ecodes.BTN_MIDDLE}
        self.gpad_btns = {
            0x00000001: ecodes.BTN_A,
            0x00000002: ecodes.BTN_B,
            0x00000004: ecodes.BTN_X,
            0x00000008: ecodes.BTN_Y,
            0x00000010: ecodes.BTN_TL,
            0x00000020: ecodes.BTN_TR,
            0x00000040: ecodes.BTN_TL2,
            0x00000080: ecodes.BTN_TR2,
            0x00000100: ecodes.BTN_SELECT,
            0x00000200: ecodes.BTN_START,
            0x00000400: ecodes.BTN_MODE,
            0x00000800: ecodes.BTN_THUMBL,
            0x00001000: ecodes.BTN_THUMBR,
        }
        
        self.DPAD_UP    = 0x00002000
        self.DPAD_DOWN  = 0x00004000
        self.DPAD_LEFT  = 0x00008000
        self.DPAD_RIGHT = 0x00010000
    
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
    
    def gamepad_buttons(self, buttons, prev):
        changed = False
        
        for mask, btn in self.gpad_btns.items():
            curr = bool(buttons & mask)
            old = bool(prev & mask)
            if curr != old:
                self.gamepad.write(self.ecodes.EV_KEY, btn, 1 if curr else 0)
                changed = True
        
        # D-Pad
        dpad_changed = False
        for mask in [self.DPAD_UP, self.DPAD_DOWN, self.DPAD_LEFT, self.DPAD_RIGHT]:
            if bool(buttons & mask) != bool(prev & mask):
                dpad_changed = True
                break
        
        if dpad_changed:
            up = bool(buttons & self.DPAD_UP)
            down = bool(buttons & self.DPAD_DOWN)
            left = bool(buttons & self.DPAD_LEFT)
            right = bool(buttons & self.DPAD_RIGHT)
            
            hat_x = -1 if left else (1 if right else 0)
            hat_y = -1 if up else (1 if down else 0)
            
            self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_HAT0X, hat_x)
            self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_HAT0Y, hat_y)
            changed = True
        
        if changed:
            self.gamepad.syn()
    
    def gamepad_left_stick(self, x, y):
        # ═══════════════════════════════════════════════════════
        # ÖLÇEKLEME: -127~+127 → -32767~+32767
        # ═══════════════════════════════════════════════════════
        scaled_x = x * 258  # 32767 / 127 ≈ 258
        scaled_y = y * 258
        
        self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_X, scaled_x)
        self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_Y, scaled_y)
        self.gamepad.syn()
    
    def gamepad_right_stick(self, x, y):
        # ═══════════════════════════════════════════════════════
        # ÖLÇEKLEME: -127~+127 → -32767~+32767
        # ═══════════════════════════════════════════════════════
        scaled_x = x * 258
        scaled_y = y * 258
        
        self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_RX, scaled_x)
        self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_RY, scaled_y)
        self.gamepad.syn()
    
    def gamepad_triggers(self, l2, r2):
        self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_Z, l2)
        self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_RZ, r2)
        self.gamepad.syn()
    
    def gamepad_gyro(self, rx, ry, rz):
        # Gyro şimdilik devre dışı - oyunlar desteklemiyor
        pass
    
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
    
    def mouse_move(self, dx, dy): self.ctrl.move(dx, dy)
    def mouse_button(self, button, pressed):
        btn = self.btns.get(button, self.Button.left)
        self.ctrl.press(btn) if pressed else self.ctrl.release(btn)
    def mouse_scroll(self, delta): self.ctrl.scroll(0, delta)

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
        try: subprocess.run(["xdotool"] + list(args), capture_output=True, timeout=1)
        except: pass
    
    def mouse_move(self, dx, dy): self._run("mousemove_relative", "--", str(dx), str(dy))
    def mouse_button(self, button, pressed):
        btn = {0: "1", 1: "3", 2: "2"}.get(button, "1")
        self._run("mousedown" if pressed else "mouseup", btn)
    def mouse_scroll(self, delta):
        btn = "4" if delta > 0 else "5"
        for _ in range(abs(delta)): self._run("click", btn)

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
        result = subprocess.run(["pgrep", "-x", "ydotoold"], capture_output=True)
        if result.returncode != 0:
            subprocess.Popen(["sudo", "ydotoold"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            time.sleep(1)
    
    def _run(self, *args):
        try: subprocess.run(["ydotool"] + list(args), capture_output=True, timeout=1)
        except: pass
    
    def mouse_move(self, dx, dy): self._run("mousemove", "-x", str(dx), "-y", str(dy))
    def mouse_button(self, button, pressed):
        code = {0: "0x00", 1: "0x01", 2: "0x02"}.get(button, "0x00")
        self._run("click", "-d" if pressed else "-u", code)
    def mouse_scroll(self, delta): self._run("mousemove", "-w", str(delta))

# ═══════════════════════════════════════════════════════════════
# BACKEND FACTORY
# ═══════════════════════════════════════════════════════════════
def create_backend(backend_type="auto"):
    errors = []
    is_wayland = os.environ.get('XDG_SESSION_TYPE') == 'wayland'
    
    if backend_type == "auto":
        order = [EvdevBackend]
        order.append(YdotoolBackend if is_wayland else PynputBackend)
        order.append(XdotoolBackend)
        
        for BackendClass in order:
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
        self.stats = {
            'packets': 0,
            'pings': 0,
            'mouse_moves': 0,
            'clicks': 0,
            'gamepad': 0,
            'gyro': 0,
            'checksum_ok': 0,
            'checksum_fail': 0
        }
    
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
        print("  📦 Paket: 12 Byte [Hdr][Btn 4B][LX][LY][RX][RY][L2][R2][XOR]")
        print("─" * 62)
        print("  🎮 Butonlar: A B X Y L1 R1 L2 R2 SEL START HOME L3 R3 D-PAD")
        print("─" * 62)
        print(f"  🐛 Debug     : {'AÇIK ✓' if Config.DEBUG_MODE else 'KAPALI'}")
        print(f"  🔐 Checksum  : {'AÇIK ✓' if Config.VERIFY_CHECKSUM else 'KAPALI'}")
        print(f"  🌀 Gyro Log  : {'AÇIK ✓' if Config.LOG_GYRO else 'KAPALI'}")
        print("═" * 62)
    
    def log(self, msg, client=None, level="INFO"):
        ts = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        client_str = f" [{client}]" if client else ""
        emoji = {
            "INFO": "ℹ️ ", "OK": "✅", "WARN": "⚠️ ", "ERROR": "❌",
            "PING": "📶", "MOUSE": "🖱️ ", "GAMEPAD": "🎮", "DEBUG": "🔧",
            "GYRO": "🌀", "CHECKSUM": "🔐"
        }.get(level, "  ")
        line = f"{ts}{client_str} {emoji} {msg}"
        print(line)
        try:
            with open(Config.LOG_FILE, "a") as f:
                f.write(f"{datetime.now().isoformat()} [{level}]{client_str} {msg}\n")
        except: pass
    
    def _signed(self, b):
        return b if b < 128 else b - 256
    
    def _verify_checksum(self, data):
        if len(data) < 12:
            return False
        xor = 0
        for i in range(11):
            xor ^= data[i]
        return xor == data[11]
    
    # ═══════════════════════════════════════════════════════════
    # HANDLERS
    # ═══════════════════════════════════════════════════════════
    
    def handle_ping(self, data, addr):
        if len(data) >= 9:
            self.sock.sendto(data, addr)
            self.stats['pings'] += 1
            if Config.LOG_PACKETS:
                self.log("Ping echo", addr[0], "PING")
    
    def handle_gamepad(self, data, addr):
        if len(data) < 12 or not self.backend:
            return
        
        # Checksum
        if Config.VERIFY_CHECKSUM:
            if self._verify_checksum(data):
                self.stats['checksum_ok'] += 1
            else:
                self.stats['checksum_fail'] += 1
                if Config.LOG_PACKETS:
                    self.log("Checksum HATA!", addr[0], "CHECKSUM")
                return
        
        ip = addr[0]
        
        # Unpack
        buttons = struct.unpack('<I', data[1:5])[0]
        lx = self._signed(data[5])
        ly = self._signed(data[6])
        rx = self._signed(data[7])
        ry = self._signed(data[8])
        l2 = data[9]
        r2 = data[10]
        
        if Config.LOG_RAW_BYTES:
            self.log(f"RAW btn=0x{buttons:08X} L({lx:4},{ly:4}) R({rx:4},{ry:4}) T:{l2:3}/{r2:3}", ip, "DEBUG")
        
        # Butonlar
        prev = self.prev_buttons.get(ip, 0)
        if buttons != prev:
            self.backend.gamepad_buttons(buttons, prev)
            self.prev_buttons[ip] = buttons
            self.stats['gamepad'] += 1
            
            if Config.LOG_BUTTONS:
                pressed = [name for mask, (name, _) in Config.GAMEPAD_BUTTONS.items() if buttons & mask]
                released = [name for mask, (name, _) in Config.GAMEPAD_BUTTONS.items() if (prev & mask) and not (buttons & mask)]
                if pressed:
                    self.log(f"▼ {', '.join(pressed)}", ip, "GAMEPAD")
                if released and Config.LOG_PACKETS:
                    self.log(f"▲ {', '.join(released)}", ip, "GAMEPAD")
        
        # Joystick deadzone
        if abs(lx) < Config.JOYSTICK_DEADZONE: lx = 0
        if abs(ly) < Config.JOYSTICK_DEADZONE: ly = 0
        if abs(rx) < Config.JOYSTICK_DEADZONE: rx = 0
        if abs(ry) < Config.JOYSTICK_DEADZONE: ry = 0
        
        self.backend.gamepad_left_stick(lx, ly)
        self.backend.gamepad_right_stick(rx, ry)
        
        # Tetikler
        if l2 < Config.TRIGGER_DEADZONE: l2 = 0
        if r2 < Config.TRIGGER_DEADZONE: r2 = 0
        self.backend.gamepad_triggers(l2, r2)
        
        # Joystick as mouse
        if Config.JOYSTICK_AS_MOUSE and (lx or ly):
            self.backend.mouse_move(
                int(lx * Config.MOUSE_SENSITIVITY / 20),
                int(ly * Config.MOUSE_SENSITIVITY / 20)
            )
    
    def handle_mouse_move(self, data, addr):
        if len(data) < 3 or not self.backend:
            return
        
        dx = self._signed(data[1])
        dy = self._signed(data[2])
        
        final_dx = int(dx * Config.MOUSE_SENSITIVITY)
        final_dy = int(dy * Config.MOUSE_SENSITIVITY)
        
        if final_dx or final_dy:
            self.backend.mouse_move(final_dx, final_dy)
            self.stats['mouse_moves'] += 1
            if Config.LOG_MOUSE_MOVE:
                self.log(f"Move ({final_dx:4},{final_dy:4})", addr[0], "MOUSE")
    
    def handle_mouse_button(self, data, addr):
        if len(data) < 3 or not self.backend:
            return
        
        button, pressed = data[1], data[2] == 1
        self.backend.mouse_button(button, pressed)
        self.stats['clicks'] += 1
        
        btn_name = {0: "Sol", 1: "Sağ", 2: "Orta"}.get(button, str(button))
        self.log(f"{btn_name} {'▼' 
        if pressed else '▲'}", addr[0], "MOUSE")
    
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
        """
        Gyro Paketi: 7 byte (Android int16 formatı)
        [0]    = 0x0D (Header)
        [1-2]  = gX (int16, Little-Endian, ±32767)
        [3-4]  = gY (int16)
        [5-6]  = gZ (int16)
        
        Değer aralığı: ±500 deg/s = ±32767
        """
        if len(data) < 7:
            if Config.LOG_GYRO:
                self.log(f"Gyro kısa paket: {len(data)}B (beklenen: 7B)", addr[0], "WARN")
            return
        
        if not self.backend:
            return
        
        try:
            gx, gy, gz = struct.unpack('<hhh', data[1:7])
            self.backend.gamepad_gyro(gx, gy, gz)
            self.stats['gyro'] += 1
            
            if Config.LOG_GYRO:
                self.log(f"Gyro: X={gx:6d} Y={gy:6d} Z={gz:6d}", addr[0], "GYRO")
                
        except struct.error as e:
            self.log(f"Gyro parse hatası: {e}", addr[0], "ERROR")
        except Exception as e:
            self.log(f"Gyro hata: {e}", addr[0], "ERROR")
    
    def handle_discovery(self, data, addr):
        try:
            if b"DISCOVER" in data:
                self.sock.sendto(b"I_AM_SERVER", addr)
                self.log("Discovery yanıtı", addr[0], "OK")
        except: pass
    
    # ═══════════════════════════════════════════════════════════
    # PACKET ROUTER
    # ═══════════════════════════════════════════════════════════
    
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
            Config.PACKET_GAMEPAD: self.handle_gamepad,
            Config.PACKET_MOUSE_MOVE: self.handle_mouse_move,
            Config.PACKET_MOUSE_BUTTON: self.handle_mouse_button,
            Config.PACKET_MOUSE_WHEEL: self.handle_mouse_wheel,
            Config.PACKET_GYRO: self.handle_gyro,
        }
        
        handler = handlers.get(ptype)
        if handler:
            handler(data, addr)
        elif Config.LOG_PACKETS:
            hex_preview = ' '.join(f'{b:02X}' for b in data[:min(16, len(data))])
            self.log(f"Bilinmeyen 0x{ptype:02X}: {hex_preview}", addr[0], "WARN")
    
    # ═══════════════════════════════════════════════════════════
    # SERVER LIFECYCLE
    # ═══════════════════════════════════════════════════════════
    
    def start(self):
        self.running = True
        
        print("\n🔧 Input backend başlatılıyor...")
        try:
            self.backend = create_backend(Config.BACKEND)
            print(f"  ✅ {self.backend.name} backend başarılı")
        except Exception as e:
            print(f"\n❌ Backend hatası: {e}")
            print("\n💡 Çözüm: sudo modprobe uinput && sudo chmod 666 /dev/uinput")
            return
        
        self._print_banner()
        
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
        
        threading.Thread(target=self._cleanup_loop, daemon=True).start()
        
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
        self.running = False
        if self.backend:
            self.backend.close()
        if self.sock:
            try: self.sock.close()
            except: pass
        
        print()
        print("─" * 62)
        self.log("Sunucu durduruldu", level="OK")
        print()
        print("📊 İstatistikler:")
        print(f"   Toplam Paket   : {self.stats['packets']:,}")
        print(f"   Ping           : {self.stats['pings']:,}")
        print(f"   Mouse Hareket  : {self.stats['mouse_moves']:,}")
        print(f"   Mouse Tık      : {self.stats['clicks']:,}")
        print(f"   Gamepad        : {self.stats['gamepad']:,}")
        print(f"   Gyro           : {self.stats['gyro']:,}")
        print(f"   Checksum OK    : {self.stats['checksum_ok']:,}")
        print(f"   Checksum HATA  : {self.stats['checksum_fail']:,}")
        print("─" * 62)
    
    def _cleanup_loop(self):
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
        description=f"🎮 Benim Gamepad/Mouse Server v{VERSION}",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Paket Formatı (12 Byte):
  [Header 1B][Buttons 4B][LX][LY][RX][RY][L2][R2][XOR]

Örnekler:
  ./run.sh                    Normal başlat
  ./run.sh -d                 Debug modu (tüm loglar)
  ./run.sh -d --no-checksum   Debug + checksum kapalı
  ./run.sh -g                 Sadece gyro logları
  ./run.sh -p 5000            Farklı port
  ./run.sh -b evdev           Evdev backend
        """
    )
    parser.add_argument("-p", "--port", type=int, default=26760, help="UDP port")
    parser.add_argument("-d", "--debug", action="store_true", help="Debug modu (tüm loglar)")
    parser.add_argument("-g", "--gyro-log", action="store_true", help="Gyro loglarını aç")
    parser.add_argument("-b", "--backend", choices=["auto", "evdev", "pynput", "xdotool", "ydotool"],
                       default="auto", help="Input backend")
    parser.add_argument("--no-checksum", action="store_true", help="XOR checksum doğrulamayı kapat")
    parser.add_argument("-v", "--version", action="version", version=f"v{VERSION}")
    
    args = parser.parse_args()
    
    # ─────────────────────────────────────────────────────────
    # Config ayarları (sıra önemli!)
    # ─────────────────────────────────────────────────────────
    
    # Debug modu: Tüm logları aç
    if args.debug:
        Config.enable_debug()
    
    # Sadece gyro log
    if args.gyro_log:
        Config.LOG_GYRO = True
    
    # Checksum kontrolü
    if args.no_checksum:
        Config.VERIFY_CHECKSUM = False
    
    Config.BACKEND = args.backend
    
    # ─────────────────────────────────────────────────────────
    # Server başlat
    # ─────────────────────────────────────────────────────────
    
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
