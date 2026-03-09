package com.example.mindflow.utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FocusLocalRuleEngine {

    private static final Pattern DOMAIN_PATTERN = Pattern.compile("\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}\\b");

    public static final class RuleAssessment {
        public final String conclusion;
        public final String behavior;
        public final String reason;
        public final String[] evidence;
        public final int confidence;
        public final String suggestion;

        RuleAssessment(String conclusion, String behavior, String reason,
                       String[] evidence, int confidence, String suggestion) {
            this.conclusion = conclusion;
            this.behavior = behavior;
            this.reason = reason;
            this.evidence = evidence;
            this.confidence = confidence;
            this.suggestion = suggestion;
        }

        public String toJsonString() {
            try {
                JSONObject json = new JSONObject();
                json.put("conclusion", conclusion);
                json.put("behavior", behavior);
                json.put("reason", reason);
                JSONArray evidenceArray = new JSONArray();
                for (String item : evidence) {
                    evidenceArray.put(item);
                }
                json.put("evidence", evidenceArray);
                json.put("confidence", confidence);
                json.put("suggestion", suggestion);
                return json.toString();
            } catch (Exception e) {
                return "{\"conclusion\":\"UNSURE\",\"behavior\":\"本地规则异常\",\"reason\":\"信息不足：本地规则生成结果失败。\",\"evidence\":[\"规则引擎异常\"],\"confidence\":20,\"suggestion\":\"等待下一次页面分析。\"}";
            }
        }
    }

    private static final class PageSignals {
        final String appCategory;
        final String domain;
        final String domainCategory;
        final String pageTitle;
        final String searchQuery;
        final boolean hasCode;
        final boolean hasDocument;
        final boolean hasLearning;
        final boolean hasWorkComm;
        final boolean hasChat;
        final boolean hasWorkChat;
        final boolean hasCasualChat;
        final boolean hasFeed;
        final boolean hasVideo;
        final boolean hasShopping;
        final boolean hasFinance;
        final boolean hasMath;
        final boolean hasSearchResults;
        final boolean hasArticle;

        PageSignals(String appCategory, String domain, String domainCategory,
                    String pageTitle, String searchQuery,
                    boolean hasCode, boolean hasDocument, boolean hasLearning,
                    boolean hasWorkComm, boolean hasChat, boolean hasWorkChat, boolean hasCasualChat, boolean hasFeed,
                    boolean hasVideo, boolean hasShopping, boolean hasFinance,
                    boolean hasMath, boolean hasSearchResults, boolean hasArticle) {
            this.appCategory = appCategory;
            this.domain = domain;
            this.domainCategory = domainCategory;
            this.pageTitle = pageTitle;
            this.searchQuery = searchQuery;
            this.hasCode = hasCode;
            this.hasDocument = hasDocument;
            this.hasLearning = hasLearning;
            this.hasWorkComm = hasWorkComm;
            this.hasChat = hasChat;
            this.hasWorkChat = hasWorkChat;
            this.hasCasualChat = hasCasualChat;
            this.hasFeed = hasFeed;
            this.hasVideo = hasVideo;
            this.hasShopping = hasShopping;
            this.hasFinance = hasFinance;
            this.hasMath = hasMath;
            this.hasSearchResults = hasSearchResults;
            this.hasArticle = hasArticle;
        }
    }

    private FocusLocalRuleEngine() {
    }

    public static RuleAssessment analyze(String goal, String packageName, String appName, String screenText,
                                         String pageDomain, String pageTitle, String searchQuery) {
        FocusGoalInterpreter.GoalProfile profile = FocusGoalInterpreter.analyzeGoal(goal);
        PageSignals signals = collectSignals(packageName, appName, screenText, pageDomain, pageTitle, searchQuery);
        String behavior = deriveBehavior(appName, signals);

        if (isWorkGoal(profile.goalType)) {
            if (isChatApp(signals.appCategory) && signals.hasCasualChat) {
                return no(behavior, goal, signals,
                        "不符合目标：当前虽然处于聊天应用，但对话内容更像日常寒暄或娱乐闲聊，不是工作学习沟通。",
                        91);
            }
            if (signals.hasShopping || signals.hasFeed || signals.hasVideo
                    || matchesDomain(signals, "shopping", "social", "entertainment")) {
                return no(behavior, goal, signals,
                        "不符合目标：当前目标偏向工作/学习/查资料，但页面明显是购物、信息流或娱乐消费内容。",
                        92);
            }
            if (signals.hasWorkChat && isChatApp(signals.appCategory)) {
                return yes(behavior, goal, signals,
                        "符合目标：当前虽然处于聊天应用，但对话中出现需求、会议、文档、项目或学习任务等工作沟通信号。",
                        87);
            }
            if (signals.hasCode || signals.hasDocument || signals.hasLearning
                    || signals.hasWorkComm || signals.hasSearchResults || signals.hasArticle
                    || matchesDomain(signals, "knowledge")) {
                return yes(behavior, goal, signals,
                        "符合目标：页面内容包含代码、文档、课程资料、搜索结果或工作沟通信号，和当前任务直接相关。",
                        88);
            }
        }

        if ("utility_math".equals(profile.goalType)) {
            if ("utility_calc".equals(signals.appCategory) || signals.hasMath || signals.hasFinance) {
                return yes(behavior, goal, signals,
                        "符合目标：当前目标是计算/记账类工具任务，页面包含明显的计算、结果或账单信号。",
                        90);
            }
            if (signals.hasFeed || signals.hasVideo || signals.hasShopping
                    || matchesDomain(signals, "social", "entertainment")) {
                return no(behavior, goal, signals,
                        "不符合目标：当前目标是计算/记账，但页面呈现的是娱乐、社交或购物内容。",
                        92);
            }
        }

        if ("communication".equals(profile.goalType)) {
            if (signals.hasChat || "social".equals(signals.appCategory)
                    || "communication_work".equals(signals.appCategory)) {
                return yes(behavior, goal, signals,
                        "符合目标：当前目标是沟通回复，页面出现聊天、消息或工作沟通特征。",
                        84);
            }
            if (signals.hasMath || signals.hasShopping || signals.hasFeed || signals.hasVideo) {
                return no(behavior, goal, signals,
                        "不符合目标：当前目标是沟通回复，但页面内容更像计算、购物或娱乐浏览。",
                        90);
            }
        }

        if (isLeisureGoal(profile.goalType)) {
            if (signals.hasMath || signals.hasDocument || signals.hasLearning || signals.hasWorkComm
                    || signals.hasWorkChat
                    || "utility_calc".equals(signals.appCategory)
                    || "productivity_work".equals(signals.appCategory)
                    || "finance".equals(signals.appCategory)
                    || matchesDomain(signals, "knowledge", "finance")) {
                return no(behavior, goal, signals,
                        "不符合目标：当前目标是休闲放松，但页面内容明显偏向工具、工作、学习或金融操作。",
                        91);
            }
            if (signals.hasFeed || signals.hasVideo
                    || "entertainment".equals(signals.appCategory)
                    || "social".equals(signals.appCategory)
                    || matchesDomain(signals, "social", "entertainment")) {
                return yes(behavior, goal, signals,
                        "符合目标：当前目标是休闲放松，页面表现出明显的信息流、视频或社交浏览特征。",
                        84);
            }
        }

        if ("research_browsing".equals(profile.goalType)) {
            if (signals.hasShopping || signals.hasFeed || signals.hasVideo
                    || matchesDomain(signals, "shopping", "social", "entertainment")) {
                return no(behavior, goal, signals,
                        "不符合目标：当前目标是查资料，但页面更像购物、社交或娱乐浏览。",
                        91);
            }
            if (signals.hasSearchResults || signals.hasArticle || signals.hasDocument
                    || signals.hasLearning || matchesDomain(signals, "knowledge")) {
                return yes(behavior, goal, signals,
                        "符合目标：当前页面包含搜索结果、文章、文档或知识站点信号，符合查资料场景。",
                        88);
            }
        }

        if ("general_task".equals(profile.goalType)) {
            if (signals.hasShopping || signals.hasFeed || signals.hasVideo
                    || matchesDomain(signals, "shopping", "social", "entertainment")) {
                return no(behavior, goal, signals,
                        "不符合目标：默认窄范围任务下，页面明显呈现购物、社交或娱乐消费内容。",
                        88);
            }
            if (signals.hasCode || signals.hasDocument || signals.hasLearning
                    || signals.hasWorkComm || signals.hasMath || matchesDomain(signals, "knowledge")) {
                return yes(behavior, goal, signals,
                        "符合目标：默认窄范围任务下，页面出现明显的工具、文档、学习或知识内容信号。",
                        80);
            }
        }

        return null;
    }

    private static RuleAssessment yes(String behavior, String goal, PageSignals signals, String reason, int confidence) {
        return new RuleAssessment(
                "YES",
                behavior,
                reason + " 目标是“" + safe(goal) + "”，当前页面特征与之匹配。",
                buildEvidence(signals),
                confidence,
                "继续当前页面，保持任务聚焦。"
        );
    }

    private static RuleAssessment no(String behavior, String goal, PageSignals signals, String reason, int confidence) {
        return new RuleAssessment(
                "NO",
                behavior,
                reason + " 目标是“" + safe(goal) + "”，当前页面与目标语义偏离。",
                buildEvidence(signals),
                confidence,
                "返回与当前目标直接相关的页面或应用。"
        );
    }

    private static String[] buildEvidence(PageSignals signals) {
        List<String> evidence = new ArrayList<>();
        evidence.add("应用类别: " + signals.appCategory);
        if (signals.domain != null && !signals.domain.isEmpty()) {
            evidence.add("页面域名: " + signals.domain + " (" + signals.domainCategory + ")");
        } else if ("unknown".equals(signals.appCategory) || "browser_research".equals(signals.appCategory)) {
            evidence.add("页面属于浏览器/高歧义场景，需要看页面内容");
        }
        if (signals.pageTitle != null && !signals.pageTitle.isEmpty()) {
            evidence.add("页面标题: " + signals.pageTitle);
        } else if (signals.searchQuery != null && !signals.searchQuery.isEmpty()) {
            evidence.add("搜索词: " + signals.searchQuery);
        }
        if (signals.hasCode) evidence.add("检测到代码或技术文本");
        else if (signals.hasDocument) evidence.add("检测到文档或资料内容");
        else if (signals.hasLearning) evidence.add("检测到学习/论文/课程内容");
        else if (signals.hasWorkChat) evidence.add("聊天内容出现任务/会议/项目信号");
        else if (signals.hasCasualChat) evidence.add("聊天内容更像日常寒暄/娱乐闲聊");
        else if (signals.hasWorkComm) evidence.add("检测到工作沟通/待办/会议内容");
        else if (signals.hasShopping) evidence.add("检测到商品/下单/购物车内容");
        else if (signals.hasVideo || signals.hasFeed) evidence.add("检测到视频/推荐流/评论区内容");
        else if (signals.hasMath) evidence.add("检测到计算器/结果/公式内容");
        while (evidence.size() > 3) {
            evidence.remove(evidence.size() - 1);
        }
        return evidence.toArray(new String[0]);
    }

    private static String deriveBehavior(String appName, PageSignals signals) {
        if (signals.hasWorkChat) return "处理任务沟通";
        if (signals.hasCasualChat) return "闲聊/回复消息";
        if (signals.hasShopping) return "浏览商品/下单";
        if (signals.hasVideo || signals.hasFeed) return "刷信息流/视频";
        if (signals.hasMath) return "使用计算工具";
        if (signals.hasCode) return "阅读或编写代码";
        if (signals.hasLearning || signals.hasArticle) return "学习/阅读资料";
        if (signals.hasDocument) return "处理文档";
        if (signals.hasWorkComm) return "处理工作沟通";
        if (signals.hasChat) return "聊天/回复消息";
        if (signals.hasSearchResults) return "搜索资料";
        return "使用 " + safe(appName);
    }

    private static PageSignals collectSignals(String packageName, String appName, String screenText,
                                              String pageDomain, String pageTitle, String searchQuery) {
        String normalizedText = normalize(screenText);
        String normalizedTitle = normalize(pageTitle);
        String normalizedQuery = normalize(searchQuery);
        String mergedText = (normalizedText + " " + normalizedTitle + " " + normalizedQuery).trim();
        String appCategory = FocusGoalInterpreter.classifyApp(packageName, appName);
        String domain = normalize(pageDomain);
        if (domain.isEmpty()) {
            domain = extractDomain(mergedText);
        }
        String domainCategory = classifyDomain(domain);

        boolean hasCode = containsAny(mergedText, "public", "private", "function", "class", "import", "return", "stack overflow", "github");
        boolean hasDocument = containsAny(mergedText, "文档", "readme", "markdown", "接口文档", "api", "sdk", "教程", "guide");
        boolean hasLearning = containsAny(mergedText, "课程", "论文", "题目", "lecture", "chapter", "abstract", "背单词", "习题");
        boolean hasWorkComm = containsAny(mergedText, "会议", "审批", "待办", "工单", "需求", "客户", "jira", "项目", "日报", "周报", "排期", "接口");
        boolean hasChat = containsAny(mergedText, "发送", "消息", "聊天", "回复", "语音通话", "视频通话", "未读");
        boolean hasWorkChat = containsAny(mergedText, "需求", "会议", "文档", "项目", "排期", "接口", "提测", "上线", "版本", "bug", "修复", "review", "merge", "pr", "老师", "作业", "论文", "实验", "汇报");
        boolean hasCasualChat = containsAny(mergedText, "哈哈", "晚安", "早安", "吃饭", "睡觉", "周末", "开黑", "打游戏", "追剧", "在吗", "有空吗", "逛街");
        boolean hasFeed = containsAny(mergedText, "推荐", "热搜", "关注", "点赞", "评论区", "猜你喜欢", "为你推荐");
        boolean hasVideo = containsAny(mergedText, "短视频", "直播", "弹幕", "up主", "播放", "暂停");
        boolean hasShopping = containsAny(mergedText, "购物车", "立即购买", "下单", "优惠券", "商品详情", "加入购物车", "sku");
        boolean hasFinance = containsAny(mergedText, "余额", "转账", "账单", "银行卡", "理财", "基金", "支付", "收付款");
        boolean hasMath = containsAny(mergedText, "计算器", "总计", "结果", "函数", "平方", "根号", "sin", "cos", "tan");
        boolean hasSearchResults = containsAny(mergedText, "搜索结果", "相关结果", "百度一下", "google search", "result", "about") || !normalizedQuery.isEmpty();
        boolean hasArticle = containsAny(mergedText, "目录", "摘要", "references", "参考文献", "readme", "教程");

        return new PageSignals(appCategory, domain, domainCategory, safeOptional(pageTitle), safeOptional(searchQuery),
                hasCode, hasDocument, hasLearning,
                hasWorkComm, hasChat, hasWorkChat, hasCasualChat, hasFeed, hasVideo, hasShopping, hasFinance, hasMath,
                hasSearchResults, hasArticle);
    }

    private static boolean isWorkGoal(String goalType) {
        return "coding_work".equals(goalType)
                || "study".equals(goalType)
                || "office_work".equals(goalType)
                || "research_browsing".equals(goalType);
    }

    private static boolean isLeisureGoal(String goalType) {
        return "generic_leisure".equals(goalType)
                || "specific_leisure".equals(goalType);
    }

    private static boolean isChatApp(String appCategory) {
        return "social".equals(appCategory) || "communication_work".equals(appCategory);
    }

    private static boolean matchesDomain(PageSignals signals, String... categories) {
        if (signals.domainCategory == null || signals.domainCategory.isEmpty()) {
            return false;
        }
        for (String category : categories) {
            if (signals.domainCategory.equals(category)) {
                return true;
            }
        }
        return false;
    }

    private static String extractDomain(String normalizedText) {
        if (normalizedText == null || normalizedText.isEmpty()) {
            return "";
        }
        Matcher matcher = DOMAIN_PATTERN.matcher(normalizedText);
        while (matcher.find()) {
            String domain = matcher.group();
            if (domain.contains("android") || domain.contains("miui") || domain.contains("huawei")) {
                continue;
            }
            return domain;
        }
        return "";
    }

    private static String classifyDomain(String domain) {
        String normalized = normalize(domain);
        if (normalized.isEmpty()) {
            return "unknown";
        }
        if (containsAny(normalized, "github", "stackoverflow", "developer.android", "docs.", "arxiv", "wikipedia", "kaggle", "leetcode", "coursera", "scholar")) {
            return "knowledge";
        }
        if (containsAny(normalized, "taobao", "tmall", "jd.", "pinduoduo", "amazon")) {
            return "shopping";
        }
        if (containsAny(normalized, "weibo", "xiaohongshu", "instagram", "facebook", "twitter", "x.com", "reddit")) {
            return "social";
        }
        if (containsAny(normalized, "bilibili", "douyin", "tiktok", "youtube", "netflix", "iqiyi", "youku", "zhihu")) {
            return "entertainment";
        }
        if (containsAny(normalized, "alipay", "bank", "wallet")) {
            return "finance";
        }
        return "unknown";
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

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(String text) {
        return text == null || text.trim().isEmpty() ? "未知内容" : text.trim();
    }

    private static String safeOptional(String text) {
        return text == null ? "" : text.trim();
    }
}
