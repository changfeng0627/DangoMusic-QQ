# Isolated renderer regression

Compiles the **actual production** Imm, GlState, GlShader and GlassBackdrop sources
against small Minecraft boundary stubs, then uses a hidden GLFW window and the
installed OpenGL driver. No Minecraft account, game directory or network is used.
The stubs are test fixtures and are never packaged in the mod.

Checks real framebuffer pixels/orientation, normal and 4x MSAA capture, resizing,
GL state restoration, sRGB appearance, fixed/vanilla interleaving, resource release,
nested guards, same-frame capture reuse and uniform cache
updates (including matrices and optimized-away uniforms). The benchmark measures
CPU submission and GPU elapsed time separately. Microbenchmarks do not establish
whole-game performance or user acceptance.

Requires Python 3, JDK 21, a Gradle lib directory containing the Kotlin 2.2.21
compiler, and cached LWJGL 3.3.3 core/glfw/opengl plus Windows x64 natives,
JOML 1.10.5. It performs no downloads. Currently the runner targets Windows x64.

```powershell
python tools/render-regression/run.py --java "$env:JAVA_HOME/bin/java.exe" --gradle-lib "$env:GRADLE_HOME/lib" --cache "$env:GRADLE_USER_HOME/caches/modules-2/files-2.1" --after-only
```

Omit `--after-only` to compare with Git revision `7c37413` (override with `--before`).
Use `--work` to choose an output directory. The default is `.gradle/render-regression`.
Run with no other GPU workload for comparable timings. Logs, compiled fixtures
and generated test shaders remain under the selected output directory.
