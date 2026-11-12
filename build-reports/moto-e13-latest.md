# Moto e13 32-bit APK build report

Status: **success**

Run: https://github.com/hh786686-ship-it/a/actions/runs/43202325497

Commit: 75b9d2496a3a4ff9342031fbeb88455806cab1c9


## setup.log


### Last 25 log lines

    Loading package information...                                                  Loading local repository...                                                     [                                       ] 3% Loading local repository...        [                                       ] 3% Fetch remote repository...         [=                                      ] 3% Fetch remote repository...         [=                                      ] 4% Fetch remote repository...         [=                                      ] 5% Fetch remote repository...         [==                                     ] 5% Fetch remote repository...         [==                                     ] 6% Fetch remote repository...         [==                                     ] 7% Fetch remote repository...         [==                                     ] 7% Computing updates...               [===                                    ] 8% Computing updates...               [===                                    ] 10% Computing updates...              [=======================================] 100% Computing updates...             
    

## build.log

    gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run_3-1794480015963.json
    [Incubating] Problems report is available at: file:///home/runner/work/a/a/LiveChineseStream-A16/build/reports/problems/problems-report.html
    
    BUILD SUCCESSFUL in 29s
    34 actionable tasks: 34 executed

### Last 25 log lines

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
    gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run_3-1794480015963.json
    [Incubating] Problems report is available at: file:///home/runner/work/a/a/LiveChineseStream-A16/build/reports/problems/problems-report.html
    
    BUILD SUCCESSFUL in 29s
    34 actionable tasks: 34 executed

## verify.log

    supports-any-density: 'true'
    locales:
    densities:
    native-code: 'armeabi-v7a'
    native-code: 'armeabi-v7a'
      3376604  1981-01-01 01:01   lib/armeabi-v7a/libsherpa-onnx-jni.so
     15026104  1981-01-01 01:01   lib/armeabi-v7a/libonnxruntime.so
    161141793  1981-01-01 01:01   assets/model/encoder.int8.onnx
      5165083  1981-01-01 01:01   assets/model/decoder.onnx
      1033416  1981-01-01 01:01   assets/model/joiner.int8.onnx

### Last 25 log lines

    Number of signers: 1
    package: name='com.example.livechinesea16' versionCode='10' versionName='1.7.0' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
    sdkVersion:'28'
    targetSdkVersion:'34'
    uses-permission: name='android.permission.RECORD_AUDIO'
    application: label='' icon=''
    application-debuggable
    launchable-activity: name='com.example.livechinesea16.MainActivity'  label='' icon=''
    feature-group: label=''
      uses-feature: name='android.hardware.microphone'
      uses-feature: name='android.hardware.faketouch'
      uses-implied-feature: name='android.hardware.faketouch' reason='default feature for all apps'
    main
    supports-screens: 'small' 'normal' 'large' 'xlarge'
    supports-any-density: 'true'
    locales:
    densities:
    native-code: 'armeabi-v7a'
    native-code: 'armeabi-v7a'
      3376604  1981-01-01 01:01   lib/armeabi-v7a/libsherpa-onnx-jni.so
     15026104  1981-01-01 01:01   lib/armeabi-v7a/libonnxruntime.so
    161141793  1981-01-01 01:01   assets/model/encoder.int8.onnx
      5165083  1981-01-01 01:01   assets/model/decoder.onnx
      1033416  1981-01-01 01:01   assets/model/joiner.int8.onnx
    -rw-r--r-- 1 runner runner 173M Nov 12 10:40 LiveChineseStream-A16/app/build/outputs/apk/debug/app-debug.apk

## publish.log

    https://github.com/hh786686-ship-it/a/releases/tag/moto-e13-v1.9.1
    {"assets":["LiveChineseOffline-MotoE13-32bit-v1.9.1.apk"],"url":"https://github.com/hh786686-ship-it/a/releases/tag/moto-e13-v1.9.1"}

### Last 25 log lines

    https://github.com/hh786686-ship-it/a/releases/tag/moto-e13-v1.9.1
    {"assets":["LiveChineseOffline-MotoE13-32bit-v1.9.1.apk"],"url":"https://github.com/hh786686-ship-it/a/releases/tag/moto-e13-v1.9.1"}
