#import "AgoraRtcEnginePlugin.h"
#import <AgoraRtcWrapper/iris_rtc_engine.h>
#import <AgoraRtcWrapper/iris_video_processor.h>
#import <ReplayKit/ReplayKit.h>
#import "CallApiMethodCallHandler.h"
#import "FlutterIrisEventHandler.h"
#import "AgoraRtcChannelPlugin.h"
#import "AgoraSurfaceViewFactory.h"
#import "AgoraTextureViewFactory.h"
#import "FlutterIrisEventHandler.h"

@interface AgoraRtcEnginePlugin ()

@property(nonatomic) agora::iris::rtc::IrisRtcEngine *irisRtcEngine;

// TODO(littlegnal): Lazy init videoFrameBufferManager
@property(nonatomic) agora::iris::IrisVideoFrameBufferManager *videoFrameBufferManager;

@property(nonatomic) FlutterIrisEventHandler *flutterIrisEventHandler;

@property(nonatomic) CallApiMethodCallHandler *callApiMethodCallHandler;

@property(nonatomic) AgoraRtcChannelPlugin *agoraRtcChannelPlugin;

@property(nonatomic, strong) AgoraTextureViewFactory *factory;

@property(nonatomic) NSObject<FlutterPluginRegistrar> *registrar;

@end

@implementation AgoraRtcEnginePlugin
+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar> *)registrar {
    FlutterMethodChannel *methodChannel =
        [FlutterMethodChannel methodChannelWithName:@"agora_rtc_engine"
                                    binaryMessenger:[registrar messenger]];
    FlutterEventChannel *eventChannel =
        [FlutterEventChannel eventChannelWithName:@"agora_rtc_engine/events"
                                  binaryMessenger:[registrar messenger]];
    AgoraRtcEnginePlugin *instance = [[AgoraRtcEnginePlugin alloc] init];
    instance.registrar = registrar;
    [registrar addMethodCallDelegate:instance channel:methodChannel];
    
    instance.flutterIrisEventHandler = [[FlutterIrisEventHandler alloc] initWith:instance.irisRtcEngine];
    
    [eventChannel setStreamHandler:instance.flutterIrisEventHandler];
    
    instance.agoraRtcChannelPlugin = [[AgoraRtcChannelPlugin alloc] initWith:[instance irisRtcEngine] binaryMessenger:[registrar messenger]];
    
    [instance
        setFactory:[[AgoraTextureViewFactory alloc] initWithRegistrar:registrar]];

    [registrar registerViewFactory:[[AgoraSurfaceViewFactory alloc]
                                       initWith:[registrar messenger]
                                         engine:instance.irisRtcEngine]
                            withId:@"AgoraSurfaceView"];
}

- (instancetype)init {
  self = [super init];
  if (self) {
    self.irisRtcEngine = new agora::iris::rtc::IrisRtcEngine;
      self.videoFrameBufferManager = new agora::iris::IrisVideoFrameBufferManager;
      self.irisRtcEngine->raw_data()->Attach(self.videoFrameBufferManager);
      
      self.callApiMethodCallHandler = [[CallApiMethodCallHandler alloc] initWith:self.irisRtcEngine];
  }
  return self;
}

- (void)dealloc {
  delete self.irisRtcEngine;
    delete self.videoFrameBufferManager;
}

- (void)handleMethodCall:(FlutterMethodCall *)call
                  result:(FlutterResult)result {
#if DEBUG
    if ([@"getIrisRtcEngineIntPtr" isEqualToString:call.method]) {
        result(@((intptr_t)self.irisRtcEngine));
        return;
    }
#endif
    if ([@"startScreenShare" isEqualToString:call.method]) {
        [self launchReplayKitBroadcast:call.arguments[@"extensionName"] result:result];
    } else if ([@"createTextureRender" isEqualToString:call.method]) {
        int64_t textureId = [self.factory
            createTextureRenderer:[self videoFrameBufferManager]];
        result(@(textureId));
    } else if ([@"destroyTextureRender" isEqualToString:call.method]) {
        NSNumber *textureId = call.arguments[@"id"];
        [self.factory destroyTextureRenderer:[textureId integerValue]];
        result(nil);
    } else if ([@"getAssetAbsolutePath" isEqualToString:call.method]) {
        [self getAssetAbsolutePath:call result:result];
    } else {
        [[self callApiMethodCallHandler] onMethodCall:call _:result];
    }
}

- (void)launchReplayKitBroadcast:(NSString *)extensionName result:(FlutterResult)result {
    if (@available(iOS 12.0, *)) {
        RPSystemBroadcastPickerView *broadcastPickerView = [[RPSystemBroadcastPickerView alloc] initWithFrame:CGRectMake(0, 0, 44, 44)];
        
        NSString *bundlePath = [[NSBundle mainBundle] pathForResource:extensionName ofType:@"appex" inDirectory:@"PlugIns"];
        if (!bundlePath) {
            NSString *nullBundlePathErrorMessage = [NSString stringWithFormat:@"Can not find path for bundle `%@.appex`", extensionName];
            NSLog(@"%@", nullBundlePathErrorMessage);
            result([FlutterError errorWithCode:@"NULL_BUNDLE_PATH" message:nullBundlePathErrorMessage details:nil]);
            return;
        }

        NSBundle *bundle = [NSBundle bundleWithPath:bundlePath];
        if (!bundle) {
            NSString *nullBundleErrorMessage = [NSString stringWithFormat:@"Can not find bundle at path: `%@`", bundlePath];
            NSLog(@"%@", nullBundleErrorMessage);
            result([FlutterError errorWithCode:@"NULL_BUNDLE" message:nullBundleErrorMessage details:nil]);
            return;
        }

        broadcastPickerView.preferredExtension = bundle.bundleIdentifier;

        // Traverse the subviews to find the button to skip the step of clicking the system view
        for (UIView *subView in broadcastPickerView.subviews) {
            if ([subView isMemberOfClass:[UIButton class]]) {
                UIButton *button = (UIButton *)subView;
                [button sendActionsForControlEvents:UIControlEventAllEvents];
            }
        }
        result(@(YES));

    } else {
        NSString *notAvailiableMessage = @"RPSystemBroadcastPickerView is only available on iOS 12.0 or above";
        NSLog(@"%@", notAvailiableMessage);
        result([FlutterError errorWithCode:@"NOT_AVAILIABLE" message:notAvailiableMessage details:nil]);
    }
}

- (void)getAssetAbsolutePath:(FlutterMethodCall *)call
                      result:(FlutterResult)result {
    NSString *assetPath = (NSString *)[call arguments];
    if (assetPath) {
        NSString *assetKey = [[self registrar] lookupKeyForAsset:assetPath];
        if (assetKey) {
            NSString *realPath = [[NSBundle mainBundle] pathForResource:assetKey ofType:nil];
            result(realPath);
            return;
        }
        result([FlutterError
            errorWithCode:@"FileNotFoundException"
                  message:nil
                  details:nil]);
        return;
    }
    result([FlutterError
        errorWithCode:@"IllegalArgumentException"
              message:nil
              details:nil]);
}

@end
