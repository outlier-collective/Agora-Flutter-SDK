package io.agora.agora_rtc_engine

import android.app.Activity
import android.content.*
import android.os.*
import androidx.annotation.NonNull
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentActivity
import io.agora.rtc.RtcEngine
import io.agora.rtc.base.RtcEngineManager
import io.agora.screenshare.ScreenShareActivity
import io.agora.videohelpers.Constants
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.*
import io.flutter.plugin.common.*
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import io.flutter.plugin.platform.PlatformViewRegistry


/** AgoraRtcEnginePlugin */
open class AgoraRtcEnginePlugin :
    FragmentActivity(), ActivityAware, FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {
  private var registrar: Registrar? = null
  private var binding: FlutterPlugin.FlutterPluginBinding? = null
  private lateinit var pluginContext: Context
  private lateinit var pluginActivity: Activity

  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private lateinit var methodChannel: MethodChannel
  private lateinit var eventChannel: EventChannel

  private var eventSink: EventChannel.EventSink? = null
  private val manager = RtcEngineManager(emit = { methodName, data -> emit(methodName, data) })
  private val handler = Handler(Looper.getMainLooper())
  private val rtcChannelPlugin = AgoraRtcChannelPlugin(this)

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
        rtcChannelPlugin.initPlugin(registrar.messenger())
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
    methodChannel = MethodChannel(binaryMessenger, "agora_rtc_engine")
    methodChannel.setMethodCallHandler(this)
    eventChannel = EventChannel(binaryMessenger, "agora_rtc_engine/events")
    eventChannel.setStreamHandler(this)

    platformViewRegistry.registerViewFactory(
      "AgoraSurfaceView",
      AgoraSurfaceViewFactory(binaryMessenger, this, rtcChannelPlugin)
    )
    platformViewRegistry.registerViewFactory(
      "AgoraTextureView",
      AgoraTextureViewFactory(binaryMessenger, this, rtcChannelPlugin)
    )
  }

  override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    this.binding = binding
    rtcChannelPlugin.onAttachedToEngine(binding)
    initPlugin(binding.applicationContext, binding.binaryMessenger, binding.platformViewRegistry)
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    rtcChannelPlugin.onDetachedFromEngine(binding)
    methodChannel.setMethodCallHandler(null)
    eventChannel.setStreamHandler(null)
    manager.release()
  }

  @RequiresApi(Build.VERSION_CODES.M)
  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    pluginActivity = binding.getActivity()
    println("plugin attached to activity")
  }

  override fun onDetachedFromActivityForConfigChanges() {
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
  }

  override fun onDetachedFromActivity() {
  }

  override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
    eventSink = events
  }

  override fun onCancel(arguments: Any?) {
    eventSink = null
  }

  private fun emit(methodName: String, data: Map<String, Any?>?) {
    handler.post {
      val event: MutableMap<String, Any?> = mutableMapOf("methodName" to methodName)
      data?.let { event.putAll(it) }
      eventSink?.success(event)
    }
  }

  fun engine(): RtcEngine? {
    return manager.engine
  }

  @RequiresApi(Build.VERSION_CODES.M)
  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    if (call.method == "getAssetAbsolutePath") {
      getAssetAbsolutePath(call, result)
      return
    }
    if (call.method == "startScreenShare") {
      println("starting screen share method call")
      Constants.rtcEngine = engine()
      bindVideoService()
      return
    }

    manager.javaClass.declaredMethods.find { it.name == call.method }?.let { function ->
      function.let { method ->
        try {
          val parameters = mutableListOf<Any?>()
          call.arguments<Map<*, *>>()?.toMutableMap()?.let {
            if (call.method == "create") {
              it["context"] = pluginContext
            }
            parameters.add(it)
          }
          method.invoke(manager, *parameters.toTypedArray(), ResultCallback(result))
          return@onMethodCall
        } catch (e: Exception) {
          e.printStackTrace()
        }
      }
    }
    result.notImplemented()
  }

  @RequiresApi(api = Build.VERSION_CODES.M)
  private fun bindVideoService() {
    val screenShareIntent = Intent(pluginContext, ScreenShareActivity::class.java).also { intent = it }
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    pluginContext.startActivity(screenShareIntent)
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
