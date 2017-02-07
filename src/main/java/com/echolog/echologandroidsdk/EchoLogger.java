package com.echolog.echologandroidsdk;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

/**
 * Created by cozu on 31.01.2017.
 */

public class EchoLogger {

    private static final int        ECHOLOG_PERMISSION_REQUEST_READ_PHONE_STATE     = 2000;
    private static final int        ECHOLOG_PERMISSION_REQUEST_INTERNET             = 2001;
    private static final String     SERVER_URL                                      = "http://www.echolog.io/logs";
    private static final long       SEND_INTERVAL                                   = 15 * 1000;
    private static final long       CHECK_ENABLED_INTERVAL                          = 30 * 60 * 1000;

    private final Activity mainActivity;

    private String deviceId;
    private String sessionId;
    private String applicationId;

    private boolean isInternetPermissionGranted = false;
    private boolean askedForInternetPermission = false;
    private boolean askedForDeviceStatusPermission = false;

    private JSONArray jsonMessagesArray = new JSONArray();
    private boolean loggingEnabled = true;

    private Object mutex = new Object();
    private SendLogsThread sendLogsThread;

    public EchoLogger(String applicationId, Activity mainActivity) {
        this.applicationId = applicationId;
        this.mainActivity = mainActivity;
        this.sessionId = UUID.randomUUID().toString();

        readDeviceId();
        checkForInternetPermission();

        this.sendLogsThread = new SendLogsThread();
        this.sendLogsThread.start();
    }

    public void log(String message) {
        try {
            if (!loggingEnabled) {
                return;
            }

            JSONObject jsonMessage = new JSONObject();
            boolean messageOk = true;
            try {
                jsonMessage.put("timestamp", System.currentTimeMillis());
                jsonMessage.put("text", message);
            } catch (JSONException e) {
                messageOk = false;
                e.printStackTrace();
            }

            if (!messageOk) {
                try {
                    jsonMessage.put("timestamp", System.currentTimeMillis());
                    jsonMessage.put("text", "<< Missing log message >>");
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }

            synchronized (mutex) {
                jsonMessagesArray.put(jsonMessage);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void readDeviceId() {
        if (deviceId != null || askedForDeviceStatusPermission)
            return;

        if (ContextCompat.checkSelfPermission(mainActivity, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(mainActivity, new String[]{ Manifest.permission.READ_PHONE_STATE },
                    ECHOLOG_PERMISSION_REQUEST_READ_PHONE_STATE);
        } else {
            TelephonyManager tManager = (TelephonyManager) mainActivity.getBaseContext()
                    .getSystemService(Context.TELEPHONY_SERVICE);
            deviceId = strToUUID(tManager.getDeviceId().toString());
        }

        askedForDeviceStatusPermission = true;
    }

    private void checkForInternetPermission() {
        if (isInternetPermissionGranted || askedForInternetPermission)
            return;

        if (ContextCompat.checkSelfPermission(mainActivity, Manifest.permission.INTERNET)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(mainActivity, new String[]{Manifest.permission.INTERNET},
                    ECHOLOG_PERMISSION_REQUEST_INTERNET);
        } else {
            isInternetPermissionGranted = true;
        }

        askedForInternetPermission = true;
    }

    public void stop() {
        sendLogsThread.interrupt();
    }

    private String strToUUID(String str) {
        String uuid = "00000000000000000000000000000000";
        uuid = uuid.substring(0, 32 - str.length()) + str;
        uuid = uuid.substring(0, 8) + "-" + uuid.substring(8, 12) + "-" + uuid.substring(12, 16)
                + "-" + uuid.substring(16, 20) + "-" + uuid.substring(20, 32);
        System.out.println("Device id: " + uuid);
        return uuid;
    }

    private class SendLogsThread extends Thread {

        public SendLogsThread() {
            this.setPriority(Thread.MIN_PRIORITY);
        }

        @Override
        public void run() {
            while (!isInterrupted()) {
                try {
                    if (!loggingEnabled) {
                        delay();
                        continue;
                    }

                    checkForInternetPermission();
                    readDeviceId();

                    if (deviceId == null) {
                        delay();
                        continue;
                    }

                    if (!isInternetPermissionGranted) {
                        delay();
                        continue;
                    }

                    if (jsonMessagesArray.length() == 0) {
                        delay();
                        continue;
                    }

                    String targetURL = SERVER_URL;
                    JSONObject jsonParams = new JSONObject();
                    try {
                        jsonParams.put("id", applicationId);
                        jsonParams.put("device_id", deviceId);
                        jsonParams.put("session_id", sessionId);
                        jsonParams.put("messages", jsonMessagesArray);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                    String contentAsString;
                    synchronized (mutex) {
                        contentAsString = jsonParams.toString();
                        jsonMessagesArray = new JSONArray();
                    }

                    URL url;
                    HttpURLConnection connection = null;
                    try {
                        url = new URL(targetURL);
                        connection = (HttpURLConnection) url.openConnection();
                        connection.setRequestMethod("POST");
                        connection.setRequestProperty("Content-Type", "application/json");
                        connection.setRequestProperty("Content-Length", "" + Integer.toString(contentAsString.length()));
                        connection.setRequestProperty("Content-Language", "en-US");
                        connection.setUseCaches(false);
                        connection.setDoInput(true);
                        connection.setDoOutput(true);
                        connection.connect();

                        DataOutputStream wr = new DataOutputStream(connection.getOutputStream());
                        wr.writeBytes(contentAsString);
                        wr.flush();
                        wr.close();

                        InputStream is = connection.getInputStream();
                        BufferedReader rd = new BufferedReader(new InputStreamReader(is));
                        String line;
                        StringBuffer response = new StringBuffer();
                        while ((line = rd.readLine()) != null) {
                            response.append(line);
                            response.append('\r');
                        }
                        rd.close();

                        String responseString = response.toString();

                        loggingEnabled = !responseString.toLowerCase().equals("off");
                        delay();
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        if (connection != null) {
                            connection.disconnect();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        private void delay() {
            try {
                if (loggingEnabled) {
                    this.sleep(SEND_INTERVAL);
                } else {
                    this.sleep(CHECK_ENABLED_INTERVAL);
                }
            } catch (InterruptedException e) { }
        }
    }
}
