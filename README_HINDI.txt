ELITE(3).ZIP -> BLACKBOX OPEN-SOURCE SDK / AAR BUILD PACKAGE
===========================================================
WHAT IS HERE
- Bcore/            : actual Android library SDK, AIDL/Java/native C++
- black-reflection/ : runtime library that must accompany Bcore AAR
- compiler/         : build-time annotation processor
- prebuilt-libs/black-reflection.jar : provided original precompiled runtime JAR
- build-aar.sh      : builds an AAR and collects output with its dependencies
- dependencies.gradle : Gradle dependencies for another project

IMPORTANT STATUS
No .aar has been pre-built in this ZIP. This environment lacks the Android SDK,
Gradle and Android NDK required to compile Bcore's AIDL and JNI C++ sources.
Never rename a .jar or ZIP into an .aar -- that will not make a working SDK.

ANDROIDIDE BUILD (on your phone)
1. Extract the ZIP to a writable folder (e.g. Download/ELITE_OpenSource_SDK_AAR_Build).
2. Open this extracted project in AndroidIDE. Java 17 + Android SDK 34,
   Gradle 8.5 and a WORKING Android NDK are required. The source specifies
   NDK 24.0.8215888 in build.gradle. NDK must be runnable on YOUR CPU.
3. Open AndroidIDE terminal in the project root.
4. Run: sh build-aar.sh
   Or: sh ./gradlew :Bcore:assembleDebug --stacktrace
5. On success, output is sdk-dist/Bcore-debug.aar and
   sdk-dist/black-reflection.jar.
   Gradle direct output: Bcore/build/outputs/aar/Bcore-debug.aar
6. For release variant: sh build-aar.sh release
   This produces Bcore-release.aar. Update the filename in dependencies.gradle.
   For troubleshooting, debug AAR is preferable (it is not minified).

DEPENDENCIES TO USE THE BUILT AAR IN ANOTHER APP
In the APP module, place the Bcore-debug.aar and black-reflection.jar
inside app/libs/ and paste the lines from dependencies.gradle into
app/build.gradle -> dependencies { }. A local .aar does NOT automatically
download transitive Gradle dependencies. If app/libs/*.jar is already
included with fileTree, omit the separate JAR line to avoid duplicates.
Add consumer-proguard-rules.pro to app's release ProGuard rules if needed.

If you are using this AAR in the original ELITE app, REMOVE
   implementation project(':Bcore')
and replace it with
   implementation files('libs/Bcore-debug.aar')
BUT keep existing Firebase and other unrelated app dependencies as-is.
The SDK AndroidManifest is merged into the host app automatically.
It uses ${applicationId} placeholders and many proxy components.

SDK REQUIREMENTS
- minSdk 24; compileSdk 34 in the supplied build
- ABI: arm64-v8a (Bcore abiFilters), native libblackbox.so built from sources
- Gradle wrapper: 8.5; Android Gradle Plugin: 8.2.1; JDK 17
- native NDK version requested: 24.0.8215888
- no app-specific Google/Firebase authentication included in this SDK.

TROUBLESHOOTING
If :Bcore:configureNdkBuild... fails with an Exec format error, your
downloaded NDK is for an incompatible CPU architecture. This is an
AndroidIDE toolchain issue, not proof that an AAR exists. Ensure an AndroidIDE
compatible NDK (including ndk-build and clang) is correctly installed.
Do not remove externalNativeBuild: without compiled libblackbox.so,
the virtualization core cannot function normally.

Files copied from the supplied user source; build NOT verified here.
