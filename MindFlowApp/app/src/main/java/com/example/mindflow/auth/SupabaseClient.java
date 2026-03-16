package com.example.mindflow.auth;

import android.content.Context;
import android.util.Log;

import com.example.mindflow.BuildConfig;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Supabase 客户端 - 处理所有 Supabase API 调用
 * 使用 OkHttp 直接调用 REST API（避免引入复杂的 Kotlin 协程依赖）
 */
public class SupabaseClient {
    private static final String TAG = "SupabaseClient";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final String supabaseUrl;
    private final String supabaseKey;
    private final OkHttpClient client;
    
    private static SupabaseClient instance;
    
    public static synchronized SupabaseClient getInstance() {
        if (instance == null) {
            instance = new SupabaseClient();
        }
        return instance;
    }
    
    private SupabaseClient() {
        this.supabaseUrl = normalizeBaseUrl(BuildConfig.SUPABASE_URL);
        this.supabaseKey = BuildConfig.SUPABASE_ANON_KEY;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    private String normalizeBaseUrl(String rawUrl) {
        if (rawUrl == null) {
            return "";
        }
        String normalized = rawUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void validateConfig() throws IOException {
        if (supabaseUrl.isEmpty() ||
                !(supabaseUrl.startsWith("https://") || supabaseUrl.startsWith("http://"))) {
            throw new IOException("Supabase URL 配置错误，请检查 local.properties 中 SUPABASE_URL");
        }
        if (supabaseKey == null || supabaseKey.trim().isEmpty()) {
            throw new IOException("Supabase Key 配置缺失，请检查 local.properties 中 SUPABASE_ANON_KEY");
        }
    }
    
    /**
     * 用户注册
     */
    public AuthResult signUp(String email, String password) throws Exception {
        validateConfig();

        JSONObject body = new JSONObject();
        body.put("email", email);
        body.put("password", password);
        
        Request request = new Request.Builder()
                .url(supabaseUrl + "/auth/v1/signup")
                .addHeader("apikey", supabaseKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            Log.d(TAG, "SignUp response: " + response.code() + " - " + responseBody);
            
            if (response.isSuccessful()) {
                JSONObject json = new JSONObject(responseBody);
                AuthResult result = new AuthResult();
                result.success = true;
                result.userId = json.optString("id");
                result.email = json.optString("email");
                
                // 提取 session token
                if (json.has("access_token")) {
                    result.accessToken = json.getString("access_token");
                    result.refreshToken = json.optString("refresh_token");
                }
                
                return result;
            } else {
                AuthResult result = new AuthResult();
                result.success = false;
                result.error = parseError(responseBody);
                return result;
            }
        }
    }
    
    /**
     * 用户登录
     */
    public AuthResult signIn(String email, String password) throws Exception {
        validateConfig();

        JSONObject body = new JSONObject();
        body.put("email", email);
        body.put("password", password);
        
        Request request = new Request.Builder()
                .url(supabaseUrl + "/auth/v1/token?grant_type=password")
                .addHeader("apikey", supabaseKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            Log.d(TAG, "SignIn response: " + response.code() + " - " + responseBody);
            
            if (response.isSuccessful()) {
                JSONObject json = new JSONObject(responseBody);
                AuthResult result = new AuthResult();
                result.success = true;
                result.accessToken = json.getString("access_token");
                result.refreshToken = json.optString("refresh_token");
                result.expiresIn = json.optLong("expires_in", 3600);
                
                // 提取用户信息
                if (json.has("user")) {
                    JSONObject user = json.getJSONObject("user");
                    result.userId = user.getString("id");
                    result.email = user.getString("email");
                }
                
                return result;
            } else {
                AuthResult result = new AuthResult();
                result.success = false;
                result.error = parseError(responseBody);
                return result;
            }
        }
    }
    
    /**
     * 获取当前用户信息
     */
    public UserProfile getUser(String accessToken) throws Exception {
        validateConfig();

        Request request = new Request.Builder()
                .url(supabaseUrl + "/auth/v1/user")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer " + accessToken)
                .get()
                .build();
        
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            Log.d(TAG, "GetUser response: " + response.code());
            
            if (response.isSuccessful()) {
                JSONObject json = new JSONObject(responseBody);
                UserProfile profile = new UserProfile();
                profile.id = json.getString("id");
                profile.email = json.getString("email");
                
                // 从 user_metadata 提取用户名
                if (json.has("user_metadata")) {
                    JSONObject meta = json.getJSONObject("user_metadata");
                    profile.username = meta.optString("username", profile.email.split("@")[0]);
                }
                
                return profile;
            } else {
                Log.e(TAG, "GetUser failed: " + responseBody);
                return null;
            }
        }
    }
    
    /**
     * 发送密码重置邮件
     */
    public boolean resetPassword(String email) throws Exception {
        validateConfig();

        JSONObject body = new JSONObject();
        body.put("email", email);
        
        Request request = new Request.Builder()
                .url(supabaseUrl + "/auth/v1/recover")
                .addHeader("apikey", supabaseKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        
        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        }
    }

    public enum UserExistence {
        EXISTS,
        NOT_EXISTS,
        UNKNOWN
    }

    /**
     * 根据邮箱检查用户是否存在（依赖 user_profiles 可查询）。
     */
    public UserExistence checkUserExistsByEmail(String email) throws Exception {
        validateConfig();

        String encodedEmail = URLEncoder.encode(email, StandardCharsets.UTF_8.name());
        String url = supabaseUrl + "/rest/v1/user_profiles?email=eq." + encodedEmail + "&select=id,email&limit=1";

        Request request = new Request.Builder()
                .url(url)
                .addHeader("apikey", supabaseKey)
                .addHeader("Content-Type", "application/json")
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            Log.d(TAG, "Check user exists response: " + response.code() + " - " + responseBody);

            if (response.isSuccessful()) {
                JSONArray arr = new JSONArray(responseBody);
                if (arr.length() <= 0) {
                    // user_profiles 可能缺历史数据，不能据此断言 auth.users 不存在。
                    return UserExistence.UNKNOWN;
                }

                JSONObject first = arr.optJSONObject(0);
                if (first == null) {
                    return UserExistence.UNKNOWN;
                }

                String id = first.optString("id", "").trim();
                String returnedEmail = first.optString("email", "").trim();
                if (id.isEmpty()) {
                    return UserExistence.UNKNOWN;
                }
                if (!returnedEmail.isEmpty() && !returnedEmail.equalsIgnoreCase(email)) {
                    return UserExistence.NOT_EXISTS;
                }

                return UserExistence.EXISTS;
            }

            // 无法查询时返回 UNKNOWN，由上层决定是否阻断。
            return UserExistence.UNKNOWN;
        }
    }

