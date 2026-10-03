# Source this file: `. scripts/env.sh`
# Points the build at the project-local toolchain in .toolchain/ (nothing global).
_root="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
export CW_ROOT="$_root"
export CW_TOOLCHAIN="$_root/.toolchain"
export JAVA_HOME="$CW_TOOLCHAIN/jdk/Contents/Home"
export ANDROID_HOME="$CW_TOOLCHAIN/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$CW_TOOLCHAIN/gradle-home"
# Keeps Android build state (analytics settings, adb keys) out of ~/.android.
export ANDROID_USER_HOME="$CW_TOOLCHAIN/android-user-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
unset _root
