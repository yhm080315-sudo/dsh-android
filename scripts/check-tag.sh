#!/usr/bin/env bash
#
# 发布前的机械校验。这个项目已经在同一件事上栽过三次：
# 发布物与 tag 指向的源码不一致（两次是二进制比 tag 新，一次是 tag 停在上一个版本）。
# 根因始终一样 —— tag 是手工动作，没有校验。
#
# 用法（在要发布的提交上）：
#   bash scripts/check-tag.sh
#
set -uo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$HERE"

[ -f VERSION ] || { echo "找不到 VERSION" >&2; exit 2; }
# shellcheck disable=SC1091
. ./VERSION

TAG="v${APP_VERSION}"
fail=0

echo "== 发布前校验（tag $TAG）=="

# a) tag 必须存在，且指向当前提交
if ! git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    echo "  ✗ tag $TAG 不存在"
    fail=1
else
    tag_commit="$(git rev-parse "$TAG^{commit}")"
    head_commit="$(git rev-parse HEAD)"
    if [ "$tag_commit" = "$head_commit" ]; then
        echo "  ✓ tag $TAG 指向当前提交 ${head_commit:0:8}"
    else
        echo "  ✗ tag $TAG 指向 ${tag_commit:0:8}，而 HEAD 是 ${head_commit:0:8}"
        fail=1
    fi
fi

# b) tag 里的 VERSION 必须与 tag 名一致
tag_version="$(git show "$TAG:VERSION" 2>/dev/null | sed -n 's/^APP_VERSION=//p')"
if [ "$tag_version" = "$APP_VERSION" ]; then
    echo "  ✓ tag 里的 APP_VERSION = $tag_version"
else
    echo "  ✗ tag 里的 APP_VERSION = '${tag_version:-<读不到>}'，应为 $APP_VERSION"
    fail=1
fi

# c) manifest 必须与 VERSION 同步
code="$(grep -o 'android:versionCode="[0-9]*"' app/AndroidManifest.xml | grep -o '[0-9]*')"
name="$(grep -o 'android:versionName="[^"]*"' app/AndroidManifest.xml | head -1 | cut -d'"' -f2)"
if [ "$code" = "$APP_VERSION_CODE" ] && [ "$name" = "${APP_VERSION}+dsh.${DSH_VERSION}" ]; then
    echo "  ✓ manifest 与 VERSION 一致（$name / $code）"
else
    echo "  ✗ manifest 是 $name / $code，应为 ${APP_VERSION}+dsh.${DSH_VERSION} / $APP_VERSION_CODE"
    fail=1
fi

# d) 工作区干净：构建源必须就是提交内容
if [ -z "$(git status --porcelain)" ]; then
    echo "  ✓ 工作区干净"
else
    echo "  ✗ 工作区有未提交改动 —— 构建出来的东西不在任何提交里"
    git status --short | head -5
    fail=1
fi

echo
if [ "$fail" -eq 0 ]; then
    echo "校验通过。构建产物的 versionName 应当是 ${APP_VERSION}+dsh.${DSH_VERSION}，"
    echo "装包后日志里的 versions 行也应当是 app ${APP_VERSION} · dsh ${DSH_VERSION}。"
else
    echo "校验未通过 —— 不要发布。"
    exit 1
fi
