package io.agora.screenshare

//import io.agora.videohelpers.Constants
import android.app.Activity
import android.content.*
import android.os.*
import android.util.DisplayMetrics
import android.widget.Button
import io.agora.agora_rtc_engine.R
import io.agora.rtc.Constants
import io.agora.rtc.IRtcEngineEventHandler
import io.agora.rtc.RtcEngine
import io.agora.rtc.ScreenCaptureParameters
import io.agora.rtc.ScreenCaptureParameters.VideoCaptureParameters
import io.agora.rtc.ScreenCaptureParameters.AudioCaptureParameters
import io.agora.rtc.models.ChannelMediaOptions
import io.agora.rtc.video.VideoEncoderConfiguration
import io.agora.videohelpers.ExternalVideoInputManager
import io.agora.videohelpers.ExternalVideoInputService
import io.agora.videohelpers.IExternalVideoInputService


class ScreenShareActivity : Activity() {
  private val requestCode: Int = 1

  private var mService: IExternalVideoInputService? = null
  private var mServiceConnection: VideoInputServiceConnection? = null
  private var dataIntent: Intent? = null
  private var screenShareContext: Context? = null

  private var screenShareEngine: RtcEngine? = null

  private var handler: Handler? = null

  private fun stopScreenSharing() {
    println("asdf calling stopScreenSharing")
    screenShareEngine!!.leaveChannel()
//    handler!!.post { RtcEngine.destroy() }
    screenShareEngine = null
    println("asdf stopScreenSharing completed")
  }

  override fun onCreate(bundle: Bundle?) {
    super.onCreate(bundle)
    println("asdf: activity created");
    handler = Handler(Looper.getMainLooper())

    screenShareContext = this

    val prefs: SharedPreferences = this
      .getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
    val appId: String? = prefs.getString("flutter.appId", null)
    val channel: String? = prefs.getString("flutter.channel", null)
    val token: String? = prefs.getString("flutter.token", null)
    println("asdf: $appId")
    println("asdf: $channel")
    println("asdf: $token")

    screenShareEngine = RtcEngine
      .create(screenShareContext, appId, iRtcEngineEventHandler)
    screenShareEngine!!.setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
    screenShareEngine!!
      .setAudioProfile(Constants.AUDIO_PROFILE_MUSIC_HIGH_QUALITY_STEREO, Constants.AUDIO_SCENARIO_CHATROOM_ENTERTAINMENT)
    screenShareEngine!!.setClientRole(IRtcEngineEventHandler.ClientRole.CLIENT_ROLE_BROADCASTER)
    screenShareEngine!!.setVideoEncoderConfiguration(
      VideoEncoderConfiguration(
        VideoEncoderConfiguration.VideoDimensions(1920, 1080),
        VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_30,
        VideoEncoderConfiguration.STANDARD_BITRATE,
        VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_LANDSCAPE
      )
    )

    screenShareEngine!!.enableVideo()
    screenShareEngine!!.enableDeepLearningDenoise(true)

    val screenCaptureParameters = ScreenCaptureParameters()
    val videoCaptureParameters = VideoCaptureParameters()
    val audioCaptureParameters = AudioCaptureParameters()

    screenCaptureParameters.captureAudio = true
    screenCaptureParameters.captureVideo = true

    audioCaptureParameters.captureSignalVolume = 50
    audioCaptureParameters.allowCaptureCurrentApp = false

    videoCaptureParameters.height = 1080
    videoCaptureParameters.width = 1920
    videoCaptureParameters.framerate = 30

    screenCaptureParameters.audioCaptureParameters = audioCaptureParameters
    screenCaptureParameters.videoCaptureParameters = videoCaptureParameters

    screenShareEngine!!.muteLocalAudioStream(true)
    screenShareEngine!!.enableLocalAudio(false)

    val options = ChannelMediaOptions()
    options.autoSubscribeAudio = false
    options.autoSubscribeVideo = false

    screenShareEngine!!.muteAllRemoteAudioStreams(true)
    screenShareEngine!!.muteAllRemoteVideoStreams(true)

    val res = screenShareEngine!!.joinChannel(token, channel, "", 1, options)
//    val res = screenShareEngine!!.joinChannelWithUserAccount(token, channel, "0")
    println("asdf: join channel res: $res")

    var request = screenShareEngine!!.startScreenCapture(screenCaptureParameters)
    println("asdf: share screen request: $request")

    setContentView(R.layout.dialog)
    this.setFinishOnTouchOutside(false)

    val stopSharingButton = findViewById<Button>(R.id.stopScreenSharingButton)
    stopSharingButton.setOnClickListener { finish() }
  }

