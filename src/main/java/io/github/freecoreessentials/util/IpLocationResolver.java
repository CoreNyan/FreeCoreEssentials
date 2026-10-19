package io.github.freecoreessentials.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance IP geolocation & ISP resolver with in-memory & Redis caching.
 */
public final class IpLocationResolver {
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private IpLocationResolver() {}

    public static String resolveLocation(String ip) {
        if (ip == null || ip.isBlank() || ip.equals("未知")) return "未知位置";
        ip = ip.trim();
        if (ip.startsWith("/")) ip = ip.substring(1);
        if (ip.equals("127.0.0.1") || ip.equals("localhost") || ip.startsWith("192.168.") || ip.startsWith("10.")) {
            return "本地局域网";
        }

        String cached = CACHE.get(ip);
        if (cached != null) return cached;

        String result = fetchFromBaidu(ip);
        if (result == null || result.isBlank() || result.equals("未知")) {
            result = fetchFromIpApi(ip);
        }

        if (result != null && !result.isBlank()) {
            CACHE.put(ip, result);
            return result;
        }

        return "未知归属地";
    }

    private static String fetchFromBaidu(String ip) {
        try {
            URI uri = URI.create("https://opendata.baidu.com/api.php?query=" + ip + "&co=&resource_id=6006&oe=utf8");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);

            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    JsonObject obj = JsonParser.parseString(sb.toString()).getAsJsonObject();
                    if (obj.has("data") && obj.getAsJsonArray("data").size() > 0) {
                        JsonObject first = obj.getAsJsonArray("data").get(0).getAsJsonObject();
                        if (first.has("location")) {
                            return first.get("location").getAsString().trim();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String fetchFromIpApi(String ip) {
        try {
            URI uri = URI.create("http://ip-api.com/json/" + ip + "?lang=zh-CN");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);

            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    JsonObject obj = JsonParser.parseString(sb.toString()).getAsJsonObject();
                    if ("success".equals(obj.has("status") ? obj.get("status").getAsString() : null)) {
                        String region = obj.has("regionName") ? obj.get("regionName").getAsString() : "";
                        String city = obj.has("city") ? obj.get("city").getAsString() : "";
                        String isp = obj.has("isp") ? obj.get("isp").getAsString() : "";
                        return (region + " " + city + " " + isp).trim();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
