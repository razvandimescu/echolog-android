package com.echolog.echologandroidsdk;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;

/**
 * Created by cozu on 06.03.2017.
 */

public class MyLogger {
    private EchoLogger echoLogger;

    private Activity mainActivity;

    public MyLogger(String applicationId, Context context, Activity mainActivity) {
        this.mainActivity = mainActivity;
        this.echoLogger = new EchoLoggerImpl(applicationId, context);
    }


    public void log(String message) {
        echoLogger.log(message);
    }

    private class EchoLoggerImpl extends EchoLogger {

        public EchoLoggerImpl(String applicationId, Context context) {
            super(applicationId, context);
        }

        @Override
        public void requestPermission(String permission) {
            if (ContextCompat.checkSelfPermission(mainActivity, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(mainActivity, new String[]{permission}, (int) (System.currentTimeMillis() % 1000));
            }
        }
    }

    public void stop() {
        echoLogger.stop();
    }
}