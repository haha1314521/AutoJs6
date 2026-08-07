package org.autojs.autojs.storage.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.autojs.autojs.timing.TimedTask;

public class TimedTaskDatabase extends Database<TimedTask> {

    private static final int VERSION = 4;   /* 4: 新增 cron 列 */
    private static final String NAME = "TimedTaskDatabase";

    public TimedTaskDatabase(Context context) {
        super(new SQLHelper(context), TimedTask.TABLE);
    }

    @Override
    protected ContentValues asContentValues(TimedTask model) {
        ContentValues values = new ContentValues();
        values.put("time", model.getTimeFlag());
        values.put("scheduled", model.isScheduled());
        values.put("delay", model.getDelay());
        values.put("interval", model.getInterval());
        values.put("loop_times", model.getLoopTimes());
        values.put("millis", model.getMillis());
        values.put("script_path", model.getScriptPath());
        values.put("cron", model.getCron());
        return values;
    }

    @Override
    protected TimedTask createModelFromCursor(Cursor cursor) {
        TimedTask task = new TimedTask();
        task.setId(cursor.getLong(0));
        task.setTimeFlag(cursor.getLong(1));
        task.setScheduled(cursor.getInt(2) != 0);
        task.setDelay(cursor.getLong(3));
        task.setInterval(cursor.getLong(4));
        task.setLoopTimes(cursor.getInt(5));
        task.setMillis(cursor.getLong(6));
        task.setScriptPath(cursor.getString(7));
        // 老库升上来的行没有这一列时, getColumnIndex 返回 -1
        int cronIndex = cursor.getColumnIndex("cron");
        if (cronIndex >= 0) {
            task.setCron(cursor.getString(cronIndex));
        }
        return task;
    }

    private static class SQLHelper extends SQLiteOpenHelper {

        public SQLHelper(Context context) {
            super(context, NAME + ".db", null, VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE `" + TimedTask.TABLE + "`(" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "`time` INTEGER, " +
                    "`scheduled` INTEGER, " +
                    "`delay` INTEGER, " +
                    "`interval` INTEGER, " +
                    "`loop_times` INTEGER, " +
                    "`millis` INTEGER, " +
                    "`script_path` TEXT, " +
                    "`cron` TEXT);");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // 原本是空实现, 加列必须在这里补迁移, 否则老用户升级后查询直接报错
            if (oldVersion < 4) {
                try {
                    db.execSQL("ALTER TABLE `" + TimedTask.TABLE + "` ADD COLUMN `cron` TEXT;");
                } catch (Exception ignored) {
                    /* 列已存在则忽略 */
                }
            }
        }
    }

}
