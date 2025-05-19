package com.example.courseproject;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class NetworkUtil {

    public static String getDeviceIpAddress(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                Network activeNetwork = cm.getActiveNetwork();
                Log.d("MyApp", "activeNetwork = " + activeNetwork);
                if (activeNetwork == null) return null;

                NetworkCapabilities nc = cm.getNetworkCapabilities(activeNetwork);
                Log.d("MyApp", "NetworkCapabilities = " + nc);
                if (nc == null) return null;

                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    WifiInfo wifiInfo = wm.getConnectionInfo();
                    int ipAddress = wifiInfo.getIpAddress();
                    String ip = ((ipAddress & 0xff) + "." +
                            ((ipAddress >> 8) & 0xff) + "." +
                            ((ipAddress >> 16) & 0xff) + "." +
                            ((ipAddress >> 24) & 0xff));
                    Log.d("MyApp", "Wifi IP: " + ip);
                    return ip;
                } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    String ip = getLocalIpByNetworkInterfaces();
                    Log.d("MyApp", "Cellular IP: " + ip);
                    return ip;
                }
            } else {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo wifiInfo = wm.getConnectionInfo();
                int ipAddress = wifiInfo.getIpAddress();
                String ip = ((ipAddress & 0xff) + "." +
                        ((ipAddress >> 8) & 0xff) + "." +
                        ((ipAddress >> 16) & 0xff) + "." +
                        ((ipAddress >> 24) & 0xff));
                Log.d("MyApp", "Legacy Wifi IP: " + ip);
                return ip;
            }
        } catch (Exception e) {
            e.printStackTrace();
            Log.e("MyApp", "Exception getting IP: " + e.getMessage());
        }
        return null;
    }


    private static String getLocalIpByNetworkInterfaces() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }
}
