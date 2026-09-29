#!/usr/bin/env bash
# 栅栏自测入口：用 -Xint 跑 check/ 自带的最小正确对照实现（ReferenceImpl）做「已发布前缀合法性」
# 复现验证（200 轮）。这份对照只存在于 check/ 与脚本里，不是参考答案。
#
# 等价于 `bash scripts/check.sh --only barrier`，但单独抽出便于快速复现并发判据本身。
set -uo pipefail

cd "$(dirname "$0")/.."

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) CPSEP=';' ;;
    *) CPSEP=':' ;;
esac

if ! bash scripts/build.sh; then
    echo "构建失败，无法运行栅栏自测" >&2
    exit 2
fi

mkdir -p out-check
find out-check -name '*.class' -delete

if ! javac -encoding UTF-8 -cp out -d out-check check/Checker.java check/ReferenceImpl.java; then
    echo "固定件编译失败" >&2
    exit 2
fi

java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
    -Xint -cp "out-check" ReferenceImpl 200
