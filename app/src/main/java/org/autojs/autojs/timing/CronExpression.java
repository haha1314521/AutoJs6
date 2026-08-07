package org.autojs.autojs.timing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.joda.time.DateTime;

import java.util.BitSet;

/**
 * 精简版 crontab 表达式解析器。
 *
 * 格式与 Linux crontab 一致, 五个字段用空格分隔:
 *
 *     分  时  日  月  周
 *     0   8   *  *   *        每天 8:00
 *     0   8,11,14,17,20 * * * 每天这 5 个整点
 *     30  9   *  *   1-5      工作日 9:30
 *     0   *&#47;3 *  *   *        每 3 小时(0,3,6...)
 *
 * 每个字段支持: 星号、数字、逗号列表、连字符范围、斜杠步长(可与前三者组合)。
 * 周字段 0 和 7 都表示周日。
 *
 * 没有引入第三方 cron 库, 因为只需要这点能力, 加个依赖不划算。
 */
public class CronExpression {

    private final BitSet mMinutes = new BitSet(60);
    private final BitSet mHours = new BitSet(24);
    private final BitSet mDaysOfMonth = new BitSet(32);
    private final BitSet mMonths = new BitSet(13);
    private final BitSet mDaysOfWeek = new BitSet(8);

    /** 日 和 周 是否都写了具体值 —— crontab 的传统语义是此时取"或" */
    private final boolean mDayOfMonthRestricted;
    private final boolean mDayOfWeekRestricted;

    private final String mExpression;

    private CronExpression(String expression, String[] fields) {
        mExpression = expression;
        parseField(fields[0], mMinutes, 0, 59);
        parseField(fields[1], mHours, 0, 23);
        parseField(fields[2], mDaysOfMonth, 1, 31);
        parseField(fields[3], mMonths, 1, 12);
        parseDayOfWeek(fields[4]);
        mDayOfMonthRestricted = !"*".equals(fields[2].trim());
        mDayOfWeekRestricted = !"*".equals(fields[4].trim());
    }

    /**
     * @return 解析成功返回对象, 表达式非法返回 null(调用方据此提示用户)
     */
    @Nullable
    public static CronExpression parse(@Nullable String expression) {
        if (expression == null) {
            return null;
        }
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] fields = trimmed.split("\\s+");
        if (fields.length != 5) {
            return null;
        }
        try {
            return new CronExpression(trimmed, fields);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isValid(@Nullable String expression) {
        return parse(expression) != null;
    }

    private static void parseField(String field, BitSet target, int min, int max) {
        for (String part : field.split(",")) {
            String item = part.trim();
            if (item.isEmpty()) {
                throw new IllegalArgumentException("empty part in: " + field);
            }
            int step = 1;
            int slash = item.indexOf('/');
            if (slash >= 0) {
                step = Integer.parseInt(item.substring(slash + 1).trim());
                if (step <= 0) {
                    throw new IllegalArgumentException("bad step: " + item);
                }
                item = item.substring(0, slash).trim();
            }
            int from, to;
            if ("*".equals(item)) {
                from = min;
                to = max;
            } else {
                int dash = item.indexOf('-');
                if (dash >= 0) {
                    from = Integer.parseInt(item.substring(0, dash).trim());
                    to = Integer.parseInt(item.substring(dash + 1).trim());
                } else {
                    from = Integer.parseInt(item);
                    // "5/2" 这种没有上界的写法, 按 crontab 语义补成 5-max
                    to = slash >= 0 ? max : from;
                }
            }
            if (from < min || to > max || from > to) {
                throw new IllegalArgumentException("out of range: " + item);
            }
            for (int i = from; i <= to; i += step) {
                target.set(i);
            }
        }
        if (target.isEmpty()) {
            throw new IllegalArgumentException("no value matched: " + field);
        }
    }

    private void parseDayOfWeek(String field) {
        BitSet raw = new BitSet(8);
        parseField(field, raw, 0, 7);
        for (int i = raw.nextSetBit(0); i >= 0; i = raw.nextSetBit(i + 1)) {
            // 0 和 7 都是周日, 统一存成 7(与 joda-time 的 DateTimeConstants.SUNDAY 一致)
            mDaysOfWeek.set(i == 0 ? 7 : i);
        }
    }

    private boolean matchesDate(DateTime time) {
        if (!mMonths.get(time.getMonthOfYear())) {
            return false;
        }
        boolean dayOfMonthHit = mDaysOfMonth.get(time.getDayOfMonth());
        boolean dayOfWeekHit = mDaysOfWeek.get(time.getDayOfWeek());
        if (mDayOfMonthRestricted && mDayOfWeekRestricted) {
            // crontab 语义: 两者都限制时取"或", 任一命中即可
            return dayOfMonthHit || dayOfWeekHit;
        }
        if (mDayOfMonthRestricted) {
            return dayOfMonthHit;
        }
        if (mDayOfWeekRestricted) {
            return dayOfWeekHit;
        }
        return true;
    }

    /**
     * 算出严格晚于 from 的下一个触发时刻。
     *
     * 逐天筛日期, 命中的日子里再找小时和分钟 —— 表达式再密也就几百次比较, 完全够快。
     *
     * @return 时间戳(毫秒); 四年内都不会触发时返回 -1
     */
    public long getNextTime(long from) {
        DateTime cursor = new DateTime(from)
                .withSecondOfMinute(0)
                .withMillisOfSecond(0)
                .plusMinutes(1);

        // 闰年周期是 4 年, 扫这么久还没命中就是表达式本身不可能满足(如 2 月 30 日)
        for (int day = 0; day < 366 * 4; day++) {
            if (matchesDate(cursor)) {
                for (int hour = cursor.getHourOfDay(); hour < 24; hour++) {
                    if (!mHours.get(hour)) {
                        continue;
                    }
                    int startMinute = (hour == cursor.getHourOfDay()) ? cursor.getMinuteOfHour() : 0;
                    for (int minute = startMinute; minute < 60; minute++) {
                        if (mMinutes.get(minute)) {
                            return cursor.withHourOfDay(hour).withMinuteOfHour(minute).getMillis();
                        }
                    }
                }
            }
            // 换到第二天零点继续找
            cursor = cursor.plusDays(1).withTimeAtStartOfDay();
        }
        return -1;
    }

    @NonNull
    @Override
    public String toString() {
        return mExpression;
    }

}
