package com.inst.uvccamerademo;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.media.SoundPool;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.encoder.MediaAudioEncoder;
import com.serenegiant.usb.encoder.MediaEncoder;
import com.serenegiant.usb.encoder.MediaMuxerWrapper;
import com.serenegiant.usb.encoder.MediaSurfaceEncoder;
import com.serenegiant.usb.encoder.MediaVideoEncoder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

import widget.CameraViewInterface;
import widget.UVCCameraTextureView;

public class CameraHandler extends Handler {
    private static final int MSG_OPEN = 0;
    private static final int MSG_CLOSE = 1;
    private static final int MSG_PREVIEW_START = 2;
    private static final int MSG_PREVIEW_STOP = 3;
    private static final int MSG_CAPTURE_STILL = 4;
    private static final int MSG_CAPTURE_START = 5;
    private static final int MSG_CAPTURE_STOP = 6;
    private static final int MSG_MEDIA_UPDATE = 7;
    private static final int MSG_RELEASE = 9;

    /**
     * set true if you want to record movie using MediaSurfaceEncoder
     * (writing frame data into Surface camera from MediaCodec
     *  by almost same way as USBCameratest2)
     * set false if you want to record movie using MediaVideoEncoder
     */
    private static final boolean USE_SURFACE_ENCODER = false;

    private final WeakReference<CameraThread> mWeakThread;

    static int previewWidth = 640;
    static int previewHeight = 480;
    static int previewMode = 1;

    public static CameraHandler createHandler(final Activity parent, final UVCCameraTextureView cameraView) {
        ((CameraViewInterface) cameraView).setAspectRatio(previewWidth / (float)previewHeight);
        final CameraThread thread = new CameraThread(parent, cameraView);
        thread.start();
        return thread.getHandler();
    }

    CameraHandler(final CameraThread thread) {
        mWeakThread = new WeakReference<CameraThread>(thread);
    }

    public boolean isCameraOpened() {
        final CameraThread thread = mWeakThread.get();
        return thread != null && thread.isCameraOpened();
    }

    public boolean isRecording() {
        final CameraThread thread = mWeakThread.get();
        return thread != null && thread.isRecording();
    }

    public void openCamera(final USBMonitor.UsbControlBlock ctrlBlock) {
        sendMessage(obtainMessage(MSG_OPEN, ctrlBlock));
    }

    public void closeCamera() {
        stopPreview();
        sendEmptyMessage(MSG_CLOSE);
    }

    public void startPreview(final Surface sureface) {
        if (sureface != null)
            sendMessage(obtainMessage(MSG_PREVIEW_START, sureface));
    }

    public void stopPreview() {
        stopRecording();
        final CameraThread thread = mWeakThread.get();
        if (thread == null) return;
        synchronized (thread.mSync) {
            sendEmptyMessage(MSG_PREVIEW_STOP);
            // wait for actually preview stopped to avoid releasing Surface/SurfaceTexture
            // while preview is still running.
            // therefore this method will take a time to execute
            try {
                thread.mSync.wait();
            } catch (final InterruptedException e) {
            }
        }
    }

    public void captureStill() {
        sendEmptyMessage(MSG_CAPTURE_STILL);
    }

    public void startRecording() {
        sendEmptyMessage(MSG_CAPTURE_START);
    }

    public void stopRecording() {
        sendEmptyMessage(MSG_CAPTURE_STOP);
    }

/*		public void release() {
			sendEmptyMessage(MSG_RELEASE);
		} */

    @Override
    public void handleMessage(final Message msg) {
        final CameraThread thread = mWeakThread.get();
        if (thread == null) return;
        switch (msg.what) {
            case MSG_OPEN:
                thread.handleOpen((USBMonitor.UsbControlBlock)msg.obj);
                break;
            case MSG_CLOSE:
                thread.handleClose();
                break;
            case MSG_PREVIEW_START:
                thread.handleStartPreview((Surface)msg.obj, previewWidth, previewHeight, previewMode);
                break;
            case MSG_PREVIEW_STOP:
                thread.handleStopPreview();
                break;
            case MSG_CAPTURE_STILL:
                thread.handleCaptureStill();
                break;
            case MSG_CAPTURE_START:
                thread.handleStartRecording();
                break;
            case MSG_CAPTURE_STOP:
                thread.handleStopRecording();
                break;
            case MSG_MEDIA_UPDATE:
                thread.handleUpdateMedia((String)msg.obj);
                break;
            case MSG_RELEASE:
                thread.handleRelease();
                break;
            default:
                throw new RuntimeException("unsupported message:what=" + msg.what);
        }
    }


