package local.station.remote;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** Station-owned schedules. Rendering, polling and phone countdowns never trigger an operation. */
final class SchedulePanel extends LinearLayout {
    interface Host {
        void request(String command,BluetoothRemoteClient.Callback callback);
        void refresh();
    }
    private static final int INK=0xff202032,SUB=0xff777384,PURPLE=0xff7560ea,LILAC=0xffeeebff,RED=0xffb83d5b;
    private final Host host;
    private final TextView timezone,notice,timersTab,alarmsTab,add,reload;
    private final LinearLayout presets,entries;
    private final List<Control> controls=new ArrayList<>();
    private final List<Counter> counters=new ArrayList<>();
    private final List<TextView> presetButtons=new ArrayList<>();
    private ScheduleModel.Snapshot snapshot;
    private AlertDialog editor,confirmation;
    private TimePickerDialog timePicker;
    private boolean connected,supported,statusKnown,fresh,listPending,actionPending,disposed,alarms;
    private String revision="",attemptedRevision="";
    private int epoch;
    private long receivedAt;

    SchedulePanel(Context context,Host host) {
        super(context); this.host=host; setOrientation(VERTICAL);
        timezone=label("—",11,SUB,false);timezone.setGravity(Gravity.RIGHT);timezone.setPadding(0,0,dp(3),dp(12));addView(timezone);
        LinearLayout segments=row(); segments.setPadding(dp(4),dp(4),dp(4),dp(4)); segments.setBackground(shape(0xffeeedf4,18));
        timersTab=button("Таймеры",()->select(false),false); alarmsTab=button("Будильники",()->select(true),false);
        segments.addView(timersTab,new LayoutParams(0,-2,1)); segments.addView(alarmsTab,new LayoutParams(0,-2,1)); addView(segments);
        presets=row(); presets.setPadding(0,dp(12),0,0);
        for (int minutes:new int[]{5,10,15}) {
            TextView preset=button(minutes+" мин",()->timerEditor(minutes*60),false); presetButtons.add(preset);
            LayoutParams p=new LayoutParams(0,-2,1); p.setMargins(dp(2),0,dp(2),0); presets.addView(preset,p);
        }
        addView(presets);
        LinearLayout actions=row(); actions.setPadding(0,dp(9),0,dp(7));
        add=button("＋ Таймер",()->{if(alarms)alarmEditor(null);else timerEditor(0);},true);
        actions.addView(add,new LayoutParams(0,-2,1));
        reload=button("Обновить",this::refreshSchedules,false);
        LayoutParams reloadParams=new LayoutParams(-2,-2); reloadParams.leftMargin=dp(8); actions.addView(reload,reloadParams); addView(actions);
        notice=label("Нет связи",12,SUB,false);
        notice.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        notice.setPadding(dp(3),dp(5),dp(3),dp(10)); addView(notice);
        entries=new LinearLayout(context); entries.setOrientation(VERTICAL); addView(entries);
        select(false); updateControls();
    }

    void connection(boolean value) {
        if (disposed) return;
        if (!value) {
            if (connected||statusKnown||listPending||actionPending) epoch++;
            connected=false; fresh=false; statusKnown=false; listPending=false; actionPending=false;
            revision=""; attemptedRevision=""; closeDialogs();
            setNotice("Нет связи");
        } else if (!connected) { connected=true; fresh=false; attemptedRevision=""; }
        updateControls();
    }

    void status(JSONObject status) {
        if (disposed||!connected||status==null) return;
        JSONObject caps=status.optJSONObject("capabilities");
        boolean healthy=caps!=null&&caps.optBoolean("schedules");
        if (!healthy&&supported) { epoch++; listPending=false; actionPending=false; attemptedRevision=""; }
        supported=healthy; statusKnown=true;
        revision=status.optString("scheduleRevision","");
        if (!supported) {
            fresh=false; setNotice("Часы Станции недоступны");
            closeDialogs(); updateControls(); return;
        }
        if (revision.isEmpty()) { fresh=false; setNotice("Станция не передала состояние часов"); updateControls(); return; }
        if (snapshot==null||!revision.equals(snapshot.revision)) fresh=false;
        if (!listPending&&!actionPending&&!revision.equals(attemptedRevision)
                &&(snapshot==null||!revision.equals(snapshot.revision)||!fresh)) load();
        updateControls();
    }

