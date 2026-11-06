# То же для Git Bash: source ./env.sh
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export JAVA_HOME="$ROOT/.toolchain/jdk-17"
export ANDROID_HOME="$ROOT/.toolchain/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$ROOT/.toolchain/android-user-home"
export GRADLE_USER_HOME="$ROOT/.toolchain/gradle-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
