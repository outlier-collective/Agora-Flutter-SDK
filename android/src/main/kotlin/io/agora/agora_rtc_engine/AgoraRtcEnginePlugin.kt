package io.agora.agora_rtc_engine

import android.app.Activity
import android.content.*
import android.os.*
import androidx.annotation.NonNull
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentActivity
import io.agora.screenshare.ScreenShareActivity
import io.agora.videohelpers.Constants
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.agora.iris.base.IrisEventHandler
import io.agora.iris.rtc.IrisRtcEngine
import io.agora.iris.rtc.base.ApiTypeEngine
import io.agora.rtc.RtcEngine
import io.agora.rtc.base.RtcEngineRegistry
import io.flutter.BuildConfig
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.*
import io.flutter.plugin.common.*
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import io.flutter.plugin.platform.PlatformViewRegistry
import kotlin.math.abs

internal class EventHandler(private val eventSink: EventChannel.EventSink?) : IrisEventHandler {
  private val handler = Handler(Looper.getMainLooper())
  override fun OnEvent(event: String?, data: String?) {
    handler.post {
      eventSink?.success(mapOf("methodName" to event, "data" to data))
    }
  }

  override fun OnEvent(event: String?, data: String?, buffer: ByteArray?) {
    handler.post {
      eventSink?.success(mapOf("methodName" to event, "data" to data, "buffer" to buffer))
    }
  }
}

open class CallApiMethodCallHandler(
  protected val irisRtcEngine: IrisRtcEngine
) : MethodCallHandler {

  protected open fun callApi(apiType: Int, params: String?, sb: StringBuffer): Int {
    val type = ApiTypeEngine.fromInt(apiType)
    val ret = irisRtcEngine.callApi(type, params, sb)
    if (type == ApiTypeEngine.kEngineInitialize) {
      RtcEngineRegistry.instance.onRtcEngineCreated(irisRtcEngine.rtcEngine as RtcEngine?)
    }
    if (type == ApiTypeEngine.kEngineRelease) {
      RtcEngineRegistry.instance.onRtcEngineDestroyed()
    }

    return ret
  }

  protected open fun callApiWithBuffer(
    apiType: Int,
    params: String?,
    buffer: ByteArray?,
    sb: StringBuffer): Int {
    return irisRtcEngine.callApi(ApiTypeEngine.fromInt(apiType), params, buffer, sb)
  }

  protected open fun callApiError(ret: Int): String {
    val description = StringBuffer()
    irisRtcEngine.callApi(
      ApiTypeEngine.kEngineGetErrorDescription,
      "{\"code\":" + abs(ret) + "}",
      description
    )
    return description.toString()
  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    val apiType = call.argument<Int>("apiType")
    val params = call.argument<String>("params")
    val sb = StringBuffer()

    if (BuildConfig.DEBUG && "getIrisRtcEngineIntPtr" == call.method) {
      result.success(irisRtcEngine.nativeHandle)
      return
    }
    try {
      val ret = when (call.method) {
        "callApi" -> {
          callApi(apiType!!, params, sb)
        }
        "callApiWithBuffer" -> {
          val buffer = call.argument<ByteArray>("buffer")
          callApiWithBuffer(apiType!!, params, buffer, sb)
        }
        else -> {
          // This should not occur
          -1
        }
      }

      if (ret == 0) {
        if (sb.isEmpty()) {
          result.success(null)
        } else {
          result.success(sb.toString())
        }
      } else if (ret > 0) {
        result.success(ret)
      } else {
        val errorMsg = callApiError(ret)
        result.error(ret.toString(), errorMsg, null)
      }
    } catch (e: Exception) {
      result.error("", e.message ?: "", null)
    }
  }
}


