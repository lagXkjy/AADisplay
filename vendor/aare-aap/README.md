# vendored AARE `aap` module

Source from [prototux/AARE](https://github.com/prototux/AARE) (GPL-3.0-or-later).
Pinned to upstream `main` @ `5ac79ba` (v1.3.0 era).
Protobuf definitions credit aasdk (see `NOTICE.md`).

AADisplay uses this library as the phone-side Android Auto Projection (AAP) protocol
stack so VirtualDisplay can reach the car **without** hooking `gearhead`.

Local overlays (kept across syncs): audio write-queue stall log in `AapSession`,
TLS nonce diagnostics in `Cryptor`, and `VideoChannel.onVideoFocusMode` +
`PROJECTED_NO_INPUT_FOCUS` → re-request full `PROJECTED`.
