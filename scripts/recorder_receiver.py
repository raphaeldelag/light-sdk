#!/usr/bin/env python3
"""Receive Recorder memos from the Light Phone over local Wi-Fi.

    scripts/recorder_receiver.py [--dir ~/LightPhoneRecordings] [--port 8787] [--no-token]

Prints the address (and a QR code if `segno` is installed: pip install segno) that the
Recorder tool's Settings screen scans or you type in. The tool then PUTs each memo to
<address>/<filename>. Files are written atomically; existing files are never overwritten
(a numeric suffix is added).

Security: Android tools may not use plain HTTP, so this serves HTTPS with a self-signed
certificate kept in ~/LightPhone/receiver/ (made with openssl on first run). The
certificate's SHA-256 fingerprint is appended to the address as `#<hex>` and the tool
pins it, so only this Mac's receiver is trusted. A random token in the URL path keeps
strangers on the same network from posting into your folder; --no-token disables it.
"""
import argparse, hashlib, http.server, os, re, secrets, socket, ssl, subprocess, sys, time

CERT_DIR = os.path.expanduser("~/LightPhone/receiver")

def ensure_cert(ip):
    os.makedirs(CERT_DIR, exist_ok=True)
    cert, key = os.path.join(CERT_DIR, "cert.pem"), os.path.join(CERT_DIR, "key.pem")
    if not (os.path.exists(cert) and os.path.exists(key)):
        subprocess.run([
            "openssl", "req", "-x509", "-newkey", "rsa:2048",
            "-nodes", "-keyout", key, "-out", cert, "-days", "3650",
            "-subj", "/CN=recorder-receiver", "-addext", f"subjectAltName=IP:{ip},DNS:localhost",
        ], check=True, capture_output=True)
        os.chmod(key, 0o600)
    der = subprocess.run(["openssl", "x509", "-in", cert, "-outform", "DER"], check=True, capture_output=True).stdout
    return cert, key, hashlib.sha256(der).hexdigest()

def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()

def print_qr(text):
    try:
        import segno
    except ImportError:
        print("(pip install segno to also get a scannable QR code here)")
        return
    segno.make(text).terminal(compact=True)

class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "RecorderReceiver/1"
    def _auth(self):
        token = self.server.token
        parts = [p for p in self.path.split("?")[0].split("/") if p]
        if token:
            if not parts or parts[0] != token:
                self.send_error(403, "bad token"); return None
            parts = parts[1:]
        return parts
    def do_GET(self):
        parts = self._auth()
        if parts is None: return
        if parts == ["ping"]:
            body = b"ok"
            self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
        else:
            self.send_error(404)
    def do_PUT(self):
        parts = self._auth()
        if parts is None: return
        if len(parts) != 1:
            self.send_error(400, "PUT /<token>/<filename>"); return
        name = re.sub(r"[^A-Za-z0-9._-]", "_", parts[0])
        if not name.lower().endswith((".m4a", ".json")):
            self.send_error(400, "only .m4a / .json"); return
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 2_000_000_000:
            self.send_error(411, "Content-Length required"); return
        dest = os.path.join(self.server.dest_dir, name)
        base, ext = os.path.splitext(dest); n = 2
        while ext != ".json" and os.path.exists(dest):
            dest = f"{base}-{n}{ext}"; n += 1
        tmp = dest + ".part"
        remaining = length
        with open(tmp, "wb") as f:
            while remaining > 0:
                chunk = self.rfile.read(min(1 << 20, remaining))
                if not chunk: break
                f.write(chunk); remaining -= len(chunk)
        if remaining != 0:
            os.unlink(tmp); self.send_error(400, "short body"); return
        os.replace(tmp, dest)
        print(f"{time.strftime('%H:%M:%S')} received {os.path.basename(dest)} ({length} bytes)", flush=True)
        body = b"saved"
        self.send_response(201); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def log_message(self, fmt, *args):
        pass

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=os.path.expanduser("~/LightPhoneRecordings"))
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--no-token", action="store_true")
    a = ap.parse_args()
    os.makedirs(a.dir, exist_ok=True)
    token = None if a.no_token else secrets.token_urlsafe(6)
    ip = lan_ip()
    cert, key, fingerprint = ensure_cert(ip)
    srv = http.server.ThreadingHTTPServer(("0.0.0.0", a.port), Handler)
    srv.dest_dir = a.dir; srv.token = token
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert, key)
    srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    url = f"https://{ip}:{a.port}" + (f"/{token}" if token else "") + f"#{fingerprint}"
    print(f"Saving to {a.dir}\nReceiver address (scan or type into Recorder → Settings):\n\n  {url}\n")
    print_qr(url)
    print("\nWaiting for recordings… Ctrl-C to stop.", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass

if __name__ == "__main__":
    main()
