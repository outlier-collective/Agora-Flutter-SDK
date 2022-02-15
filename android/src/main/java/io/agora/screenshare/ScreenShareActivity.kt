package io.agora.screenshare

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import io.agora.rtc.video.VideoEncoderConfiguration
import io.agora.videohelpers.Constants
import io.agora.videohelpers.ExternalVideoInputManager
import io.agora.videohelpers.ExternalVideoInputService
import io.agora.videohelpers.IExternalVideoInputService

class ScreenShareActivity : Activity() {
  private var mService: IExternalVideoInputService? = null
  private var mServiceConnection: VideoInputServiceConnection? = null
  private var dataIntent: Intent? = null
  private var screenShareContext: Context? = null

  override fun onStart() {
    super.onStart()
    println("onStart() called")
    moveTaskToBack(true)
    window.setLayout(0, 0)
    window.decorView.visibility = View.VISIBLE
  }

  override fun onStop() {
    println("onStop() called")
    window.decorView.visibility = View.GONE
    super.onStop()
  }

  override fun onCreate(bundle: Bundle?) {
    super.onCreate(bundle)
    screenShareContext = this
    window.setLayout(0, 0)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    val mpm = this.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val captureIntent = mpm.createScreenCaptureIntent()
    this.startActivityForResult(captureIntent, 1)
//    this.setFinishOnTouchOutside(false)
  }

  override fun onBackPressed() {
    // prevent activity from getting destroyed on back button
    println("activity onBackPressed")
//    moveTaskToBack(false)
    super.onBackPressed()
  }

  override fun onDetachedFromWindow() {
    println("activity onDetachedFromWindow")
//    super.onDetachedFromWindow()
  }

  override fun onDestroy() {
    super.onDestroy()
    println("StartScreenShareActivity destroyed")
    if (mServiceConnection != null) {
      screenShareContext?.unbindService(mServiceConnection!!)
      mServiceConnection = null
    }
    Constants.rtcEngine.enableVideo()
  }

  fun setVideoConfig(width: Int, height: Int) {
    /**Setup video stream encoding configs */
    Constants.rtcEngine.setVideoEncoderConfiguration(
      VideoEncoderConfiguration(
        VideoEncoderConfiguration.VideoDimensions(width, height),
        VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_30,
        VideoEncoderConfiguration.STANDARD_BITRATE, VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_PORTRAIT
      )
    )
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    println("onActivityResult reached")
    if (requestCode == 1 && resultCode == RESULT_OK) {

//      val alertDialog = AlertDialog.Builder(this).create()
//      alertDialog.setTitle("You are sharing your screen")
////      alertDialog.setMessage("Message")
//
//      alertDialog.setButton(
//        AlertDialog.BUTTON_POSITIVE, "Stop screen sharing"
//      ) { _, _ -> finish() }
//      alertDialog.show()

      Constants.rtcEngine.enableLocalVideo(true)
      Constants.rtcEngine.muteLocalVideoStream(false)

      dataIntent = data
      val metrics = DisplayMetrics()
      this.windowManager.getDefaultDisplay().getMetrics(metrics)
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
      dataIntent!!.putExtra(ExternalVideoInputManager.FLAG_FRAME_RATE, 30)
      setVideoConfig(metrics.widthPixels, metrics.heightPixels)

      val videoInputIntent = Intent(screenShareContext, ExternalVideoInputService::class.java)
      mServiceConnection = VideoInputServiceConnection()
      screenShareContext?.bindService(videoInputIntent, mServiceConnection!!, BIND_AUTO_CREATE)
    } else {
      println("finish() called")
      mServiceConnection?.let { this.unbindService(it) }
      finish()
    }
  }


  inner class VideoInputServiceConnection : ServiceConnection {
    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
      mService = iBinder as IExternalVideoInputService
      if (mService != null) {
        try {
          mService?.setExternalVideoInput(ExternalVideoInputManager.TYPE_SCREEN_SHARE, dataIntent!!)
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
