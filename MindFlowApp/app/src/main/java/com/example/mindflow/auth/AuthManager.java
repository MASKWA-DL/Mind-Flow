package com.example.mindflow.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 认证管理器 - 单例模式
 * 管理用户登录状态、Token 存储、用户信息缓存
 */
public class AuthManager {
    private static final String TAG = "AuthManager";
    private static final String PREFS_NAME = "MindFlowAuth";
    
    // SharedPreferences Keys
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_IS_LOGGED_IN = "is_logged_in";
    private static final String KEY_IS_OFFLINE_MODE = "is_offline_mode";
    
    private static AuthManager instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService executor;
    private final SupabaseClient supabaseClient;
    
    // 当前用户缓存
    private UserProfile currentUser;
    
    public static synchronized AuthManager getInstance(Context context) {
        if (instance == null) {
            instance = new AuthManager(context.getApplicationContext());
        }
        return instance;
    }
    
    private AuthManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.executor = Executors.newSingleThreadExecutor();
        this.supabaseClient = SupabaseClient.getInstance();
        
        // 加载缓存的用户信息
        loadCachedUser();
    }
    
    /**
     * 用户登录
     */
    public void login(String email, String password, AuthCallback callback) {
        executor.execute(() -> {
            try {
                SupabaseClient.AuthResult result = supabaseClient.signIn(email, password);
                
                if (result.success) {
                    // 保存 Token
                    saveSession(result.userId, result.email, null, result.accessToken, result.refreshToken);
                    
                    // 获取用户完整信息
                    UserProfile profile = supabaseClient.getUser(result.accessToken);
                    if (profile != null) {
                        currentUser = profile;
                        // 更新用户名
                        prefs.edit().putString(KEY_USERNAME, profile.username).apply();
                        
                        notifySuccess(callback, profile.id, profile.email, profile.username);
                    } else {
                        notifySuccess(callback, result.userId, result.email, result.email.split("@")[0]);
                    }
                } else {
                    notifyFailure(callback, result.error);
                }
            } catch (Exception e) {
                Log.e(TAG, "Login error", e);
                notifyFailure(callback, "网络错误: " + e.getMessage());
            }
        });
    }
    
    /**
     * 用户注册
     */
    public void register(String username, String email, String password, AuthCallback callback) {
        executor.execute(() -> {
            try {
                // 1. 注册用户
                SupabaseClient.AuthResult result = supabaseClient.signUp(email, password);
                
                if (result.success) {
                    // 2. 如果注册成功但没有自动登录（需要验证邮箱的情况）
                    if (result.accessToken == null || result.accessToken.isEmpty()) {
                        // 需要验证邮箱
                        notifyFailure(callback, "注册成功！请查收验证邮件后登录");
                        return;
                    }
                    
                    // 3. 保存 Token
                    saveSession(result.userId, result.email, username, result.accessToken, result.refreshToken);
                    
                    // 4. 创建用户 profile
                    boolean profileCreated = supabaseClient.createUserProfile(
                            result.accessToken, result.userId, username, email);
                    
                    if (profileCreated) {
                        currentUser = new UserProfile(result.userId, email, username);
                        notifySuccess(callback, result.userId, email, username);
                    } else {
                        // Profile 创建失败，但用户已注册
                        Log.w(TAG, "Profile creation failed, but user registered");
                        currentUser = new UserProfile(result.userId, email, username);
                        notifySuccess(callback, result.userId, email, username);
                    }
                } else {
                    notifyFailure(callback, result.error);
                }
            } catch (Exception e) {
                Log.e(TAG, "Register error", e);
                notifyFailure(callback, "网络错误: " + e.getMessage());
            }
        });
    }
    
    /**
     * 发送密码重置邮件
     */
    public void resetPassword(String email, AuthCallback callback) {
        executor.execute(() -> {
            try {
                SupabaseClient.UserExistence existence = supabaseClient.checkUserExistsByEmail(email);
                if (existence == SupabaseClient.UserExistence.NOT_EXISTS) {
                    notifyFailure(callback, "该账户不存在，请先注册");
                    return;
                }

                boolean success = supabaseClient.resetPassword(email);
                if (success) {
                    notifySuccess(callback, null, email, null);
                } else {
                    notifyFailure(callback, "发送失败，请检查邮箱地址");
                }
            } catch (Exception e) {
                Log.e(TAG, "Reset password error", e);
                notifyFailure(callback, "网络错误: " + e.getMessage());
            }
        });
    }

    /**
     * 使用重置密码回调里的 access token 直接更新密码。
     */
    public void resetPasswordWithToken(String accessToken, String newPassword, AuthCallback callback) {
        executor.execute(() -> {
            try {
                boolean success = supabaseClient.updatePasswordWithAccessToken(accessToken, newPassword);
                if (success) {
                    notifySuccess(callback, null, null, null);
                } else {
                    notifyFailure(callback, "密码更新失败，请重试");
                }
            } catch (Exception e) {
                Log.e(TAG, "Update password error", e);
                notifyFailure(callback, "网络错误: " + e.getMessage());
            }
        });
    }
    
    /**
     * 退出登录
     */
    public void logout() {
        executor.execute(() -> {
            try {
                String accessToken = prefs.getString(KEY_ACCESS_TOKEN, null);
                if (accessToken != null) {
                    supabaseClient.signOut(accessToken);
                }
            } catch (Exception e) {
                Log.e(TAG, "Logout error", e);
            } finally {
                clearSession();
            }
        });
    }
    
    /**
     * 检查是否已登录
     */
    public boolean isLoggedIn() {
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false);
    }
    
    /**
     * 检查是否离线模式
     */
    public boolean isOfflineMode() {
        return prefs.getBoolean(KEY_IS_OFFLINE_MODE, false);
    }
    
    /**
     * 设置离线模式
     */
    public void setOfflineMode(boolean offline) {
        prefs.edit().putBoolean(KEY_IS_OFFLINE_MODE, offline).apply();
    }
    
    /**
     * 获取当前用户
     */
    public UserProfile getCurrentUser() {
        if (currentUser != null) {
            return currentUser;
        }
        
        // 从缓存加载
        if (isLoggedIn()) {
            String userId = prefs.getString(KEY_USER_ID, null);
            String email = prefs.getString(KEY_EMAIL, null);
            String username = prefs.getString(KEY_USERNAME, null);
            
            if (userId != null && email != null) {
                currentUser = new UserProfile(userId, email, username != null ? username : email.split("@")[0]);
                return currentUser;
            }
        }
        
        return null;
    }
    
    /**
     * 获取 Access Token
     */
    public String getAccessToken() {
        return prefs.getString(KEY_ACCESS_TOKEN, null);
    }
    
    /**
     * 获取用户 ID
     */
    public String getUserId() {
        return prefs.getString(KEY_USER_ID, null);
    }
    
    /**
     * 获取用户名
     */
    public String getUsername() {
        return prefs.getString(KEY_USERNAME, null);
    }
    
    /**
     * 获取邮箱
     */
    public String getEmail() {
        return prefs.getString(KEY_EMAIL, null);
    }
    
    // ========== 私有方法 ==========
    
    private void saveSession(String userId, String email, String username, String accessToken, String refreshToken) {
        SharedPreferences.Editor editor = prefs.edit()
                .putString(KEY_USER_ID, userId)
                .putString(KEY_EMAIL, email)
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putBoolean(KEY_IS_LOGGED_IN, true)
                .putBoolean(KEY_IS_OFFLINE_MODE, false);
        
        if (username != null) {
            editor.putString(KEY_USERNAME, username);
        }
        if (refreshToken != null) {
            editor.putString(KEY_REFRESH_TOKEN, refreshToken);
        }
        
        editor.apply();
    }
    
    private void clearSession() {
        prefs.edit()
                .remove(KEY_USER_ID)
                .remove(KEY_EMAIL)
                .remove(KEY_USERNAME)
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .putBoolean(KEY_IS_LOGGED_IN, false)
                .putBoolean(KEY_IS_OFFLINE_MODE, false)
                .apply();
        
        currentUser = null;
    }
    
    private void loadCachedUser() {
        if (isLoggedIn()) {
            String userId = prefs.getString(KEY_USER_ID, null);
            String email = prefs.getString(KEY_EMAIL, null);
            String username = prefs.getString(KEY_USERNAME, null);
            
            if (userId != null && email != null) {
                currentUser = new UserProfile(userId, email, username != null ? username : email.split("@")[0]);
            }
        }
    }
    
    private void notifySuccess(AuthCallback callback, String userId, String email, String username) {
        if (callback != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> 
                    callback.onSuccess(userId, email, username));
        }
    }
    
    private void notifyFailure(AuthCallback callback, String error) {
        if (callback != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> 
                    callback.onFailure(error));
        }
    }
}
