# 第三方组件声明 / Third-Party Notices

本文件随 **DSH for Android** APK 一起分发（`assets/runtime.tar.gz` → 安装后落在
`/data/user/0/com.dsh.android/files/runtime/THIRD-PARTY-NOTICES.md`，App 的
诊断页 `http://127.0.0.1:3097/` 也会把它原样打出来）。

- App 版本：`1.0.19`（versionCode 10019）
- 内嵌 DSH 运行时：`0.2.1-rc.2`
- 本文件由构建脚本随载荷一起打包；改动请改仓库里的
  `payload/THIRD-PARTY-NOTICES.md`，不要只改 APK 里的那一份。

---

## 1. 随 APK 分发的第三方插件

### 1.1 `dsh-screen-operate` 0.1.5 — 许可待作者确认

- 在本 App 里的用途：本机识屏与操作（`screen_look` / `screen_act` /
  `screen_loop`），是 App 的核心功能；只在设备本地取帧与注入手势，不上传屏幕。
- 许可状态：**待作者确认**。`package.json` 没有 `license` 字段，包内也没有
  `LICENSE` / `COPYING` 文件，因此这里不能替作者声明任何许可。在作者给出明确
  许可之前，它是「随 App 一起构建的第一方代码」，**不构成对外再分发授权**；
  第三方若要把这个包单独拿出来用，需要先联系作者。
- 位置：`dsh/node_modules/dsh-screen-operate/`（含 `OWNER.md`、`HANDOFF.md`）。

### 1.2 `dsh-web-mobile` 3.0.5 — MIT

- 作者：mexiaosh（`package.json` 的 `author` 字段；包内 `LICENSE` 的版权持有者写作
  `mexiaosqwq`，是同一作者的上游账号名，此处照录原文，未做改写）
- 上游：https://github.com/mexiaosqwq/dsh-web-mobile
- 在 App 里的用途：**手机端界面适配**——把 DSH 的 Web UI 在竖屏/窄屏上排成可用的布局。
  它不是可选装饰：没有它，手机浏览器里打开的是桌面宽度布局，界面不可用。因此它随 APK
  分发，App 启动时会在 profile 的 `cordis.patch.yml` 里补上对应的 client 插入行。
- 许可：MIT。下面照录包内 `LICENSE` 全文（版权行与年份均按原文，未改动）：

```text
MIT License

Copyright (c) 2026 mexiaosqwq

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

- 位置：`dsh/node_modules/dsh-web-mobile/`（包内 `LICENSE` 原样保留）。

### 1.3 Shizuku 客户端库（`dev.rikka.shizuku:*` 13.1.0）— Apache-2.0

- 作者 / 维护者：RikkaApps（RikkaW）
- 上游：https://github.com/RikkaApps/Shizuku-API
- 在 App 里的用途：**非 root 提权通道**的客户端。用户在自己设备上装好并授权 Shizuku 后，
  App 通过它拿到 adb 级权限来跑 `screencap` / `input`；**只带客户端库，不含 Shizuku 本体**，
  也没有修改上游代码。四个构件的 `classes.jar` 在构建时解出，随本项目代码一起用 D8 打成
  `classes.dex`（`dx` 会拒绝它们里的 `invokedynamic`，见 `docs/PITFALLS.md` 第 17 条）。
- 许可：Apache-2.0。按 Apache-2.0 第 4 条，随二进制分发时附上许可证全文，照录如下。

```text

                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```

---

## 2. 已从载荷中移除的插件（不随本 APK 分发）

| 插件 | 原许可 | 处理 | 原因 |
|---|---|---|---|
| `dsh-skin-whale-fantasy` | CC-BY-NC-SA-4.0 | 删除 | 非商用 + 传染性（ShareAlike）许可，不能随 APK 分发 |
| `dsh-pro-mode-splash` | MIT | 删除 | 纯装饰，非必要 |
| `dsh-boot-animation` | BSD-3-Clause | 删除 | 纯装饰（片头动画），用户要求去掉；白屏探针已改为按渲染内容判断，不再依赖它，见下 |

`dsh-web-mobile` **曾**在这张表里（1.0.19-beta13 / beta14）：当时把它当成「非必要的布局
增强」删掉了，判断错了——它就是手机界面适配本身。自 1.0.19-beta15 起已放回载荷，见 1.2；
`NodeServer` 的 `REMOVED_PLUGINS` 也把它去掉了，否则启动时会把它的 profile 行清掉。

`dsh-boot-animation` 0.4.2 自 1.0.19-beta16 起不再随 APK 分发，因此本节不再需要照录它的
BSD-3-Clause 版权声明与免责声明（不再构成二进制再分发）。它的 `cordis.patch.yml` 插入行
由 `NodeServer.REMOVED_PLUGINS` 在每次启动时从用户 profile 里清掉。

删除动作同时覆盖：载荷目录、profile 的 `cordis.patch.yml` 里的插入行
（App 启动时会清掉历史遗留行）、以及 `NodeServer` 里的 `BUNDLED_ROWS` /
`CLIENT_ROWS` 硬编码表。删除后不能留「配置行引用了不存在的插件」这种状态，
否则 Loader 启动即报错。

---

## 3. 其余组件

- **DSH 本体**（`dsh/node_modules/@deepseek-ai/dsh`）：MIT，版权归
  DeepSeek，正文见同目录 `LICENSE`。
- **内嵌 npm 依赖**（`dsh/node_modules` 下约 568 个包）：各自沿用上游许可
  （以 MIT / ISC / Apache-2.0 / BSD 为主），许可正文随包保留在各自的
  `LICENSE` / `LICENSE.md` / `COPYING` 或 `package.json` 的 `license` 字段里，
  未做删改。要逐条核对可执行：

  ```sh
  cd /data/user/0/com.dsh.android/files/runtime/dsh/node_modules
  find . -maxdepth 3 -iname 'LICENSE*' -o -maxdepth 3 -iname 'COPYING*'
  ```
- **bionic Node 运行时与系统工具**（`usr/`）：Node.js（MIT）、bash
  （GPL-3.0-or-later）、ripgrep（MIT/Unlicense）、busybox（GPL-2.0）等，
  来自 Termux 的 bionic 构建。
