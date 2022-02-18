package io.agora.screenshare

import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.DisplayMetrics
import android.widget.Button
import io.agora.agora_rtc_engine.R
import io.agora.rtc.mediaio.AgoraDefaultSource
import io.agora.rtc.video.VideoEncoderConfiguration
import io.agora.videohelpers.Constants
import io.agora.videohelpers.ExternalVideoInputManager
import io.agora.videohelpers.ExternalVideoInputService
import io.agora.videohelpers.IExternalVideoInputService

class ScreenShareActivity : Activity() {
  private val requestCode: Int = 1

  private var mService: IExternalVideoInputService? = null
  private var mServiceConnection: VideoInputServiceConnection? = null
  private var dataIntent: Intent? = null
  private var screenShareContext: Context? = null

  private fun initScreenSharing() {
    Constants.rtcEngine.enableLocalVideo(true)
    Constants.rtcEngine.muteLocalVideoStream(false)

    setContentView(R.layout.dialog)
    this.setFinishOnTouchOutside(false)

    val stopSharingButton = findViewById<Button>(R.id.stopScreenSharingButton)
    stopSharingButton.setOnClickListener { finish() }
  }

  private fun stopScreenSharing() {
    if (mServiceConnection != null) {
      screenShareContext?.unbindService(mServiceConnection!!)
      mServiceConnection = null

      Constants.rtcEngine.enableLocalVideo(false)
      Constants.rtcEngine.muteLocalVideoStream(true)
      Constants.rtcEngine.setVideoSource(AgoraDefaultSource())
    }
  }

  override fun onCreate(bundle: Bundle?) {
    super.onCreate(bundle)
    screenShareContext = this
    val mpm = this.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val captureIntent = mpm.createScreenCaptureIntent()
    this.startActivityForResult(captureIntent, requestCode)
  }

  override fun onBackPressed() {
    // Don't call super method here to prevent destroying activity
  }

  override fun onDestroy() {
    stopScreenSharing()
    super.onDestroy()
  }

  private fun setVideoConfig(width: Int, height: Int) {
    // Setup video stream encoding configs
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
    if (requestCode == requestCode && resultCode == RESULT_OK) {
      initScreenSharing()

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
      mService = null
    }
  }
}
