#!/usr/bin/env python3
import http.server
import socketserver
import urllib.parse
import json
import os
import subprocess

PORT = 8899

class ConvertHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps({'status': 'running', 'service': 'asus_icon_pack_converter'}).encode('utf-8'))

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        params = urllib.parse.parse_qs(parsed.query)
        target = params.get('package', [None])[0] or params.get('path', [None])[0]

        if not target:
            self.send_response(400)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({'error': 'Missing package or path parameter'}).encode('utf-8'))
            return

        print(f"[*] Received conversion request for: {target}")
        cmd = ["/usr/bin/python3", "/root/iconchange/fix_iconpack.py", target]
        try:
            out = subprocess.check_output(cmd, stderr=subprocess.STDOUT).decode('utf-8')
            # Look for output apk path
            out_apk = None
            for line in out.splitlines():
                if "-->" in line:
                    out_apk = line.split("-->", 1)[1].strip()
            
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({
                'success': True,
                'output_apk': out_apk,
                'log': out
            }).encode('utf-8'))
        except subprocess.CalledProcessError as e:
            self.send_response(500)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({
                'success': False,
                'error': e.output.decode('utf-8', errors='ignore')
            }).encode('utf-8'))

    def log_message(self, format, *args):
        pass # Quiet logging

if __name__ == '__main__':
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(('127.0.0.1', PORT), ConvertHandler) as httpd:
        print(f"[*] Converter daemon listening on http://127.0.0.1:{PORT}")
        httpd.serve_forever()
