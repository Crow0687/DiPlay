# Leapmotor C11 adaptation

This branch reconstructs the vehicle-specific behavior observed in
`DiPlay-C11车道级导航版-v0.2.15-245.apk.1` on the current 0.2.18 source tree.
The APK is a behavior reference, not the original Kotlin project. Its SHA-256 is
`47eb21c467ad5830888b18760d8069e1c723be970d3199e0140889b9ab03b699`.

## Vehicle selection and map

Like the reference APK, the adapter recognizes fingerprints containing
`qti/msmnile_gvmq`. This is a firmware-family heuristic, not proof of a vehicle model.
The measured map profile additionally requires a 1920 × 720 presentation display.
It requests a 720 × 720 instrument map at 30 fps and places the window at (1200, 0),
with the reference safe area (top 255, bottom 39, left/right 255).
The driver's existing instrument-map enable setting still controls map streaming.
Other screen sizes and other firmware retain the current upstream map paths.

This displays the instrument map rendered by the iPhone's maps application. It does
not implement local lane recognition or guarantee that a maps application supplies
lane-level imagery.

## Media keys and gear

The adapter listens to the reference Leapmotor media broadcasts and decodes
`receiver` JSON packets, `ICU_MediaKey`/`ICU_MediaSwitch`, and C11
`keyEventType`/`value` messages. A 250 ms window suppresses duplicate bus events.
Messages marked `src=diplay` are excluded, including from key learning.

Vehicle → Steering-wheel keys includes **Learn C11 media buttons** on the detected
firmware. Select an action and press a bus or Android media key within 15 seconds.
Assignments are persisted locally and can be cleared. MediaSession, foreground
activity and an enabled accessibility key service can deliver Android key events.
The new normalized binding IDs are not a migration of the old APK's saved IDs.

While a CarPlay controller is attached, the gear adapter requests a CAN update every
second from `com.leapmotor.carcanserver` and reads `1010105` from
`leapmotor.cantoapp.update`. It preserves the reference mapping R=1, N=2, D=3.
R pauses phone playback; leaving R resumes only if DiPlay's pause succeeded. The
reference video policy reports allowed only for N; unknown gear remains unknown.
These mappings need vehicle validation before extending them to other Leapmotor
models or firmware. Receivers and polling stop when the owning controller detaches.

## Legacy map window

Advanced → Instrument map includes **C11 map overlay (experimental)**, off by
default. With overlay permission, `com.autonavi.plus.showmap` opens/resizes a mirror
of the main CarPlay video according to x/y/w/h. `com.autonavi.plus.closemap` closes
it. The reference 16:9 crop and touch forwarding are retained. Mirror output is
released after its decoder worker exits, not while MediaCodec still uses it.

## Boundaries

The branch keeps the current networking, system Telecom, Opus, decoder and update
implementations. The reference APK's 32-bit-only `libdiplay_lwip.so` and generic
userspace USB network modifications are not imported: no original native source is
available, and this is not a Leapmotor broadcast or display adapter. This branch
does not claim to reproduce every binary modification in the reference APK.

The standard build and standalone authentication procedure remains in BUILD.md.
Runtime assets stay outside Git. A debug build has a separate `.hudtest` package;
it cannot access the installed reference application's private preferences.

## Vehicle checks

Confirm instrument display selection and the right-hand square-map placement;
next/previous/toggle on every physical wheel channel; learned bindings and no
double actions; CAN gear values, reverse pause/resume and unknown-gear behavior;
showmap open/resize/close and touch; reconnect/background ownership; and regression
of USB, wireless, phone calls and navigation audio. Automated checks cannot replace
these checks on a C11.

## Local validation (2026-10-12)

On macOS ARM64 with the project's JDK 25, all 10 new adaptation/rendering tests
passed. Compact, full-width and Arabic RTL screenshots were rendered at 1.6× font
scale for both new settings controls. The three application debug builds and lint
tasks passed; the standalone APK was built with explicit external authentication
inputs, whose two packaged files were checked against those inputs.

The complete AGENTS.md CI command was run. Its final run contained 2509 tests,
with 4 failures and 1 skip. These three failures were independently reproduced on
an unmodified archive of the starting commit:

- `AirPlayDualListenerTest.acceptsBothFamiliesOnTheSamePort`
- `AirPlayTcpAcceptTest.bothAddressFamiliesReportOwnerAndOldCleanupPreservesNewListener`
- `CarPlayBonjourDualStackTest.dualLanThenSingleP2pThenDualLanServesAirPlayInfo`

`SettingsBackupServerTest.exportReturnsTheDocumentAsAnAttachment` also failed once
in the final full run (HTTP 401 instead of 200); its entire test class passed on an
isolated rerun of both this branch and the unmodified archive. The final isolated
run also included all 10 new tests and passed. The complete CI is therefore not
reported as green. No C11 hardware validation has been performed.

## 自动开启车机热点入口

C11 平台指纹命中 `qti/msmnile_gvmq` 时，“车机热点”连接模式也显示“零跑功能 · 需要 ADB”，复用上游的“自动开启热点”开关与授权流程。需要本机 ADB 可连接或等待授权；不可连接时上游仍会隐藏此入口。

先在系统设置配置热点，保存相同的名称和密码，再启用自动开启热点，并按需启用“车辆启动后打开”。启动使用系统共享网络接口及已有 ADB 回退，不更改热点名称、密码，不主动关闭热点。这次只开放入口，未加入零跑私有热点服务；热点能否自动恢复、开机自启是否被固件允许，仍需实车验证。
