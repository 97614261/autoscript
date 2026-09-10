# Pixel vision benchmark

This benchmark is deliberately dependency-free and deterministic. It creates the same seeded
1920x1080 RGBA frame and 32x32 template on every run, places the only exact match at `(1731,947)`,
warms the scalar engine five times, then reports P50/P95/P99 for a single-threaded full-ROI search.

Run an optimized build with an explicit sample count:

```powershell
cargo run --release -p pixel-vision --example vision_benchmark -- 100
```

Record the command output together with the physical device model, Android version, ABI, CPU
governor/power mode and build commit. Emulator/desktop numbers are development signals only and
must not be used to justify NEON or product performance claims. ARM64 NEON remains incomplete until
an implementation is checked pixel-for-pixel against this scalar baseline and improves real-device
P50/P95/P99 without changing result order.
