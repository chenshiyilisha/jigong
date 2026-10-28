#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
记工 · 局域网更新服务器

在电脑上跑这个，手机 App 的「更新」页就能发现新版本并安装。原理很朴素：

    App  --UDP 广播-->  8766 端口   「谁在发记工更新？」
    本脚本 --回复-->    "192.168.x.x:8765"      （省得手打 IP）
    App  --GET-->      /version.json             （对面是什么版本，带 SHA-256）
    App  --GET-->      /jigong-x.y.apk           （下载，校验，交给系统安装）

用法：
    python lan-update-server.py

需要的只有 Python 自带的库，不用 pip install 任何东西。
要发新版：在 jigong/ 目录执行 `gradle pubApk`，它会把 APK 和 version.json 一起写进 dist/。
"""

import argparse
import http.server
import json
import os
import socket
import socketserver
import sys
import threading

PROBE = b"JIGONG1"
REPLY_PREFIX = "JIGONG:"
DEFAULT_HTTP_PORT = 8765
DEFAULT_UDP_PORT = 8766


# ------------------------------------------------------------------ 网络

def lan_ip() -> str:
    """取本机在局域网里的地址。优先 192.168 / 10. / 172.16-31 这几个私有网段。"""
    candidates = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            candidates.append(info[4][0])
    except Exception:
        pass

    # 这条 UDP connect 不会真的发包，只是让系统选一条默认路由
    for probe in ("8.8.8.8", "223.5.5.5"):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect((probe, 80))
            candidates.insert(0, s.getsockname()[0])
        except Exception:
            pass
        finally:
            s.close()

    def score(ip: str) -> int:
        if ip.startswith("192.168."):
            return 3
        if ip.startswith("10."):
            return 2
        if ip.startswith("172."):
            return 2
        if ip.startswith("127."):
            return -1
        return 1

    candidates = [c for c in candidates if not c.startswith("169.254.")]
    if not candidates:
        return "127.0.0.1"
    return max(candidates, key=score)


# ------------------------------------------------------------------ UDP 发现

def udp_responder(udp_port: int, http_port: int, stop: threading.Event):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.bind(("", udp_port))
    except OSError as e:
        print(f"[警告] UDP {udp_port} 端口没绑上（{e}），自动查找会失效，手填 IP 仍然可用")
        return

    s.settimeout(1.0)
    while not stop.is_set():
        try:
            data, addr = s.recvfrom(256)
        except socket.timeout:
            continue
        except OSError:
            break
        if data.strip() == PROBE:
            ip = lan_ip()
            s.sendto(f"{REPLY_PREFIX}{ip}:{http_port}".encode(), addr)
    s.close()


# ------------------------------------------------------------------ HTTP

class Handler(http.server.SimpleHTTPRequestHandler):
    """只读地发目录里的文件。多加了两个东西：
       1. version.json 不许缓存（否则手机一直看到旧版本号）
       2. / 返回一个说明页，方便你拿浏览器先验证一下
    """

    def end_headers(self):
        if self.path.startswith("/version.json"):
            self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
            self.send_header("Pragma", "no-cache")
        self.send_header("Access-Control-Allow-Origin", "*")
        super().end_headers()

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            return self.send_help()
        return super().do_GET()

    def send_help(self):
        ip = lan_ip()
        port = self.server.server_address[1]
        try:
            with open(os.path.join(self.directory, "version.json"), encoding="utf-8") as f:
                v = json.load(f)
        except Exception:
            v = None

        if v:
            ver = f"{v.get('versionName')}（versionCode {v.get('versionCode')}）"
            apk = v.get("apk", "")
            size = v.get("size", 0) / 1024.0 / 1024.0
            body = f"""
            <p class="ver">当前可发的版本：<b>{ver}</b></p>
            <p>文件：<a href="/{apk}">{apk}</a>（{size:.1f} MB）</p>
            <p class="ok">手机 App 里填 <code>{ip}:{port}</code>，点「检查更新」。</p>
            """
        else:
            body = """
            <p class="bad">dist/ 里没有 version.json。</p>
            <p>先在 <code>jigong/</code> 目录执行 <code>gradle pubApk</code> 打一次包。</p>
            """

        html = f"""<!doctype html>