  override fun onBackPressed() {
    // Don't call super method here to prevent destroying activity
  }

  override fun onDestroy() {
    println("asdf calling onDestroy")
    stopScreenSharing()
    super.onDestroy()
  }

  /**
   * IRtcEngineEventHandler is an abstract class providing default implementation.
   * The SDK uses this class to report to the app on SDK runtime events.
   */
  private val iRtcEngineEventHandler: IRtcEngineEventHandler = object : IRtcEngineEventHandler() {
    /**Reports a warning during SDK runtime.
     * Warning code: https://docs.agora.io/en/Voice/API%20Reference/java/classio_1_1agora_1_1rtc_1_1_i_rtc_engine_event_handler_1_1_warn_code.html */
    override fun onWarning(warn: Int) {
    }

    /**Reports an error during SDK runtime.
     * Error code: https://docs.agora.io/en/Voice/API%20Reference/java/classio_1_1agora_1_1rtc_1_1_i_rtc_engine_event_handler_1_1_error_code.html */
    override fun onError(err: Int) {
    }

    /**Occurs when a user leaves the channel.
     * @param stats With this callback, the application retrieves the channel information,
     * such as the call duration and statistics.
     */
    override fun onLeaveChannel(stats: RtcStats) {
      super.onLeaveChannel(stats)
      println("asdf: leave channel success")
    }

    /**Occurs when the local user joins a specified channel.
     * The channel name assignment is based on channelName specified in the joinChannel method.
     * If the uid is not specified when joinChannel is called, the server automatically assigns a uid.
     * @param channel Channel name
     * @param uid User ID
     * @param elapsed Time elapsed (ms) from the user calling joinChannel until this callback is triggered
     */
    override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
      println("asdf: joined channel success")
    }

    override fun onFirstLocalVideoFramePublished(elapsed: Int) {
      println("asdf: published video frame")
    }

