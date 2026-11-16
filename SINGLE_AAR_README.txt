ELITE Bcore + Black Reflection -> one AAR (SOURCE BUILD PACKAGE)

What changed:
- Bcore/build.gradle compiles ../black-reflection/src/main/java directly.
- Bcore no longer declares implementation(project(":black-reflection")).
- annotationProcessor(project(":compiler")) remains because it generates classes.
- :compiler may still depend on :black-reflection while building; that does not
  create a separate JAR dependency for the consuming app.
- build-aar.sh copies only Bcore AAR, proguard rules, and dependencies.gradle.

Build on an Android development environment with compatible Android SDK/NDK:
  sh build-aar.sh
  or: sh ./gradlew :Bcore:assembleDebug --no-daemon --stacktrace

One-file output:
  sdk-dist/Bcore-debug.aar

Host project:
  1. Place Bcore-debug.aar in app/libs/
  2. Add in app/build.gradle dependencies:
       implementation files('libs/Bcore-debug.aar')
  3. Keep additional Maven dependencies listed in dependencies.gradle.
  4. DO NOT additionally install black-reflection.jar or add :black-reflection
     as a runtime dependency: its classes are already inside the AAR.

Verification after build:
  unzip -p sdk-dist/Bcore-debug.aar classes.jar > /tmp/Bcore-classes.jar
  unzip -l /tmp/Bcore-classes.jar | grep 'blackreflection/BlackReflection.class'
  You should see the class listed in the AAR's classes.jar.

This ZIP contains source and a build helper. It does NOT contain a compiled AAR.
Native Android NDK build is still required to compile the Bcore native libraries.
