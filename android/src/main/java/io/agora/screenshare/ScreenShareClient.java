package io.agora.screenshare;

import static android.app.Activity.RESULT_OK;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.Fragment;

import io.agora.rtc.RtcEngine;
import io.agora.videohelpers.Constants;
import io.agora.videohelpers.ExternalVideoInputManager;
import io.agora.videohelpers.ExternalVideoInputService;
import io.agora.videohelpers.IExternalVideoInputService;

public class ScreenShareClient extends Fragment {
  private static final String TAG = "screen_share_client";

  private static final int PROJECTION_REQ_CODE = 1 << 2;

  private FrameLayout fl_remote;
  private RelativeLayout fl_local;
  private int myUid, remoteUid = -1;
  private static final int DEFAULT_SHARE_FRAME_RATE = 15;
  private IExternalVideoInputService mService;
  private VideoInputServiceConnection mServiceConnection;

  public Context context;
  public Activity activity;

  @RequiresApi(api = Build.VERSION_CODES.M)
  @Override
  public void onAttach(@NonNull Context context) {
    super.onAttach(context);
    System.out.println("XXXXX reached onAttach()");
    this.context = context;
    bindVideoService();
  }

  @RequiresApi(api = Build.VERSION_CODES.M)
  public void bindVideoService() {
    System.out.println("XXXXX reached bind service");
    Intent intent = new Intent(requireActivity(), ExternalVideoInputService.class);
    mServiceConnection = new VideoInputServiceConnection();
    requireActivity().bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
    System.out.println("XXXXX finished bind service");
  }

  @RequiresApi(api = Build.VERSION_CODES.M)
  public void unbindVideoService() {
    if (mServiceConnection != null) {
      context.unbindService(mServiceConnection);
      mServiceConnection = null;
    }
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == PROJECTION_REQ_CODE && resultCode == RESULT_OK) {
      DisplayMetrics metrics = new DisplayMetrics();
      getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);

      float percent = 0.f;
      float hp = ((float) metrics.heightPixels) - 1920.f;
      float wp = ((float) metrics.widthPixels) - 1080.f;

      if (hp < wp) {
        percent = (((float) metrics.widthPixels) - 1080.f) / ((float) metrics.widthPixels);
      } else {
        percent = (((float) metrics.heightPixels) - 1920.f) / ((float) metrics.heightPixels);
      }
      metrics.heightPixels = (int) (((float) metrics.heightPixels) - (metrics.heightPixels * percent));
      metrics.widthPixels = (int) (((float) metrics.widthPixels) - (metrics.widthPixels * percent));

      data.putExtra(ExternalVideoInputManager.FLAG_SCREEN_WIDTH, metrics.widthPixels);
      data.putExtra(ExternalVideoInputManager.FLAG_SCREEN_HEIGHT, metrics.heightPixels);
      data.putExtra(ExternalVideoInputManager.FLAG_SCREEN_DPI, (int) metrics.density);
      data.putExtra(ExternalVideoInputManager.FLAG_FRAME_RATE, DEFAULT_SHARE_FRAME_RATE);
//      setVideoConfig(ExternalVideoInputManager.TYPE_SCREEN_SHARE, metrics.widthPixels, metrics.heightPixels);
      try {
        mService.setExternalVideoInput(ExternalVideoInputManager.TYPE_SCREEN_SHARE, data);
      } catch (RemoteException e) {
        e.printStackTrace();
      }
    }
  }

  private class VideoInputServiceConnection implements ServiceConnection {
    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
      mService = (IExternalVideoInputService) iBinder;
      // Starts capturing screen data. Ensure that your Android version must be Lollipop or higher.
      // Instantiates a MediaProjectionManager object
      MediaProjectionManager mpm = (MediaProjectionManager)
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
      // Creates an intent
      Intent intent = mpm.createScreenCaptureIntent();
      // Starts screen capturing
      startActivityForResult(intent, PROJECTION_REQ_CODE);
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
      mService = null;
    }
  }
}
