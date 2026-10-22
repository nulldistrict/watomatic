package com.parishod.watomatic.service;

import android.app.Notification;
import android.service.notification.StatusBarNotification;
import android.text.SpannableString;

import com.parishod.watomatic.model.preferences.PreferencesManager;
import com.parishod.watomatic.model.utils.ContactsHelper;
import com.parishod.watomatic.model.utils.DbUtils;
import com.parishod.watomatic.model.utils.NotificationUtils;

import java.util.Set;

import static java.lang.Math.max;

/**
 * Encapsulates the decision logic for whether to auto-reply to a notification.
 * Extracted from NotificationService to enable unit testing without requiring
 * a NotificationListenerService instance.
 */
public class NotificationReplyDecider {

    // Do not reply to consecutive notifications from same person/group within this window
    private static final int MIN_DELAY_BETWEEN_REPLIES_MS = 10 * 1000;

    // Apps that should only auto-reply to direct chat messages and skip other
    // notification types (likes, mentions, retweets, replies to your posts).
    // X (Twitter) sends both DMs and social engagement notifications; only DMs
    // should receive an auto-reply.
    private static final Set<String> CHAT_ONLY_PACKAGES = Set.of("com.twitter.android");

    private final PreferencesManager preferencesManager;
    private final DbUtils dbUtils;
    private final ContactsHelper contactsHelper;

    public NotificationReplyDecider(PreferencesManager preferencesManager,
                                    DbUtils dbUtils,
                                    ContactsHelper contactsHelper) {
        this.preferencesManager = preferencesManager;
        this.dbUtils = dbUtils;
        this.contactsHelper = contactsHelper;
    }

    /**
     * Checks all preconditions for replying: service enabled, supported app,
     * fresh notification, group rules, and cooldown.
     */
    public boolean canReply(StatusBarNotification sbn) {
        return isServiceEnabled() &&
                isSupportedPackage(sbn) &&
                NotificationUtils.isNewNotification(sbn) &&
                isGroupMessageAndReplyAllowed(sbn) &&
                isChatMessageOrNotChatOnlyApp(sbn) &&
                canSendReplyNow(sbn);
    }

    /**
     * Checks contact-based reply filters (whitelist/blacklist).
     * Returns true if we should reply to this sender.
     */
    public boolean shouldReply(StatusBarNotification sbn) {
        boolean isGroup = sbn.getNotification().extras.getBoolean("android.isGroupConversation");

        if (preferencesManager.isContactReplyEnabled() && !isGroup) {
            String senderName = sbn.getNotification().extras.getString("android.title");

            boolean isNameSelected =
                    (contactsHelper.hasContactPermission()
                            && preferencesManager.getReplyToNames().contains(senderName)) ||
                            preferencesManager.getCustomReplyNames().contains(senderName);

            if ((isNameSelected && preferencesManager.isContactReplyBlacklistMode()) ||
                    (!isNameSelected && !preferencesManager.isContactReplyBlacklistMode())) {
                return false;
            }
        }

        return true;
    }

    boolean isServiceEnabled() {
        return preferencesManager.isServiceEnabled();
    }

    boolean isSupportedPackage(StatusBarNotification sbn) {
        return preferencesManager.getEnabledApps().contains(sbn.getPackageName());
    }

    /**
     * Some apps (currently X) post both direct messages and other engagement
     * notifications such as likes, mentions, retweets or replies to your posts.
     * Those apps are chat-only: we only auto-reply when the notification is a
     * direct chat message. Other apps are unaffected and always pass.
     *
     * X categorizes direct messages as {@code Notification.CATEGORY_MESSAGE} and
     * social engagement (likes, mentions, retweets, post replies) as
     * {@code Notification.CATEGORY_SOCIAL}. When a category is present we rely on
     * it; when it is absent we allow the reply so behaviour matches the previous,
     * category-agnostic path rather than silently dropping real DMs.
     */
    boolean isChatMessageOrNotChatOnlyApp(StatusBarNotification sbn) {
        if (!CHAT_ONLY_PACKAGES.contains(sbn.getPackageName())) {
            return true;
        }
        String category = sbn.getNotification().category;
        if (category == null || category.isEmpty()) {
            return true;
        }
        return Notification.CATEGORY_MESSAGE.equals(category);
    }

    boolean canSendReplyNow(StatusBarNotification sbn) {
        String title = NotificationUtils.getTitle(sbn);
        String selfDisplayName = sbn.getNotification().extras.getString("android.selfDisplayName");
        if (title != null && title.equalsIgnoreCase(selfDisplayName)) {
            return false;
        }
        long timeDelay = preferencesManager.getAutoReplyDelay();
        return (System.currentTimeMillis() - dbUtils.getLastRepliedTime(sbn.getPackageName(), title)
                >= max(timeDelay, MIN_DELAY_BETWEEN_REPLIES_MS));
    }

    boolean isGroupMessageAndReplyAllowed(StatusBarNotification sbn) {
        String rawTitle = NotificationUtils.getTitleRaw(sbn);
        SpannableString rawText = SpannableString.valueOf(
                "" + sbn.getNotification().extras.get("android.text"));

        boolean isPossiblyAnImageGrpMsg =
                ((rawTitle != null) && (": ".contains(rawTitle) || "@ ".contains(rawTitle)))
                        && ((rawText != null) && rawText.toString().contains("\uD83D\uDCF7"));

        if (!sbn.getNotification().extras.getBoolean("android.isGroupConversation")) {
            return !isPossiblyAnImageGrpMsg;
        } else {
            return preferencesManager.isGroupReplyEnabled();
        }
    }
}
