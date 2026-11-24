#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 Stonecutter 两个节点（1.20.1 基准 + 1.21.11 最新）的产物收敛成**一个** jar：
build/libs/smile_toolb-<版本>.jar。

为什么一个 jar 就够：
  toolb 侧现在没有任何 net.minecraft 符号引用（连 ModMenu 的 `Screen create(Screen)`
  都用 java.lang.reflect.Proxy 绕开了，见 ModMenuIntegration 的注释），
  界面、注入、讲述人这些版本相关的东西全在前置库的按版本适配层里。
  所以 1.20.1 ~ 26.2 共用同一份字节码，`depends.minecraft` 写宽范围即可。

为什么只留两个节点（1.20.1 + 1.21.11）：
  纯粹当**回归检查**——两端夹逼。谁往源码里塞了 net.minecraft 引用，那个节点
  会直接编译失败。本脚本再顺手做一次交叉核对：把各节点 class 的
  「class 文件版本号」抹掉后逐字节比对，只要有一个类不同，就说明有代码按版本
  分叉了，一个 jar 的前提被破坏，直接报错。
  中间那 6 个版本节点已归档到 archived-versions/，要加回来只需拷回
  versions/<版本>/gradle.properties 并写进 settings.gradle 的 versions 行。

基准节点取 1.20.1：它按 Java 17 编译（class major 61），
Java 17/21/25 的运行时都能加载；反过来用 1.21.11 的（major 65）就装不进 1.20.1。

用法：先 ./gradlew build，再 python tools/build_release.py
"""

import glob
import hashlib
import json
import re
import shutil
import struct
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE_VERSION = "1.20.1"
SKIP_ENTRIES = {"META-INF/MANIFEST.MF"}
OUT = ROOT / "build" / "libs"


def version_tuple(jar_name):
    """从 smile_toolb-<ver>.jar 取版本号，按数字段比较（1.0.10 > 1.0.9）。"""
    stem = jar_name[len("smile_toolb-"):-len(".jar")]
    return tuple(int(part) if part.isdigit() else 0 for part in re.split(r"[.\-+]", stem))


def mod_version():
    """当前 mod 版本，取自 gradle.properties（产物名就是 smile_toolb-<它>.jar）。"""
    for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("mod.version="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("gradle.properties 里没有 mod.version")


def node_jars():
    """每个节点只认当前版本的产物：build/libs 里常留着历次构建的旧 jar，
    按文件名排序会把 1.0.9 排在 1.0.10 后面（字符串序），收敛出一份旧包。"""
    wanted = "smile_toolb-%s.jar" % mod_version()
    found = {}
    fallback = {}
    for path in sorted(glob.glob(str(ROOT / "versions" / "*" / "build" / "libs" / "smile_toolb-*.jar"))):
        p = Path(path)
        ver = p.parent.parent.parent.name
        if p.name == wanted:
            found[ver] = p
        else:
            current = fallback.get(ver)
            if current is None or version_tuple(p.name) > version_tuple(current.name):
                fallback[ver] = p
    for ver, path in fallback.items():
        found.setdefault(ver, path)
    if any(p.name != wanted for p in found.values()):
        print("注意：有节点没有 %s，退回该节点最新的其它产物" % wanted)
    return found


def entries(jar):
    """名字 → 归一化后的哈希（class 文件抹掉版本号再算）。"""
    table = {}
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if name.endswith("/") or name in SKIP_ENTRIES:
                continue
            data = z.read(name)
            if name.endswith(".class") and len(data) >= 8:
                data = data[:6] + b"\x00\x00" + data[8:]
            table[name] = hashlib.sha1(data).hexdigest()
    return table


def main():
    jars = node_jars()
    if len(jars) < 2:
        raise SystemExit("节点产物不足，先跑 ./gradlew build")
    if BASE_VERSION not in jars:
        raise SystemExit("缺少基准节点 %s 的产物" % BASE_VERSION)

    base_path = jars[BASE_VERSION]
    base = entries(base_path)
    print("基准：%s（%d 个条目）" % (base_path.name, len(base)))

    failures = []
    for ver, path in sorted(jars.items()):
        if ver == BASE_VERSION:
            continue
        other = entries(path)
        only_a = sorted(set(base) - set(other))
        only_b = sorted(set(other) - set(base))
        diff = sorted(n for n in base if n in other and base[n] != other[n])
        if only_a or only_b or diff:
            failures.append((ver, only_a, only_b, diff))
        else:
            print("  一致：%-7s 全部 %d 个条目与基准只差 class 版本号" % (ver, len(other)))

    if failures:
        print("\n有节点与本 jar 不兼容（一个 jar 的前提被破坏）：", file=sys.stderr)
        for ver, only_a, only_b, diff in failures:
            print("  %s 仅基准有=%s 仅该节点有=%s 内容不同=%s"
                  % (ver, only_a[:5], only_b[:5], diff[:5]), file=sys.stderr)
        raise SystemExit(1)

    OUT.mkdir(parents=True, exist_ok=True)
    target = OUT / base_path.name
    shutil.copyfile(base_path, target)

    meta = json.loads(zipfile.ZipFile(target).read("fabric.mod.json").decode("utf-8"))
    bad = []
    with zipfile.ZipFile(target) as z:
        pattern = re.compile(rb"net/minecraft/")
        for name in z.namelist():
            if name.endswith(".class") and pattern.search(z.read(name)):
                bad.append(name)
    if bad:
        raise SystemExit("这些类仍引用 net.minecraft，单 jar 前提不成立：" + ", ".join(bad))
    if meta["depends"].get("minecraft") != ">=1.20.1":
        raise SystemExit("minecraft 范围不是 >=1.20.1：%r" % meta["depends"].get("minecraft"))
    if "malilib" in meta["depends"]:
        raise SystemExit("depends 里还留着 malilib")

    major = None
    with zipfile.ZipFile(target) as z:
        sample = next(n for n in z.namelist() if n.endswith(".class"))
        major = struct.unpack(">H", z.read(sample)[6:8])[0]
    print("\n已生成 %s" % target)
    print("  class 版本 %d（Java %d 起可加载）、%d 个条目、零 net/minecraft 引用"
          % (major, major - 44, len(base)))
    print("  depends: %s" % meta["depends"])


if __name__ == "__main__":
    main()