/** AgoraRtcEnginePlugin */
open class AgoraRtcEnginePlugin :
    FragmentActivity(), ActivityAware, FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {
  private var registrar: Registrar? = null
  private var binding: FlutterPlugin.FlutterPluginBinding? = null
  private lateinit var pluginContext: Context
  private lateinit var pluginActivity: Activity

  private lateinit var irisRtcEngine: IrisRtcEngine

  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private lateinit var methodChannel: MethodChannel
  private lateinit var eventChannel: EventChannel

  private var eventSink: EventChannel.EventSink? = null
//  private val managerAgoraTextureView = RtcEngineManager { methodName, data -> emit(methodName, data) }
  private val handler = Handler(Looper.getMainLooper())
  private lateinit var rtcChannelPlugin: AgoraRtcChannelPlugin;// = AgoraRtcChannelPlugin(irisRtcEngine)
  private lateinit var callApiMethodCallHandler: CallApiMethodCallHandler

  // This static function is optional and equivalent to onAttachedToEngine. It supports the old
  // pre-Flutter-1.12 Android projects. You are encouraged to continue supporting
  // plugin registration via this function while apps migrate to use the new Android APIs
  // post-flutter-1.12 via https://flutter.dev/go/android-project-migration.
  //
  // It is encouraged to share logic between onAttachedToEngine and registerWith to keep
  // them functionally equivalent. Only one of onAttachedToEngine or registerWith will be called
  // depending on the user's project. onAttachedToEngine or registerWith must both be defined
  // in the same class.
  companion object {
    @JvmStatic
    fun registerWith(registrar: Registrar) {
      AgoraRtcEnginePlugin().apply {
        this.registrar = registrar

        initPlugin(registrar.context(), registrar.messenger(), registrar.platformViewRegistry())
      }
    }
  }

  private fun initPlugin(
    context: Context,
    binaryMessenger: BinaryMessenger,
    platformViewRegistry: PlatformViewRegistry
  ) {
    pluginContext = context.applicationContext
    irisRtcEngine = IrisRtcEngine(pluginContext)
    methodChannel = MethodChannel(binaryMessenger, "agora_rtc_engine")
    methodChannel.setMethodCallHandler(this)
    eventChannel = EventChannel(binaryMessenger, "agora_rtc_engine/events")
    eventChannel.setStreamHandler(this)

    callApiMethodCallHandler = CallApiMethodCallHandler(irisRtcEngine)

    platformViewRegistry.registerViewFactory(
      "AgoraSurfaceView",
      AgoraSurfaceViewFactory(binaryMessenger, irisRtcEngine)
    )
    platformViewRegistry.registerViewFactory(
      "AgoraTextureView",
      AgoraTextureViewFactory(binaryMessenger, irisRtcEngine)
    )

    rtcChannelPlugin = AgoraRtcChannelPlugin(irisRtcEngine)
    rtcChannelPlugin.initPlugin(binaryMessenger)
  }

  override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    this.binding = binding
//    rtcChannelPlugin.onAttachedToEngine(binding)
    initPlugin(binding.applicationContext, binding.binaryMessenger, binding.platformViewRegistry)
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    rtcChannelPlugin.onDetachedFromEngine(binding)
    methodChannel.setMethodCallHandler(null)
    eventChannel.setStreamHandler(null)

    irisRtcEngine.destroy()
  }

  @RequiresApi(Build.VERSION_CODES.M)
  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    pluginActivity = binding.getActivity()
  }

  override fun onDetachedFromActivityForConfigChanges() {
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
  }

  override fun onDetachedFromActivity() {
  }

  override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {

    eventSink = events
    irisRtcEngine.setEventHandler(EventHandler(eventSink))
    Log.e("MainActivity", "eventSink: $eventSink")
  }

  override fun onCancel(arguments: Any?) {
    irisRtcEngine.setEventHandler(null)
    eventSink = null
  }

//  private fun emit(methodName: String, data: Map<String, Any?>?) {
//    handler.post {
//      val event: MutableMap<String, Any?> = mutableMapOf("methodName" to methodName)
//      data?.let { event.putAll(it) }
//      eventSink?.success(event)
//    }
//  }

  @RequiresApi(Build.VERSION_CODES.M)
  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    print(call.method)
    when (call.method) {
      "startScreenShare" -> {
        Constants.rtcEngine = irisRtcEngine.rtcEngine as RtcEngine?
        bindVideoService()
        return
      }
      "createTextureRender" -> {
        result.notImplemented()
        return
      }
      "destroyTextureRender" -> {
        result.notImplemented()
        return
      }
      "getAssetAbsolutePath" -> {
        getAssetAbsolutePath(call, result)
        return
      }
      else -> {
        callApiMethodCallHandler.onMethodCall(call, result)
      }
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.M)
  private fun bindVideoService() {
    val screenShareIntent = Intent(pluginContext, ScreenShareActivity::class.java).also { intent = it }
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    pluginContext.startActivity(screenShareIntent)
    overridePendingTransition(R.anim.nav_default_pop_enter_anim, R.anim.nav_default_pop_exit_anim)
  }

  private fun getAssetAbsolutePath(call: MethodCall, result: Result) {
    call.arguments<String>()?.let {
      val assetKey = registrar?.lookupKeyForAsset(it)
        ?: binding?.flutterAssets?.getAssetFilePathByName(it)
      try {
        pluginContext.assets.openFd(assetKey!!).close()
        result.success("/assets/$assetKey")
      } catch (e: Exception) {
        result.error(e.javaClass.simpleName, e.message, e.cause)
      }
      return@getAssetAbsolutePath
    }
    result.error(IllegalArgumentException::class.simpleName, null, null)
  }
}

