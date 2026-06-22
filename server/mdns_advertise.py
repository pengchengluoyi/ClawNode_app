#!/usr/bin/env python3
"""
与 MiniOrangeServer/main.py register_mdns 保持一致的 mDNS 广播（参考实现）。

MiniOrangeServer 启动时已内置 register_mdns，通常无需单独运行本脚本。
若独立调试 ClawNode，可临时执行：

  pip install -r requirements.txt
  python mdns_advertise.py --port 10104
"""
from __future__ import annotations

import argparse
import atexit
import platform
import socket
import sys
import time

try:
    from zeroconf import IPVersion, ServiceInfo, Zeroconf
except ImportError:
    print("请先安装: pip install zeroconf", file=sys.stderr)
    sys.exit(1)

# 与 MiniOrangeServer/main.py 完全一致
SERVICE_TYPE = "_http._tcp.local."


def register_mdns(port: int, local_ip: str | None = None) -> tuple[Zeroconf, ServiceInfo]:
    ip = local_ip or get_local_ip()
    hostname = platform.node().split(".")[0]
    safe_hostname = "".join(c for c in hostname if c.isalnum() or c == "-") or "miniorange"
    mdns_hostname = f"miniorange-{safe_hostname}.local."
    info = ServiceInfo(
        SERVICE_TYPE,
        f"miniorange-{safe_hostname}.{SERVICE_TYPE}",
        addresses=[socket.inet_aton(ip)],
        port=port,
        server=mdns_hostname,
    )
    zc = Zeroconf(ip_version=IPVersion.V4Only)
    zc.register_service(info, allow_name_change=True)
    print(
        f"[mDNS] Registered: http://{mdns_hostname.rstrip('.')}:{port} ({ip})",
        flush=True,
    )
    return zc, info


def get_local_ip() -> str:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="MiniOrange 兼容 mDNS 广播 (_http._tcp)")
    parser.add_argument("--port", type=int, default=10104)
    parser.add_argument("--host", default=None, help="广播 IP，默认自动检测")
    args = parser.parse_args()

    zc, info = register_mdns(args.port, args.host)
    atexit.register(lambda: (zc.unregister_service(info), zc.close()))

    print("[mDNS] Ctrl+C 退出", flush=True)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        zc.unregister_service(info)
        zc.close()


if __name__ == "__main__":
    main()
