package com.example.mindflow.ui.auth;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.mindflow.MainActivity;
import com.example.mindflow.R;
import com.example.mindflow.databinding.ActivityLoginBinding;
import com.example.mindflow.auth.AuthManager;
import com.example.mindflow.auth.AuthCallback;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.tabs.TabLayout;

/**
 * 登录/注册界面
 * 支持：
 * 1. 邮箱+密码登录
 * 2. 用户名+邮箱+密码注册
 * 3. 跳过登录（离线模式）
 */
public class LoginActivity extends AppCompatActivity {
    private static final String TAG = "LoginActivity";
    public static final String EXTRA_FORCE_LOGIN = "extra_force_login";
    
    private ActivityLoginBinding binding;
    private boolean isRegisterMode = false;
    private boolean forceLoginMode = false;
    private boolean inRecoveryFlow = false;
    private String recoveryAccessToken = "";
    private long forgotCooldownUntilMs = 0L;
    private final Handler forgotCooldownHandler = new Handler(Looper.getMainLooper());
    private final Runnable forgotCooldownTicker = new Runnable() {
        @Override
        public void run() {
            updateForgotPasswordUi();
            if (System.currentTimeMillis() < forgotCooldownUntilMs) {
                forgotCooldownHandler.postDelayed(this, 1000);
            }
        }
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        forceLoginMode = getIntent().getBooleanExtra(EXTRA_FORCE_LOGIN, false);

        handleRecoveryIntentIfNeeded(getIntent());
        
        // 检查是否已登录或离线模式
        checkLoginState();
        
        setupTabLayout();
        setupClickListeners();
        setupPasswordVisibilityToggles();
        updateForceLoginUi();
        updateForgotPasswordUi();
    }

    private void setupPasswordVisibilityToggles() {
        setupPasswordToggle(binding.passwordLayout, binding.etPassword);
        setupPasswordToggle(binding.confirmPasswordLayout, binding.etConfirmPassword);
    }

    private void setupPasswordToggle(TextInputLayout layout, android.widget.EditText editText) {
        if (layout == null || editText == null) {
            return;
        }

        setPasswordVisible(layout, editText, false);
        layout.setEndIconOnClickListener(v -> {
            boolean currentlyHidden = editText.getTransformationMethod() instanceof PasswordTransformationMethod;
            setPasswordVisible(layout, editText, currentlyHidden);
        });
    }

    private void setPasswordVisible(TextInputLayout layout, android.widget.EditText editText, boolean visible) {
        int cursorPos = editText.getSelectionStart();
        if (visible) {
            editText.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
            layout.setEndIconDrawable(R.drawable.ic_eye_open);
        } else {
            editText.setTransformationMethod(PasswordTransformationMethod.getInstance());
            layout.setEndIconDrawable(R.drawable.ic_eye_closed);
        }
        if (cursorPos >= 0 && cursorPos <= editText.length()) {
            editText.setSelection(cursorPos);
        }
    }
    
    /**
     * 检查登录状态，如果已登录则直接进入主页
     */
    private void checkLoginState() {
        AuthManager authManager = AuthManager.getInstance(this);

        if (inRecoveryFlow) {
            return;
        }

        // 从设置页主动进入登录时，不允许离线模式自动跳过
        if (forceLoginMode && authManager.isOfflineMode()) {
            authManager.setOfflineMode(false);
        }
        
        // 如果已登录或离线模式，直接进入主页
        if (!forceLoginMode && (authManager.isLoggedIn() || authManager.isOfflineMode())) {
            navigateToMain();
            finish();
        }
    }

    private void updateForceLoginUi() {
        if (forceLoginMode) {
            binding.tvLoginHint.setText("请先登录，登录后可同步云端数据");
            binding.btnOfflineMode.setVisibility(View.GONE);
        } else {
            binding.tvLoginHint.setText("登录后可同步专注数据，离线模式也可直接使用");
            binding.btnOfflineMode.setVisibility(View.VISIBLE);
        }
    }

    private void handleRecoveryIntentIfNeeded(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return;
        }

        Uri data = intent.getData();
        if (data == null) {
            return;
        }

        String path = data.getPath() == null ? "" : data.getPath();
        if (!path.contains("reset-password")) {
            return;
        }

        String fragment = data.getFragment();
        if (fragment == null) {
            fragment = "";
        }
        if (fragment.startsWith("#")) {
            fragment = fragment.substring(1);
        }

        String type = readCallbackParam(fragment, "type");
        String token = readCallbackParam(fragment, "access_token");

        if (!"recovery".equalsIgnoreCase(type) || TextUtils.isEmpty(token)) {
            Toast.makeText(this, "重置密码回调无效，请重新操作", Toast.LENGTH_LONG).show();
            return;
        }