    /**Since v2.9.0.
     * This callback indicates the state change of the remote audio stream.
     * PS: This callback does not work properly when the number of users (in the Communication profile) or
     * broadcasters (in the Live-broadcast profile) in the channel exceeds 17.
     * @param uid ID of the user whose audio state changes.
     * @param state State of the remote audio
     * REMOTE_AUDIO_STATE_STOPPED(0): The remote audio is in the default state, probably due
     * to REMOTE_AUDIO_REASON_LOCAL_MUTED(3), REMOTE_AUDIO_REASON_REMOTE_MUTED(5),
     * or REMOTE_AUDIO_REASON_REMOTE_OFFLINE(7).
     * REMOTE_AUDIO_STATE_STARTING(1): The first remote audio packet is received.
     * REMOTE_AUDIO_STATE_DECODING(2): The remote audio stream is decoded and plays normally,
     * probably due to REMOTE_AUDIO_REASON_NETWORK_RECOVERY(2),
     * REMOTE_AUDIO_REASON_LOCAL_UNMUTED(4) or REMOTE_AUDIO_REASON_REMOTE_UNMUTED(6).
     * REMOTE_AUDIO_STATE_FROZEN(3): The remote audio is frozen, probably due to
     * REMOTE_AUDIO_REASON_NETWORK_CONGESTION(1).
     * REMOTE_AUDIO_STATE_FAILED(4): The remote audio fails to start, probably due to
     * REMOTE_AUDIO_REASON_INTERNAL(0).
     * @param reason The reason of the remote audio state change.
     * REMOTE_AUDIO_REASON_INTERNAL(0): Internal reasons.
     * REMOTE_AUDIO_REASON_NETWORK_CONGESTION(1): Network congestion.
     * REMOTE_AUDIO_REASON_NETWORK_RECOVERY(2): Network recovery.
     * REMOTE_AUDIO_REASON_LOCAL_MUTED(3): The local user stops receiving the remote audio
     * stream or disables the audio module.
     * REMOTE_AUDIO_REASON_LOCAL_UNMUTED(4): The local user resumes receiving the remote audio
     * stream or enables the audio module.
     * REMOTE_AUDIO_REASON_REMOTE_MUTED(5): The remote user stops sending the audio stream or
     * disables the audio module.
     * REMOTE_AUDIO_REASON_REMOTE_UNMUTED(6): The remote user resumes sending the audio stream
     * or enables the audio module.
     * REMOTE_AUDIO_REASON_REMOTE_OFFLINE(7): The remote user leaves the channel.
     * @param elapsed Time elapsed (ms) from the local user calling the joinChannel method
     * until the SDK triggers this callback.
     */
    override fun onRemoteAudioStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
      super.onRemoteAudioStateChanged(uid, state, reason, elapsed)
    }

    /**Since v2.9.0.
     * Occurs when the remote video state changes.
     * PS: This callback does not work properly when the number of users (in the Communication
     * profile) or broadcasters (in the Live-broadcast profile) in the channel exceeds 17.
     * @param uid ID of the remote user whose video state changes.
     * @param state State of the remote video:
     * REMOTE_VIDEO_STATE_STOPPED(0): The remote video is in the default state, probably due
     * to REMOTE_VIDEO_STATE_REASON_LOCAL_MUTED(3), REMOTE_VIDEO_STATE_REASON_REMOTE_MUTED(5),
     * or REMOTE_VIDEO_STATE_REASON_REMOTE_OFFLINE(7).
     * REMOTE_VIDEO_STATE_STARTING(1): The first remote video packet is received.
     * REMOTE_VIDEO_STATE_DECODING(2): The remote video stream is decoded and plays normally,
     * probably due to REMOTE_VIDEO_STATE_REASON_NETWORK_RECOVERY (2),
     * REMOTE_VIDEO_STATE_REASON_LOCAL_UNMUTED(4), REMOTE_VIDEO_STATE_REASON_REMOTE_UNMUTED(6),
     * or REMOTE_VIDEO_STATE_REASON_AUDIO_FALLBACK_RECOVERY(9).
     * REMOTE_VIDEO_STATE_FROZEN(3): The remote video is frozen, probably due to
     * REMOTE_VIDEO_STATE_REASON_NETWORK_CONGESTION(1) or REMOTE_VIDEO_STATE_REASON_AUDIO_FALLBACK(8).
     * REMOTE_VIDEO_STATE_FAILED(4): The remote video fails to start, probably due to
     * REMOTE_VIDEO_STATE_REASON_INTERNAL(0).
     * @param reason The reason of the remote video state change:
     * REMOTE_VIDEO_STATE_REASON_INTERNAL(0): Internal reasons.
     * REMOTE_VIDEO_STATE_REASON_NETWORK_CONGESTION(1): Network congestion.
     * REMOTE_VIDEO_STATE_REASON_NETWORK_RECOVERY(2): Network recovery.
     * REMOTE_VIDEO_STATE_REASON_LOCAL_MUTED(3): The local user stops receiving the remote
     * video stream or disables the video module.
     * REMOTE_VIDEO_STATE_REASON_LOCAL_UNMUTED(4): The local user resumes receiving the remote
     * video stream or enables the video module.
     * REMOTE_VIDEO_STATE_REASON_REMOTE_MUTED(5): The remote user stops sending the video
     * stream or disables the video module.
     * REMOTE_VIDEO_STATE_REASON_REMOTE_UNMUTED(6): The remote user resumes sending the video
     * stream or enables the video module.
     * REMOTE_VIDEO_STATE_REASON_REMOTE_OFFLINE(7): The remote user leaves the channel.
     * REMOTE_VIDEO_STATE_REASON_AUDIO_FALLBACK(8): The remote media stream falls back to the
     * audio-only stream due to poor network conditions.
     * REMOTE_VIDEO_STATE_REASON_AUDIO_FALLBACK_RECOVERY(9): The remote media stream switches
     * back to the video stream after the network conditions improve.
     * @param elapsed Time elapsed (ms) from the local user calling the joinChannel method until
     * the SDK triggers this callback.
     */
    override fun onRemoteVideoStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
      super.onRemoteVideoStateChanged(uid, state, reason, elapsed)
    }

    /**Occurs when a remote user (Communication)/host (Live Broadcast) joins the channel.
     * @param uid ID of the user whose audio state changes.
     * @param elapsed Time delay (ms) from the local user calling joinChannel/setClientRole
     * until this callback is triggered.
     */
    override fun onUserJoined(uid: Int, elapsed: Int) {
      super.onUserJoined(uid, elapsed)
    }

    /**Occurs when a remote user (Communication)/host (Live Broadcast) leaves the channel.
     * @param uid ID of the user whose audio state changes.
     * @param reason Reason why the user goes offline:
     * USER_OFFLINE_QUIT(0): The user left the current channel.
     * USER_OFFLINE_DROPPED(1): The SDK timed out and the user dropped offline because no data
     * packet was received within a certain period of time. If a user quits the
     * call and the message is not passed to the SDK (due to an unreliable channel),
     * the SDK assumes the user dropped offline.
     * USER_OFFLINE_BECOME_AUDIENCE(2): (Live broadcast only.) The client role switched from
     * the host to the audience.
     */
    override fun onUserOffline(uid: Int, reason: Int) {
    }
  }
}
