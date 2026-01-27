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
VERSION = "2.0.0"

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
    
    # ═══════════════════════════════════════════════════════════
    # 32-BIT BUTON MAPPING
    # ═══════════════════════════════════════════════════════════
    GAMEPAD_BUTTONS = {
        # Ana Butonlar (Bit 0-7)
        0x00000001: ("A", "BTN_A"),
        0x00000002: ("B", "BTN_B"),
        0x00000004: ("X", "BTN_X"),
        0x00000008: ("Y", "BTN_Y"),
        0x00000010: ("L1", "BTN_TL"),
        0x00000020: ("R1", "BTN_TR"),
        0x00000040: ("L2", "BTN_TL2"),      # Dijital L2
        0x00000080: ("R2", "BTN_TR2"),      # Dijital R2
        
        # Sistem Butonları (Bit 8-12)
        0x00000100: ("SELECT", "BTN_SELECT"),
        0x00000200: ("START", "BTN_START"),
        0x00000400: ("HOME", "BTN_MODE"),
        0x00000800: ("L3", "BTN_THUMBL"),
        0x00001000: ("R3", "BTN_THUMBR"),
        
        # D-Pad (Bit 13-16)
        0x00002000: ("DPAD_UP", "DPAD"),
        0x00004000: ("DPAD_DOWN", "DPAD"),
        0x00008000: ("DPAD_LEFT", "DPAD"),
        0x00010000: ("DPAD_RIGHT", "DPAD"),
        
        # Opsiyonel (Bit 17+) - Gerekirse aktif et
        # 0x00020000: ("TOUCHPAD", "BTN_TOUCH"),
        # 0x00040000: ("CAPTURE", "BTN_TRIGGER_HAPPY1"),
        # 0x00080000: ("MIC", "BTN_TRIGGER_HAPPY2"),
    }
    
    BACKEND = "auto"
    LOG_FILE = "gamepad_server.log"
    LOG_PACKETS = False
    LOG_MOUSE_MOVE = False
    LOG_RAW_BYTES = False
    VERIFY_CHECKSUM = True  # XOR checksum doğrulama
    
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
    
    def gamepad_buttons(self, buttons, prev): pass
    def gamepad_left_stick(self, x, y): pass
    def gamepad_right_stick(self, x, y): pass
    def gamepad_triggers(self, l2, r2): pass
    def gamepad_dpad(self, up, down, left, right): pass
    def gamepad_gyro(self, rx, ry, rz): pass
    def close(self): pass
    
    def get_info(self):
        return {"name": self.name, "method": self.method, "library": self.library}

