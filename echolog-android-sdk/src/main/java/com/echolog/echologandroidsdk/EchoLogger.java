package com.echolog.echologandroidsdk;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
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
import java.util.Map;
import java.util.UUID;

/**
 * Created by cozu on 31.01.2017.
 */

public class EchoLogger {

    private static final String     SERVER_URL                                      = "https://www.echolog.io/logs";
    private static final long       SEND_INTERVAL                                   = 15 * 1000;
    private static final long       CHECK_ENABLED_INTERVAL                          = 30 * 60 * 1000;
    private static final String     INVALID_DEVICE_ID                               = "9774d56d682e549c";

    private Context context;

    private String deviceId;
    private String sessionId;
    private String applicationId;
    private String os = "Android";
    private String osVersion = "";
    private String deviceType = "";
    private String appVersion = "";
    private int buildVersion = -1;
    private String deviceName;

    private boolean isInternetPermissionGranted = false;
    private boolean isInitialized;

    private JSONArray jsonMessagesArray = new JSONArray();
    private JSONObject jsonDeviceInfo = new JSONObject();
    private boolean loggingEnabled = true;

    private Object mutex = new Object();
    private SendLogsThread sendLogsThread;


    private static EchoLogger echoLogger;

    private EchoLogger() {
        this.isInitialized = false;
    }

    public static void initialize(String applicationId, Context context) {
        EchoLogger logger = getInstance();

        if (logger.isInitialized)
            return;

        logger.applicationId = applicationId;
        logger.context = context;
        logger.sessionId = UUID.randomUUID().toString();

        logger.readDeviceInfo();

        logger.checkForInternetPermission();

        logger.sendLogsThread = new SendLogsThread();
        logger.sendLogsThread.start();

        logger.isInitialized = true;
    }

    private static EchoLogger getInstance() {
        if (echoLogger == null) {
            echoLogger = new EchoLogger();
        }

        return echoLogger;
    }

    public static void info(String message) {
        log(EchoLogLevel.INFO, message, null);
    }
    public static void info(String message, Map<String, String> customFields) {
        log(EchoLogLevel.INFO, message, customFields);
    }

    public static void warning(String message) {
        log(EchoLogLevel.WARNING, message, null);
    }
    public static void warning(String message, Map<String, String> customFields) {
        log(EchoLogLevel.WARNING, message, customFields);
    }

    public static void error(String message) {
        log(EchoLogLevel.ERROR, message, null);
    }
    public static void error(String message, Map<String, String> customFields) {
        log(EchoLogLevel.ERROR, message, customFields);
    }

    public static void event(String message) { log(EchoLogLevel.EVENT, message, null); }
    public static void event(String message, Map<String, String> customFields) {
        log(EchoLogLevel.EVENT, message, customFields);
    }

    public static void debug(String message) { log(EchoLogLevel.DEBUG, message, null); }
    public static void debug(String message, Map<String, String> customFields) { log(EchoLogLevel.DEBUG, message, customFields); }

    public static void log(EchoLogLevel level, String message) {
        log(level, message, null);
    }

    public static void log(EchoLogLevel level, String message, Map<String, String> customFields) {
        try {
            EchoLogger logger = getInstance();
            if (!logger.isInitialized) {
                System.out.println("EchoLogger: ERROR - Attempt to use EchoLogger before initializing.");
            }

            if (!logger.loggingEnabled) {
                return;
            }

            JSONObject jsonMessage = new JSONObject();
            boolean messageOk = true;
            try {
                jsonMessage.put("timestamp", System.currentTimeMillis());
                jsonMessage.put("text", message);
                jsonMessage.put("level", level.getValue());

                if (customFields != null) {
                    JSONObject cstmFields = new JSONObject();
                    for(String key: customFields.keySet()) {
                        cstmFields.put(key, customFields.get(key));

                    }
                    jsonMessage.put("info", cstmFields);
                }
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

            synchronized (logger.mutex) {
                logger.jsonMessagesArray.put(jsonMessage);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private UUID getDeviceUUID(){
        final String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        try {
            if (!INVALID_DEVICE_ID.equals(androidId)) {
                return UUID.nameUUIDFromBytes(androidId.getBytes("utf8"));
            } else {
                final String deviceId = ((TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE)).getDeviceId();
                if (deviceId != null)
                    return UUID.nameUUIDFromBytes(deviceId.getBytes("utf8"));
            }
        } catch (Exception e) {
        }
        return UUID.randomUUID();
    }

    protected void readDeviceInfo() {
        if (deviceId != null)
            return;

        deviceId = getDeviceUUID().toString();

        try {
            BluetoothAdapter myDevice = BluetoothAdapter.getDefaultAdapter();
            deviceName = myDevice.getName();
        } catch (Exception e) { }

        osVersion = Build.VERSION.RELEASE;
        deviceType = Build.MANUFACTURER + " " + Build.MODEL;

        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageInfo(context.getPackageName(), 0);
            appVersion = info.versionName;
            buildVersion = info.versionCode;
        } catch (Exception e) {
        }

        try {
            jsonDeviceInfo.put("os", os);
            jsonDeviceInfo.put("os_version", osVersion);
            jsonDeviceInfo.put("device_type", deviceType);
            jsonDeviceInfo.put("app_version", appVersion);
            if (buildVersion > 0) jsonDeviceInfo.put("build_version", buildVersion);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void checkForInternetPermission() {
        if (isInternetPermissionGranted)
            return;

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.INTERNET)
                != PackageManager.PERMISSION_GRANTED) {
            System.out.println("EchoLogger: ERROR - Manifest.permission.INTERNET not granted. Logs will not be sent to server.");
        } else {
            isInternetPermissionGranted = true;
        }
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

    private static class SendLogsThread extends Thread {

        public SendLogsThread() {
            this.setPriority(Thread.MIN_PRIORITY);
        }

        @Override
        public void run() {
            while (!isInterrupted()) {
                try {
                    EchoLogger logger = getInstance();
                    if (!logger.loggingEnabled) {
                        delay();
                        continue;
                    }

                    logger.checkForInternetPermission();
                    logger.readDeviceInfo();

                    if (logger.deviceId == null) {
                        delay();
                        continue;
                    }

                    if (!logger.isInternetPermissionGranted) {
                        delay();
                        continue;
                    }

                    if (logger.jsonMessagesArray.length() == 0) {
                        delay();
                        continue;
                    }

                    String targetURL = SERVER_URL;
                    JSONObject jsonParams = new JSONObject();
                    try {
                        jsonParams.put("id", logger.applicationId);
                        jsonParams.put("device_id", logger.deviceId);
                        jsonParams.put("session_id", logger.sessionId);
                        jsonParams.put("messages", logger.jsonMessagesArray);
                        if (logger.deviceName != null && !logger.deviceName.isEmpty()) jsonParams.put("name", logger.deviceName);
                        jsonParams.put("device_info", logger.jsonDeviceInfo);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                    String contentAsString;
                    synchronized (logger.mutex) {
                        contentAsString = jsonParams.toString();
                        logger.jsonMessagesArray = new JSONArray();
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
                        }
                        rd.close();

                        String responseString = response.toString();

                        logger.loggingEnabled = !responseString.toLowerCase().equals("off");
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
                EchoLogger logger = getInstance();
                if (logger.loggingEnabled) {
                    this.sleep(SEND_INTERVAL);
                } else {
                    this.sleep(CHECK_ENABLED_INTERVAL);
                }
            } catch (InterruptedException e) { }
        }
    }
}
