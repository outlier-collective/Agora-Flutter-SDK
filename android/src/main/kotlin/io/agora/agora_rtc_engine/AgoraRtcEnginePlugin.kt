package io.agora.agora_rtc_engine

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.NonNull
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import io.agora.rtc.RtcEngine
import io.agora.rtc.base.RtcEngineManager
import io.agora.rtc.video.VideoEncoderConfiguration
import io.agora.rtc.video.VideoEncoderConfiguration.ORIENTATION_MODE
import io.agora.rtc.video.VideoEncoderConfiguration.VideoDimensions
import io.agora.videohelpers.Constants
import io.agora.videohelpers.ExternalVideoInputManager
import io.agora.videohelpers.ExternalVideoInputService
import io.agora.videohelpers.IExternalVideoInputService
import io.flutter.embedding.android.FlutterFragment
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.*
import io.flutter.plugin.common.*
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.PluginRegistry.Registrar
import io.flutter.plugin.platform.PlatformViewRegistry
import io.flutter.plugin.common.MethodChannel.Result


/** AgoraRtcEnginePlugin */
open class AgoraRtcEnginePlugin :
    FragmentActivity(), ActivityAware, FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {
  private var registrar: Registrar? = null
  private var binding: FlutterPlugin.FlutterPluginBinding? = null
  private lateinit var myContext: Context
  private lateinit var myActivity: Activity

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

  private var fragmentManager: FragmentManager? = null
  private var flutterFragment: FlutterFragment? = null
  private val id = 0x123456

  private val PROJECTION_REQ_CODE = 1
  private val DEFAULT_SHARE_FRAME_RATE = 15
  private var mServiceConnection: VideoInputServiceConnection? = null

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
    // Define a tag String to represent the FlutterFragment within this
    // Activity's FragmentManager. This value can be whatever you'd like.
    const val TAG_FLUTTER_FRAGMENT = "flutter_fragment"

    var mService: IExternalVideoInputService? = null
    var dataIntent: Intent? = null

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
    myContext = context.applicationContext
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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Inflate a layout that has a container for your FlutterFragment. For
    // this example, assume that a FrameLayout exists with an ID of
    // R.id.fragment_container.
//    setContentView(R.layout.activity_main)
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
    // Your plugin is now associated with an Android Activity.
    //
    // If this method is invoked, it is always invoked after
    // onAttachedToFlutterEngine().
    //
    // You can obtain an Activity reference with
    // binding.getActivity()
    //
    // You can listen for Lifecycle changes with
    // binding.getLifecycle()
    //
    // You can listen for Activity results, new Intents, user
    // leave hints, and state saving callbacks by using the
    // appropriate methods on the binding.
    myActivity = binding.getActivity()
    println("plugin attached to activity")

    val vParams: ViewGroup.LayoutParams = FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )
    val container = FrameLayout(myContext)
    container.layoutParams = vParams
    container.id = id
    myActivity.setContentView(container, vParams)

    fragmentManager = supportFragmentManager
    println("fragment manager: ${fragmentManager.toString()}")
  }

  override fun onDetachedFromActivityForConfigChanges() {
    // The Activity your plugin was associated with has been
    // destroyed due to config changes. It will be right back
    // but your plugin must clean up any references to that
    // Activity and associated resources.
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    // Your plugin is now associated with a new Activity instance
    // after config changes took place. You may now re-establish
    // a reference to the Activity and associated resources.
  }

  override fun onDetachedFromActivity() {
    // Your plugin is no longer associated with an Activity.
    // You must clean up all resources and references. Your
    // plugin may, or may not ever be associated with an Activity
    // again.
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
//      engine()?.stopPreview()
//      engine()?.muteLocalVideoStream(true)

      bindVideoService()

      return
    }

    manager.javaClass.declaredMethods.find { it.name == call.method }?.let { function ->
      function.let { method ->
        try {
          val parameters = mutableListOf<Any?>()
          call.arguments<Map<*, *>>()?.toMutableMap()?.let {
            if (call.method == "create") {
              it["context"] = myContext
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
    println("reached bind service")
//    val videoInputIntent = Intent(myContext, ExternalVideoInputService::class.java)
//    mServiceConnection = VideoInputServiceConnection()
//    val didBind = myContext.bindService(videoInputIntent, mServiceConnection!!, BIND_AUTO_CREATE)
//    println("finished bind service as: $didBind")

    val screenShareIntent = Intent(this, StartScreenShareActivity::class.java).also { intent = it }
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    this.startActivity(screenShareIntent)
    println("finished start screen share activity")
  }

  fun startVideoService() {
    val videoInputIntent = Intent(applicationContext, ExternalVideoInputService::class.java)
    mServiceConnection = VideoInputServiceConnection()
    val didBind = applicationContext.bindService(videoInputIntent, mServiceConnection!!, BIND_AUTO_CREATE)
    println("start video service is $didBind")
  }


  fun setVideoConfig(width: Int, height: Int) {
    /**Setup video stream encoding configs */
    engine()?.setVideoEncoderConfiguration(
      VideoEncoderConfiguration(
        VideoDimensions(width, height),
        VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
        VideoEncoderConfiguration.STANDARD_BITRATE, ORIENTATION_MODE.ORIENTATION_MODE_FIXED_PORTRAIT
      )
    )
  }

  private fun getAssetAbsolutePath(call: MethodCall, result: Result) {
    call.arguments<String>()?.let {
      val assetKey = registrar?.lookupKeyForAsset(it)
        ?: binding?.flutterAssets?.getAssetFilePathByName(it)
      try {
        myContext.assets.openFd(assetKey!!).close()
        result.success("/assets/$assetKey")
      } catch (e: Exception) {
        result.error(e.javaClass.simpleName, e.message, e.cause)
      }
      return@getAssetAbsolutePath
    }
    result.error(IllegalArgumentException::class.simpleName, null, null)
  }

  inner class VideoInputServiceConnection : ServiceConnection {
    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
      mService = iBinder as IExternalVideoInputService
      println("mService has been set as $mService")

      if (mService != null) {
        println("onActivityResult result should execute")
        val metrics = DisplayMetrics()
        myActivity.windowManager.getDefaultDisplay().getMetrics(metrics)
        var percent = 0f
        val hp = metrics.heightPixels.toFloat() - 1920f
        val wp = metrics.widthPixels.toFloat() - 1080f
        percent = if (hp < wp) {
          (metrics.widthPixels.toFloat() - 1080f) / metrics.widthPixels.toFloat()
        } else {
          (metrics.heightPixels.toFloat() - 1920f) / metrics.heightPixels.toFloat()
        }
        metrics.heightPixels = (metrics.heightPixels.toFloat() - metrics.heightPixels * percent).toInt()
        metrics.widthPixels = (metrics.widthPixels.toFloat() - metrics.widthPixels * percent).toInt()
        dataIntent!!.putExtra(ExternalVideoInputManager.FLAG_SCREEN_WIDTH, metrics.widthPixels)
        dataIntent!!.putExtra(ExternalVideoInputManager.FLAG_SCREEN_HEIGHT, metrics.heightPixels)
        dataIntent!!.putExtra(ExternalVideoInputManager.FLAG_SCREEN_DPI, metrics.density.toInt())
        dataIntent!!.putExtra(ExternalVideoInputManager.FLAG_FRAME_RATE, 15)
        AgoraRtcEnginePlugin().setVideoConfig(metrics.widthPixels, metrics.heightPixels)
        try {
          println("trying mService setExternalVideoInput")
          println("mService: ${mService}")
//          val binder = ExternalVideoInputService().getmService()
//          println("binder: $binder")
          mService?.setExternalVideoInput(ExternalVideoInputManager.TYPE_SCREEN_SHARE, dataIntent!!)
          println("finished mService setExternalVideoInput")
        } catch (e: RemoteException) {
          e.printStackTrace()
        }
      }
    }

    override fun onServiceDisconnected(componentName: ComponentName) {
      println("video input service disconnected")
      mService = null
    }

    override fun onBindingDied(name: ComponentName?) {
      println("binding died")
      super.onBindingDied(name)
    }

    override fun onNullBinding(name: ComponentName?) {
      println("on null binding")
      super.onNullBinding(name)
    }
  }
}

class StartScreenShareActivity : Activity() {
  override fun onCreate(bundle: Bundle?) {
    super.onCreate(bundle)
    println("StartScreenShareActivity created")
    val mpm = this.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val captureIntent = mpm.createScreenCaptureIntent()
    this.startActivityForResult(captureIntent, 1)
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    println("onActivityResult reached")
    println(requestCode)
    println(resultCode)
    if (requestCode == 1 && resultCode == RESULT_OK) {
      AgoraRtcEnginePlugin.dataIntent = data
      AgoraRtcEnginePlugin().startVideoService()
    }
    finish()
    println("share screen activity finished")
  }
}