# ═══════════════════════════════════════════════════════════════
# EVDEV BACKEND (Tam Gamepad Desteği)
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
        
        # ─────────────────────────────────────────────────────
        # MOUSE
        # ─────────────────────────────────────────────────────
        mouse_cap = {
            ecodes.EV_REL: [ecodes.REL_X, ecodes.REL_Y, ecodes.REL_WHEEL],
            ecodes.EV_KEY: [ecodes.BTN_LEFT, ecodes.BTN_RIGHT, ecodes.BTN_MIDDLE],
        }
        self.mouse = UInput(mouse_cap, name="Benim Virtual Mouse")
        
        # ─────────────────────────────────────────────────────
        # GAMEPAD (Xbox-style layout)
        # ─────────────────────────────────────────────────────
        gamepad_cap = {
            ecodes.EV_KEY: [
                # Ana butonlar
                ecodes.BTN_A, ecodes.BTN_B, ecodes.BTN_X, ecodes.BTN_Y,
                # Shoulder
                ecodes.BTN_TL, ecodes.BTN_TR,
                ecodes.BTN_TL2, ecodes.BTN_TR2,
                # Sistem
                ecodes.BTN_SELECT, ecodes.BTN_START, ecodes.BTN_MODE,
                # Thumbstick click
                ecodes.BTN_THUMBL, ecodes.BTN_THUMBR,
            ],
            ecodes.EV_ABS: [
                # Sol Joystick
                (ecodes.ABS_X, AbsInfo(0, -127, 127, 0, 10, 0)),
                (ecodes.ABS_Y, AbsInfo(0, -127, 127, 0, 10, 0)),
                # Sağ Joystick
                (ecodes.ABS_RX, AbsInfo(0, -127, 127, 0, 10, 0)),
                (ecodes.ABS_RY, AbsInfo(0, -127, 127, 0, 10, 0)),
                # Tetikler (Analog)
                (ecodes.ABS_Z, AbsInfo(0, 0, 255, 0, 0, 0)),   # L2
                (ecodes.ABS_RZ, AbsInfo(0, 0, 255, 0, 0, 0)),  # R2
                # D-Pad (HAT)
                (ecodes.ABS_HAT0X, AbsInfo(0, -1, 1, 0, 0, 0)),
                (ecodes.ABS_HAT0Y, AbsInfo(0, -1, 1, 0, 0, 0)),
            ],
        }
        self.gamepad = UInput(gamepad_cap, name="Benim Virtual Gamepad", vendor=0x045e, product=0x028e)
        
        # ─────────────────────────────────────────────────────
        # GYRO (Ayrı cihaz - motion controller olarak)
        # ─────────────────────────────────────────────────────
        gyro_cap = {
            ecodes.EV_ABS: [
                (ecodes.ABS_RX, AbsInfo(0, -32767, 32767, 0, 0, 0)),
                (ecodes.ABS_RY, AbsInfo(0, -32767, 32767, 0, 0, 0)),
                (ecodes.ABS_RZ, AbsInfo(0, -32767, 32767, 0, 0, 0)),
            ],
        }
        self.gyro_device = UInput(gyro_cap, name="Benim Virtual Gyro")
        
        # ─────────────────────────────────────────────────────
        # MAPPING
        # ─────────────────────────────────────────────────────
        self.mouse_btns = {
            0: ecodes.BTN_LEFT,
            1: ecodes.BTN_RIGHT,
            2: ecodes.BTN_MIDDLE
        }
        
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
        
        # D-Pad bit maskeleri
        self.DPAD_UP    = 0x00002000
        self.DPAD_DOWN  = 0x00004000
        self.DPAD_LEFT  = 0x00008000
        self.DPAD_RIGHT = 0x00010000
    
    # ─────────────────────────────────────────────────────────
    # MOUSE
    # ─────────────────────────────────────────────────────────
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
    
    # ─────────────────────────────────────────────────────────
    # GAMEPAD BUTTONS
    # ─────────────────────────────────────────────────────────
    def gamepad_buttons(self, buttons, prev):
        changed = False
        
        # Normal butonlar
        for mask, btn in self.gpad_btns.items():
            curr = bool(buttons & mask)
            old = bool(prev & mask)
            if curr != old:
                self.gamepad.write(self.ecodes.EV_KEY, btn, 1 if curr else 0)
                changed = True
        
        # D-Pad (HAT olarak)
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
            
            # HAT X: -1=Left, 0=Center, 1=Right
            hat_x = -1 if left else (1 if right else 0)
            # HAT Y: -1=Up, 0=Center, 1=Down
            hat_y = -1 if up else (1 if down else 0)
            
            self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_HAT0X, hat_x)
            self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_HAT0Y, hat_y)
            changed = True
        
        if changed:
            self.gamepad.syn()
    
    # ─────────────────────────────────────────────────────────
    # GAMEPAD AXES
    # ─────────────────────────────────────────────────────────
    def gamepad_left_stick(self, x, y):
        self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_X, x)
        self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_Y, y)
        self.gamepad.syn()
    
    def gamepad_right_stick(self, x, y):
        self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_RX, x)
        self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_RY, y)
        self.gamepad.syn()
    
    def gamepad_triggers(self, l2, r2):
        self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_Z, l2)
        self.gamepad.write(self.ecodes.EV_ABS, self.ecodes.ABS_RZ, r2)
        self.gamepad.syn()
    
    # ─────────────────────────────────────────────────────────
    # GYRO
    # ─────────────────────────────────────────────────────────
    def gamepad_gyro(self, rx, ry, rz):
        self.gyro_device.write(self.ecodes.EV_ABS, self.ecodes.ABS_RX, rx)
        self.gyro_device.write(self.ecodes.EV_ABS, self.ecodes.ABS_RY, ry)
        self.gyro_device.write(self.ecodes.EV_ABS, self.ecodes.ABS_RZ, rz)
        self.gyro_device.syn()
    
    def close(self):
        try:
            self.mouse.close()
            self.gamepad.close()
            self.gyro_device.close()
        except: pass

