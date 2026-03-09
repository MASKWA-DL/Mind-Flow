package com.example.mindflow.utils;

import java.util.Locale;

public final class FocusGoalInterpreter {

    public static final class GoalProfile {
        public final String goalType;
        public final String scope;
        public final String normalizedIntent;
        public final String[] allowedCategories;
        public final String[] discouragedCategories;
        public final String ruleSummary;

        GoalProfile(String goalType, String scope, String normalizedIntent,
                    String[] allowedCategories, String[] discouragedCategories,
                    String ruleSummary) {
            this.goalType = goalType;
            this.scope = scope;
            this.normalizedIntent = normalizedIntent;
            this.allowedCategories = allowedCategories;
            this.discouragedCategories = discouragedCategories;
            this.ruleSummary = ruleSummary;
        }
    }

    private FocusGoalInterpreter() {
    }

    public static GoalProfile analyzeGoal(String goal) {
        String normalizedGoal = normalize(goal);

        if (containsAny(normalizedGoal, "计算器", "计算", "算题", "数学", "记账", "报销", "汇率", "税")) {
            return new GoalProfile(
                    "utility_math",
                    "narrow",
                    "使用计算器或类似工具完成计算、做题、记账等明确工具任务",
                    new String[]{"utility_calc", "learning", "finance"},
                    new String[]{"entertainment", "social", "shopping", "productivity_work"},
                    "目标是工具任务时，只应放行与该工具直接相关的界面；娱乐、社交和普通浏览都应视为偏离目标。"
            );
        }

        if (containsAny(normalizedGoal, "写代码", "编程", "开发", "debug", "调试", "代码")) {
            return new GoalProfile(
                    "coding_work",
                    "narrow",
                    "进行编程、调试、阅读技术文档等开发相关任务",
                    new String[]{"productivity_work", "browser_research", "learning", "communication_work", "utility_calc"},
                    new String[]{"entertainment", "shopping", "social"},
                    "代码任务只应包含开发、查资料、工作沟通等相关行为；刷视频、购物、闲聊默认算偏离。"
            );
        }

        if (containsAny(normalizedGoal, "学习", "复习", "作业", "上课", "背单词", "英语", "阅读", "论文", "做题")) {
            return new GoalProfile(
                    "study",
                    "narrow",
                    "学习、复习、做题、阅读课程或论文内容",
                    new String[]{"learning", "browser_research", "productivity_work", "utility_calc"},
                    new String[]{"entertainment", "shopping", "social"},
                    "学习目标应优先放行课程、资料、笔记和必要工具；社交娱乐和购物通常属于偏离。"
            );
        }

        if (containsAny(normalizedGoal, "工作", "办公", "文档", "写作", "汇报", "总结", "会议", "邮件", "word", "excel", "ppt")) {
            return new GoalProfile(
                    "office_work",
                    "narrow",
                    "处理工作文档、会议、邮件、汇报等办公事务",
                    new String[]{"productivity_work", "browser_research", "communication_work", "utility_calc"},
                    new String[]{"entertainment", "shopping", "social"},
                    "工作目标应围绕办公、沟通和资料检索；纯娱乐和无关社交默认算偏离。"
            );
        }

        if (containsAny(normalizedGoal, "微信", "qq", "聊天", "消息", "回复", "短信", "电话", "拨号", "联系")) {
            return new GoalProfile(
                    "communication",
                    "narrow",
                    "围绕即时通讯进行沟通回复，不代表手机上的任何操作都算命中目标",
                    new String[]{"social", "communication_work"},
                    new String[]{"entertainment", "shopping", "utility_calc"},
                    "沟通类目标只覆盖消息和对话相关操作，计算器、设置、支付、视频娱乐不应算命中。"
            );
        }

        if (containsAny(normalizedGoal, "抖音", "短视频", "b站", "bilibili", "小红书", "看视频", "看剧", "电影", "游戏", "听歌", "微博", "知乎")) {
            return new GoalProfile(
                    "specific_leisure",
                    "narrow",
                    "进行明确的娱乐放松行为，如短视频、视频、游戏、音乐",
                    new String[]{"entertainment", "social", "browser_casual"},
                    new String[]{"utility_calc", "productivity_work", "learning", "finance"},
                    "明确娱乐目标只覆盖对应的休闲行为，不代表计算器、工作学习工具也算命中目标。"
            );
        }

        if (containsAny(normalizedGoal, "玩手机", "刷手机", "休息", "放松", "摸鱼")) {
            return new GoalProfile(
                    "generic_leisure",
                    "broad",
                    "泛娱乐或放松型手机使用，通常指社交、视频、资讯、游戏等休闲行为，而不是任意手机操作",
                    new String[]{"entertainment", "social", "browser_casual"},
                    new String[]{"utility_calc", "productivity_work", "learning", "finance", "system_settings"},
                    "像“玩手机/刷手机/休息”这类目标，不应理解成手机上的任何行为都算命中；计算器、支付、工作学习、系统设置通常应判为偏离。"
            );
        }

        if (containsAny(normalizedGoal, "浏览器", "网页", "查资料", "搜索", "搜资料")) {
            return new GoalProfile(
                    "research_browsing",
                    "narrow",
                    "通过浏览器或网页查资料、搜索、阅读与任务相关的信息",
                    new String[]{"browser_research", "learning", "productivity_work"},
                    new String[]{"entertainment", "shopping", "social"},
                    "查资料目标应以资料检索和阅读为主，信息流闲逛、购物和纯社交默认算偏离。"
            );
        }

        return new GoalProfile(
                "general_task",
                "narrow",
                "围绕用户描述的具体任务进行判断，只有直接相关的行为才算命中目标",
                new String[]{"productivity_work", "learning", "browser_research", "communication_work", "utility_calc", "finance"},
                new String[]{"entertainment", "social", "shopping"},
                "默认按任务字面含义做窄范围判断，不要把“正在用手机”本身当成命中目标。"
        );
    }