    /** Read-only explicit reload; repeated status polls otherwise load only a changed revision. */
    public void refreshSchedules() {
        if (disposed||!connected||!statusKnown||!supported||listPending||actionPending) return;
        attemptedRevision=""; load();
    }

    private void load() {
        if (!connected||!supported||disposed||listPending||actionPending) return;
        listPending=true; attemptedRevision=revision; setNotice("Обновляю часы…"); updateControls();
        final int requestEpoch=epoch;
        host.request(ScheduleModel.command(ScheduleModel.list()),(result,error)->{
            if (disposed||requestEpoch!=epoch||!connected||!supported) return;
            listPending=false;
            if (error!=null||result==null) { fresh=false; setNotice(ScheduleModel.error(error)); updateControls(); return; }
            try { accept(result); setNotice(""); }
            catch (IllegalArgumentException invalid) { fresh=false; setNotice("Неполное расписание · обнови"); }
            updateControls();
        });
    }

    private void accept(JSONObject result) {
        snapshot=new ScheduleModel.Snapshot(result); receivedAt=SystemClock.elapsedRealtime();
        revision=snapshot.revision; attemptedRevision=revision; fresh=true;
        timezone.setText("Europe/Moscow".equals(snapshot.timezone)?"Москва · UTC+3":snapshot.timezone); render();
    }

    private void select(boolean value) {
        alarms=value; presets.setVisibility(value?GONE:VISIBLE); add.setText(value?"＋ Будильник":"＋ Таймер");
        timersTab.setBackground(shape(value?0:0xffffffff,14)); alarmsTab.setBackground(shape(value?0xffffffff:0,14));
        timersTab.setTextColor(value?SUB:PURPLE); alarmsTab.setTextColor(value?PURPLE:SUB);
        timersTab.setSelected(!value); alarmsTab.setSelected(value); render();
    }

    private void render() {
        if (entries==null) return;
        entries.removeAllViews(); controls.clear(); counters.clear();
        if (snapshot==null) { entries.addView(label("",14,SUB,false)); return; }
        int count=0;
        // An active signal remains prominent even when its other segment is selected.
        for (ScheduleModel.Entry entry:snapshot.entries) if (entry.ringing) entries.addView(ringingCard(entry));
        for (ScheduleModel.Entry entry:snapshot.entries) if (entry.timer()!=alarms) {
            count++; LinearLayout card=card();
            if (entry.timer()) {
                TextView time=label("",32,INK,true); counters.add(new Counter(entry,time)); card.addView(time);
                card.addView(label(entry.name(),15,INK,true));
            } else {
                LinearLayout heading=row();
                heading.addView(label(ScheduleModel.alarmTime(entry.hour,entry.minute),32,INK,true),new LayoutParams(0,-2,1));
                CheckBox enabled=new CheckBox(getContext()); enabled.setChecked(entry.enabled);
                enabled.setButtonTintList(ColorStateList.valueOf(PURPLE)); enabled.setMinWidth(dp(48)); enabled.setMinHeight(dp(48));
                enabled.setContentDescription((entry.enabled?"Выключить":"Включить")+" будильник "+entry.name());
                enabled.setOnCheckedChangeListener((button,checked)->{if(ready())mutate(ScheduleModel.enabled(entry.id,checked),null);});
                heading.addView(enabled); control(enabled,true); card.addView(heading);
                card.addView(label(entry.name(),15,INK,true));
                card.addView(label(ScheduleModel.days(entry.daysMask),13,SUB,false));
                if (entry.enabled&&entry.nextAtMs>0) card.addView(label(ScheduleModel.next(entry.nextAtMs,snapshot.timezone),12,SUB,false));
            }
            TextView detail=label(entry.state()+(entry.timer()?"":" · "+entry.volume+"%"),12,SUB,false);
            detail.setPadding(0,dp(7),0,dp(10)); card.addView(detail);
            LinearLayout actions=row();
            if (!entry.timer()) {
                TextView edit=button("Изменить",()->alarmEditor(entry),false); actions.addView(edit,new LayoutParams(0,-2,1)); control(edit,true);
            }
            TextView delete=button("Удалить",()->confirmDelete(entry),false); delete.setTextColor(RED);
            actions.addView(delete,new LayoutParams(0,-2,1)); control(delete,true); card.addView(actions); entries.addView(card);
        }
        if (count==0) {
            TextView empty=label(alarms?"Будильников пока нет":"Таймеров пока нет",16,SUB,false);
            empty.setPadding(dp(4),dp(24),dp(4),dp(24)); entries.addView(empty);
        }
        if (snapshot.entries.size()>=ScheduleModel.LIMIT) {
            TextView limit=label("Лимит: 32 записи",12,SUB,false);
            limit.setPadding(0,dp(10),0,dp(10)); entries.addView(limit);
        }
        tick(); updateControls();
    }