# ═══════════════════════════════════════════════════════════════
# PYNPUT BACKEND (Sadece Mouse)
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
# XDOTOOL BACKEND (Sadece Mouse)
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
# YDOTOOL BACKEND (Wayland Mouse)
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
        self.stats = {
            'packets': 0,
            'pings': 0,
            'mouse_moves': 0,
            'clicks': 0,
            'gamepad': 0,
            'checksum_errors': 0
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
        print("  📦 Paket Formatı: 12 Byte (XOR Checksum)")
        print("     [Header][Buttons 4B][LX][LY][RX][RY][L2][R2][XOR]")
        print("─" * 62)
        print("  🎮 Desteklenen Butonlar (17 adet):")
        print("     A B X Y L1 R1 L2 R2 SELECT START HOME L3 R3")
        print("     D-PAD: UP DOWN LEFT RIGHT")
        print("─" * 62)
        print(f"  📁 Log       : {Config.LOG_FILE}")
        print(f"  🐛 Debug     : {'AÇIK' if Config.DEBUG_MODE else 'KAPALI'}")
        print(f"  🔐 Checksum  : {'AÇIK' if Config.VERIFY_CHECKSUM else 'KAPALI'}")
        print("═" * 62)
    
    def log(self, msg, client=None, level="INFO"):
        ts = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        client_str = f" [{client}]" if client else ""
        emoji = {
            "INFO": "ℹ️", "OK": "✅", "WARN": "⚠️", "ERROR": "❌",
            "PING": "📶", "MOUSE": "🖱️", "GAMEPAD": "🎮", "DEBUG": "🔧",
            "GYRO": "🌀", "CHECKSUM": "🔐"
        }.get(level, "")
        print(f"{ts}{client_str} {emoji} {msg}")
        try:
            with open(Config.LOG_FILE, "a") as f:
                f.write(f"{datetime.now().isoformat()} [{level}]{client_str} {msg}\n")
        except: pass
    
    def _signed(self, b):
        """Unsigned byte -> Signed (-128~127)"""
        return b if b < 128 else b - 256
    
    def _verify_checksum(self, data):
        """XOR checksum doğrula (Byte 0-10 XOR == Byte 11)"""
        if len(data) < 12:
            return False
        xor = 0
        for i in range(11):
            xor ^= data[i]
        return xor == data[11]
    
    # ═══════════════════════════════════════════════════════════
    # PING HANDLER
    # ═══════════════════════════════════════════════════════════
    def handle_ping(self, data, addr):
        if len(data) >= 9:
            self.sock.sendto(data, addr)
            self.stats['pings'] += 1
            if Config.LOG_PACKETS:
                self.log("Ping echo", addr[0], "PING")
    
    # ═══════════════════════════════════════════════════════════
    # GAMEPAD HANDLER (12-Byte Paket)
    # ═══════════════════════════════════════════════════════════
    def handle_gamepad(self, data, addr):
        """
        12-Byte Gamepad Paketi:
        [0]     Header (0x01)
        [1-4]   Buttons (32-bit Little-Endian)
        [5]     LX (-127~127)
        [6]     LY (-127~127)
        [7]     RX (-127~127)
        [8]     RY (-127~127)
        [9]     L2 (0~255)
        [10]    R2 (0~255)
        [11]    XOR Checksum
        """
        if len(data) < 12:
            if Config.LOG_PACKETS:
                self.log(f"Kısa paket: {len(data)} byte", addr[0], "WARN")
            return
        
        if not self.backend:
            return
        
        # XOR Checksum doğrula
        if Config.VERIFY_CHECKSUM:
            if not self._verify_checksum(data):
                self.stats['checksum_errors'] += 1
                if Config.LOG_PACKETS:
                    self.log("Checksum hatası!", addr[0], "CHECKSUM")
                return
        
        ip = addr[0]
        
        # Unpack (Little-Endian)
        buttons = struct.unpack('<I', data[1:5])[0]  # 32-bit unsigned int
        lx = self._signed(data[5])
        ly = self._signed(data[6])
        rx = self._signed(data[7])
        ry = self._signed(data[8])
        l2 = data[9]   # 0-255
        r2 = data[10]  # 0-255
        
        if Config.LOG_RAW_BYTES:
            self.log(
                f"RAW: btn=0x{buttons:08X} L:({lx:4d},{ly:4d}) R:({rx:4d},{ry:4d}) T:{l2:3d}/{r2:3d}",
                ip, "DEBUG"
            )
        
        # ─────────────────────────────────────────────────────
        # BUTONLAR
        # ─────────────────────────────────────────────────────
        prev = self.prev_buttons.get(ip, 0)
        if buttons != prev:
            self.backend.gamepad_buttons(buttons, prev)
            self.prev_buttons[ip] = buttons
            self.stats['gamepad'] += 1
            
            if Config.LOG_PACKETS or buttons > 0:
                pressed = [name for mask, (name, _) in Config.GAMEPAD_BUTTONS.items() if buttons & mask]
                if pressed:
                    self.log(f"Tuşlar: {', '.join(pressed)}", ip, "GAMEPAD")
        
        # ─────────────────────────────────────────────────────
        # SOL JOYSTİCK
        # ─────────────────────────────────────────────────────
        if abs(lx) < Config.JOYSTICK_DEADZONE:
            lx = 0
        if abs(ly) < Config.JOYSTICK_DEADZONE:
            ly = 0
        
        self.backend.gamepad_left_stick(lx, ly)
        
        # Joystick → Mouse modu
        if Config.JOYSTICK_AS_MOUSE and (lx or ly):
            self.backend.mouse_move(
                int(lx * Config.MOUSE_SENSITIVITY / 20),
                int(ly * Config.MOUSE_SENSITIVITY / 20)
            )
        
        # ─────────────────────────────────────────────────────
        # SAĞ JOYSTİCK
        # ─────────────────────────────────────────────────────
        if abs(rx) < Config.JOYSTICK_DEADZONE:
            rx = 0
        if abs(ry) < Config.JOYSTICK_DEADZONE:
            ry = 0
        
        self.backend.gamepad_right_stick(rx, ry)
        
        # ─────────────────────────────────────────────────────
        # TETİKLER (Analog)
        # ─────────────────────────────────────────────────────
        if l2 < Config.TRIGGER_DEADZONE:
            l2 = 0
        if r2 < Config.TRIGGER_DEADZONE:
            r2 = 0
        
        self.backend.gamepad_triggers(l2, r2)
    
    # ═══════════════════════════════════════════════════════════
    # MOUSE HANDLERS
    # ═══════════════════════════════════════════════════════════
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
    
    # ═══════════════════════════════════════════════════════════
    # GYRO HANDLER
    # ═══════════════════════════════════════════════════════════
    def handle_gyro(self, data, addr):
        if len(data) < 13 or not self.backend:
            return
        
        try:
            gx, gy, gz = struct.unpack('<fff', data[1:13])
            
            # rad/s * 1000 (Android'den) → derece/s
            gx_deg = gx * (180 / 3141.5926535)
            gy_deg = gy * (180 / 3141.5926535)
            gz_deg = gz * (180 / 3141.5926535)
            
            # ±500 derece/s = ±32767
            scale = 32767 / 500.0
            
            rx = max(min(int(gx_deg * scale), 32767), -32767)
            ry = max(min(int(gy_deg * scale), 32767), -32767)
            rz = max(min(int(gz_deg * scale), 32767), -32767)
            
            self.backend.gamepad_gyro(rx, ry, rz)
            
            if Config.LOG_PACKETS:
                self.log(f"Gyro: {rx:6d}, {ry:6d}, {rz:6d}", addr[0], "GYRO")
                
        except struct.error:
            self.log("Gyro parse hatası", addr[0], "ERROR")
    
    # ═══════════════════════════════════════════════════════════
    # DISCOVERY HANDLER
    # ═══════════════════════════════════════════════════════════
    def handle_discovery(self, data, addr):
        try:
            if b"DISCOVER" in data:
                self.sock.sendto(b"I_AM_SERVER", addr)
                self.log("Discovery yanıtı gönderildi", addr[0], "OK")
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
            self.log(f"Bilinmeyen paket: 0x{ptype:02X}", addr[0], "WARN")
    
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
            print("\n💡 Çözüm: ./install.sh çalıştırın")
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
            try:
                self.sock.close()
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
        print(f"   Gamepad Buton  : {self.stats['gamepad']:,}")
        print(f"   Checksum Hata  : {self.stats['checksum_errors']:,}")
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
        description="🎮 Benim Gamepad/Mouse Server v" + VERSION,
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Paket Formatı (12 Byte):
  [Header 1B][Buttons 4B][LX][LY][RX][RY][L2][R2][XOR]

Örnekler:
  ./run.sh              Normal başlat
  ./run.sh -d           Debug modu
  ./run.sh -p 5000      Farklı port
  ./run.sh -b evdev     Evdev backend
  ./run.sh --no-checksum  Checksum kontrolünü kapat
        """
    )
    parser.add_argument("-p", "--port", type=int, default=26760, help="UDP port")
    parser.add_argument("-d", "--debug", action="store_true", help="Debug modu")
    parser.add_argument("-b", "--backend", choices=["auto", "evdev", "pynput", "xdotool", "ydotool"],
                       default="auto", help="Input backend")
    parser.add_argument("--no-checksum", action="store_true", help="XOR checksum doğrulamayı kapat")
    parser.add_argument("-v", "--version", action="version", version=f"v{VERSION}")
    args = parser.parse_args()
    
    if args.debug:
        Config.enable_debug()
    
    if args.no_checksum:
        Config.VERIFY_CHECKSUM = False
    
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