    public static String classifyApp(String packageName, String appName) {
        String normalized = normalize(packageName) + " " + normalize(appName);

        if (containsAny(normalized, "calculator", "calc", "计算器", "算器")) {
            return "utility_calc";
        }
        if (containsAny(normalized, "chrome", "browser", "firefox", "edge", "opera", "网页", "浏览器")) {
            return "browser_research";
        }
        if (containsAny(normalized, "douyin", "tiktok", "bilibili", "kuaishou", "xiaohongshu", "youtube", "netflix", "music", "video", "game", "weibo", "zhihu")) {
            return "entertainment";
        }
        if (containsAny(normalized, "taobao", "jd", "pinduoduo", "amazon", "shopping", "mall")) {
            return "shopping";
        }
        if (containsAny(normalized, "wechat", "tencent.mm", "qq", "telegram", "whatsapp", "line", "signal")) {
            return "social";
        }
        if (containsAny(normalized, "dingtalk", "rimet", "lark", "feishu", "wework", "slack", "teams")) {
            return "communication_work";
        }
        if (containsAny(normalized, "notion", "evernote", "docs", "sheets", "slides", "office", "word", "excel", "ppt", "wps", "notes", "mail", "gmail")) {
            return "productivity_work";
        }
        if (containsAny(normalized, "anki", "coursera", "classroom", "dictionary", "read", "kindle", "study", "learn")) {
            return "learning";
        }
        if (containsAny(normalized, "alipay", "wallet", "bank", "pay", "finance")) {
            return "finance";
        }
        if (containsAny(normalized, "settings", "systemui", "launcher", "calendar", "deskclock", "contacts", "mms", "message")) {
            return "system_settings";
        }
        return "unknown";
    }

    public static boolean isPackageLikelyRelevant(String goal, String packageName, String appName) {
        GoalProfile profile = analyzeGoal(goal);
        String appCategory = classifyApp(packageName, appName);

        if (isExplicitGoalMatch(goal, packageName, appName)) {
            return true;
        }
        if (contains(profile.discouragedCategories, appCategory)) {
            return false;
        }
        if (contains(profile.allowedCategories, appCategory)) {
            return true;
        }
        return "broad".equals(profile.scope);
    }

    public static boolean shouldFallbackToUnsure(String goal, String packageName, String appName) {
        GoalProfile profile = analyzeGoal(goal);
        String appCategory = classifyApp(packageName, appName);
        if (isExplicitGoalMatch(goal, packageName, appName)) {
            return false;
        }
        if ("unknown".equals(appCategory)) {
            return true;
        }
        if ("browser_research".equals(appCategory)) {
            return "generic_leisure".equals(profile.goalType)
                    || "specific_leisure".equals(profile.goalType)
                    || "general_task".equals(profile.goalType);
        }
        return false;
    }

    public static String buildGoalRuleBlock(String goal) {
        GoalProfile profile = analyzeGoal(goal);
        return "【目标语义】\n" +
                "- goal_type: " + profile.goalType + "\n" +
                "- scope: " + profile.scope + "\n" +
                "- normalized_intent: " + profile.normalizedIntent + "\n" +
                "- allowed_categories: " + join(profile.allowedCategories) + "\n" +
                "- discouraged_categories: " + join(profile.discouragedCategories) + "\n" +
                "- special_rule: " + profile.ruleSummary + "\n";
    }

    public static String buildFallbackReason(String goal, String packageName, String appName, boolean focused) {
        GoalProfile profile = analyzeGoal(goal);
        String appCategory = classifyApp(packageName, appName);
        if (focused) {
            return "符合目标：当前仅依据应用类别做保守判断，" + appCategory + " 与目标语义较为匹配。";
        }
        return "不符合目标：目标语义为“" + profile.normalizedIntent + "”，但当前应用类别是 " +
                appCategory + "，按规则通常属于偏离目标的操作。";
    }

    private static boolean isExplicitGoalMatch(String goal, String packageName, String appName) {
        String normalizedGoal = normalize(goal);
        String normalizedPackage = normalize(packageName);
        String normalizedApp = normalize(appName);

        if (normalizedGoal.isEmpty()) {
            return false;
        }
        return (!normalizedPackage.isEmpty() && normalizedGoal.contains(normalizedPackage))
                || (!normalizedApp.isEmpty() && normalizedGoal.contains(normalizedApp))
                || (containsAny(normalizedGoal, "计算器") && containsAny(normalizedPackage + " " + normalizedApp, "calculator", "calc", "计算器"))
                || (containsAny(normalizedGoal, "微信") && containsAny(normalizedPackage + " " + normalizedApp, "wechat", "tencent.mm"))
                || (containsAny(normalizedGoal, "qq") && containsAny(normalizedPackage + " " + normalizedApp, "qq"))
                || (containsAny(normalizedGoal, "浏览器", "网页") && containsAny(normalizedPackage + " " + normalizedApp, "browser", "chrome", "firefox", "edge"));
    }

    private static boolean contains(String[] values, String target) {
        if (values == null || target == null) {
            return false;
        }
        for (String value : values) {
            if (target.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String text, String... keywords) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String join(String[] values) {
        if (values == null || values.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(values[i]);
        }
        return builder.toString();
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT);
    }
}
