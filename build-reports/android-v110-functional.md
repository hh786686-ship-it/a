# Live Chinese v1.10 Android emulator functional test

Result: **failure**

Run: https://github.com/hh786686-ship-it/a/actions/runs/43593549852

Commit: 310e3ee8d5c3877ad8d56e693bc27123fc1033b2

NOTE: CI-only test hooks were injected into x86_64 QA build, not the released Moto e13 APK.

## sdk.log

    Loading package information...                                                  Loading local repository...                                                     [                                       ] 3% Loading local repository...        [                                       ] 3% Fetch remote repository...         [=                                      ] 3% Fetch remote repository...         [=                                      ] 4% Fetch remote repository...         [=                                      ] 5% Fetch remote repository...         [==                                     ] 5% Fetch remote repository...         [==                                     ] 6% Fetch remote repository...         [==                                     ] 7% Fetch remote repository...         [==                                     ] 7% Computing updates...               [===                                    ] 8% Computing updates...               [===                                    ] 10% Computing updates...              [=======================================] 100% Computing updates...             
    

## build.log

    To honour the JVM settings for this build a single-use Daemon process will be forked. For more on this, please refer to https://docs.gradle.org/8.11.1/userguide/gradle_daemon.html#sec:disabling_the_daemon in the Gradle documentation.
    Daemon will be stopped at the end of the build 
    > Task :app:preBuild UP-TO-DATE
    > Task :app:preDebugBuild UP-TO-DATE
    > Task :app:mergeDebugNativeDebugMetadata NO-SOURCE
    > Task :app:javaPreCompileDebug
    > Task :app:generateDebugResValues
    > Task :app:checkDebugAarMetadata
    > Task :app:mapDebugSourceSetPaths
    > Task :app:generateDebugResources
    > Task :app:createDebugCompatibleScreenManifests
    > Task :app:extractDeepLinksDebug
    > Task :app:mergeDebugResources
    > Task :app:processDebugMainManifest
    > Task :app:packageDebugResources
    > Task :app:parseDebugLocalResources
    > Task :app:processDebugManifest
    > Task :app:mergeDebugShaders
    > Task :app:processDebugManifestForPackage
    > Task :app:compileDebugShaders NO-SOURCE
    > Task :app:generateDebugAssets UP-TO-DATE
    > Task :app:processDebugResources
    > Task :app:mergeDebugAssets
    
    > Task :app:compileDebugJavaWithJavac
    Note: Some input files use or override a deprecated API.
    Note: Recompile with -Xlint:deprecation for details.
    
    > Task :app:desugarDebugFileDependencies
    > Task :app:compressDebugAssets
    > Task :app:dexBuilderDebug
    > Task :app:processDebugJavaRes NO-SOURCE
    > Task :app:mergeDebugGlobalSynthetics
    > Task :app:checkDebugDuplicateClasses
    > Task :app:mergeDebugJavaResource
    > Task :app:mergeExtDexDebug
    > Task :app:mergeLibDexDebug
    > Task :app:mergeDebugJniLibFolders
    > Task :app:mergeProjectDexDebug
    > Task :app:mergeDebugNativeLibs
    > Task :app:validateSigningDebug
    > Task :app:writeDebugAppMetadata
    > Task :app:writeDebugSigningConfigVersions
    
    > Task :app:stripDebugDebugSymbols
    Unable to strip the following libraries, packaging them as they are: libonnxruntime.so, libsherpa-onnx-c-api.so, libsherpa-onnx-cxx-api.so, libsherpa-onnx-jni.so.
    
    > Task :app:packageDebug
    > Task :app:createDebugApkListingFileRedirect
    > Task :app:assembleDebug
    gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run_4-1794658293260.json
    [Incubating] Problems report is available at: file:///home/runner/work/a/a/LiveChineseStream-A16/build/reports/problems/problems-report.html
    
    BUILD SUCCESSFUL in 21s
    34 actionable tasks: 34 executed

## qa-functional.log

    Performing Streamed Install
    Success
    Starting: Intent { cmp=com.example.livechinesea16/.MainActivity }
    TEST 1: on-device model loads in an Android emulator
    PASS: real model initialised
    TEST 2: microphone starts with an overlay

