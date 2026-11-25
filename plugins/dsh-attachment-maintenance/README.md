# dsh-attachment-maintenance — 附件库维护（孤儿回收 + 重复别名折叠）

`~/.dsh/attachments/v1` 是内容寻址的，会话日志里只存 `sha256:<64hex>` 引用，本体在

```
attachments/v1/objects/<前两位>/<sha256>       图片等对象
attachments/v1/file-objects/<前两位>/<sha256>  文件对象（规范副本）
attachments/v1/files/<前两位>/<sha256>/<名字>  文件对象的别名（以真实文件名结尾，给模型/用户一个可读路径）
```

`dsh-attachment-local` 没有引用计数也没有 GC，于是：

* **孤儿**：上传了但没进任何消息、或被编辑掉的历史对象，只增不减。
* **重复**：`files/<h>/<name>` 本该是 `file-objects/<h>` 的硬链接，但 Android 的 SELinux
  不允许 app 私有目录里 `link(2)`（`avc: denied { link } ... tclass=file permissive=0`），
  于是退化成复制，同一份字节在盘上存两遍。1.0.25 的 P38 让写侧改走相对符号链接，
  这个插件负责把历史遗留的重复也折叠掉。

## 工具：`attachments_gc`

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `dryRun` | `true` | 只看不改。要真正动手必须显式传 `dryRun:false`。 |
| `delete` | `false` | 删除 `objects/` 与 `file-objects/` 里**没有任何会话引用**的对象。 |
| `dedupe` | `false` | 把 `files/<h>/<name>` 换成指向 `file-objects/<h>` 的链接（先比 size 再比两侧 sha256）。 |
| `olderThanDays` | `0` | 只删 mtime 至少 N 天前的孤儿。 |
| `extraSources` | `true` | 额外扫 home 下非缓存文件里的引用（关掉可能删掉草稿还指着的东西）。 |

返回：`referenced / orphans / orphanBytes / dedupePairs / dedupeBytes / deleted / linked /
symlinked / refs / frames / alreadyLinked / keptByAlias / manifest …`

### 典型用法

```
# 1) 先看
attachments_gc {}
# 2) 折叠重复（省一半重复字节，无损）
attachments_gc { "dedupe": true, "dryRun": false }
# 3) 清孤儿（先写清单再删）
attachments_gc { "delete": true, "dryRun": false }
# 只清两周前的孤儿
attachments_gc { "delete": true, "dryRun": false, "olderThanDays": 14 }
```

### 安全边界

* 默认 dry-run，返回统计但不动盘；
* 引用集合来自 `sessions/**` 的**每一个** zstd 帧（多帧文件逐帧解压，不是只解第一帧），
  外加 home 下其它非缓存文件里的 `sha256:` 字符串；
* `files/<h>` 目录存在时，`<h>` 一律视为被引用 —— 别名永远不会被回收成悬空链接；
* 只碰 64 位小写十六进制名字；`tmp/`、`files/` 里其它名字一律不动；
* 删除前把清单（hash / 大小 / 时间 / 路径）写进 `~/.dsh/attachments/gc-<时间>.json`；
* 链接先在临时名上备好，再 `rename` 原子替换；失败不会留下一个空别名。

## 离线自测

```
node check.mjs      # 假数据、无网络、不碰真实 ~/.dsh
```

覆盖：默认 dry-run 不改盘 / delete 只删无引用 / dedupe 只折叠内容相同的别名 /
缺 `sessions` 目录不崩 / 参数类型校验。

## 1.0.25 真机实测（Xiaomi 17 Pro Max · Android 17）

见仓库 `HANDOVER-20261026.md` §16：`attachments/v1` 666 MB → 441.5 MB
（折叠 12 对重复省 217.3 MB，删 37 个孤儿省 7.2 MB）。