<meta charset="utf-8">
<title>记工 更新服务器</title>
<style>
 body {{ font: 16px/1.7 system-ui, "Microsoft YaHei", sans-serif; max-width: 640px;
        margin: 48px auto; padding: 0 20px; color: #1a1a1a; }}
 h1 {{ font-size: 22px; }}
 code {{ background: #f2f2f2; padding: 2px 6px; border-radius: 4px; }}
 .ver {{ font-size: 20px; }}
 .ok {{ color: #1565c0; font-weight: 600; }}
 .bad {{ color: #c62828; font-weight: 600; }}
 .tip {{ color: #555; font-size: 14px; margin-top: 28px; }}
</style>
<h1>记工 · 局域网更新服务器</h1>
<p>本机地址：<code>{ip}</code>　端口：<code>{port}</code></p>
{body}
<p class="tip">这个页面能打开，说明手机也一定能连上（前提是同一个 WiFi、防火墙放行）。</p>
"""
        data = html.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        # 只在真的传了东西时打一行，别刷屏
        if self.path.startswith("/version.json"):
            return
        sys.stdout.write(f"  · {self.requestline}  {args[1] if len(args) > 1 else ''}\n")
        sys.stdout.flush()


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


# ------------------------------------------------------------------ 主流程

def main():
    ap = argparse.ArgumentParser(description="记工 App 的局域网更新服务器")
    ap.add_argument("--dir", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "dist"),
                    help="要分发的目录（默认 ./dist）")
    ap.add_argument("--port", type=int, default=DEFAULT_HTTP_PORT, help="HTTP 端口，默认 8765")
    ap.add_argument("--udp-port", type=int, default=DEFAULT_UDP_PORT, help="自动发现用的 UDP 端口，默认 8766")
    args = ap.parse_args()

    if not os.path.isdir(args.dir):
        print(f"[错误] 目录不存在：{args.dir}")
        print("       先在 jigong/ 目录执行：  gradle pubApk")
        return 1

    apks = [f for f in os.listdir(args.dir) if f.endswith(".apk")]
    if not apks:
        print(f"[错误] {args.dir} 里没有 APK")
        print("       先在 jigong/ 目录执行：  gradle pubApk")
        return 1

    version_txt = "未知"
    try:
        with open(os.path.join(args.dir, "version.json"), encoding="utf-8") as f:
            v = json.load(f)
        version_txt = f"{v.get('versionName')}（versionCode {v.get('versionCode')}）"
    except Exception:
        print("[警告] 没有 version.json，App 会读不到版本号。用 gradle pubApk 打包会自动生成。")

    ip = lan_ip()
    stop = threading.Event()
    threading.Thread(target=udp_responder, args=(args.udp_port, args.port, stop), daemon=True).start()

    handler = lambda *a, **kw: Handler(*a, directory=args.dir, **kw)  # noqa: E731

    try:
        httpd = Server(("0.0.0.0", args.port), handler)
    except OSError as e:
        print(f"[错误] 端口 {args.port} 被占用了（{e}）。换个端口： --port 8766")
        return 1

    print()
    print("  记工 · 局域网更新服务器已启动")
    print("  " + "-" * 46)
    print(f"  地址    : {ip}:{args.port}")
    print(f"  版本    : {version_txt}")
    print(f"  目录    : {args.dir}")
    print(f"  自动发现: UDP {args.udp_port}（App 里点「自动查找」）")
    print("  " + "-" * 46)
    print("  手机和电脑必须连同一个 WiFi。")
    print("  第一次运行 Windows 会问防火墙，选「允许访问」（专用网络）。")
    print("  停止：Ctrl + C")
    print()

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n停止。")
    finally:
        stop.set()
        httpd.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