    private LinearLayout ringingCard(ScheduleModel.Entry entry) {
        LinearLayout card=card(); card.setBackground(shape(LILAC,22));
        card.addView(label("СИГНАЛ ЗВУЧИТ",11,PURPLE,true));
        TextView name=label(entry.name(),21,INK,true); name.setPadding(0,dp(7),0,dp(12)); card.addView(name);
        TextView stop=button("Остановить",()->mutate(ScheduleModel.dismiss(entry.id),null),true);stop.setContentDescription(entry.timer()?"Остановить звучащие штатные сигналы Станции":"Остановить сигнал");
        card.addView(stop,new LayoutParams(-1,-2)); control(stop,true); return card;
    }

    void tick() {
        if (disposed||!connected||!fresh) return;
        long now=SystemClock.elapsedRealtime();
        for (Counter counter:counters) {
            long left=ScheduleModel.remaining(counter.entry,receivedAt,now);
            counter.view.setText(counter.entry.ringing?"Сигнал":left==0&&counter.entry.enabled?"Ожидаю Станцию":ScheduleModel.countdown(left));
        }
    }

    private void timerEditor(int initialSeconds) {
        if (!canCreate()) return; closeDialogs();
        LinearLayout body=form(); body.addView(label("Длительность",14,INK,true));
        LinearLayout numbers=row();
        EditText hours=number(initialSeconds/3600,"Часы"),minutes=number(initialSeconds/60%60,"Минуты"),seconds=number(initialSeconds%60,"Секунды");
        int fieldIndex=0;String[] units={"ч","мин","сек"};
        for (EditText input:new EditText[]{hours,minutes,seconds}){
            LinearLayout field=new LinearLayout(getContext());field.setOrientation(VERTICAL);TextView unit=label(units[fieldIndex],12,SUB,true);unit.setGravity(Gravity.CENTER);unit.setPadding(0,dp(8),0,dp(8));field.addView(unit);
            field.addView(input,new LayoutParams(-1,dp(58)));LayoutParams params=new LayoutParams(0,-2,1);params.rightMargin=dp(fieldIndex++<2?8:0);numbers.addView(field,params);
        }
        body.addView(numbers);
        EditText label=labelInput(""); body.addView(label,new LayoutParams(-1,-2));
        TextView error=label("",12,RED,false); body.addView(error);
        AlertDialog dialog=dialog("Таймер",body,"Запустить"); editor=dialog;
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            if (!canCreate()) { error.setText("Обнови связь со Станцией"); return; }
            try { mutate(ScheduleModel.timer(ScheduleModel.timerSeconds(hours.getText().toString(),minutes.getText().toString(),seconds.getText().toString()),label.getText().toString(),35),dialog); }
            catch (IllegalArgumentException invalid) { error.setText("От 5 секунд до 24 часов · минуты и секунды до 59"); }
        })); dialog.show();PhoneUi.styleDialog(dialog);
    }

    private void alarmEditor(ScheduleModel.Entry entry) {
        if (!(entry==null?canCreate():ready())) return; closeDialogs();
        LinearLayout body=form();
        Calendar now=Calendar.getInstance(TimeZone.getTimeZone(snapshot.timezone)); now.setTimeInMillis(snapshot.nowMs);
        final int[] time={entry==null?now.get(Calendar.HOUR_OF_DAY):entry.hour,entry==null?now.get(Calendar.MINUTE):entry.minute};
        TextView timeButton=button(ScheduleModel.alarmTime(time[0],time[1]),()->{
            timePicker=new TimePickerDialog(getContext(),(picker,hour,minute)->{time[0]=hour;time[1]=minute;updateTimeButton(body,time);},time[0],time[1],true);
            timePicker.setTitle("Время"); timePicker.show();PhoneUi.styleDialog(timePicker);
        },false); timeButton.setTextSize(34); timeButton.setTag("alarm-time"); body.addView(timeButton,new LayoutParams(-1,-2));
        TextView zone=label("Europe/Moscow".equals(snapshot.timezone)?"Москва · UTC+3":snapshot.timezone,12,SUB,false); zone.setPadding(0,dp(6),0,dp(10)); body.addView(zone);
        body.addView(label("Повтор",14,INK,true));
        CheckBox[] days=new CheckBox[7]; String[] names={"Пн","Вт","Ср","Чт","Пт","Сб","Вс"};
        TextView repeatSummary=label(ScheduleModel.days(entry==null?0:entry.daysMask),12,SUB,false);
        int columns=getResources().getConfiguration().fontScale>1.2f||getResources().getConfiguration().screenWidthDp<360?2:4;
        for (int start=0;start<7;start+=columns) {
            LinearLayout line=row();
            for (int i=start;i<Math.min(7,start+columns);i++) {
                CheckBox day=new CheckBox(getContext()); day.setText(names[i]); day.setTextSize(12); day.setTextColor(INK);
                day.setPadding(0,0,0,0); day.setMinWidth(0); day.setMinimumWidth(0); day.setMinHeight(dp(48));
                day.setButtonDrawable(null);day.setGravity(Gravity.CENTER);day.setChecked(entry!=null&&(entry.daysMask&(1<<i))!=0);day.setTextColor(day.isChecked()?PURPLE:INK);day.setBackground(shape(day.isChecked()?LILAC:0xfff0edf5,13));day.setOnCheckedChangeListener((button,checked)->{button.setBackground(shape(checked?LILAC:0xfff0edf5,13));button.setTextColor(checked?PURPLE:INK);int mask=0;for(int d=0;d<days.length;d++)if(days[d]!=null&&days[d].isChecked())mask|=1<<d;repeatSummary.setText(ScheduleModel.days(mask));});
                days[i]=day;LayoutParams dayParams=new LayoutParams(0,-2,1);dayParams.setMargins(dp(2),dp(3),dp(2),dp(3));line.addView(day,dayParams);
            }
            body.addView(line);
        }
        body.addView(repeatSummary);
        EditText label=labelInput(entry==null?"":entry.label); body.addView(label,new LayoutParams(-1,-2));
        VolumeInput volume=new VolumeInput(getContext(),entry==null?35:entry.volume); body.addView(volume);
        CheckBox enabled=new CheckBox(getContext()); enabled.setText("Будильник включён"); enabled.setChecked(entry==null||entry.enabled);
        enabled.setButtonTintList(ColorStateList.valueOf(PURPLE)); enabled.setMinHeight(dp(48)); body.addView(enabled);
        TextView error=label("",12,RED,false); body.addView(error);
        AlertDialog dialog=dialog("Будильник",body,"Сохранить"); editor=dialog;
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            if (!(entry==null?canCreate():ready())) { error.setText("Обнови связь со Станцией"); return; }
            int mask=0; for (int i=0;i<7;i++) if (days[i].isChecked()) mask|=1<<i;
            try { mutate(ScheduleModel.alarm(entry==null?null:entry.id,time[0],time[1],mask,label.getText().toString(),volume.value(),enabled.isChecked()),dialog); }
            catch (IllegalArgumentException invalid) { error.setText("Проверь время и подпись: не больше 48 символов"); }
        })); dialog.show();PhoneUi.styleDialog(dialog);
    }

    private void updateTimeButton(LinearLayout body,int[] time) {
        TextView view=body.findViewWithTag("alarm-time"); if (view!=null) view.setText(ScheduleModel.alarmTime(time[0],time[1]));
    }

    private void confirmDelete(ScheduleModel.Entry entry) {
        if (!ready()) return;
        confirmation=new AlertDialog.Builder(getContext()).setTitle(entry.timer()?"Удалить таймер?":"Удалить будильник?")
                .setMessage(entry.name())
                .setNegativeButton("Отмена",null).setPositiveButton("Удалить",(dialog,which)->{
                    if (ready()) mutate(ScheduleModel.delete(entry.id),null);
                }).create(); confirmation.show();PhoneUi.styleDialog(confirmation);
    }

    private void mutate(JSONObject request,AlertDialog source) {
        if (!ready()) return;
        final String command=ScheduleModel.command(request); final int requestEpoch=epoch;
        actionPending=true; setNotice("Сохраняю…"); updateControls();
        if (source!=null) source.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        host.request(command,(result,error)->{
            if (disposed||requestEpoch!=epoch) return;
            actionPending=false;
            if (source!=null) { source.dismiss(); if (editor==source) editor=null; }
            if (error!=null||result==null) {
                // No mutation retry: a lost response may follow a successfully accepted command.
                fresh=false; setNotice(ScheduleModel.error(error)+" · обнови список"); updateControls(); host.refresh(); return;
            }
            try { accept(result); setNotice("Сохранено"); }
            catch (IllegalArgumentException invalid) { fresh=false; attemptedRevision=""; load(); }
            updateControls(); host.refresh();
        });
    }

    private void setNotice(String value){notice.setText(value);notice.setVisibility(value==null||value.isEmpty()?GONE:VISIBLE);}
    private boolean ready() { return connected&&supported&&fresh&&!disposed&&!actionPending&&!listPending; }
    private boolean canCreate() { return ready()&&snapshot!=null&&snapshot.entries.size()<ScheduleModel.LIMIT; }
    private void updateControls() {
        enabled(add,canCreate()); enabled(reload,connected&&supported&&statusKnown&&!disposed&&!listPending&&!actionPending);
        for (TextView preset:presetButtons) enabled(preset,canCreate());
        for (Control control:controls) enabled(control.view,ready()&&control.allowed);
    }
    private void control(View view,boolean allowed) { controls.add(new Control(view,allowed)); }
    void dispose() { disposed=true; epoch++; closeDialogs(); }
    private void closeDialogs() {
        if (timePicker!=null) { timePicker.dismiss(); timePicker=null; }
        if (editor!=null) { editor.dismiss(); editor=null; }
        if (confirmation!=null) { confirmation.dismiss(); confirmation=null; }
    }
    private AlertDialog dialog(String title,LinearLayout body,String positive) {
        ScrollView scroll=new ScrollView(getContext()); scroll.addView(body);
        return new AlertDialog.Builder(getContext()).setTitle(title).setView(scroll)
                .setNegativeButton("Отмена",null).setPositiveButton(positive,null).create();
    }
    private LinearLayout form() {
        LinearLayout view=new LinearLayout(getContext()); view.setOrientation(VERTICAL); view.setPadding(dp(20),dp(8),dp(20),dp(12)); return view;
    }
    private EditText labelInput(String value) {
        EditText input=new EditText(getContext()); input.setHint("Название"); input.setText(value); input.setTextSize(15);
        input.setSingleLine(true); input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(48)});
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); input.setMinHeight(dp(56)); return input;
    }
    private EditText number(int value,String description) {
        EditText input=new EditText(getContext()); input.setText(value==0?"":String.valueOf(value)); input.setHint("00");
        input.setInputType(InputType.TYPE_CLASS_NUMBER); input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2)});
        input.setSelectAllOnFocus(true); input.setTextSize(27); input.setTextColor(INK);input.setBackground(shape(LILAC,16));input.setPadding(dp(3),dp(8),dp(3),dp(8));input.setGravity(Gravity.CENTER); input.setContentDescription(description); return input;
    }
    private TextView label(String text,int size,int color,boolean bold) {
        TextView view=new TextView(getContext()); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setIncludeFontPadding(false);view.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
        view.setMaxLines(5); view.setEllipsize(TextUtils.TruncateAt.END); return view;
    }
    private TextView button(String text,Runnable action,boolean primary) {
        TextView view=label(text,14,primary?0xffffffff:PURPLE,true); view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(48)); view.setPadding(dp(10),dp(10),dp(10),dp(10)); view.setMaxLines(3);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x227560ea),shape(primary?PURPLE:LILAC,14),shape(0xffffffff,14)));
        view.setFocusable(true); view.setOnClickListener(ignored->action.run()); return view;
    }
    private LinearLayout row() { LinearLayout view=new LinearLayout(getContext()); view.setOrientation(HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout card() {
        LinearLayout view=new LinearLayout(getContext()); view.setOrientation(VERTICAL); view.setPadding(dp(16),dp(16),dp(16),dp(14)); view.setBackground(shape(0xffffffff,22));
        LayoutParams params=new LayoutParams(-1,-2); params.topMargin=dp(10); params.bottomMargin=dp(10); view.setLayoutParams(params); return view;
    }
    private GradientDrawable shape(int color,int radius) { GradientDrawable value=new GradientDrawable(); value.setColor(color); value.setCornerRadius(dp(radius)); return value; }
    private void enabled(View view,boolean value) { view.setEnabled(value); view.setAlpha(value?1f:.38f); }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private static final class Control { final View view; final boolean allowed; Control(View view,boolean allowed){this.view=view;this.allowed=allowed;} }
    private static final class Counter { final ScheduleModel.Entry entry; final TextView view; Counter(ScheduleModel.Entry entry,TextView view){this.entry=entry;this.view=view;} }
    private static final class VolumeInput extends LinearLayout implements SeekBar.OnSeekBarChangeListener {
        private final TextView text; private final SeekBar seek;
        VolumeInput(Context context,int initial) {
            super(context); setOrientation(VERTICAL); text=new TextView(context); text.setTextSize(13); text.setTextColor(SUB); addView(text);
            seek=new SeekBar(context); seek.setMax(75); seek.setProgress(initial-5); seek.setContentDescription("Громкость сигнала, отдельно от музыки");
            seek.setProgressTintList(ColorStateList.valueOf(PURPLE)); seek.setThumbTintList(ColorStateList.valueOf(PURPLE));
            seek.setOnSeekBarChangeListener(this); addView(seek,new LayoutParams(-1,Math.round(48*getResources().getDisplayMetrics().density))); update();
        }
        int value() { return seek.getProgress()+5; }
        private void update() { text.setText("Громкость · "+value()+"%"); }
        @Override public void onProgressChanged(SeekBar bar,int value,boolean user) { update(); }
        @Override public void onStartTrackingTouch(SeekBar bar) { }
        @Override public void onStopTrackingTouch(SeekBar bar) { }
    }
}