    private static final class CameraThread extends Thread {
        private static final String TAG_THREAD = "CameraThread";
        private final Object mSync = new Object();
        private final WeakReference<Activity> mWeakParent;
        private final WeakReference<CameraViewInterface> mWeakCameraView;
        private boolean mIsRecording;
        /**
         * shutter sound
         */
        private SoundPool mSoundPool;
        private int mSoundId;
        private CameraHandler mHandler;
        /**
         * for accessing UVC camera
         */
        private UVCCamera mUVCCamera;
        /**
         * muxer for audio/video recording
         */
        private MediaMuxerWrapper mMuxer;

        private CameraThread(Activity parent, final CameraViewInterface cameraView) {
            super("CameraThread");
            mWeakParent = new WeakReference<Activity>(parent);
            mWeakCameraView = new WeakReference<CameraViewInterface>(cameraView);
            loadSutterSound(parent);
        }

        @Override
        protected void finalize() throws Throwable {
            super.finalize();
        }

        public CameraHandler getHandler() {
            synchronized (mSync) {
                if (mHandler == null)
                    try {
                        mSync.wait();
                    } catch (final InterruptedException e) {
                    }
            }
            return mHandler;
        }

        public boolean isCameraOpened() {
            return mUVCCamera != null;
        }

        public boolean isRecording() {
            return (mUVCCamera != null) && (mMuxer != null);
        }

        public void handleOpen(final USBMonitor.UsbControlBlock ctrlBlock) {
            handleClose();
            mUVCCamera = new UVCCamera();
            mUVCCamera.open(ctrlBlock);
        }

        public void handleClose() {
            handleStopRecording();
            if (mUVCCamera != null) {
                mUVCCamera.stopPreview();
                mUVCCamera.destroy();
                mUVCCamera = null;
            }
        }

        public void handleStartPreview(final Surface surface, int previewWidth, int previewHeight, int previewMode) {
            if (mUVCCamera == null) return;
            try {
                mUVCCamera.setPreviewSize(previewWidth, previewHeight, previewMode);
            } catch (final IllegalArgumentException e) {
                try {
                    // fallback to YUV mode
                    mUVCCamera.setPreviewSize(previewWidth, previewHeight, UVCCamera.DEFAULT_PREVIEW_MODE);
                } catch (final IllegalArgumentException e1) {
                    handleClose();
                }
            }
            if (mUVCCamera != null) {
//					mUVCCamera.setFrameCallback(mIFrameCallback, UVCCamera.PIXEL_FORMAT_YUV);
                mUVCCamera.setPreviewDisplay(surface);
                mUVCCamera.startPreview();
            }
        }

        public void handleStopPreview() {
            if (mUVCCamera != null) {
                mUVCCamera.stopPreview();
            }
            synchronized (mSync) {
                mSync.notifyAll();
            }
        }

        public void handleCaptureStill() {
            final Activity parent = mWeakParent.get();
            if (parent == null) return;
            mSoundPool.play(mSoundId, 0.2f, 0.2f, 0, 0, 1.0f);	// play shutter sound
            final Bitmap bitmap = mWeakCameraView.get().captureStillImage();
            try {
                // get buffered output stream for saving a captured still image as a file on external storage.
                // the file name is came from current time.
                // You should use extension name as same as CompressFormat when calling Bitmap#compress.
                final File outputFile = MediaMuxerWrapper.getCaptureFile(Environment.DIRECTORY_DCIM, ".png");
                final BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(outputFile));
                try {
                    try {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
                        os.flush();
                        mHandler.sendMessage(mHandler.obtainMessage(MSG_MEDIA_UPDATE, outputFile.getPath()));
                    } catch (final IOException e) {
                    }
                } finally {
                    os.close();
                }
            } catch (final FileNotFoundException e) {
            } catch (final IOException e) {
            }
        }

