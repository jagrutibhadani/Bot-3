package com.recursivepenguin.botcubed.service;

import android.app.Service;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import com.googlecode.androidannotations.annotations.EService;
import com.googlecode.androidannotations.annotations.SystemService;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.HexDump;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.recursivepenguin.botcubed.Printer;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@EService
public class PrinterConnectionService extends Service {

    final String TAG = "PrinterConnectionService";

    public static final String ACTION_POSITION_CHANGED = "com.recursivepenguin.botcubed.service.ACTION_POSITION_CHANGED";

    @SystemService
    UsbManager usbManager;

    UsbSerialDriver mSerialDevice;

    private SerialInputOutputManager mSerialIoManager;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    boolean connected = false;

    private Printer printer = new Printer();

    LocalBroadcastManager mManager;

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private final IBinder mBinder = new LocalBinder();

    public class LocalBinder extends Binder {
        public PrinterConnectionService getService() {
            return PrinterConnectionService.this;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i("LocalService", "Received start id " + startId + ": " + intent);
        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mManager = LocalBroadcastManager.getInstance(this);
    }

    public boolean isConnected() {
        return connected;
    }

    public void connectToPrinter() throws PrinterError {
        // Find the first available driver.
        mSerialDevice = UsbSerialProber.acquire(usbManager);

        if (mSerialDevice != null) {
            try {
                mSerialDevice.open();

                connected = true;

                startIoManager();
            } catch (IOException e) {
                throw new PrinterError("Failed to open printer connection.");
            }
        } else {
            throw new PrinterError("No Printer Found");
        }
    }

    public void disconnectFromPrinter() {

        stopIoManager();

        try {
            mSerialDevice.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /*
    Send a print command, that does not belong to a print job.
     */
    public void injectManualCommand(String command) {
        Log.d(TAG, command);
        addToCodeQueue(command);
    }

    public Printer getPrinter() {
        return printer;
    }

    private final SerialInputOutputManager.Listener mListener =
            new SerialInputOutputManager.Listener() {

                @Override
                public void onRunError(Exception e) {
                    Log.d(TAG, "Runner stopped.");
                }

                @Override
                public void onNewData(final byte[] data) {
                    String response = HexDump.dumpHexString(data);
                    parseResponse(response);
                }
            };

    private void stopIoManager() {
        if (mSerialIoManager != null) {
            Log.i(TAG, "Stopping io manager ..");
            mSerialIoManager.stop();
            mSerialIoManager = null;
        }
    }

    private void startIoManager() {
        if (mSerialDevice != null) {
            Log.i(TAG, "Starting io manager ..");
            mSerialIoManager = new SerialInputOutputManager(mSerialDevice, mListener);
            mExecutor.submit(mSerialIoManager);
            addToCodeQueue("M114");
        }
    }

    public void addToCodeQueue(String code) {
        if (mSerialIoManager != null) {
            mSerialIoManager.writeAsync((code + "\n").getBytes());
        }
    }

    Pattern tempPattern = Pattern.compile("T:(\\d+(\\.\\d+)?) B:(\\d+(\\.\\d+)?)");
    Pattern positionPattern = Pattern.compile("C: X:(\\d+(\\.\\d+)?) Y:(\\d+(\\.\\d+)?) Z:(\\d+(\\.\\d+)?) E:(\\d+(\\.\\d+)?)");

    private void parseResponse(String response) {
        String type = response.substring(0, 2);
        if (type.equals("ok")) {
            //okay

            if (response.length() > 3) {
                response = response.substring(3);

                Matcher m = tempPattern.matcher(response);
                if (m.find()) {
                    printer.setExtruderTemp(Double.parseDouble(m.group(1)));
                    printer.setBedTemp(Double.parseDouble(m.group(3)));
                    return;
                }

                m = positionPattern.matcher(response);
                if (m.find()) {
                    printer.setxPos(Double.parseDouble(m.group(1)));
                    printer.setyPos(Double.parseDouble(m.group(3)));
                    printer.setzPos(Double.parseDouble(m.group(5)));
                    printer.setzPos(Double.parseDouble(m.group(7)));

                    Intent intent = new Intent();
                    intent.setAction(ACTION_POSITION_CHANGED);
                    mManager.sendBroadcast(intent);
                    return;
                }
            }

        } else if (type.equals("rs")) {
            //resend

        } else if (type.equals("!!")) {
            //Oh shit hardware dead!
        }
    }
}
