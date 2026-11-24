#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 mods 目录里 `_mods_backup_smile/*.pending` 的暂存包正式装进去。

游戏在跑的时候 jar 被 javaw 锁住，直接覆盖会失败；所以构建脚本一律先写
`<名字>.pending`，等玩家退出游戏后再跑这个脚本换上。脚本会：

  1. 找 `_mods_backup_smile/*.pending`（没有就说没事可做）；
  2. 把 mods 里**同前缀**的旧包（`smile_libbox-*` / `smile_toolb-*`）挪进备份目录；
  3. 把 `.pending` 去掉后缀复制进 mods。

旧包被占用（游戏还开着）时立刻停手，什么都不改，退出码 2。

用法：
    python tools/apply_pending.py            # 默认 26.2 实例
    MC_VERSION=1.21.11 python tools/apply_pending.py
"""

import os
import shutil
import sys
from pathlib import Path

MC_HOME = Path(os.environ.get("MC_HOME", "C:/Apps/PCL2CE/.minecraft"))
VERSION = os.environ.get("MC_VERSION", "26.2")
MODS = MC_HOME / "versions" / VERSION / "mods"
BACKUP = MODS / "_mods_backup_smile"


def prefix_of(name):
    """`smile_libbox-1.0.16+26.2.jar` → `smile_libbox`。"""
    return name.split("-", 1)[0]


def main():
    if not MODS.is_dir():
        raise SystemExit("找不到 mods 目录：" + str(MODS))
    pending = sorted(BACKUP.glob("*.pending"))
    if not pending:
        print("没有待安装的暂存包（%s）" % BACKUP)
        return 0

    BACKUP.mkdir(parents=True, exist_ok=True)
    moved = []
    installed = []
    for item in pending:
        target_name = item.name[: -len(".pending")]
        prefix = prefix_of(target_name)

        # 先挪旧的，再放新的；同名文件撞上时也一并挪走
        for old in sorted(MODS.glob(prefix + "-*.jar")):
            try:
                shutil.move(str(old), str(BACKUP / old.name))
            except OSError as exc:
                for pair in moved:
                    shutil.move(str(pair[1]), str(pair[0]))
                raise SystemExit(
                    "旧包被占用（游戏大概还开着）：%s\n%s\n"
                    "退出游戏后再跑一次本脚本。" % (old.name, exc))
            moved.append((old, BACKUP / old.name))

        shutil.copy2(str(item), str(MODS / target_name))
        item.unlink()
        installed.append(target_name)

    for old, _ in moved:
        print("备份  %s" % old.name)
    for name in installed:
        print("安装  %s" % name)
    print("\n完成：%d 个包已装上，重启游戏生效。" % len(installed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
