/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.lite.examples.AirWriting;

import android.Manifest;
import android.app.Fragment;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.StrictMode;
import android.os.Trace;
import android.provider.MediaStore;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Base64;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.tensorflow.lite.examples.AirWriting.env.ImageUtils;
import org.tensorflow.lite.examples.AirWriting.env.Logger;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import static org.tensorflow.lite.examples.AirWriting.tracking.MultiBoxTracker.bmap;

//import okhttp3.Response;

public abstract class CameraActivity extends AppCompatActivity
        implements OnImageAvailableListener,
        Camera.PreviewCallback,
        CompoundButton.OnCheckedChangeListener,
        View.OnClickListener {

    //  private EditText Img_title;
//    private Button BnChoose, BnUpload;
    private Switch debugSwitch;
    private ImageView img;
    private static final int IMG_REQUEST = 777;
    private Bitmap bitmap;

    private static final Logger LOGGER = new Logger();

    private static final int PERMISSIONS_REQUEST = 1;

    private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
    protected int previewWidth = 0;
    protected int previewHeight = 0;
    private boolean debug = false;
    private Handler handler;
    private HandlerThread handlerThread;
    private boolean useCamera2API;
    private boolean isProcessingFrame = false;
    private byte[][] yuvBytes = new byte[3][];
    private int[] rgbBytes = null;
    private int yRowStride;
    private Runnable postInferenceCallback;
    private Runnable imageConverter;


    protected TextView inferenceTimeTextView;

    SimpleDateFormat mFormat = new SimpleDateFormat("yyyy-MM-dd_hh_mm_ss");
    private long mNow;
    private Date mDate;


    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        LOGGER.d("onCreate " + this);
        super.onCreate(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_camera);
        if (android.os.Build.VERSION.SDK_INT > 9) {
            StrictMode.ThreadPolicy policy = new
                    StrictMode.ThreadPolicy.Builder().permitAll().build();
            StrictMode.setThreadPolicy(policy);
        }
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayShowTitleEnabled(false);

        //vin
//    img_title = (EditText)findViewById(R.id.img_title);
        img = (ImageView) findViewById(R.id.imageView);

        if (hasPermission()) {
            setFragment();
        } else {
            requestPermission();
        }
        debugSwitch = findViewById(R.id.debug);
        debugSwitch.setOnCheckedChangeListener(this);
        inferenceTimeTextView = findViewById(R.id.inference_info);

//        apiSwitchCompat.setOnCheckedChangeListener(this);

    }

    protected int[] getRgbBytes() {
        imageConverter.run();
        return rgbBytes;
    }

    protected int getLuminanceStride() {
        return yRowStride;
    }

    protected byte[] getLuminance() {
        return yuvBytes[0];
    }

    /**
     * Callback for android.hardware.Camera API
     */
    @Override
    public void onPreviewFrame(final byte[] bytes, final Camera camera) {
        if (isProcessingFrame) {
            LOGGER.w("Dropping frame!");
            return;
        }

        try {
            // Initialize the storage bitmaps once when the resolution is known.
            if (rgbBytes == null) {
                Camera.Size previewSize = camera.getParameters().getPreviewSize();
                previewHeight = previewSize.height;
                previewWidth = previewSize.width;
                rgbBytes = new int[previewWidth * previewHeight];
                onPreviewSizeChosen(new Size(previewSize.width, previewSize.height), 90);
            }
        } catch (final Exception e) {
            LOGGER.e(e, "Exception!");
            return;
        }
        isProcessingFrame = true;
        yuvBytes[0] = bytes;
        yRowStride = previewWidth;

//        camera.startPreview();
        imageConverter =
                new Runnable() {
                    @Override
                    public void run() {
                        ImageUtils.convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, rgbBytes);
                    }
                };

        postInferenceCallback =
                new Runnable() {
                    @Override
                    public void run() {
                        camera.addCallbackBuffer(bytes);
                        isProcessingFrame = false;
                    }
                };
        processImage();
    }

    /**
     * Callback for Camera2 API
     */
    @Override
    public void onImageAvailable(final ImageReader reader) {
        // We need wait until we have some size from onPreviewSizeChosen
        if (previewWidth == 0 || previewHeight == 0) {
            return;
        }
        if (rgbBytes == null) {
            rgbBytes = new int[previewWidth * previewHeight];
        }
        try {
            final Image image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            if (isProcessingFrame) {
                image.close();
                return;
            }
            isProcessingFrame = true;
            Trace.beginSection("imageAvailable");
            final Plane[] planes = image.getPlanes();
            fillBytes(planes, yuvBytes);
            yRowStride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();

            imageConverter =
                    new Runnable() {
                        @Override
                        public void run() {
                            ImageUtils.convertYUV420ToARGB8888(
                                    yuvBytes[0],
                                    yuvBytes[1],
                                    yuvBytes[2],
                                    previewWidth,
                                    previewHeight,
                                    yRowStride,
                                    uvRowStride,
                                    uvPixelStride,
                                    rgbBytes);
                        }
                    };

            postInferenceCallback =
                    new Runnable() {
                        @Override
                        public void run() {
                            image.close();
                            isProcessingFrame = false;
                        }
                    };
//            LOGGER.w("readers max images number : ", reader.getSurface());

            LOGGER.i("IS IT RIGHT??");
            processImage();
        } catch (final Exception e) {
            LOGGER.e(e, "Exception!");
            Trace.endSection();
            return;
        }
        Trace.endSection();
    }

    @Override
    public synchronized void onStart() {
        LOGGER.d("onStart " + this);
        super.onStart();
    }

    @Override
    public synchronized void onResume() {
        LOGGER.d("onResume " + this);
        super.onResume();

        handlerThread = new HandlerThread("inference");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    @Override
    public synchronized void onPause() {
        LOGGER.d("onPause " + this);

        handlerThread.quitSafely();
        try {
            handlerThread.join();
            handlerThread = null;
            handler = null;
        } catch (final InterruptedException e) {
            LOGGER.e(e, "Exception!");
        }

        super.onPause();
    }

    @Override
    public synchronized void onStop() {
        LOGGER.d("onStop " + this);
        super.onStop();
    }

    @Override
    public synchronized void onDestroy() {
        LOGGER.d("onDestroy " + this);
        super.onDestroy();
    }

    protected synchronized void runInBackground(final Runnable r) {
        if (handler != null) {
            handler.post(r);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode, final String[] permissions, final int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                setFragment();
            } else {
                requestPermission();
            }
        }
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA)) {
                Toast.makeText(
                        CameraActivity.this,
                        "Camera permission is required for this demo",
                        Toast.LENGTH_LONG)
                        .show();
            }
            requestPermissions(new String[]{PERMISSION_CAMERA}, PERMISSIONS_REQUEST);
        }
    }

    // Returns true if the device supports the required hardware level, or better.
    private boolean isHardwareLevelSupported(
            CameraCharacteristics characteristics, int requiredLevel) {
        int deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            return requiredLevel == deviceLevel;
        }
        // deviceLevel is not LEGACY, can use numerical sort
        return requiredLevel <= deviceLevel;
    }

    private String chooseCamera() {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (final String cameraId : manager.getCameraIdList()) {
                final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                final StreamConfigurationMap map =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                if (map == null) {
                    continue;
                }

                // Fallback to camera1 API for internal cameras that don't have full support.
                // This should help with legacy situations where using the camera2 API causes
                // distorted or otherwise broken previews.
                useCamera2API =
                        (facing == CameraCharacteristics.LENS_FACING_EXTERNAL)
                                || isHardwareLevelSupported(
                                characteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
                LOGGER.i("Camera API lv2?: %s", useCamera2API);
                return cameraId;
            }
        } catch (CameraAccessException e) {
            LOGGER.e(e, "Not allowed to access camera");
        }

        return null;
    }

    protected void setFragment() {
        String cameraId = chooseCamera();

        Fragment fragment;
        if (useCamera2API) {
            CameraConnectionFragment camera2Fragment =
                    CameraConnectionFragment.newInstance(
                            new CameraConnectionFragment.ConnectionCallback() {
                                @Override
                                public void onPreviewSizeChosen(final Size size, final int rotation) {
                                    previewHeight = size.getHeight();
                                    previewWidth = size.getWidth();
                                    CameraActivity.this.onPreviewSizeChosen(size, rotation);
                                }
                            },
                            this,
                            getLayoutId(),
                            getDesiredPreviewFrameSize());

            camera2Fragment.setCamera(cameraId);
            fragment = camera2Fragment;
        } else {
            fragment =
                    new LegacyCameraConnectionFragment(this, getLayoutId(), getDesiredPreviewFrameSize());
        }
        getFragmentManager().beginTransaction().replace(R.id.container, fragment).commit();
    }

    protected void fillBytes(final Plane[] planes, final byte[][] yuvBytes) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        String lengplanes = Integer.toString(planes.length);

        LOGGER.i("FILLBYTES FUNCTION RUN planes.length : ", lengplanes);
        for (int i = 0; i < planes.length; ++i) {
            final ByteBuffer buffer = planes[i].getBuffer();
            if (yuvBytes[i] == null) {
                LOGGER.d("Initializing buffer %d at size %d", i, buffer.capacity());
                yuvBytes[i] = new byte[buffer.capacity()];
            }
            buffer.get(yuvBytes[i]);
        }
    }

    public boolean isDebug() {
        return debug;
    }

    protected void readyForNextImage() {
        if (postInferenceCallback != null) {
            postInferenceCallback.run();
        }
    }

    protected int getScreenOrientation() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_90:
                return 90;
            default:
                return 0;
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (buttonView == debugSwitch) {
            debug = !debug;
            if (debug) {
                Toast.makeText(getApplicationContext(), "Debug Mode ON", Toast.LENGTH_SHORT).show();
                String text = showText();
                writeWord(text);
            } else {
                Toast.makeText(getApplicationContext(), "Debug Mode OFF", Toast.LENGTH_SHORT).show();
            }
        }