        public void handleStartRecording() {
            try {
                if ((mUVCCamera == null) || (mMuxer != null)) return;
                mMuxer = new MediaMuxerWrapper(".mp4");	// if you record audio only, ".m4a" is also OK.
                if (USE_SURFACE_ENCODER) {
                    // for video capturing using MediaSurfaceEncoder
                    new MediaSurfaceEncoder(mMuxer, mMediaEncoderListener);
                } else {
                    // for video capturing using MediaVideoEncoder
                    new MediaVideoEncoder(mMuxer, mMediaEncoderListener);
                }
                if (true) {
                    // for audio capturing
                    new MediaAudioEncoder(mMuxer, mMediaEncoderListener);
                }
                mMuxer.prepare();
                mMuxer.startRecording();
            } catch (final IOException e) {
            }
        }

        public void handleStopRecording() {
            if (mMuxer != null) {
                mMuxer.stopRecording();
                mMuxer = null;
                // you should not wait here
            }
        }

        public void handleUpdateMedia(final String path) {
            final Activity parent = mWeakParent.get();
            if (parent != null && parent.getApplicationContext() != null) {
                try {

                    MediaScannerConnection.scanFile(parent.getApplicationContext(), new String[]{ path }, null, null);
                } catch (final Exception e) {

                }
                if (parent.isDestroyed())
                    handleRelease();
            } else {

                // give up to add this movice to MediaStore now.
                // Seeing this movie on Gallery app etc. will take a lot of time.
                handleRelease();
            }
        }

        public void handleRelease() {

            handleClose();
            if (!mIsRecording)
                Looper.myLooper().quit();
        }

/*			// if you need frame data as ByteBuffer on Java side, you can use this callback method with UVCCamera#setFrameCallback
			private final IFrameCallback mIFrameCallback = new IFrameCallback() {
				@Override
				public void onFrame(final ByteBuffer frame) {
				}
			}; */

        private final MediaEncoder.MediaEncoderListener mMediaEncoderListener = new MediaEncoder.MediaEncoderListener() {
            @Override
            public void onPrepared(final MediaEncoder encoder) {
                mIsRecording = true;
                if (encoder instanceof MediaVideoEncoder)
                    try {
                        mWeakCameraView.get().setVideoEncoder(encoder);
                    } catch (final Exception e) {
                    }
                if (encoder instanceof MediaSurfaceEncoder)
                    try {
                        mWeakCameraView.get().setVideoEncoder(encoder);
                        mUVCCamera.startCapture(((MediaSurfaceEncoder)encoder).getInputSurface());
                    } catch (final Exception e) {
                    }
            }

            @Override
            public void onStopped(final MediaEncoder encoder) {
                if ((encoder instanceof MediaVideoEncoder)
                        || (encoder instanceof MediaSurfaceEncoder))
                    try {
                        mIsRecording = false;
                        final Activity parent = mWeakParent.get();
                        mWeakCameraView.get().setVideoEncoder(null);
                        mUVCCamera.stopCapture();
                        final String path = encoder.getOutputPath();
                        if (!TextUtils.isEmpty(path)) {
                            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_MEDIA_UPDATE, path), 1000);
                        } else {
                            if (parent == null || parent.isDestroyed()) {
                                handleRelease();
                            }
                        }
                    } catch (final Exception e) {
                    }
            }
        };

        /**
         * prepare and load shutter sound for still image capturing
         */
        @SuppressWarnings("deprecation")
        private void loadSutterSound(final Context context) {
            // get system stream type using refrection
            int streamType;
            try {
                final Class<?> audioSystemClass = Class.forName("android.media.AudioSystem");
                final Field sseField = audioSystemClass.getDeclaredField("STREAM_SYSTEM_ENFORCED");
                streamType = sseField.getInt(null);
            } catch (final Exception e) {
                streamType = AudioManager.STREAM_SYSTEM;	// set appropriate according to your app policy
            }
            if (mSoundPool != null) {
                try {
                    mSoundPool.release();
                } catch (final Exception e) {
                }
                mSoundPool = null;
            }
            // load sutter sound from resource
            mSoundPool = new SoundPool(2, streamType, 0);
            mSoundId = mSoundPool.load(context, R.raw.camera_click, 1);
        }

        @Override
        public void run() {
            Looper.prepare();
            synchronized (mSync) {
                mHandler = new CameraHandler(this);
                mSync.notifyAll();
            }
            Looper.loop();
            synchronized (mSync) {
                mHandler = null;
                mSoundPool.release();
                mSoundPool = null;
                mSync.notifyAll();
            }
        }
    }
}