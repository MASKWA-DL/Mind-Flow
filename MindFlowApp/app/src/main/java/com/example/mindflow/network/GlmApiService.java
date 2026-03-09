package com.example.mindflow.network;

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import com.example.mindflow.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class GlmApiService {
    private static final String TAG = "GlmApiService";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private static final String API_KEY = BuildConfig.AI_API_KEY;
    private static final String API_URL = BuildConfig.AI_BASE_URL;
    private static final String TEXT_MODEL = BuildConfig.AI_TEXT_MODEL;
    private static final String VISION_MODEL = BuildConfig.AI_VISION_MODEL;

    private static String currentFocusGoal = "工作";
    private static String currentAppName = "";

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private static Call currentCall = null;
    private static volatile boolean isCancelled = false;

    public interface AiCallback {
        void onSuccess(String result);
        void onFailure(String error);
    }

    public static void cancelAllRequests() {
        isCancelled = true;
        if (currentCall != null) {
            currentCall.cancel();
            currentCall = null;
            Log.i(TAG, "🛑 已取消所有AI请求");
        }
    }

    public static void resetCancelState() {
        isCancelled = false;
        Log.d(TAG, "✅ AI请求状态已重置");
    }

    public static void setFocusGoal(String goal) {
        if (goal != null && !goal.trim().isEmpty()) {
            currentFocusGoal = goal.trim();
        }
    }

    public static String getFocusGoal() {
        return currentFocusGoal;
    }

    public static void setCurrentAppName(String appName) {
        if (appName != null) {
            currentAppName = appName.trim();
        }
    }

    public static String getTextModel() {
        return TEXT_MODEL;
    }

    public static String getVisionModel() {
        return VISION_MODEL;
    }

    public static String getApiUrl() {
        return API_URL;
    }

    private static String buildVisionPrompt() {
        String appInfo = "";
        if (currentAppName != null && !currentAppName.isEmpty()) {
            appInfo = "当前App：" + currentAppName + "\n";
        }
        return "你是专注度分析助手，请根据截图判断用户是否偏离任务。\n\n" +
                appInfo +
                "用户专注目标：【" + currentFocusGoal + "】\n\n" +
                "判断要求：\n" +
                "1. 优先看截图里的实际内容，而不是只看 App 名。\n" +
                "2. 如果内容明显与目标相关，返回 YES。\n" +
                "3. 如果内容明显是娱乐、闲聊、购物、无关浏览，返回 NO。\n" +
                "4. 如果刚切页面、截图信息太少、界面在加载、被弹窗遮挡，返回 UNSURE。\n" +
                "5. reason 必须详细说明：目标是什么、截图里看到什么、为什么符合/不符合。\n" +
                "6. evidence 必须给 1-3 条简短证据。\n";
    }

    private static String buildTextSystemPrompt() {
        return "你是一个专注度分析助手。你的任务是根据【专注目标】【当前应用】【屏幕内容】判断用户是否分心。\n\n" +
                "判断原则：\n" +
                "1. 不要只按 App 名判断，必须结合屏幕文本内容。\n" +
                "2. 与任务直接相关、为任务服务、或合理的过渡操作，应判 YES。\n" +
                "3. 明显与任务无关的娱乐、购物、闲聊、信息流刷屏，应判 NO。\n" +
                "4. 如果信息不足、刚切换页面、仍在加载、权限弹窗、通知遮挡，请判 UNSURE。\n" +
                "5. reason 必须写详细，至少覆盖：任务目标、当前行为、关键证据、最终结论。\n" +
                "6. evidence 必须给 1-3 条来自当前页面的关键证据。\n";
    }

    private static JSONObject buildResponseFormat() throws Exception {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");

        JSONObject properties = new JSONObject();
        properties.put("conclusion", enumProperty("YES", "NO", "UNSURE"));
        properties.put("behavior", stringProperty("当前行为，5-14字"));
        properties.put("reason", stringProperty("详细说明：任务目标、当前行为、关键证据、判断理由"));
        properties.put("evidence", arrayProperty("来自屏幕内容/截图的关键证据"));
        properties.put("confidence", integerProperty("0-100 的整数置信度"));
        properties.put("suggestion", stringProperty("建议用户下一步如何回到任务"));

        schema.put("properties", properties);

        JSONArray required = new JSONArray();
        required.put("conclusion");
        required.put("behavior");
        required.put("reason");
        required.put("evidence");
        required.put("confidence");
        required.put("suggestion");
        schema.put("required", required);
        schema.put("additionalProperties", false);

        JSONObject jsonSchema = new JSONObject();
        jsonSchema.put("name", "focus_assessment");
        jsonSchema.put("strict", true);
        jsonSchema.put("schema", schema);

        JSONObject responseFormat = new JSONObject();
        responseFormat.put("type", "json_schema");
        responseFormat.put("json_schema", jsonSchema);
        return responseFormat;
    }

    private static JSONObject enumProperty(String... values) throws Exception {
        JSONObject property = new JSONObject();
        property.put("type", "string");
        JSONArray enums = new JSONArray();
        for (String value : values) {
            enums.put(value);
        }
        property.put("enum", enums);
        return property;
    }

    private static JSONObject stringProperty(String description) throws Exception {
        JSONObject property = new JSONObject();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private static JSONObject integerProperty(String description) throws Exception {
        JSONObject property = new JSONObject();
        property.put("type", "integer");
        property.put("description", description);
        property.put("minimum", 0);
        property.put("maximum", 100);
        return property;
    }

    private static JSONObject arrayProperty(String description) throws Exception {
        JSONObject property = new JSONObject();
        property.put("type", "array");
        property.put("description", description);
        JSONObject items = new JSONObject();
        items.put("type", "string");
        property.put("items", items);
        property.put("minItems", 1);
        property.put("maxItems", 3);
        return property;
    }

    private static void applyCommonOptions(JSONObject root, int maxTokens) throws Exception {
        root.put("temperature", 0.1);
        root.put("top_p", 0.8);
        root.put("max_tokens", maxTokens);
        root.put("enable_thinking", false);
        root.put("response_format", buildResponseFormat());
    }

    public static void analyzeImage(Bitmap bitmap, AiCallback callback) {
        if (isCancelled) {
            Log.d(TAG, "⏹️ AI请求已取消，跳过分析");
            callback.onFailure("AI 请求已取消，请重试");
            return;
        }

        if (API_KEY == null || API_KEY.trim().isEmpty()) {
            Log.e(TAG, "AI API key is missing. Set AI_API_KEY in local.properties.");
            callback.onFailure("API Key 缺失");
            return;
        }

        String base64Image = bitmapToBase64(bitmap);
        if (base64Image == null) {
            callback.onFailure("图片处理失败");
            return;
        }

        try {
            JSONObject root = new JSONObject();
            root.put("model", VISION_MODEL);

            JSONArray messages = new JSONArray();
            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");

            JSONArray contentList = new JSONArray();
            JSONObject textObj = new JSONObject();
            textObj.put("type", "text");
            textObj.put("text", buildVisionPrompt());
            contentList.put(textObj);

            JSONObject imageObj = new JSONObject();
            imageObj.put("type", "image_url");
            JSONObject urlObj = new JSONObject();
            urlObj.put("url", base64Image);
            imageObj.put("image_url", urlObj);
            contentList.put(imageObj);

            userMessage.put("content", contentList);
            messages.put(userMessage);
            root.put("messages", messages);
            applyCommonOptions(root, 320);

            Log.d(TAG, "发送视觉分析请求: model=" + VISION_MODEL);
            enqueueRequest(root, callback, true);
        } catch (Exception e) {
            Log.e(TAG, "JSON 构造异常", e);
            callback.onFailure("数据构造错误");
        }
    }

    private static String bitmapToBase64(Bitmap bitmap) {
        if (bitmap == null) return null;
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, byteArrayOutputStream);
            byte[] byteArray = byteArrayOutputStream.toByteArray();
            return "data:image/jpeg;base64," + Base64.encodeToString(byteArray, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "图片转 Base64 失败", e);
            return null;
        }
    }

    private static String parseResponse(String jsonStr) {
        try {
            JSONObject json = new JSONObject(jsonStr);
            if (json.has("choices")) {
                JSONArray choices = json.getJSONArray("choices");
                if (choices.length() > 0) {
                    JSONObject firstChoice = choices.getJSONObject(0);
                    JSONObject message = firstChoice.getJSONObject("message");
                    Object content = message.opt("content");
                    if (content instanceof String) {
                        return ((String) content).trim();
                    }
                    if (content instanceof JSONArray) {
                        JSONArray parts = (JSONArray) content;
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < parts.length(); i++) {
                            JSONObject part = parts.optJSONObject(i);
                            if (part != null && part.has("text")) {
                                if (sb.length() > 0) sb.append('\n');
                                sb.append(part.optString("text"));
                            }
                        }
                        return sb.toString().trim();
                    }
                }
            }
            return "无法解析内容: " + jsonStr;
        } catch (Exception e) {
            Log.e(TAG, "解析响应失败", e);
            return "解析错误";
        }
    }

    public static void analyzeText(String prompt, AiCallback callback) {
        if (isCancelled) {
            Log.d(TAG, "⏹️ AI请求已取消，跳过分析");
            callback.onFailure("AI 请求已取消，请重试");
            return;
        }

        if (API_KEY == null || API_KEY.trim().isEmpty()) {
            callback.onFailure("API Key 缺失");
            return;
        }

        try {
            JSONObject root = new JSONObject();
            root.put("model", TEXT_MODEL);

            JSONArray messages = new JSONArray();

            JSONObject systemMessage = new JSONObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", buildTextSystemPrompt());
            messages.put(systemMessage);

            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.put(userMessage);

            root.put("messages", messages);
            applyCommonOptions(root, 360);

            Log.d(TAG, "发送文字分析请求: model=" + TEXT_MODEL);
            enqueueRequest(root, callback, true);
        } catch (Exception e) {
            Log.e(TAG, "构造 JSON 失败", e);
            callback.onFailure("构造 JSON 失败");
        }
    }

    private static void enqueueRequest(JSONObject root, AiCallback callback, boolean allowStructuredFallback) {
        RequestBody body = RequestBody.create(root.toString(), JSON_MEDIA_TYPE);
        Request request = new Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        currentCall = client.newCall(request);
        currentCall.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                currentCall = null;
                if (isCancelled || call.isCanceled()) {
                    Log.d(TAG, "⏹️ AI请求已取消");
                    return;
                }
                Log.e(TAG, "网络请求失败", e);
                callback.onFailure("网络错误: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                currentCall = null;
                if (isCancelled || call.isCanceled()) {
                    Log.d(TAG, "⏹️ AI请求已取消，忽略响应");
                    return;
                }

                String responseStr = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    if (allowStructuredFallback && looksLikeStructuredOutputError(responseStr)) {
                        try {
                            JSONObject fallback = new JSONObject(root.toString());
                            fallback.remove("response_format");
                            Log.w(TAG, "response_format 不被接受，回退到普通 JSON 输出");
                            enqueueRequest(fallback, callback, false);
                            return;
                        } catch (Exception e) {
                            Log.e(TAG, "构造 structured fallback 失败", e);
                        }
                    }
                    Log.e(TAG, "API 错误: " + response.code() + " - " + responseStr);
                    callback.onFailure("API 错误: " + response.code());
                    return;
                }

                String resultText = parseResponse(responseStr);
                callback.onSuccess(resultText);
            }
        });
    }

    private static boolean looksLikeStructuredOutputError(String responseStr) {
        if (responseStr == null) return false;
        String lower = responseStr.toLowerCase();
        return lower.contains("response_format")
                || lower.contains("json_schema")
                || lower.contains("structured output");
    }
}
