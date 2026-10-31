package com.waibao.pingdian.complaint.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 投诉举证与裁定参数（对应 spec「默认参数（可配置）」）。
 */
@ConfigurationProperties(prefix = "complaint")
public class ComplaintProperties {

    /** 举证窗口（小时），默认 48。 */
    private int deadlineHours = 48;

    /** 截止前提醒（小时），Task 11 使用。 */
    private int reminderHoursBefore = 24;

    /** 裁定目标时长（小时），超时只标记 overdue，不阻断。 */
    private int rulingTargetHours = 48;

    /** 同一公司同期投诉判定窗口（天），默认 7。 */
    private int sameCompanyWindowDays = 7;

    /** 同一公司同期投诉触发双人复核的条数阈值。 */
    private int sameCompanyThreshold = 3;

    /** 拟下架已发布超过该天数的点评需双人复核。 */
    private int removalAgeDays = 30;

    public int getDeadlineHours() {
        return deadlineHours;
    }

    public void setDeadlineHours(int deadlineHours) {
        this.deadlineHours = deadlineHours;
    }

    public int getReminderHoursBefore() {
        return reminderHoursBefore;
    }

    public void setReminderHoursBefore(int reminderHoursBefore) {
        this.reminderHoursBefore = reminderHoursBefore;
    }

    public int getRulingTargetHours() {
        return rulingTargetHours;
    }

    public void setRulingTargetHours(int rulingTargetHours) {
        this.rulingTargetHours = rulingTargetHours;
    }

    public int getSameCompanyWindowDays() {
        return sameCompanyWindowDays;
    }

    public void setSameCompanyWindowDays(int sameCompanyWindowDays) {
        this.sameCompanyWindowDays = sameCompanyWindowDays;
    }

    public int getSameCompanyThreshold() {
        return sameCompanyThreshold;
    }

    public void setSameCompanyThreshold(int sameCompanyThreshold) {
        this.sameCompanyThreshold = sameCompanyThreshold;
    }

    public int getRemovalAgeDays() {
        return removalAgeDays;
    }

    public void setRemovalAgeDays(int removalAgeDays) {
        this.removalAgeDays = removalAgeDays;
    }
}
