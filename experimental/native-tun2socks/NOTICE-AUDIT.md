# Declared notice inventory, 2026-11-16

The source lock identifies every repository revision. Android.mk loads exactly
three static libraries (`yaml`, `lwip`, `hev-task-system`) into the tunnel shared
library. Each corresponding `build.mk` recursively selects `*.c` and `*.S` under
its `src`; the tunnel scan includes its nested `src/core` repository.

| Module | Translation units |
| --- | ---: |
| Tunnel and nested core | 31 |
| YAML | 8 |
| lwIP | 95 |
| Task system | 50 |
| Total | 184 |

The source/header superset has 458 files. Some translation units are empty on
Android because their contents are preprocessor-guarded for other platforms;
their notices are retained rather than guessing which functions survive linking.
The inventory includes all headers in these source trees and the public YAML/task
include trees, plus eleven build-graph files. Test/apps trees outside those roots,
Wintun binaries and unrelated components are not Android inputs.

`notice_bundle.py` records every input hash and inherited license, and preserves
302 distinct full copyright/license comments. The initial five-input bundle was
insufficient: lwIP files also credit Carnegie Mellon, Paul Mackerras, Google,
NetBSD, Sun, MINIX, PolarSSL/XySSL, Garmin and other authors with differing
permission/disclaimer text. The current bundle retains those exact texts, their
file mappings and the component licenses, including the previously omitted
`third-part/lwip/LICENSE`. The local ZIP preserves all input file bytes, including
comments not recognized by the notice extractor.

## Remaining public distribution questions

This is a declared-notice inventory, not a claim that a root repository license
establishes the complete licensing history of every imported implementation.

- The task-system red-black tree files name Andrea Arcangeli, David Woodhouse,
  Michel Lespinasse and Heiher, under a file copyright marker for “everyone”.
  Their balancing/rotation structure and several explanatory comments match
  Linux's older red-black tree implementation. The pinned task-system repository
  declares MIT at its root; the compared Linux files explicitly declare
  GPL-2.0-or-later. The relationship and any additional relicensing permission
  have not been established by this inventory. This does not assert that every
  component is GPL or that private use is prohibited.
  [Pinned fork](https://github.com/heiher/hev-task-system/blob/ded55c412b3951bc7decb5cb3b9135701e762998/src/lib/rbtree/hev-rbtree.c),
  [Linux v4.19 source](https://github.com/torvalds/linux/blob/v4.19/lib/rbtree.c).
- The YAML fork identifies libyaml as its upstream, but its root license credits
  hev. Upstream libyaml0.2.5 has MIT terms with Ingy döt Net and Kirill Simonov
  credits. The upstream attribution should be reconciled before a public binary
  release instead of silently assuming the fork's root credit replaces it.
  [Upstream license](https://github.com/yaml/libyaml/blob/0.2.5/License).
- The NDK contributes toolchain/runtime code separately from these three static
  project libraries. The NDK notice and exact linked runtime input closure must
  accompany a public binary release as applicable; this source-only inventory
  does not claim to cover it. Apache notices on Android make scripts are retained
  verbatim and are distinct from the C library root licenses.

The public workflow therefore continues to upload **four reports only**. It does
not upload the `.so` or source snapshot. Local compilation and bounded testing
on the owner's device can proceed independently; loading/JNI/fd ownership and
network safety remain separate runtime tests. No source file in the pinned
third-party checkout was changed by this audit.