    /**
     * 使用恢复回调中的 access token 更新密码。
     */
    public boolean updatePasswordWithAccessToken(String accessToken, String newPassword) throws Exception {
        validateConfig();

        JSONObject body = new JSONObject();
        body.put("password", newPassword);

        Request request = new Request.Builder()
                .url(supabaseUrl + "/auth/v1/user")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Content-Type", "application/json")
                .put(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            Log.d(TAG, "Update password response: " + response.code() + " - " + responseBody);
            return response.isSuccessful();
        }
    }
    
    /**
     * 退出登录
     */
    public boolean signOut(String accessToken) throws Exception {
        validateConfig();

        Request request = new Request.Builder()
                .url(supabaseUrl + "/auth/v1/logout")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer " + accessToken)
                .post(RequestBody.create("", JSON))
                .build();
        
        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        }
    }
    
    /**
     * 创建用户 profile 记录
     */
    public boolean createUserProfile(String accessToken, String userId, String username, String email) throws Exception {
        validateConfig();

        JSONObject body = new JSONObject();
        body.put("id", userId);
        body.put("username", username);
        body.put("email", email);
        
        Request request = new Request.Builder()
                .url(supabaseUrl + "/rest/v1/user_profiles")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        
        try (Response response = client.newCall(request).execute()) {
            Log.d(TAG, "Create profile response: " + response.code());
            return response.isSuccessful() || response.code() == 409; // 409 = 已存在
        }
    }
    
    /**
     * 获取用户 profile
     */
    public UserProfile getUserProfile(String accessToken, String userId) throws Exception {
        validateConfig();

        Request request = new Request.Builder()
                .url(supabaseUrl + "/rest/v1/user_profiles?id=eq." + userId + "&select=*")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer " + accessToken)
                .get()
                .build();
        
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            Log.d(TAG, "Get profile response: " + response.code());
            
            if (response.isSuccessful()) {
                org.json.JSONArray arr = new org.json.JSONArray(responseBody);
                if (arr.length() > 0) {
                    JSONObject json = arr.getJSONObject(0);
                    UserProfile profile = new UserProfile();
                    profile.id = json.getString("id");
                    profile.email = json.getString("email");
                    profile.username = json.getString("username");
                    profile.avatarUrl = json.optString("avatar_url");
                    return profile;
                }
            }
            return null;
        }
    }
    
    private String parseError(String responseBody) {
        try {
            JSONObject json = new JSONObject(responseBody);
            if (json.has("error_description")) {
                return json.getString("error_description");
            }
            if (json.has("msg")) {
                return json.getString("msg");
            }
            if (json.has("error")) {
                if (json.get("error") instanceof String) {
                    return json.getString("error");
                }
                JSONObject error = json.getJSONObject("error");
                return error.optString("message", "未知错误");
            }
            if (json.has("message")) {
                return json.getString("message");
            }
            return "请求失败";
        } catch (Exception e) {
            return "请求失败: " + responseBody;
        }
    }
    
    /**
     * 认证结果
     */
    public static class AuthResult {
        public boolean success;
        public String userId;
        public String email;
        public String username;
        public String accessToken;
        public String refreshToken;
        public long expiresIn;
        public String error;
    }
}
