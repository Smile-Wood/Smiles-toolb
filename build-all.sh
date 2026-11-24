#!/usr/bin/env bash
# 构建回归节点，产物在 versions/<游戏版本>/build/libs/。
#
# 只保留两个节点（1.20.1 基准 + 1.21.11 最新）两端夹逼：
# 中间 6 个版本的配置归档在 archived-versions/，要加回来时
# 拷回 versions/<版本>/gradle.properties 并写进下面的 VERSIONS 与 settings.gradle。
#
# 例：
#   ./build-all.sh              # 默认两个节点
#   ./build-all.sh 1.20.1       # 只构建指定节点
#
# 首次构建某个节点需要下载该版本的 Minecraft 与依赖，之后是增量。
set -euo pipefail

cd "$(dirname "$0")"

VERSIONS=(1.20.1 1.21.11)
[ "$#" -gt 0 ] && VERSIONS=("$@")

fail=0
for v in "${VERSIONS[@]}"; do
	printf '%-9s ' "$v"
	if ./gradlew :"$v":build --console=plain -q > "/tmp/build-$v.log" 2>&1; then
		jar=$(ls versions/"$v"/build/libs/*.jar 2>/dev/null | grep -v sources | head -1)
		printf 'OK   %s (%s bytes)\n' "$(basename "$jar")" "$(stat -c%s "$jar")"
	else
		printf 'FAIL (见 /tmp/build-%s.log)\n' "$v"
		fail=1
	fi
done

echo
echo "产物："
ls -l versions/*/build/libs/*.jar 2>/dev/null | grep -v sources | awk '{print "  " $9 "  " $5 " B"}'
exit "$fail"
