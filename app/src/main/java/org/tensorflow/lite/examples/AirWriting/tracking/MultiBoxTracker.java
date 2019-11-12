/* Copyright 2019 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/

package org.tensorflow.lite.examples.AirWriting.tracking;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Cap;
import android.graphics.Paint.Join;
import android.graphics.Paint.Style;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Environment;
import android.support.v4.content.ContextCompat;
import android.util.Pair;
import android.util.TypedValue;

import org.tensorflow.lite.examples.AirWriting.env.BorderedText;
import org.tensorflow.lite.examples.AirWriting.env.ImageUtils;
import org.tensorflow.lite.examples.AirWriting.env.Logger;
import org.tensorflow.lite.examples.AirWriting.tflite.Classifier.Recognition;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;


/**
 * A tracker that handles non-max suppression and matches existing objects to new detections.
 */
public class MultiBoxTracker {
    private static final float TEXT_SIZE_DIP = 18;
    private static final float MIN_SIZE = 16.0f;
    private static final int[] COLORS = {
            Color.parseColor("#F4924F")
    };
    final List<Pair<Float, RectF>> screenRects = new LinkedList<Pair<Float, RectF>>();
    private final Logger logger = new Logger();
    private final Queue<Integer> availableColors = new LinkedList<Integer>();
    private final List<TrackedRecognition> trackedObjects = new LinkedList<TrackedRecognition>();
    private final Paint boxPaint = new Paint();
    private final float textSizePx;
    private final BorderedText borderedText;
    private Matrix frameToCanvasMatrix;
    private int frameWidth;
    private int frameHeight;
    private int sensorOrientation;

    private int numDetect;
    private float lastX;
    private float lastY;
    private boolean first = true;
    private int count = 0;
    private int endCount = 0;
    private boolean flag = false;
    Path mPath = new Path();
    public static Bitmap bmap;

    final static String foldername = Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + "tensorflow/";

