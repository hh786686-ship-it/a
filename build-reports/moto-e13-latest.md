# Moto e13 32-bit APK build report

Status: **failure**

Run: https://github.com/hh786686-ship-it/a/actions/runs/43209538821

Commit: 48b4073ab40bee570a4cffadafe6855b59a5e52b
App source: v1.9.0 tag (overlay and Call Mode)


## setup.log


### Last 25 log lines

    Loading package information...                                                  Loading local repository...                                                     [                                       ] 3% Loading local repository...        [                                       ] 3% Fetch remote repository...         [=                                      ] 3% Fetch remote repository...         [=                                      ] 4% Fetch remote repository...         [=                                      ] 5% Fetch remote repository...         [==                                     ] 5% Fetch remote repository...         [==                                     ] 6% Fetch remote repository...         [==                                     ] 7% Fetch remote repository...         [==                                     ] 7% Computing updates...               [===                                    ] 8% Computing updates...               [===                                    ] 10% Computing updates...              [=======================================] 100% Computing updates...             
    

## build.log

    gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run_4-1794482988127.json
    [Incubating] Problems report is available at: file:///home/runner/work/a/a/LiveChineseStream-A16/build/reports/problems/problems-report.html
    
    BUILD SUCCESSFUL in 37s
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
    gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run_4-1794482988127.json
    [Incubating] Problems report is available at: file:///home/runner/work/a/a/LiveChineseStream-A16/build/reports/problems/problems-report.html
    
    BUILD SUCCESSFUL in 37s
    34 actionable tasks: 34 executed

## verify.log

    other-services
    supports-screens: 'small' 'normal' 'large' 'xlarge'
    supports-any-density: 'true'
    native-code: 'armeabi-v7a'

### Last 25 log lines

    Verified using v3.1 scheme (APK Signature Scheme v3.1): false
    Verified using v4 scheme (APK Signature Scheme v4): false
    Verified for SourceStamp: false
    Number of signers: 1
    package: name='com.example.livechinesea16' versionCode='12' versionName='1.9.0-call-experimental' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
    sdkVersion:'28'
    targetSdkVersion:'34'
    uses-permission: name='android.permission.RECORD_AUDIO'
    uses-permission: name='android.permission.SYSTEM_ALERT_WINDOW'
    uses-permission: name='android.permission.FOREGROUND_SERVICE'
    uses-permission: name='android.permission.FOREGROUND_SERVICE_MICROPHONE'
    application-label:'Live Chinese Offline'
    application: label='Live Chinese Offline' icon=''
    application-debuggable
    launchable-activity: name='com.example.livechinesea16.MainActivity'  label='' icon=''
    feature-group: label=''
      uses-feature: name='android.hardware.microphone'
      uses-feature: name='android.hardware.faketouch'
      uses-implied-feature: name='android.hardware.faketouch' reason='default feature for all apps'
    provides-component:'accessibility'
    main
    other-services
    supports-screens: 'small' 'normal' 'large' 'xlarge'
    supports-any-density: 'true'
    native-code: 'armeabi-v7a'
