# WaveTalk ProGuard rules
# R8/minification is disabled in this build configuration; rules below are
# kept as documentation and safety net if minification is enabled later.

# Keep crash/debug friendly stack traces for our networking & audio pipeline
-keepattributes SourceFile,LineNumberTable

# kotlinx.coroutines ships consumer rules; nothing extra required.