        inRecoveryFlow = true;
        forceLoginMode = true;
        recoveryAccessToken = token;

        binding.tvLoginHint.setText("请设置新密码，设置成功后再登录");
        binding.btnOfflineMode.setVisibility(View.GONE);
        showResetPasswordDialog();
    }

    private String readCallbackParam(String fragment, String key) {
        if (TextUtils.isEmpty(fragment) || TextUtils.isEmpty(key)) {
            return "";
        }
        String[] parts = fragment.split("&");
        for (String part : parts) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && key.equals(kv[0])) {
                return Uri.decode(kv[1]);
            }
        }
        return "";
    }

    private void showResetPasswordDialog() {
        EditText etNew = new EditText(this);
        etNew.setHint("新密码（至少6位）");
        etNew.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        EditText etConfirm = new EditText(this);
        etConfirm.setHint("确认新密码");
        etConfirm.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, 0, padding, 0);
        container.addView(etNew);
        container.addView(etConfirm);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("重设密码")
                .setMessage("请输入新的登录密码")
                .setView(container)
                .setCancelable(false)
                .setNegativeButton("取消", (d, w) -> {
                    inRecoveryFlow = false;
                    recoveryAccessToken = "";
                })
                .setPositiveButton("确认", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newPwd = etNew.getText().toString().trim();
            String confirmPwd = etConfirm.getText().toString().trim();

            if (newPwd.length() < 6) {
                etNew.setError("密码至少6位");
                return;
            }
            if (!newPwd.equals(confirmPwd)) {
                etConfirm.setError("两次输入不一致");
                return;
            }

            setLoading(true);
            AuthManager.getInstance(this).resetPasswordWithToken(recoveryAccessToken, newPwd, new AuthCallback() {
                @Override
                public void onSuccess(String userId, String email, String username) {
                    runOnUiThread(() -> {
                        setLoading(false);
                        inRecoveryFlow = false;
                        recoveryAccessToken = "";
                        Toast.makeText(LoginActivity.this, "密码已更新，请使用新密码登录", Toast.LENGTH_LONG).show();
                        dialog.dismiss();
                    });
                }

                @Override
                public void onFailure(String error) {
                    runOnUiThread(() -> {
                        setLoading(false);
                        Toast.makeText(LoginActivity.this, "重设失败: " + error, Toast.LENGTH_LONG).show();
                    });
                }
            });
        }));

        dialog.show();
    }
    
    private void setupTabLayout() {
        binding.tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                isRegisterMode = tab.getPosition() == 1;
                updateFormVisibility();
            }
            
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
    }
    
    private void updateFormVisibility() {
        if (isRegisterMode) {
            // 注册模式：显示用户名和确认密码
            binding.usernameLayout.setVisibility(View.VISIBLE);
            binding.confirmPasswordLayout.setVisibility(View.VISIBLE);
            binding.btnSubmit.setText("注册");
            binding.tvForgotPassword.setVisibility(View.GONE);
        } else {
            // 登录模式：隐藏用户名和确认密码
            binding.usernameLayout.setVisibility(View.GONE);
            binding.confirmPasswordLayout.setVisibility(View.GONE);
            binding.btnSubmit.setText("登录");
            binding.tvForgotPassword.setVisibility(View.VISIBLE);
            updateForgotPasswordUi();
        }
    }
    
    private void setupClickListeners() {
        // 登录/注册按钮
        binding.btnSubmit.setOnClickListener(v -> handleSubmit());
        
        // 忘记密码
        binding.tvForgotPassword.setOnClickListener(v -> handleForgotPassword());
        
        binding.btnOfflineMode.setOnClickListener(v -> {
            AuthManager.getInstance(LoginActivity.this).setOfflineMode(true);
            navigateToMain();
        });
    }
    
    private void handleSubmit() {
        String email = binding.etEmail.getText().toString().trim();
        String password = binding.etPassword.getText().toString().trim();
        
        // 基本验证
        if (!validateInput(email, password)) {
            return;
        }
        
        // 显示加载状态
        setLoading(true);
        
        if (isRegisterMode) {
            // 注册
            String username = binding.etUsername.getText().toString().trim();
            String confirmPassword = binding.etConfirmPassword.getText().toString().trim();
            
            if (!validateRegisterInput(username, password, confirmPassword)) {
                setLoading(false);
                return;
            }
            
            register(username, email, password);
        } else {
            // 登录
            login(email, password);
        }
    }
    
    private boolean validateInput(String email, String password) {
        if (TextUtils.isEmpty(email)) {
            binding.etEmail.setError("请输入邮箱");
            return false;
        }
        
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.etEmail.setError("请输入有效的邮箱地址");
            return false;
        }
        
        if (TextUtils.isEmpty(password)) {
            binding.etPassword.setError("请输入密码");
            return false;
        }
        
        if (password.length() < 6) {
            binding.etPassword.setError("密码至少6位");
            return false;
        }
        
        return true;
    }
    
    private boolean validateRegisterInput(String username, String password, String confirmPassword) {
        if (TextUtils.isEmpty(username)) {
            binding.etUsername.setError("请输入用户名");
            return false;
        }
        
        if (username.length() < 2 || username.length() > 20) {
            binding.etUsername.setError("用户名需2-20个字符");
            return false;
        }
        
        if (!password.equals(confirmPassword)) {
            binding.etConfirmPassword.setError("两次密码不一致");
            return false;
        }
        
        return true;
    }
    
    private void login(String email, String password) {
        AuthManager.getInstance(this).login(email, password, new AuthCallback() {
            @Override
            public void onSuccess(String userId, String email, String username) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(LoginActivity.this, "登录成功", Toast.LENGTH_SHORT).show();
                    navigateToMain();
                });
            }
            
            @Override
            public void onFailure(String error) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(LoginActivity.this, "登录失败: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    
    private void register(String username, String email, String password) {
        AuthManager.getInstance(this).register(username, email, password, new AuthCallback() {
            @Override
            public void onSuccess(String userId, String email, String username) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(LoginActivity.this, "注册成功", Toast.LENGTH_SHORT).show();
                    navigateToMain();
                });
            }
            
            @Override
            public void onFailure(String error) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(LoginActivity.this, "注册失败: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    
    private void handleForgotPassword() {
        long now = System.currentTimeMillis();
        if (now < forgotCooldownUntilMs) {
            long left = Math.max(1L, (forgotCooldownUntilMs - now + 999L) / 1000L);
            Toast.makeText(this, "发送过于频繁，请在 " + left + " 秒后重试", Toast.LENGTH_SHORT).show();
            updateForgotPasswordUi();
            return;
        }

        String email = binding.etEmail.getText().toString().trim();
        
        if (TextUtils.isEmpty(email)) {
            Toast.makeText(this, "请先输入邮箱地址", Toast.LENGTH_SHORT).show();
            return;
        }
        
        setLoading(true);
        AuthManager.getInstance(this).resetPassword(email, new AuthCallback() {
            @Override
            public void onSuccess(String userId, String email, String username) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(LoginActivity.this, "重置邮件请求已提交", Toast.LENGTH_LONG).show();
                });
            }
            
            @Override
            public void onFailure(String error) {
                runOnUiThread(() -> {
                    setLoading(false);
                    int retrySec = extractRetrySeconds(error);
                    if (retrySec > 0) {
                        startForgotCooldown(retrySec);
                    }
                    Toast.makeText(LoginActivity.this, "重置失败: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private int extractRetrySeconds(String text) {
        if (TextUtils.isEmpty(text)) {
            return -1;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d+)\\s*(秒|seconds?)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (Exception ignored) {
                return -1;
            }
        }
        return -1;
    }

    private void startForgotCooldown(int seconds) {
        int safeSeconds = Math.max(1, seconds);
        forgotCooldownUntilMs = System.currentTimeMillis() + safeSeconds * 1000L;
        forgotCooldownHandler.removeCallbacks(forgotCooldownTicker);
        forgotCooldownHandler.post(forgotCooldownTicker);
    }

    private void updateForgotPasswordUi() {
        if (binding == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < forgotCooldownUntilMs) {
            long left = Math.max(1L, (forgotCooldownUntilMs - now + 999L) / 1000L);
            binding.tvForgotPassword.setEnabled(false);
            binding.tvForgotPassword.setText("忘记密码？（" + left + "s）");
        } else {
            binding.tvForgotPassword.setEnabled(true);
            binding.tvForgotPassword.setText("忘记密码？");
        }
    }
    
    private void setLoading(boolean loading) {
        binding.progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.btnSubmit.setEnabled(!loading);
        binding.btnOfflineMode.setEnabled(!loading);
        binding.etEmail.setEnabled(!loading);
        binding.etPassword.setEnabled(!loading);
        binding.etUsername.setEnabled(!loading);
        binding.etConfirmPassword.setEnabled(!loading);
    }
    
    private void navigateToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleRecoveryIntentIfNeeded(intent);
    }
    
    @Override
    protected void onDestroy() {
        forgotCooldownHandler.removeCallbacks(forgotCooldownTicker);
        super.onDestroy();
        binding = null;
    }
}
