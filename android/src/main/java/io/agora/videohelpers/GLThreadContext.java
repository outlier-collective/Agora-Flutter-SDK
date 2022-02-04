package io.agora.videohelpers;

import android.opengl.EGLContext;
import io.agora.gles.ProgramTextureOES;
import io.agora.gles.core.EglCore;

public class GLThreadContext {
  public EglCore eglCore;
  public EGLContext context;
  public ProgramTextureOES program;
}
