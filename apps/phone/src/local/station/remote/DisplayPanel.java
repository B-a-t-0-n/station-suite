package local.station.remote;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Bluetooth control of native LED settings; no writes until an explicit user action. */
final class DisplayPanel extends LinearLayout {
    interface Host {
        void request(String command,BluetoothRemoteClient.Callback callback);
        boolean displayCanPoll();
    }
    private static final int INK=0xff202032,SUB=0xff777384,PURPLE=0xff7560ea,LILAC=0xffeeebff;
    private final Host host;
    private final TextView badge,observed,notice,reload,brightnessLabel,automatic,manual,musicStatus,musicHint;
    private final LinearLayout sizes,musicChoices;
    private final DotClockView preview;
    private final SeekBar brightness;
    private final List<View> sizeButtons=new ArrayList<>();
    private final List<View> musicButtons=new ArrayList<>();
    private final List<DotMusicPreviewView> musicPreviews=new ArrayList<>();
    private final DisplayReadSchedule reads=new DisplayReadSchedule();
    private DisplayModel.Snapshot snapshot;
    private boolean connected,disposed,requestPending,fresh,dragging,rendering;
    private String musicCardsKey="";
    private int epoch;

    DisplayPanel(Context context,Host host,Runnable back){
        super(context);this.host=host;setOrientation(VERTICAL);
        LinearLayout navigation=row();navigation.addView(pill("‹ Настройки",back));
        navigation.addView(new View(context),new LayoutParams(0,1,1));
        reload=pill("Обновить",this::load);navigation.addView(reload);addView(navigation);

        LinearLayout hero=column();hero.setGravity(Gravity.CENTER_HORIZONTAL);hero.setPadding(dp(16),dp(14),dp(16),dp(14));
        GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xffeee8ff,0xffe3dafa,0xfff3ecff});
        gradient.setCornerRadius(dp(23));hero.setBackground(gradient);
        badge=label("Подключаемся…",11,PURPLE,true);badge.setGravity(Gravity.CENTER);badge.setPadding(dp(12),dp(6),dp(12),dp(6));badge.setBackground(shape(0x99ffffff,12));hero.addView(badge);
        preview=new DotClockView(context);preview.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);LayoutParams previewParams=new LayoutParams(dp(137),dp(89));previewParams.topMargin=dp(9);hero.addView(preview,previewParams);
        observed=label("Загрузка…",12,INK,true);observed.setGravity(Gravity.CENTER);observed.setPadding(0,dp(6),0,0);hero.addView(observed);
        LayoutParams heroParams=new LayoutParams(-1,-2);heroParams.topMargin=dp(12);heroParams.bottomMargin=dp(18);addView(hero,heroParams);

        addView(label("Размер часов",17,INK,true));sizes=row();addView(sizes,new LayoutParams(-1,-2));
        renderSizes();
        LinearLayout light=card();LinearLayout lightHeading=row();lightHeading.addView(label("Яркость",17,INK,true),new LayoutParams(0,-2,1));
        brightnessLabel=label("—",12,PURPLE,true);brightnessLabel.setGravity(Gravity.RIGHT);lightHeading.addView(brightnessLabel);light.addView(lightHeading);
        LinearLayout mode=row();automatic=pill("Авто",()->setBrightnessMode(true));manual=pill("Вручную",()->setBrightnessMode(false));
        LayoutParams autoParams=new LayoutParams(0,-2,1);autoParams.rightMargin=dp(6);mode.addView(automatic,autoParams);mode.addView(manual,new LayoutParams(0,-2,1));LayoutParams modeParams=new LayoutParams(-1,-2);modeParams.topMargin=dp(14);light.addView(mode,modeParams);
        brightness=new SeekBar(context);brightness.setMax(100);brightness.setContentDescription("Яркость LED-дисплея колонки. Ноль выключает экран");
        brightness.setProgressTintList(ColorStateList.valueOf(PURPLE));brightness.setProgressBackgroundTintList(ColorStateList.valueOf(0xffeae8f2));brightness.setThumbTintList(ColorStateList.valueOf(PURPLE));
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onStartTrackingTouch(SeekBar bar){dragging=true;}
            @Override public void onProgressChanged(SeekBar bar,int value,boolean user){if(user&&!rendering)brightnessLabel.setText(value==0?"Выключен":value+"%");}
            @Override public void onStopTrackingTouch(SeekBar bar){dragging=false;if(canEdit())changeBrightness(false,bar.getProgress()/100d);else render();}
        });LayoutParams sliderParams=new LayoutParams(-1,dp(48));sliderParams.topMargin=dp(6);light.addView(brightness,sliderParams);addView(light);

        LinearLayout music=column();music.setPadding(0,dp(22),0,dp(4));music.addView(label("Во время музыки",17,INK,true));
        musicHint=label("Загрузка…",12,SUB,false);musicHint.setPadding(0,dp(8),0,0);music.addView(musicHint);
        musicStatus=label("",12,PURPLE,true);musicStatus.setPadding(0,dp(7),0,0);music.addView(musicStatus);addView(music);
        musicChoices=column();addView(musicChoices);
        notice=label("Нет подключения",12,SUB,false);notice.setPadding(dp(3),dp(12),dp(3),dp(12));notice.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);addView(notice);
        updateControls();
    }
    void connection(boolean value){
        if(disposed||connected==value)return;connected=value;epoch++;requestPending=false;fresh=false;
        reads.clear();if(value)load();else{for(DotMusicPreviewView view:musicPreviews)view.running(false);badge.setText("Нет связи");setNotice("Нет подключения");updateControls();}
    }
    void dispose(){disposed=true;epoch++;reads.clear();for(DotMusicPreviewView view:musicPreviews)view.running(false);}
    /** Called only by the activity's foreground loop. The shared request budget stays authoritative. */
    void tick(){tryRead();}
    private void load(){
        if(disposed||!connected||requestPending||dragging)return;
        reads.refresh(SystemClock.elapsedRealtime());
        if(!host.displayCanPoll())setNotice("Станция занята…");
        tryRead();
    }
    private void tryRead(){
        if(disposed||!connected||requestPending||dragging)return;
        String command=reads.take(SystemClock.elapsedRealtime(),host.displayCanPoll());
        if(command!=null)send(command,null,null);
    }
    private boolean canEdit(){return connected&&!disposed&&!requestPending&&fresh&&snapshot!=null&&snapshot.canEdit();}
    private void chooseSize(String size){if(canEdit())send(DisplayModel.size(snapshot,size),size,null);}
    private void chooseMusicStyle(String style){if(canEdit())send(DisplayModel.musicStyle(snapshot,style),null,null,style);}
    private void setBrightnessMode(boolean auto){
        if(!canEdit())return;DisplayModel.Brightness chosen=snapshot.chosenBrightness();
        double value=chosen==null?.5:chosen.value;
        if(chosen!=null&&chosen.auto==auto)return;
        changeBrightness(auto,value);
    }
    private void changeBrightness(boolean auto,double value){
        if(!canEdit())return;
        try{send(DisplayModel.brightness(snapshot,auto,value),null,new DisplayModel.Brightness(new org.json.JSONObject().put("auto",auto).put("value",value)));}
        catch(org.json.JSONException invalid){throw new IllegalArgumentException("display_invalid");}
    }
    private void send(String command,String chosenSize,DisplayModel.Brightness chosenBrightness){
        send(command,chosenSize,chosenBrightness,null);
    }
    private void send(String command,String chosenSize,DisplayModel.Brightness chosenBrightness,String chosenMusicStyle){
        final int requestEpoch=epoch;requestPending=true;updateControls();
        boolean mutation=chosenSize!=null||chosenBrightness!=null||chosenMusicStyle!=null;
        if(mutation)setNotice("Сохраняем…");
        host.request(command,(result,error)->{
            if(disposed||requestEpoch!=epoch)return;requestPending=false;
            if(error!=null||result==null){fresh=false;reads.failed(SystemClock.elapsedRealtime());badge.setText("Нет подтверждения");setNotice("Нет ответа · повторяем проверку");updateControls();return;}
            try{
                DisplayModel.Snapshot received=new DisplayModel.Snapshot(result);
                if(chosenSize!=null&&!received.acknowledgesSize(chosenSize)||chosenBrightness!=null&&!received.acknowledgesBrightness(chosenBrightness.auto,chosenBrightness.value)
                        ||chosenMusicStyle!=null&&!received.acknowledgesMusicStyle(chosenMusicStyle))throw new IllegalArgumentException("display_invalid");
                snapshot=received;fresh=received.ok;reads.received(received,SystemClock.elapsedRealtime());render();
            }catch(IllegalArgumentException invalid){fresh=false;reads.failed(SystemClock.elapsedRealtime());badge.setText("Нет подтверждения");setNotice("Неполный ответ · повторяем проверку");}
            updateControls();
        });
    }
    private void render(){
        if(snapshot==null)return;rendering=true;
        badge.setText(snapshot.badge());
        if(snapshot.nativeAvailable){
            observed.setText(DisplayModel.sizeName(snapshot.clockSize)+(snapshot.clockSizeExplicit?"":" · по умолчанию"));
            preview.setSize(snapshot.clockSize);preview.setAlpha(1);
            preview.setBrightness(snapshot.brightness!=null&&!snapshot.brightness.auto?snapshot.brightness.value:1);
        }else{observed.setText("Размер не получен");preview.setAlpha(.35f);}
        renderSizes();renderMusic();
        DisplayModel.Brightness chosen=snapshot.chosenBrightness();
        if(chosen==null){brightnessLabel.setText("Не получена");brightness.setProgress(50);automatic.setSelected(false);manual.setSelected(false);}
        else{
            automatic.setSelected(chosen.auto);manual.setSelected(!chosen.auto);
            int percent=(int)Math.round(chosen.value*100);brightness.setProgress(percent);
            brightnessLabel.setText(chosen.auto?"Авто":percent==0?"Выключен":percent+"%");
            if(!snapshot.brightnessSelectionSuffix().isEmpty())brightnessLabel.append(" · ожидает");
        }
        automatic.setBackground(ripple(automatic.isSelected()?0xffded5ff:LILAC,22));manual.setBackground(ripple(manual.isSelected()?0xffded5ff:LILAC,22));
        if(!snapshot.ok)setNotice(shortError(snapshot.code));
        else if("pending".equals(snapshot.state))setNotice("Ожидаем подтверждение…");
        else if("applied".equals(snapshot.state)||"defaults".equals(snapshot.state))setNotice("");
        else if(snapshot.nativeAvailable&&!snapshot.writeAvailable)setNotice("Только просмотр · настройка недоступна");
        else if(snapshot.hasRequest())setNotice("Применение не подтверждено");
        else setNotice("Настройки не получены");
        rendering=false;
    }
    private void renderMusic(){
        if(snapshot.musicModes.isEmpty()){
            clearMusicPreviews();musicCardsKey="";
            musicHint.setText("Варианты недоступны");musicHint.setVisibility(VISIBLE);musicStatus.setText("");return;
        }
        musicHint.setVisibility(GONE);
        String chosen=snapshot.chosenMusicStyle();String title=null;
        for(DisplayModel.MusicMode mode:snapshot.musicModes)if(mode.id.equals(chosen))title=mode.title;
        musicStatus.setText(title==null?"Текущий вариант неизвестен":title+" · "+selection(snapshot.musicSelectionLabel()));
        String playback=snapshot.musicPlaybackCaption();
        if(playback.contains("не подтверждено"))musicStatus.append(" · плеер ожидает");
        StringBuilder key=new StringBuilder(chosen).append('|').append(snapshot.musicSelectionLabel());
        for(DisplayModel.MusicMode mode:snapshot.musicModes)key.append('|').append(mode.id).append('|').append(mode.title).append('|').append(mode.previewKind);
        if(musicCardsKey.equals(key.toString())){for(DotMusicPreviewView view:musicPreviews)view.running(connected&&!disposed);return;}
        musicCardsKey=key.toString();clearMusicPreviews();LinearLayout line=null;int index=0;
        for(DisplayModel.MusicMode mode:snapshot.musicModes){
            if(index%2==0){line=row();line.setGravity(Gravity.TOP);musicChoices.addView(line,new LayoutParams(-1,-2));}
            boolean selected=mode.id.equals(chosen);
            LinearLayout choice=column();choice.setGravity(Gravity.CENTER_HORIZONTAL);choice.setPadding(dp(10),dp(15),dp(10),dp(13));
            GradientDrawable background=shape(selected?LILAC:0xffffffff,21);if(selected)background.setStroke(dp(1),0xffc7b8ff);
            choice.setBackground(new RippleDrawable(ColorStateList.valueOf(0x227560ea),background,shape(0xffffffff,21)));
            DotMusicPreviewView example=new DotMusicPreviewView(getContext(),mode.previewKind);example.running(connected&&!disposed);
            choice.addView(example,new LayoutParams(dp(102),dp(71)));musicPreviews.add(example);
            TextView name=label(mode.title,14,selected?PURPLE:INK,true);name.setGravity(Gravity.CENTER);name.setPadding(0,dp(10),0,dp(5));choice.addView(name);
            TextView state=label(selected?selection(snapshot.musicSelectionLabel()):"",10,selected?PURPLE:SUB,false);state.setGravity(Gravity.CENTER);state.setMinHeight(dp(15));choice.addView(state);
            choice.setSelected(selected);choice.setContentDescription("Оформление «"+mode.title+"». "+(selected?snapshot.musicSelectionLabel():"Выбрать"));
            interactive(choice,()->chooseMusicStyle(mode.id));musicButtons.add(choice);
            LayoutParams params=new LayoutParams(0,-2,1);params.topMargin=dp(10);params.rightMargin=dp(index%2==0?10:0);line.addView(choice,params);index++;
        }
        if(index%2==1&&line!=null)line.addView(new View(getContext()),new LayoutParams(0,1,1));
    }
    private static String selection(String value){
        if(value.contains("ожидает"))return "Ожидает Станцию";
        if(value.contains("не подтверждено"))return "Не подтверждено";
        if(value.contains("по умолчанию"))return "По умолчанию";
        return "Подтверждено";
    }
    private void clearMusicPreviews(){for(DotMusicPreviewView view:musicPreviews)view.running(false);musicPreviews.clear();musicButtons.clear();musicChoices.removeAllViews();}
    private void renderSizes(){
        sizes.removeAllViews();sizeButtons.clear();String[] values={"small","middle","large"};int index=0;
        for(String size:values){
            boolean selected=snapshot!=null&&size.equals(snapshot.chosenSize());
            LinearLayout choice=column();choice.setGravity(Gravity.CENTER_HORIZONTAL);choice.setPadding(dp(5),dp(12),dp(5),dp(11));
            GradientDrawable background=shape(selected?LILAC:0xffffffff,19);if(selected)background.setStroke(dp(1),0xffc7b8ff);
            choice.setBackground(new RippleDrawable(ColorStateList.valueOf(0x227560ea),background,shape(0xffffffff,19)));
            DotClockView smallPreview=new DotClockView(getContext());smallPreview.setSize(size);smallPreview.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);choice.addView(smallPreview,new LayoutParams(dp(72),dp(48)));
            TextView name=label(DisplayModel.sizeName(size),12,selected?PURPLE:INK,true);name.setGravity(Gravity.CENTER);name.setPadding(0,dp(9),0,0);choice.addView(name);
            String state=selected?(snapshot.sizeSelectionLabel().contains("ожидает")?"Ожидает":snapshot.sizeSelectionLabel().contains("не подтверждено")?"Не подтверждено":"Выбрано"):"";
            TextView hint=label(state,9,selected?PURPLE:SUB,false);hint.setMinHeight(dp(15));hint.setGravity(Gravity.CENTER);hint.setPadding(0,dp(5),0,0);choice.addView(hint);
            choice.setSelected(selected);choice.setContentDescription(DisplayModel.sizeName(size)+" цифры"+(selected?". "+snapshot.sizeSelectionLabel():". Выбрать размер"));
            interactive(choice,()->chooseSize(size));LayoutParams params=new LayoutParams(0,-2,1);params.topMargin=dp(9);params.rightMargin=dp(index++<2?7:0);sizes.addView(choice,params);sizeButtons.add(choice);
        }
    }
    private void setNotice(String value){notice.setText(value);notice.setVisibility(value==null||value.isEmpty()?GONE:VISIBLE);}
    private static String shortError(String value){
        if("native_display_unavailable".equals(value))return "Дисплей не отвечает · обнови";
        if("invalid_display_request".equals(value))return "Настройка не принята · обнови";
        if("display_busy".equals(value))return "Станция занята";
        if("display_interrupted".equals(value))return "Проверка прервана · обнови";
        return DisplayModel.error(value);
    }
    private void updateControls(){
        boolean can=canEdit();enabled(reload,connected&&!disposed&&!requestPending);for(View view:sizeButtons)enabled(view,can);
        for(View view:musicButtons)enabled(view,can);
        enabled(automatic,can);enabled(manual,can);DisplayModel.Brightness selected=snapshot==null?null:snapshot.chosenBrightness();
        enabled(brightness,can&&selected!=null&&!selected.auto);
    }
    private void enabled(View view,boolean enabled){view.setEnabled(enabled);view.setAlpha(enabled?1f:.45f);}
    private void interactive(View view,Runnable action){view.setFocusable(true);view.setOnClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);action.run();});view.setOnTouchListener((v,event)->{int type=event.getActionMasked();if(type==MotionEvent.ACTION_DOWN)v.animate().scaleX(.985f).scaleY(.985f).setDuration(90).start();if(type==MotionEvent.ACTION_UP||type==MotionEvent.ACTION_CANCEL)v.animate().scaleX(1).scaleY(1).setDuration(140).start();return false;});}
    private TextView label(String text,int size,int color,boolean bold){TextView value=new TextView(getContext());value.setText(text);value.setTextSize(size);value.setTextColor(color);value.setIncludeFontPadding(false);value.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));return value;}
    private LinearLayout column(){LinearLayout value=new LinearLayout(getContext());value.setOrientation(VERTICAL);return value;}
    private LinearLayout row(){LinearLayout value=new LinearLayout(getContext());value.setOrientation(HORIZONTAL);value.setGravity(Gravity.CENTER_VERTICAL);return value;}
    private LinearLayout card(){LinearLayout value=column();value.setPadding(dp(16),dp(16),dp(16),dp(16));value.setBackground(shape(0xffffffff,20));LayoutParams params=new LayoutParams(-1,-2);params.topMargin=dp(17);params.bottomMargin=dp(2);value.setLayoutParams(params);return value;}
    private TextView pill(String text,Runnable action){TextView value=label(text,12,PURPLE,true);value.setGravity(Gravity.CENTER);value.setPadding(dp(14),dp(10),dp(14),dp(10));value.setMinHeight(dp(44));value.setBackground(ripple(LILAC,22));interactive(value,action);return value;}
    private GradientDrawable shape(int color,int radius){GradientDrawable value=new GradientDrawable();value.setColor(color);value.setCornerRadius(dp(radius));return value;}
    private RippleDrawable ripple(int color,int radius){return new RippleDrawable(ColorStateList.valueOf(0x227560ea),shape(color,radius),shape(0xffffffff,radius));}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
