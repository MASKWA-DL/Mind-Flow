package com.example.mindflow.ui.splash;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.example.mindflow.MainActivity;
import com.example.mindflow.R;
import com.example.mindflow.auth.AuthManager;
import com.example.mindflow.ui.auth.LoginActivity;

public class SplashActivity extends AppCompatActivity {

    private View logoView;
    private View sloganView;
    private View orbLeft;
    private View orbRight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        logoView = findViewById(R.id.ivSplashLogo);
        sloganView = findViewById(R.id.tvSplashSlogan);
        orbLeft = findViewById(R.id.orbLeft);
        orbRight = findViewById(R.id.orbRight);

        playIntroAnimation();
    }

    private void playIntroAnimation() {
        logoView.setAlpha(0f);
        logoView.setScaleX(0.8f);
        logoView.setScaleY(0.8f);
        sloganView.setAlpha(0f);
        sloganView.setTranslationY(24f);

        ObjectAnimator logoFade = ObjectAnimator.ofFloat(logoView, View.ALPHA, 0f, 1f);
        ObjectAnimator logoScaleX = ObjectAnimator.ofFloat(logoView, View.SCALE_X, 0.8f, 1f);
        ObjectAnimator logoScaleY = ObjectAnimator.ofFloat(logoView, View.SCALE_Y, 0.8f, 1f);
        ObjectAnimator sloganFade = ObjectAnimator.ofFloat(sloganView, View.ALPHA, 0f, 1f);
        ObjectAnimator sloganRise = ObjectAnimator.ofFloat(sloganView, View.TRANSLATION_Y, 24f, 0f);

        ObjectAnimator orbLeftFloat = ObjectAnimator.ofFloat(orbLeft, View.TRANSLATION_Y, 0f, -18f, 0f);
        orbLeftFloat.setRepeatCount(1);
        ObjectAnimator orbRightFloat = ObjectAnimator.ofFloat(orbRight, View.TRANSLATION_Y, 0f, 16f, 0f);
        orbRightFloat.setRepeatCount(1);

        AnimatorSet intro = new AnimatorSet();
        intro.playTogether(logoFade, logoScaleX, logoScaleY, sloganFade, sloganRise, orbLeftFloat, orbRightFloat);
        intro.setDuration(900);
        intro.start();

        logoView.postDelayed(this::routeNext, 1200);
    }

    private void routeNext() {
        Intent next;
        AuthManager authManager = AuthManager.getInstance(this);
        if (authManager.isLoggedIn() || authManager.isOfflineMode()) {
            next = new Intent(this, MainActivity.class);
        } else {
            next = new Intent(this, LoginActivity.class);
        }
        startActivity(next);
        finish();
    }
}