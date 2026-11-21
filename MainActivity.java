package com.aimruler;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(48, 96, 48, 48);
        TextView tv = new TextView(this);
        tv.setTextSize(16);
        tv.setText("Модули танка: схема примерного расположения модулей поверх игры.\n\n"
            + "Вручную: схему вы перетаскиваете на танк сами. Игру не нагружает.\n"
            + "Авто: приложение записывает экран, находит над врагом красную метку и само ставит схему под неё. Кадры снижаются.\n\n"
            + "Настройка авто-режима (один раз за бой):\n"
            + "1. Нажмите «Цвет», наведите кольцо на красную метку врага (полоску здоровья) и нажмите «Взять».\n"
            + "2. Если схема не появляется или цепляется не за то, меняйте «Допуск−» и «Допуск+».\n"
            + "3. Размер и разворот схемы подбираются вручную: «+», «−», «Зеркало». Метка не показывает, как далеко и как повёрнут танк.\n\n"
            + "Остальное: «Тип» класс танка, «Проз» прозрачность, «Закрепить» и «Двигать» перехват касаний, «Скрыть», «Выход».\n\n"
            + "Цвета: красный боеукладка, оранжевый двигатель, жёлтый топливо, синий экипаж, серый гусеницы. Схема общая для класса и не точная, сверяйтесь с игрой. Используйте только в тренировке с ботами.");
        Button on = new Button(this); on.setText("Включить вручную");
        Button onA = new Button(this); onA.setText("Включить авто");
        Button off = new Button(this); off.setText("Выключить");
        on.setOnClickListener(v -> {
            if (!prepare()) return;
            Intent i = new Intent(this, OverlayService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            moveTaskToBack(true);
        });
        onA.setOnClickListener(v -> {
            if (!prepare()) return;
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), 100);
        });
        off.setOnClickListener(v -> stopService(new Intent(this, OverlayService.class)));
        ll.addView(tv); ll.addView(on); ll.addView(onA); ll.addView(off);
        ScrollView sv = new ScrollView(this); sv.addView(ll);
        setContentView(sv);
    }

    boolean prepare() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "Разрешите показ поверх других приложений и нажмите кнопку снова", Toast.LENGTH_LONG).show();
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        return true;
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 100 && res == RESULT_OK && data != null) {
            Intent i = new Intent(this, OverlayService.class);
            i.putExtra("code", res);
            i.putExtra("data", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            moveTaskToBack(true);
        }
    }
}
