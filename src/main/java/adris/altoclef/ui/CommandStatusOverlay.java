package adris.altoclef.ui;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

public class CommandStatusOverlay {

    //For the ingame timer
    private long _timeRunning;
    private long _lastTime = 0;
    private DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.from(ZoneOffset.of("+00:00"))); // The date formatter

    public void render(AltoClef mod, GuiGraphicsExtractor extractor) {
        if (mod.getModSettings().shouldShowTaskChain()) {
            List<Task> tasks = Collections.emptyList();
            if (mod.getTaskRunner().getCurrentTaskChain() != null) {
                tasks = mod.getTaskRunner().getCurrentTaskChain().getTasks();
            }

            int color = 0xFFFFFFFF;
            drawTaskChain(Minecraft.getInstance().font, 0, 0, color, false,
                    extractor, tasks, mod);
        }
    }

    private void drawTaskChain(Font font, int x, int y, int color, boolean shadow, GuiGraphicsExtractor extractor, List<Task> tasks, AltoClef mod) {
        if (tasks.size() == 0) {
            extractor.text(font, " (no task running) ", x, y, color, shadow);
            if (_lastTime + 10000 < Instant.now().toEpochMilli() && mod.getModSettings().shouldShowTimer()) {//if it doesn't run any task in 10 secs
                _timeRunning = Instant.now().toEpochMilli();//reset the timer
            }
        } else {
            int fontHeight = font.lineHeight;
            if (mod.getModSettings().shouldShowTimer()) { //If it's enabled
                _lastTime = Instant.now().toEpochMilli(); //keep the last time for the timer reset
                String _realTime = DATE_TIME_FORMATTER.format(Instant.now().minusMillis(_timeRunning)); //Format the running time to string
                extractor.text(font, "<" + _realTime + ">", x, y, color, shadow);
                x += 8;//Do the same thing to list the tasks
                y += fontHeight + 2;
            }
            if (tasks.size() > 10) {
                for (int i = 0; i < tasks.size(); ++i) {
                    // Skip over the next tasks
                    if (i == 0 || i > tasks.size() - 10) {
                        extractor.text(font, tasks.get(i).toString(), x, y, color, shadow);
                    } else if (i == 1) {
                        extractor.text(font, " ... ", x, y, color, shadow);
                    } else {
                        continue;
                    }
                    x += 8;
                    y += fontHeight + 2;
                }
            } else {
                if (!tasks.isEmpty()) {
                    for (Task task : tasks) {
                        extractor.text(font, task.toString(), x, y, color, shadow);
                        x += 8;
                        y += fontHeight + 2;
                    }
                }
            }

        }
    }
}
