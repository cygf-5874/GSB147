#!/usr/bin/env bash
# 固定验收入口（check/ 是固定验收程序，别改）。
# 用法：bash scripts/check.sh [-list] [--only <组名>[,<组名>...]]
#
# 10 个场景：review 6 + evidence 2 + barrier 2。barrier 2 在独立子进程里用 -Xint 跑
# check/ 自带的最小正确对照实现（ReferenceImpl）做栅栏自测，带看门狗，超时判不通过；
# 某一场景失败不会遮蔽其余场景（失败不早退）。
#
# classpath 分隔符按平台判定：Windows（Git Bash / MSYS / Cygwin）是 `;`，其余是 `:`。
set -uo pipefail

cd "$(dirname "$0")/.."

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) CPSEP=';' ;;
    *) CPSEP=':' ;;
esac

if ! bash scripts/build.sh; then
    echo "构建失败，无法运行验收" >&2
    exit 2
fi

mkdir -p out-check
find out-check -name '*.class' -delete

if ! javac -encoding UTF-8 -cp out -d out-check check/Checker.java check/ReferenceImpl.java; then
    echo "固定件编译失败（对外类型与方法签名可能被改动）" >&2
    exit 2
fi

java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
    -cp "out${CPSEP}out-check" Checker "$@"
