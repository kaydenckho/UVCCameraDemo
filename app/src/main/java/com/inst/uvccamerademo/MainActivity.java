package com.inst.uvccamerademo;
/*
 * UVCCamera
 * library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2015 saki t_saki@serenegiant.com
 *
 * File name: MainActivity.java
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 * All files in the folder are under this Apache License, Version 2.0.
 * Files in the jni/libjpeg, jni/libusb, jin/libuvc, jni/rapidjson folder may have a different license, see the respective files.
*/

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewTreeObserver;
import android.widget.ImageButton;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
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

public final class MainActivity extends Activity implements CameraDialog.CameraDialogParent {
	private static final boolean DEBUG = true;	// TODO set false on release
	private static final String TAG = "MainActivity";



	USBMonitor mUSBMonitor;



	/**
	 * Handler to execute camera releated methods sequentially on private thread
	 */
	private CameraHandler mHandler;
	/**
	 * for camera preview display
	 */
	private UVCCameraTextureView mUVCCameraView;
	/**
	 * for open&start / stop&close camera preview
	 */
	private ToggleButton mToggleButton;
	/**
	 * button for start/stop recording
	 */
	private ImageButton mCaptureButton;
	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (DEBUG) Log.v(TAG, "onCreate:");

		setContentView(R.layout.activity_main);

		mToggleButton = (ToggleButton) findViewById(R.id.camera_button);
		mToggleButton.setOnClickListener(mOnClickListener);

		mCaptureButton = (ImageButton)findViewById(R.id.capture_button);
		mCaptureButton.setOnClickListener(mOnClickListener);
		mCaptureButton.setVisibility(View.INVISIBLE);

		mUVCCameraView = (UVCCameraTextureView) findViewById(R.id.camera_view);

		mUVCCameraView.setOnLongClickListener(mOnLongClickListener);

		mUSBMonitor = new USBMonitor(this, mOnDeviceConnectListener);
		mHandler = CameraHandler.createHandler(MainActivity.this, mUVCCameraView);
		if (!mHandler.isCameraOpened()) {
			CameraDialog.showDialog(MainActivity.this);
		} else {
			mHandler.closeCamera();
		}
	}
	private final OnDeviceConnectListener mOnDeviceConnectListener = new OnDeviceConnectListener() {
		@Override
		public void onAttach(final UsbDevice device) {
			Toast.makeText(MainActivity.this, "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show();
		}

		@Override
		public void onConnect(final UsbDevice device, final UsbControlBlock ctrlBlock, final boolean createNew) {
			if (DEBUG) Log.v(TAG, "onConnect:");
			mHandler.openCamera(ctrlBlock);
			startPreview();
		}

		@Override
		public void onDisconnect(final UsbDevice device, final UsbControlBlock ctrlBlock) {
			if (DEBUG) Log.v(TAG, "onDisconnect:");
			if (mHandler != null) {
				mHandler.closeCamera();
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						mCaptureButton.setVisibility(View.INVISIBLE);
						mToggleButton.setChecked(false);
					}
				});
			}
		}
		@Override
		public void onDettach(final UsbDevice device) {
			Toast.makeText(MainActivity.this, "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();
		}

		@Override
		public void onCancel() {
		}
	};

	@Override
	protected void onResume() {
		super.onResume();
		mUSBMonitor.register();
	}

	private void startPreview() {
		final SurfaceTexture st = mUVCCameraView.getSurfaceTexture();
		mHandler.startPreview(new Surface(st));
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				mCaptureButton.setVisibility(View.VISIBLE);
			}
		});
	}

	/**
	 * event handler when click camera / capture button
	 */
	private final OnClickListener mOnClickListener = new OnClickListener() {
		@Override
		public void onClick(final View view) {
			switch (view.getId()) {
			case R.id.camera_button:

				break;
			case R.id.capture_button:
				if (mHandler.isCameraOpened()) {
					if (!mHandler.isRecording()) {
                        Toast.makeText(MainActivity.this, "视频录制开始", Toast.LENGTH_SHORT).show();
                        mCaptureButton.setColorFilter(0xffff0000);	// turn red
						mHandler.startRecording();
					} else {
                        Toast.makeText(MainActivity.this, "视频录制结束", Toast.LENGTH_SHORT).show();
                        mCaptureButton.setColorFilter(0);	// return to default color
						mHandler.stopRecording();
					}
				}
				break;
			}
		}
	};

	/**
	 * capture still image when you long click on preview image(not on buttons)
	 */
	private final OnLongClickListener mOnLongClickListener = new OnLongClickListener() {
		@Override
		public boolean onLongClick(final View view) {
			switch (view.getId()) {
			case R.id.camera_view:
                Toast.makeText(MainActivity.this, "抓拍", Toast.LENGTH_SHORT).show();
                if (mHandler.isCameraOpened()) {
					mHandler.captureStill();
					return true;
				}
			}
			return false;
		}
	};


	@Override
	public USBMonitor getUSBMonitor() {
		return mUSBMonitor;
	}
}
