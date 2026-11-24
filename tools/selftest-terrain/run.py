#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
地形判定 + 重锤落点判定的纯 Java 自检：编译并运行 tools/selftest-terrain/TerrainCheck.java。

只用到纯 Java 源文件（库的 World / EntityView / Packets + 本模组的 Terrain / SlamTargets /
ChestSwap / HandSwap / LeadEstimate / GlideDive / FlightResume），
不加载 Minecraft、不需要 Fabric，也不需要游戏在不在跑。

用法：python tools/selftest-terrain/run.py
"""

import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOOLB = ROOT
LIB = ROOT.parent / "Smile's Lib Box"

WORLD = LIB / "src/main/java/smile_libbox/api/world/World.java"
ENTITY_VIEW = LIB / "src/main/java/smile_libbox/api/world/EntityView.java"
PACKETS = LIB / "src/main/java/smile_libbox/api/world/Packets.java"
TERRAIN = TOOLB / "src/client/java/smile_toolb/client/combat/Terrain.java"
SLAM = TOOLB / "src/client/java/smile_toolb/client/combat/SlamTargets.java"
CHESTSWAP = TOOLB / "src/client/java/smile_toolb/client/combat/ChestSwap.java"
HANDSWAP = TOOLB / "src/client/java/smile_toolb/client/combat/HandSwap.java"
LEAD = TOOLB / "src/client/java/smile_toolb/client/combat/LeadEstimate.java"
GLIDEDIVE = TOOLB / "src/client/java/smile_toolb/client/combat/GlideDive.java"
FLIGHT = TOOLB / "src/client/java/smile_toolb/client/combat/FlightResume.java"
CHECK = Path(__file__).resolve().parent / "TerrainCheck.java"

OUT = Path(__file__).resolve().parent / "build"


def find_javac():
    for candidate in (
        Path("D:/Program Files/Java/jdk-26/bin/javac.exe"),
        Path("C:/Program Files/Java/jdk-26/bin/javac.exe"),
        Path("D:/Program Files/Java/jdk-21/bin/javac.exe"),
        Path("C:/Program Files/Java/jdk-21/bin/javac.exe"),
    ):
        if candidate.exists():
            return candidate
    found = shutil.which("javac")
    if found:
        return Path(found)
    raise SystemExit("找不到 javac，请设置 PATH 或改用完整路径")


def main():
    for path in (WORLD, ENTITY_VIEW, PACKETS, TERRAIN, SLAM, CHESTSWAP, HANDSWAP, LEAD,
                 GLIDEDIVE, FLIGHT, CHECK):
        if not path.exists():
            raise SystemExit("缺少源文件: %s" % path)
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)

    javac = find_javac()
    java = javac.with_name("java.exe" if javac.suffix.lower() == ".exe" else "java")
    if not java.exists():
        java = Path(str(javac).replace("javac", "java"))

    print("javac:", javac)
    subprocess.run(
        [str(javac), "-encoding", "utf-8", "-d", str(OUT),
         str(WORLD), str(ENTITY_VIEW), str(PACKETS), str(TERRAIN), str(SLAM),
         str(CHESTSWAP), str(HANDSWAP), str(LEAD), str(GLIDEDIVE), str(FLIGHT),
         str(CHECK)],
        check=True,
    )
    # JDK 18+ 用 stdout.encoding 控制 System.out；Windows 控制台默认 GBK，
    # 直接把字节按 UTF-8 读回来再交给 Python 输出，才不会被代码页弄成乱码。
    result = subprocess.run(
        [str(java), "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", str(OUT),
         "smile_toolb.client.combat.TerrainCheck"],
        capture_output=True,
        check=False,
    )
    text = result.stdout.decode("utf-8", errors="replace")
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
    print(text, end="")
    if result.returncode != 0 and result.stderr:
        print(result.stderr.decode("utf-8", errors="replace"))
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
