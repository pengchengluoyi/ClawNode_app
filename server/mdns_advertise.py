#!/usr/bin/env python3
"""与 MiniOrangeServer/server/core/gateway_beacon.py 一致的参考实现。"""
from __future__ import annotations

import asyncio

from server.core.gateway_beacon import register_gateway_beacons, unregister_gateway_beacons


async def main() -> None:
    handle = await register_gateway_beacons(port=10104, ws_path="/ws")
    print("[mDNS] gateway beacon running, Ctrl+C to stop")
    try:
        while True:
            await asyncio.sleep(3600)
    finally:
        await unregister_gateway_beacons(handle)


if __name__ == "__main__":
    asyncio.run(main())
