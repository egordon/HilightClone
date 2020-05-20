package com.ethankgordon.hilightclone;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.Range;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.google.common.util.concurrent.ListenableFuture;

import org.jtransforms.fft.DoubleFFT_1D;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    // Debugging
    private final String TAG = "HilightClone";

    // Camera Stuffs
    private PreviewView viewFinder;
    private ExecutorService cameraExecutor;

    // Visual Stuffs
    private ConcurrentLinkedQueue<Integer> bitQueue;
    private enum State {
        kNONE,
        kWORD,
        kBER
    };
    private TextView mBitText;
    private TextView mBitTitle;
    private State mState = State.kNONE;
    private Button mWordButton;
    private Button mBERButton;

    private Handler mHandler;
    private Runnable mTimeTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.viewFinder);
        cameraExecutor = Executors.newSingleThreadExecutor();
        bitQueue = new ConcurrentLinkedQueue<>();
        mBitText = findViewById(R.id.bit_queue);
        mBitTitle = findViewById(R.id.bit_queue_title);

        mWordButton = findViewById(R.id.read_words);
        mBERButton = findViewById(R.id.bit_err_rate);
        mWordButton.setOnClickListener(this);
        mBERButton.setOnClickListener(this);

        mHandler = new Handler();
        mTimeTask = new Runnable() {
            @Override
            public void run() {
                timedTask();
                mHandler.postDelayed(this, 1000);
            }
        };
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Get Camera Permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA},0);
        }

        startCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.postDelayed(mTimeTask, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTimeTask);
    }

    private static byte[] encodeToByteArray(Integer[] bits) {
        byte[] results = new byte[(bits.length + 7) / 8];
        int byteValue = 0;
        int index;
        for (index = 0; index < bits.length; index++) {

            byteValue = (byteValue << 1) | bits[index];

            if (index % 8 == 7) {
                results[index / 8] = (byte) byteValue;
            }
        }

        if (index % 8 != 0) {
            results[index / 8] = (byte) (byteValue << (8 - (index % 8)));
        }

        return results;
    }

    private Deque<Integer> visQueue = new ConcurrentLinkedDeque<>();
    private void timedTask() {
        boolean newBits = false;
        while(!bitQueue.isEmpty()) {
            newBits = true;
            visQueue.add(bitQueue.poll());
        }
        if (mState == State.kNONE) {
            while(visQueue.size() > 16) {
                visQueue.poll();
            }

            Integer[] dispArr = visQueue.toArray(new Integer[0]);
            StringBuilder sb = new StringBuilder();
            for(int i = 0; i < dispArr.length; i++) {
                if(i > 0 && i % 4 == 0) sb.append(" ");
                sb.append(dispArr[i]);
            }
            mBitText.setText(sb.toString());
        } else if(mState == State.kWORD) {
            if(newBits) return;
            if(visQueue.isEmpty()) return;
            if(visQueue.size() % 8 == 1) {
                visQueue.poll();
            }
            if(visQueue.size() % 8 != 0) {
                Log.d(TAG, "Bad Size");
                StringBuilder badSize = new StringBuilder();
                badSize.append("Err: Bad Size ");
                badSize.append(visQueue.size());
                mBitText.setText(badSize.toString());
                Log.d(TAG, mBitText.getText().toString());
                visQueue.clear();
                return;
            }
            Log.d(TAG, "Size: " + visQueue.size());
            mBitText.setText(new String(encodeToByteArray(visQueue.toArray(new Integer[0])), StandardCharsets.UTF_8));
            Log.d(TAG, mBitText.getText().toString());
            visQueue.clear();
        } else if(mState == State.kBER) {
            if(newBits) {
                StringBuilder sb = new StringBuilder();
                sb.append("Received ");
                sb.append(visQueue.size());
                sb.append("/200");
                mBitText.setText(sb.toString());
                return;
            }
            if(visQueue.isEmpty()) return;
            if(visQueue.size() < 180) return;
            double errCount = Math.abs(visQueue.size() - 200);
            int prevBit = 0;
            while(!visQueue.isEmpty()) {
                @SuppressWarnings("ConstantConditions") int bit = visQueue.poll();
                if(bit == prevBit) {
                    errCount++;
                }
                prevBit = bit;
            }
            errCount /= 2.0; // Each error causes 2 flips, each drop causes 1 flip.
            double ber = errCount / 200.0;
            StringBuilder sb = new StringBuilder();
            sb.append("BER: ");
            sb.append(ber * 100.0);
            sb.append("%");
            mBitText.setText(sb.toString());
        }
    }

    private void resetTask() {
        // Clear Bit Queue
        bitQueue.clear();

        // Clear Visual Vars
        visQueue.clear();
        mBitText.setText("");
    }

    private class HiLightAnalyzer implements ImageAnalysis.Analyzer {

        // Debugging
        private int frame = 0;
        private long startTime = 0L;

        // FFT
        final int FFT_SIZE = 12;
        private DoubleFFT_1D fft;
        double[] luminData = new double[FFT_SIZE];
        final int bin_15hz = Math.round(15.0f / 60.0f * FFT_SIZE);
        final int bin_20hz = Math.round(20.0f / 60.0f * FFT_SIZE);

        final double THRESH = 6.0;
        int[] bitPool = new int[FFT_SIZE];

        // Queue
        ConcurrentLinkedQueue<Integer> int_queue;
        ConcurrentLinkedQueue<Integer> ext_queue;

        HiLightAnalyzer(ConcurrentLinkedQueue<Integer> extQ) {
            for(int i = 0; i < FFT_SIZE; i++) {
                luminData[i] = 0.0;
                bitPool[i] = 2;
            }
            fft = new DoubleFFT_1D(FFT_SIZE);
            int_queue = new ConcurrentLinkedQueue<>();
            ext_queue = extQ;
        }

        @Override
        public void analyze(@NonNull ImageProxy image) {
            // Debugging, Check that FPS is close to 60
            if(frame % 60 == 0) {
                if(startTime == 0L) {
                    startTime = System.nanoTime();
                } else {
                    long endTime = System.nanoTime();
                    long duration = endTime - startTime;
                    startTime = endTime;
                    double secs = (double)duration / 1.0E9;
                    double fps = 60.0 / secs;
                    Log.d(TAG, "ImageAnalyzer FPS: " + fps);
                }
                Log.d(TAG, "Collected Bits: " + int_queue.size());
                StringBuilder bits = new StringBuilder();
                int_queue.clear();
                Log.d(TAG, bits.toString());
            }

            // Extract Average Luminosity (Y) from center 200x200px
            double average = 0.0;
            double count = 0.0;

            ByteBuffer yBuf = image.getPlanes()[0].getBuffer();
            int stride      = image.getPlanes()[0].getRowStride();

            int t=image.getHeight() / 2 - 200; int l=image.getWidth()/2 - 200;
            int out_h = 400; int out_w = 400;
            int firstRowOffset = stride * t + l;
            for (int row = 0; row < out_h; row++) {
                yBuf.position(firstRowOffset + row * stride);
                for(int col = 0; col < out_w; col++) {
                    average += yBuf.get();
                    count += 1.0;
                }
            }
            average /= count;

            // Push to Array
            System.arraycopy(luminData, 0, luminData, 1, FFT_SIZE - 1);
            luminData[0] = average;

            // if array is full, do FFT
            double[] sData = new double[FFT_SIZE];
            double[] power = new double[FFT_SIZE / 2];
            System.arraycopy(luminData, 0, sData, 0, FFT_SIZE);
            fft.realForward(sData);

            // Convert to power spectrum
            for (int i=0; i<power.length; i++) {
                double re  = sData[2*i];
                double im  = sData[2*i+1];
                power[i] = 10.0 * Math.log10(Math.sqrt(re * re + im * im));
            }

            // Add bit to pool
            int index = frame % 12;
            if(power[bin_15hz] >= THRESH || power[bin_20hz] >= THRESH) {
                if(power[bin_15hz] > power[bin_20hz]) {
                    bitPool[index] = 0;
                } else{
                    bitPool[index] = 1;
                }
            } else {
                bitPool[index] = 2;
            }

            // Add found bits to queue
            if(frame % 12 == 0) {
                int count0 = 0;
                int count1 = 0;
                for(int i = 0; i < FFT_SIZE; i++) {
                    if(bitPool[i] == 0) count0++;
                    else if(bitPool[i] == 1) count1++;
                }
                if(count0 >= 6 || count1 >= 6) {
                    if (count0 >= count1) {
                        int_queue.add(0);
                        ext_queue.add(0);
                    } else {
                        int_queue.add(1);
                        ext_queue.add(1);
                    }
                }
            }

            // Cleanup
            frame = (frame + 1) % 60;
            image.close();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            ProcessCameraProvider cameraProvider = null;
            try {
                cameraProvider = cameraProviderFuture.get();
            } catch (Exception e) {
                e.printStackTrace();
            }

            // Build Preview Object
            Preview preview = new Preview.Builder().build();

            // Build ImageAnalysis Object
            ImageAnalysis.Builder builder = new ImageAnalysis.Builder();
            Camera2Interop.Extender ext = new Camera2Interop.Extender<>(builder);
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(60, 60));
            builder.setBackpressureStrategy(ImageAnalysis.STRATEGY_BLOCK_PRODUCER);

            ImageAnalysis imageAnalysis = builder.build();
            imageAnalysis.setAnalyzer(cameraExecutor, new HiLightAnalyzer(bitQueue));


            // Select back camera
            CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build();

            try {
                // Unbind use cases before rebinding
                if (cameraProvider != null) {
                    cameraProvider.unbindAll();

                    // Bind use cases to camera
                    Camera camera = cameraProvider.bindToLifecycle(MainActivity.this, cameraSelector, preview, imageAnalysis);
                    preview.setSurfaceProvider(viewFinder.createSurfaceProvider(camera.getCameraInfo()));
                }
            } catch(Exception exc) {
                Log.e(TAG, "Use case binding failed", exc);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void setState(State s) {
        if(mState == s) {
            s = State.kNONE;
        }
        switch (s) {
            case kNONE:
                mWordButton.setText("Receive Words");
                mBERButton.setText("Measure BER");
                mWordButton.setEnabled(true);
                mBERButton.setEnabled(true);
                mBitTitle.setText("Last 16 Bits:");
                break;
            case kWORD:
                mWordButton.setText("Cancel");
                mBERButton.setText("Measure BER");
                mWordButton.setEnabled(true);
                mBERButton.setEnabled(false);
                mBitTitle.setText("Receiving Word:");
                break;
            case kBER:
                mWordButton.setText("Receive Words");
                mBERButton.setText("Cancel");
                mWordButton.setEnabled(false);
                mBERButton.setEnabled(true);
                mBitTitle.setText("Measuring BER:");
                break;
        }
        mState = s;
        resetTask();
    }

    @Override
    public void onClick(View v) {
        if(v.getId() == mWordButton.getId()) {
            // Toggle Word Receiving
            setState(State.kWORD);
        } else if(v.getId() == mBERButton.getId()) {
            // Start BER Measurement
            setState(State.kBER);
        }
    }
}
