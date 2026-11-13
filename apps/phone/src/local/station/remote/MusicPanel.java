package local.station.remote;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Agent music only. Rendering and polling never issue playback commands. */
final class MusicPanel extends LinearLayout implements SeekBar.OnSeekBarChangeListener {
    interface Host {
        void request(String command, BluetoothRemoteClient.Callback callback);
        void refresh();
    }

    private static final int INK=0xff202032, SUB=0xff777384, PURPLE=0xff7560ea, LILAC=0xffeeebff;
    private final Host host;
    private final MusicPlayback clock = new MusicPlayback();
    private final TextView badge, title, artist, elapsed, duration, notice, queueHint;
    private final SeekBar seek;
    private final LinearLayout queue;
    private final RemoteIconView previous, playPause, next, reload;
    private final List<View> queueButtons = new ArrayList<>();
    private boolean connected, fresh, disposed, dragging, actionPending, queuePending;
    private boolean prepared, playing, canSeek, canQueue, hasPrevious, hasNext;
    private String revision="", loadedRevision="", dragRevision="";
    private int epoch;

    MusicPanel(Context context, Host host) {
        super(context);
        this.host = host;
        setOrientation(VERTICAL);

        LinearLayout card=card();
        LinearLayout heading=row();
        TextView overline=label("Сейчас играет",13,INK,true);
        heading.addView(overline,new LayoutParams(0,-2,1));
        badge=label("Нет связи",10,SUB,true); badge.setPadding(dp(10),dp(6),dp(10),dp(6));
        badge.setBackground(shape(0xfff5f2fb,12)); heading.addView(badge); card.addView(heading);

        LinearLayout artwork=new LinearLayout(context); artwork.setGravity(Gravity.CENTER);
        GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xffefe9ff,0xffddd0ff,0xfff4edff}); gradient.setCornerRadius(dp(32));
        artwork.setBackground(gradient);
        RemoteIconView music=new RemoteIconView(context,"music",PURPLE);
        music.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        artwork.addView(music,new LayoutParams(dp(64),dp(64)));
        LayoutParams art=new LayoutParams(dp(144),dp(144)); art.gravity=Gravity.CENTER_HORIZONTAL;
        art.setMargins(0,dp(18),0,dp(22)); card.addView(artwork,art);

        title=label("Ничего не играет",23,INK,true); title.setGravity(Gravity.CENTER);
        title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); card.addView(title,new LayoutParams(-1,-2));
        artist=label("",13,SUB,false); artist.setGravity(Gravity.CENTER);
        artist.setPadding(0,dp(7),0,dp(12)); artist.setMaxLines(2); artist.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(artist,new LayoutParams(-1,-2));

        seek=new SeekBar(context); seek.setMax(1000); seek.setContentDescription("Позиция воспроизведения");
        seek.setProgressTintList(ColorStateList.valueOf(PURPLE));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0xffeae8f2));
        seek.setThumbTintList(ColorStateList.valueOf(PURPLE));
        seek.setOnSeekBarChangeListener(this);
        card.addView(seek,new LayoutParams(-1,dp(48)));
        LinearLayout times=row(); elapsed=label("0:00",11,SUB,false); duration=label("—:—",11,SUB,false);
        times.addView(elapsed,new LayoutParams(0,-2,1)); times.addView(duration); card.addView(times);

        LinearLayout controls=row(); controls.setGravity(Gravity.CENTER); controls.setPadding(0,dp(12),0,dp(7));
        previous=button("previous","Предыдущий трек",INK,0,()->perform("MEDIA PREVIOUS"));
        playPause=button("play","Воспроизвести",0xffffffff,PURPLE,()->perform(playing?"MEDIA PAUSE":"MEDIA RESUME"));
        next=button("next","Следующий трек",INK,0,()->perform("MEDIA NEXT"));
        controls.addView(previous,new LayoutParams(dp(52),dp(52)));
        LayoutParams center=new LayoutParams(dp(66),dp(66)); center.setMargins(dp(24),0,dp(24),0);
        controls.addView(playPause,center); controls.addView(next,new LayoutParams(dp(52),dp(52))); card.addView(controls);
        addView(card);

        notice=label("",12,SUB,false);
        notice.setPadding(dp(4),dp(2),dp(4),dp(12)); notice.setVisibility(GONE);
        notice.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE); addView(notice);
        LinearLayout queueHeader=row(); queueHeader.setPadding(0,dp(10),0,0);
        queueHeader.addView(label("Далее",20,INK,true),new LayoutParams(0,-2,1));
        reload=button("refresh","Обновить очередь",PURPLE,0,this::loadQueue);
        queueHeader.addView(reload,new LayoutParams(dp(48),dp(48))); addView(queueHeader);
        queueHint=label("Очередь пуста",12,SUB,false);
        queueHint.setPadding(0,dp(5),0,dp(8)); addView(queueHint);
        queue=new LinearLayout(context); queue.setOrientation(VERTICAL); addView(queue);
        updateControls();
    }

    @Override public void onStartTrackingTouch(SeekBar bar) { dragging=true; dragRevision=revision; }
    @Override public void onProgressChanged(SeekBar bar,int value,boolean user) {
        if (user) {
            long position=MusicPlayback.fromSlider(value,clock.duration());
            elapsed.setText(MusicPlayback.time(position));
            // Accessibility/keyboard progress actions do not have a touch start/stop pair.
            if (!dragging && ready() && prepared && canSeek && !revision.isEmpty())
                perform("SEEK "+revision+" "+position);
        }
    }
    @Override public void onStopTrackingTouch(SeekBar bar) {
        dragging=false;
        if (!ready() || !prepared || !canSeek) { tick(); return; }
        if (clock.duration()>0 && dragRevision.equals(revision))
            perform("SEEK "+dragRevision+" "+MusicPlayback.fromSlider(bar.getProgress(),clock.duration()));
        else { showNotice("Трек изменился"); tick(); }
    }

    void connection(boolean value) {
        if (disposed) return;
        if (!value) {
            connected=false; fresh=false; actionPending=false; queuePending=false; dragging=false; epoch++;
            badge.setText("Нет связи"); title.setText("Музыка"); artist.setText("");
            elapsed.setText("0:00"); duration.setText("—:—"); seek.setProgress(0);
            showNotice("Нет связи");
        } else connected=true;
        updateControls();
    }

    void status(JSONObject value) {
        if (disposed || !connected || value==null) return;
        fresh=true; prepared=value.optBoolean("prepared"); playing=value.optBoolean("playing");
        hasPrevious=value.optBoolean("hasPrevious"); hasNext=value.optBoolean("hasNext");
        JSONObject capabilities=value.optJSONObject("capabilities");
        canSeek=capabilities!=null&&capabilities.optBoolean("seek");
        canQueue=capabilities!=null&&capabilities.optBoolean("queue");
        clock.update(value.optLong("positionMs"),value.optLong("durationMs"),playing,SystemClock.elapsedRealtime());
        String name=value.optString("title","").trim(), performer=value.optString("artist","").trim();
        title.setText(name.isEmpty()?"Ничего не играет":name);
        artist.setText(performer);
        badge.setText(playing?"Играет":prepared?"На паузе":name.isEmpty()?"Нет трека":"Загрузка");
        playPause.setSymbol(playing?"pause":"play"); playPause.setContentDescription(playing?"Пауза":"Воспроизвести");
        revision=value.optString("queueRevision","");
        if (!canQueue) queueHint.setText("Очередь недоступна");
        if (!actionPending) showNotice("");
        tick(); updateControls();
        if (canQueue && !revision.isEmpty() && !revision.equals(loadedRevision) && !queuePending && !actionPending) loadQueue();
    }

    void tick() {
        if (disposed || !fresh || dragging) return;
        long position=clock.position(SystemClock.elapsedRealtime());
        seek.setProgress(MusicPlayback.slider(position,clock.duration()));
        elapsed.setText(MusicPlayback.time(position));
        duration.setText(clock.duration()>0?MusicPlayback.time(clock.duration()):"—:—");
    }

    private boolean ready() { return connected&&fresh&&!disposed&&!actionPending; }

    private void updateControls() {
        enabled(previous,ready()&&prepared&&hasPrevious); enabled(next,ready()&&prepared&&hasNext);
        enabled(playPause,ready()&&prepared); enabled(seek,ready()&&prepared&&canSeek&&clock.duration()>0);
        enabled(reload,ready()&&canQueue&&!queuePending);
        for (View row:queueButtons) enabled(row,ready()&&canQueue&&!queuePending&&loadedRevision.equals(revision));
    }

    private void perform(String command) {
        if (!ready()) return;
        actionPending=true; updateControls(); showNotice("Отправляю…");
        final int requestEpoch=epoch;
        host.request(command,(result,error)->{
            if (disposed || requestEpoch!=epoch) return;
            actionPending=false; updateControls();
            showNotice(error==null?"":error);
            // Do not retry a playback operation: the Station might already have accepted it.
            host.refresh();
        });
    }

    private void loadQueue() {
        if (!ready() || !canQueue || queuePending) return;
        queuePending=true; queueHint.setText("Обновляю…"); updateControls();
        final int requestEpoch=epoch;
        host.request("QUEUE",(result,error)->{
            if (disposed || requestEpoch!=epoch) return;
            queuePending=false;
            if (error!=null || result==null) {
                // Avoid retrying the same failed snapshot on every status poll.
                loadedRevision=revision; queueHint.setText(error==null?"Очередь недоступна":error);
                queueButtons.clear(); queue.removeAllViews(); updateControls(); return;
            }
            renderQueue(result); updateControls();
        });
    }

    private void renderQueue(JSONObject value) {
        queue.removeAllViews(); queueButtons.clear();
        loadedRevision=value.optString("revision","");
        JSONArray tracks=value.optJSONArray("tracks"); int current=value.optInt("index",-1);
        int total=value.optInt("total",tracks==null?0:tracks.length());
        if (tracks==null || tracks.length()==0) { queueHint.setText("Очередь пуста"); return; }
        queueHint.setText(total+" "+trackWord(total)+(value.optBoolean("truncated")?" · часть очереди":""));
        final String snapshotRevision=loadedRevision;
        for (int i=0;i<tracks.length();i++) {
            JSONObject track=tracks.optJSONObject(i); if (track==null) continue;
            final int index=track.optInt("index",i); boolean selected=index==current;
            LinearLayout line=row(); line.setPadding(dp(14),dp(14),dp(14),dp(14));
            line.setMinimumHeight(dp(68)); line.setBackground(ripple(selected?LILAC:0xffffffff,22));
            TextView number=label(selected?"♪":String.valueOf(index+1),13,selected?PURPLE:SUB,true);
            number.setGravity(Gravity.CENTER); line.addView(number,new LayoutParams(dp(28),-2));
            LinearLayout names=new LinearLayout(getContext()); names.setOrientation(VERTICAL); names.setPadding(dp(8),0,dp(6),0);
            String name=track.optString("title","").trim(), performer=track.optString("artist","").trim();
            TextView nameView=label(name.isEmpty()?"Без названия":name,14,selected?PURPLE:INK,true);
            nameView.setMaxLines(2); nameView.setEllipsize(TextUtils.TruncateAt.END); names.addView(nameView);
            if (!performer.isEmpty()) { TextView detail=label(performer,12,SUB,false); detail.setPadding(0,dp(5),0,0); detail.setMaxLines(1); detail.setEllipsize(TextUtils.TruncateAt.END); names.addView(detail); }
            line.addView(names,new LayoutParams(0,-2,1));
            long length=track.optLong("durationMs");
            if (length>0) line.addView(label(MusicPlayback.time(length),11,SUB,false));
            line.setContentDescription("Включить: "+name+(performer.isEmpty()?"":", "+performer));
            line.setOnClickListener(v->{
                if (ready() && snapshotRevision.equals(revision)) {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    perform("QUEUE_PLAY "+snapshotRevision+" "+index);
                }
            }); line.setFocusable(true);
            LayoutParams params=new LayoutParams(-1,-2); params.bottomMargin=dp(7); queue.addView(line,params); queueButtons.add(line);
        }
    }

    void dispose() { disposed=true; epoch++; }

    private void showNotice(String value) { notice.setText(value); notice.setVisibility(value==null||value.isEmpty()?GONE:VISIBLE); }

    private static String trackWord(int count) {
        int mod100=Math.abs(count)%100, mod10=Math.abs(count)%10;
        return mod100>=11&&mod100<=14?"треков":mod10==1?"трек":mod10>=2&&mod10<=4?"трека":"треков";
    }
    private void enabled(View view,boolean value) { view.setEnabled(value); view.setAlpha(value?1f:.38f); }
    private TextView label(String text,int size,int color,boolean bold) {
        TextView view=new TextView(getContext()); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setIncludeFontPadding(false);view.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL)); return view;
    }
    private LinearLayout row() { LinearLayout view=new LinearLayout(getContext()); view.setOrientation(HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout card() {
        LinearLayout view=new LinearLayout(getContext()); view.setOrientation(VERTICAL); view.setPadding(dp(18),dp(17),dp(18),dp(14));
        view.setBackground(shape(0xffffffff,24)); LayoutParams p=new LayoutParams(-1,-2); p.topMargin=dp(1); p.bottomMargin=dp(10); view.setLayoutParams(p); return view;
    }
    private RemoteIconView button(String symbol,String description,int color,int background,Runnable action) {
        RemoteIconView view=new RemoteIconView(getContext(),symbol,color); view.setContentDescription(description); view.setFocusable(true);
        view.setBackground(ripple(background,50)); view.setOnClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);action.run();}); return view;
    }
    private GradientDrawable shape(int color,int radius) { GradientDrawable view=new GradientDrawable(); view.setColor(color); view.setCornerRadius(dp(radius)); return view; }
    private RippleDrawable ripple(int color,int radius) { return new RippleDrawable(ColorStateList.valueOf(0x227560ea),shape(color,radius),shape(0xffffffff,radius)); }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}