//        setUseNNAPI(isChecked);
//        if (isChecked) apiSwitchCompat.setText("NNAPI");
//        else apiSwitchCompat.setText("TFLITE");
    }

    @Override
    public void onClick(View v) {

    }

    private void selectImage() {
        Intent intent = new Intent();
        intent.setType("image/*");
        intent.setAction(Intent.ACTION_GET_CONTENT);
//    intent.setType(android.provider.MediaStore.Images.Media.CONTENT_TYPE);
//    intent.setData(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);

        startActivityForResult(intent, IMG_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == IMG_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri path = data.getData();

            try {
                bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), path);
                img.setImageBitmap(bitmap);
                img.setVisibility(View.VISIBLE);
//        img_title.setVisibility(View.VISIBLE);
//                BnChoose.setEnabled(false);
//                BnUpload.setEnabled(true);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
//  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
//    super.onActivityResult(requestCode, resultCode, data);
//    if(requestCode==IMG_REQUEST && resultCode == RESULT_OK && data != null) {
////        ImageView im = (ImageView) findViewById(R.id.image);
//      Uri uri = data.getData();
//      try {
//        bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), Uri.parse("file://"+uri));
//
////        getRealPathFromURI(uri);
//
//        img.setImageBitmap(bitmap);
//        img.setVisibility(View.VISIBLE);
//        BnChoose.setEnabled(false);
//        BnUpload.setEnabled(false);
//      } catch (FileNotFoundException e) {
//        e.printStackTrace();
//      } catch (IOException e) {
//        e.printStackTrace();
//      }
//    }
//  }

    private String getRealPathFromURI(Uri contentUri) {
        String[] proj = {MediaStore.Images.Media.DATA};

        CursorLoader cursorLoader = new CursorLoader(this, contentUri, proj, null, null, null);
        Cursor cursor = cursorLoader.loadInBackground();

        int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
        cursor.moveToFirst();
        return cursor.getString(column_index);
    }

    private String imageToString() {
        Matrix matrix = new Matrix();
        matrix.postScale((float) 0.2, (float) 0.2);
        Bitmap resizedBitmap = Bitmap.createBitmap(bmap, 0,0,bmap.getWidth(), bmap.getHeight(), matrix, true);

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        resizedBitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
        byte[] imgByte = byteArrayOutputStream.toByteArray();
        return Base64.encodeToString(imgByte, Base64.DEFAULT);
    }

    public void uploadImage() {
        String Image = imageToString();
//        mNow = System.currentTimeMillis();
//        mDate = new Date(mNow);
//        String Title = mFormat.format(mDate).toString();

        String Title = "title";

        ApiInterface apiInterface = ApiClient.getApiClient().create(ApiInterface.class);
        Call<ImageClass> call = apiInterface.uploadImage(Title, Image);
//        LOGGER.i("####################################################upload finish");

        call.enqueue(new Callback<ImageClass>() {
            @Override
            public void onResponse(Call<ImageClass> call, Response<ImageClass> response) {
//                ImageClass imageClass = response.body();
//                Toast.makeText(CameraActivity.this, "Server Response : " + imageClass.getResponse(), Toast.LENGTH_LONG).show();
//                img.setVisibility(View.GONE);
            }

            @Override
            public void onFailure(Call<ImageClass> call, Throwable t) {
            }
        });
    }

    public void downloadText() {

//        String rtext = "";

        ApiInterfaceText apiInterfaceText = ApiClient.getApiClient().create(ApiInterfaceText.class);
        Call<String> call = apiInterfaceText.downloadText("");


        call.enqueue(new Callback<String>() {
            public void onResponse(Call<String> call, Response<String> response) {

//                Log.i("http", "innen: " + response.message());
//                Log.i("http", "innen: " + response.body()); // here is your string!

            }

            public void onFailure(Call<String> call, Throwable t) {
//                Log.d("http", "Throwable " + t.toString());
            }

        });


        try {
            String str = call.clone().execute().body();
//            LOGGER.i("return string  " + str);

            long start = System.currentTimeMillis();

            Toast userToast = Toast.makeText(this, str, Toast.LENGTH_LONG);
            userToast.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 900);
            ViewGroup group = (ViewGroup) userToast.getView();
            TextView messageTextView = (TextView) group.getChildAt(0);
            messageTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 70);
            userToast.show();

            long end = System.currentTimeMillis();
            LOGGER.i("#########################토스트 소요시간 : " + (end-start) + "\n\n");

        } catch (IOException e) {
            LOGGER.i("hoxy is never  ");
            e.printStackTrace();
        }

    }

    public String showText() {
        String[] words;
        Random r = new Random();
        try {
            String texts = readFromAssets("seven.txt");
            words = texts.split("\n");
            String word = words[r.nextInt(1284)];
            for (int i = 0; i < 3; i++) {
                Toast toast = Toast.makeText(getApplicationContext(), word, Toast.LENGTH_LONG);
                ViewGroup group = (ViewGroup) toast.getView();
                TextView messageTextView = (TextView) group.getChildAt(0);
                messageTextView.setTextSize(25);
                toast.show();
            }
            return word;
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

//    public String showText() {
//        String[] words;
//        Random r = new Random();
//        try {
//            String texts = readFromAssets("rest1000.txt");
//            words = texts.split("\n");
//
//            LOGGER.i("this is what : " + words[0]);
//
//            File saveFile = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + "tensorflow/"); // 저장 경로
//            BufferedReader buf = new BufferedReader(new FileReader(saveFile + "/index.txt"));
//            String line = buf.readLine();
//            int index = Integer.parseInt(line);
//
//            String word = words[index];
//            for (int i = 0; i < 3; i++) {
//                Toast toast = Toast.makeText(getApplicationContext(), word, Toast.LENGTH_LONG);
//                ViewGroup group = (ViewGroup) toast.getView();
//                TextView messageTextView = (TextView) group.getChildAt(0);
//                messageTextView.setTextSize(25);
//                toast.show();
//            }
//            if ((index+1) > 23){
//                index = 0;
//            }
//            String strIndex = String.valueOf(index+1);
//
//            WriteTextFile(Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + "tensorflow/", "index.txt", strIndex, false);
//
//            return word;
//        } catch (Exception e) {
//            e.printStackTrace();
//            return "";
//        }
//    }

    private String readFromAssets(String filename) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(getAssets().open(filename)));

        StringBuilder sb = new StringBuilder();
        String line = reader.readLine();
        String txt;
        while (line != null) {
            txt = line + "\n";
            sb.append(txt);
            line = reader.readLine();
        }
        reader.close();
        return sb.toString();
    }

    protected void showInference(String inferenceTime) {
        inferenceTimeTextView.setText(inferenceTime);
    }
    protected abstract void writeWord(String text);

    protected abstract void WriteTextFile(String folderName, String fileName, String contents, boolean append);

    protected abstract void processImage();

    protected abstract void onPreviewSizeChosen(final Size size, final int rotation);

    protected abstract int getLayoutId();

    protected abstract Size getDesiredPreviewFrameSize();

    protected abstract void setNumThreads(int numThreads);

    protected abstract void setUseNNAPI(boolean isChecked);


}
