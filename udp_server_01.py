#!/usr/bin/env python3
"""
Linux UDP Server for Benim Gamepad/Mouse Controller
Port: 26760
"""

import socket
import struct
import time
import threading
from datetime import datetime
import json
import sys

class UdpServer:
    def __init__(self, host='0.0.0.0', port=26760):
        self.host = host
        self.port = port
        self.running = False
        self.clients = {}
        self.last_activity = {}
        
        # Paket tipleri (Android uygulamasıyla aynı)
        self.PACKET_PING = 0x7F
        self.PACKET_JOYSTICK = 0x01
        self.PACKET_MOUSE_MOVE = 0x02
        self.PACKET_MOUSE_BUTTON = 0x03
        self.PACKET_MOUSE_WHEEL = 0x04
        
        # Log dosyası
        self.log_file = "gamepad_server.log"
        
        print(f"Benim Gamepad/Mouse Server başlatılıyor...")
        print(f"Port: {port}")
        print(f"IP adresiniz: {self.get_local_ip()}")
        print(f"Log dosyası: {self.log_file}")
        print("-" * 50)
    
    def get_local_ip(self):
        """Yerel IP adresini al"""
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except:
            return "127.0.0.1"
    
    def log(self, message, client_ip=None):
        """Log mesajı yaz"""
        timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        client_str = f" [{client_ip}]" if client_ip else ""
        log_msg = f"{timestamp}{client_str}: {message}"
        
        print(log_msg)
        
        # Dosyaya log yaz
        try:
            with open(self.log_file, "a", encoding="utf-8") as f:
                f.write(log_msg + "\n")
        except:
            pass
    
    def handle_ping(self, data, client_address):
        """Ping paketini işle (echo gönder)"""
        if len(data) >= 9:
            # Android'den gelen timestamp'i al
            # İlk byte packet tipi, sonraki 8 byte timestamp
            timestamp_bytes = data[1:9]
            
            # Aynı paketi geri gönder (echo)
            try:
                self.sock.sendto(data, client_address)
                self.log(f"Ping echo gönderildi", client_address[0])
            except Exception as e:
                self.log(f"Ping echo gönderme hatası: {e}", client_address[0])
    
    def handle_joystick(self, data, client_address):
        """Joystick paketini işle"""
        if len(data) >= 5:
            buttons = data[1]
            x = data[2]  # -127 ile 127 arası
            y = data[3]  # -127 ile 127 arası
            z = data[4]  # Genellikle 0
            
            # Butonları binary olarak göster
            buttons_bin = bin(buttons)[2:].zfill(8)
            
            # X ve Y değerlerini signed byte olarak çevir
            def signed_byte(b):
                return b if b < 128 else b - 256
            
            x_val = signed_byte(x)
            y_val = signed_byte(y)
            
            self.log(f"Joystick: Butonlar={buttons_bin}, X={x_val:4d}, Y={y_val:4d}, Z={z}", client_address[0])
            
            # Burada joystick komutlarını işleyebilirsiniz
            # Örneğin: pygame ile oyun kontrolü, veya başka bir uygulamaya yönlendirme
    
    def handle_mouse_move(self, data, client_address):
        """Mouse hareket paketini işle"""
        if len(data) >= 5:
            dx = data[1]  # X hareketi (-127 ile 127)
            dy = data[2]  # Y hareketi (-127 ile 127)
            
            # Signed byte dönüşümü
            def signed_byte(b):
                return b if b < 128 else b - 256
            
            dx_val = signed_byte(dx)
            dy_val = signed_byte(dy)
            
            self.log(f"Mouse Hareket: dX={dx_val:4d}, dY={dy_val:4d}", client_address[0])
            
            # Burada mouse hareketini uygulayabilirsiniz
            # Örneğin: pyautogui ile mouse hareketi
    
    def handle_mouse_button(self, data, client_address):
        """Mouse buton paketini işle"""
        if len(data) >= 5:
            button = data[1]  # 0=sol, 1=sağ, 2=orta
            pressed = data[2]  # 1=basıldı, 0=bırakıldı
            
            button_names = {0: "SOL", 1: "SAĞ", 2: "ORTA"}
            button_name = button_names.get(button, f"Bilinmeyen({button})")
            state = "BASILDI" if pressed == 1 else "BIRAKILDI"
            
            self.log(f"Mouse Buton: {button_name} {state}", client_address[0])
            
            # Burada mouse buton işlemlerini yapabilirsiniz
            # Örneğin: pyautogui ile tıklama
    
    def handle_mouse_wheel(self, data, client_address):
        """Mouse tekerlek paketini işle"""
        if len(data) >= 5:
            delta = data[1]  # Kaydırma miktarı
            
            # Signed byte dönüşümü
            delta_val = delta if delta < 128 else delta - 256
            
            direction = "YUKARI" if delta_val > 0 else "AŞAĞI" if delta_val < 0 else "YOK"
            
            self.log(f"Mouse Tekerlek: Delta={delta_val:3d} ({direction})", client_address[0])
            
            # Burada scroll işlemini yapabilirsiniz
            # Örneğin: pyautogui ile scroll
    
    def handle_discovery(self, data, client_address):
        """Keşif (discovery) paketini işle"""
        try:
            message = data.decode('utf-8', errors='ignore').strip()
            if "DISCOVER_JOYSTICK_SERVER" in message:
                response = "I_AM_SERVER"
                self.sock.sendto(response.encode('utf-8'), client_address)
                self.log(f"Keşif isteği alındı, cevap gönderildi", client_address[0])
        except:
            pass
    
    def process_packet(self, data, client_address):
        """Gelen paketi işle"""
        client_ip = client_address[0]
        
        # Aktivite zamanını güncelle
        self.last_activity[client_ip] = time.time()
        
        # İlk byte paket tipini belirler
        if len(data) == 0:
            return
        
        packet_type = data[0]
        
        # Keşif paketi mi? (string olarak geliyor)
        try:
            if data.decode('utf-8', errors='ignore').startswith("DISCOVER"):
                self.handle_discovery(data, client_address)
                return
        except:
            pass
        
        # Binary paketleri işle
        if packet_type == self.PACKET_PING:
            self.handle_ping(data, client_address)
        
        elif packet_type == self.PACKET_JOYSTICK:
            self.handle_joystick(data, client_address)
        
        elif packet_type == self.PACKET_MOUSE_MOVE:
            self.handle_mouse_move(data, client_address)
        
        elif packet_type == self.PACKET_MOUSE_BUTTON:
            self.handle_mouse_button(data, client_address)
        
        elif packet_type == self.PACKET_MOUSE_WHEEL:
            self.handle_mouse_wheel(data, client_address)
        
        else:
            self.log(f"Bilinmeyen paket tipi: {packet_type:02X}", client_ip)
    
    def cleanup_clients(self):
        """Eski bağlantıları temizle"""
        current_time = time.time()
        timeout = 30  # 30 saniye
        
        to_remove = []
        for client_ip, last_time in self.last_activity.items():
            if current_time - last_time > timeout:
                to_remove.append(client_ip)
                self.log(f"İstemci zaman aşımı: {client_ip}")
        
        for client_ip in to_remove:
            del self.last_activity[client_ip]
            if client_ip in self.clients:
                del self.clients[client_ip]
    
    def start(self):
        """Sunucuyu başlat"""
        self.running = True
        
        try:
            # UDP soketi oluştur
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.sock.bind((self.host, self.port))
            
            # Broadcast'leri dinlemek için
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            
            self.log(f"Sunucu başlatıldı: {self.host}:{self.port}")
            self.log("Android uygulamasını başlatın ve 'Discover' butonuna tıklayın")
            self.log("Çıkmak için Ctrl+C")
            print("-" * 50)
            
            # Temizleme thread'i
            cleanup_thread = threading.Thread(target=self.cleanup_loop, daemon=True)
            cleanup_thread.start()
            
            # Ana dinleme döngüsü
            while self.running:
                try:
                    data, client_address = self.sock.recvfrom(1024)
                    
                    # Yeni thread'de paketi işle
                    thread = threading.Thread(
                        target=self.process_packet,
                        args=(data, client_address),
                        daemon=True
                    )
                    thread.start()
                    
                except socket.timeout:
                    continue
                except KeyboardInterrupt:
                    self.log("Sunucu durduruluyor...")
                    break
                except Exception as e:
                    self.log(f"Alım hatası: {e}")
        
        except Exception as e:
            self.log(f"Sunucu başlatma hatası: {e}")
        
        finally:
            self.stop()
    
    def cleanup_loop(self):
        """Periyodik temizleme döngüsü"""
        while self.running:
            time.sleep(10)  # Her 10 saniyede bir
            self.cleanup_clients()
    
    def stop(self):
        """Sunucuyu durdur"""
        self.running = False
        try:
            self.sock.close()
        except:
            pass
        self.log("Sunucu durduruldu")

def main():
    """Ana fonksiyon"""
    print("=" * 50)
    print("Benim Gamepad/Mouse UDP Sunucusu")
    print("=" * 50)
    
    # Kullanım bilgisi
    if len(sys.argv) > 1 and sys.argv[1] in ["-h", "--help"]:
        print("Kullanım: python3 server.py [port]")
        print("Örnek: python3 server.py 26760")
        return
    
    # Port belirleme
    port = 26760
    if len(sys.argv) > 1:
        try:
            port = int(sys.argv[1])
        except:
            print(f"Geçersiz port: {sys.argv[1]}, varsayılan {port} kullanılıyor")
    
    # Sunucuyu başlat
    server = UdpServer(port=port)
    
    try:
        server.start()
    except KeyboardInterrupt:
        print("\nSunucu kullanıcı tarafından durduruldu.")
    except Exception as e:
        print(f"Beklenmeyen hata: {e}")

if __name__ == "__main__":
    main()