    public MultiBoxTracker(final Context context) {
        for (final int color : COLORS) {
            availableColors.add(color);
        }

        boxPaint.setColor(ContextCompat.getColor(context, android.R.color.holo_orange_dark));
        boxPaint.setStyle(Style.STROKE);
        boxPaint.setStrokeWidth(45.0f);
        boxPaint.setStrokeCap(Cap.ROUND);
        boxPaint.setStrokeJoin(Join.ROUND);
        boxPaint.setStrokeMiter(100);

        textSizePx =
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, context.getResources().getDisplayMetrics());
        borderedText = new BorderedText(textSizePx);
    }

    public synchronized void setFrameConfiguration(
            final int width, final int height, final int sensorOrientation) {
        frameWidth = width;
        frameHeight = height;
        this.sensorOrientation = sensorOrientation;
    }

    protected void writeWord(String destination, String text, boolean append) {
        WriteTextFile(foldername, destination, text, append);
    }

    protected void WriteTextFile(String folderName, String fileName, String contents, boolean append) {
        try {
            File dir = new File(folderName);
            //디렉토리 폴더가 없으면 생성함
            if (!dir.exists()) {
                dir.mkdir();
            }
            //파일 output stream 생성
            FileOutputStream fos = new FileOutputStream(folderName + "/" + fileName, append);
            //파일쓰기
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fos));
            writer.write(contents);
            writer.flush();

            writer.close();
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized void drawDebug(final Canvas canvas) {
        final Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(60.0f);

        final Paint boxPaint = new Paint();
        boxPaint.setColor(Color.RED);
        boxPaint.setAlpha(200);
        boxPaint.setStyle(Style.STROKE);

//        writeWord("xpoints.txt", "", false);
//        writeWord("ypoints.txt", "", false);
//        writeWord("zpoints.txt", "", false);

        for (final Pair<Float, RectF> detection : screenRects) {
            final RectF rect = detection.second;
            canvas.drawRect(rect, boxPaint);
            if (rect.height() > 0 && rect.height() <1000) {
                long distance = Math.round(Math.pow((Math.sqrt(Math.pow(rect.height() / 2, 2) + Math.pow(rect.width() / 2, 2))) / 30, 3));

                logger.i("rect is here : " + distance + " center x : " + Math.round(rect.centerX()));

                String xs = "";
                String ys = "";
                String zs = "";
                if (rect.width() > 0) {
                    xs = (Integer.toString(Math.round(rect.centerX()))) + " ";
                    ys = (Integer.toString(Math.round(rect.centerY()))) + " ";
                    zs = (Double.toString(Math.round(distance))) + " ";
                }


                writeWord("xpoints.txt", xs, true);
                writeWord("ypoints.txt", ys, true);
                writeWord("zpoints.txt", zs, true);
            }

//            canvas.drawText("" + detection.first, rect.left, rect.top, textPaint);
            borderedText.drawText(canvas, rect.centerX(), rect.centerY(), "" + detection.first);
        }
    }

    public synchronized int trackResults(final List<Recognition> results, final long timestamp) {
        logger.i("Processing %d results from %d", results.size(), timestamp);
        numDetect = results.size();
        if (flag && numDetect == 0) {
            endCount++;
        } else {
            endCount = 0;
        }
        if (numDetect == 1) {
            flag = true;
            count++;
        }
//        else if (numDetect >= 3 && flag) {
//            flag = false;
//            count = 0;
//            return 3;
//        }

        else if (count > 10 && flag && endCount > 7) {
            flag = false;
            count = 0;
            return 3;

        }

        processResults(results);

        return 1;
    }

    private Matrix getFrameToCanvasMatrix() {
        return frameToCanvasMatrix;
    }

    public synchronized void draw(final Canvas canvas) {
        if (!flag) {
            mPath.reset();
            first = true;
        }

        final boolean rotated = sensorOrientation % 180 == 90;
        final float multiplier =
                Math.min(
                        canvas.getHeight() / (float) (rotated ? frameWidth : frameHeight),
                        canvas.getWidth() / (float) (rotated ? frameHeight : frameWidth));
        frameToCanvasMatrix =
                ImageUtils.getTransformationMatrix(
                        frameWidth,
                        frameHeight,
                        (int) (multiplier * (rotated ? frameHeight : frameWidth)),
                        (int) (multiplier * (rotated ? frameWidth : frameHeight)),
                        sensorOrientation,
                        false);
        if (count > 3 && flag) {
            Paint P = new Paint();
            P.setColor(Color.BLACK);
            P.setStyle(Style.STROKE);
            P.setStrokeWidth(45.0f);        //두께 width
            P.setStrokeCap(Cap.ROUND);
            P.setStrokeJoin(Join.ROUND);
            P.setStrokeMiter(100);
            for (final TrackedRecognition recognition : trackedObjects) {
                final RectF trackedPos = new RectF(recognition.location);
                getFrameToCanvasMatrix().mapRect(trackedPos);
                boxPaint.setColor(recognition.color);

                if (first) {
                    mPath.moveTo(trackedPos.centerX(), trackedPos.centerY() + 50);
                    first = false;
                } else {
                    mPath.lineTo(trackedPos.centerX(), trackedPos.centerY() + 50);
                }
                canvas.drawPath(mPath, boxPaint);
                bmap = Bitmap.createBitmap(canvas.getWidth(), canvas.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas C = new Canvas(bmap);
                C.drawColor(Color.WHITE);
                C.drawPath(mPath, P);
            }
        }

    }

    private void processResults(final List<Recognition> results) {
        final List<Pair<Float, Recognition>> rectsToTrack = new LinkedList<Pair<Float, Recognition>>();

        screenRects.clear();
        final Matrix rgbFrameToScreen = new Matrix(getFrameToCanvasMatrix());

        for (final Recognition result : results) {
            if (result.getLocation() == null) {
                continue;
            }
            final RectF detectionFrameRect = new RectF(result.getLocation());

            final RectF detectionScreenRect = new RectF();
            rgbFrameToScreen.mapRect(detectionScreenRect, detectionFrameRect);

            logger.v(
                    "Result! Frame: " + result.getLocation() + " mapped to screen:" + detectionScreenRect);

            screenRects.add(new Pair<Float, RectF>(result.getConfidence(), detectionScreenRect));

            if (detectionFrameRect.width() < MIN_SIZE || detectionFrameRect.height() < MIN_SIZE) {
                logger.w("Degenerate rectangle! " + detectionFrameRect);
                continue;
            }

            rectsToTrack.add(new Pair<Float, Recognition>(result.getConfidence(), result));
        }

        if (rectsToTrack.isEmpty()) {
            logger.v("Nothing to track, aborting.");
            return;
        }

        trackedObjects.clear();
        for (final Pair<Float, Recognition> potential : rectsToTrack) {
            final TrackedRecognition trackedRecognition = new TrackedRecognition();
            trackedRecognition.detectionConfidence = potential.first;
            trackedRecognition.location = new RectF(potential.second.getLocation());
            trackedRecognition.title = potential.second.getTitle();
            trackedRecognition.color = COLORS[trackedObjects.size()];
            trackedObjects.add(trackedRecognition);

            if (trackedObjects.size() >= COLORS.length) {
                break;
            }
        }
    }

    private static class TrackedRecognition {
        RectF location;
        float detectionConfidence;
        int color;
        String title;
    }
}