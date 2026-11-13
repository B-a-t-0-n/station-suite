package local.station.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Native Bluetooth remote. HID sends keys; RFCOMM carries station data. */
public final class MainActivity extends Activity implements MusicPanel.Host, SchedulePanel.Host, DisplayPanel.Host {
    private static final int BG=0xfff5f5f9, INK=0xff202032, SUB=0xff777384, PURPLE=0xff7560ea, LILAC=0xffeeebff;
    private static final String PERMISSION="android.permission.BLUETOOTH_CONNECT";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private BluetoothRemoteClient agent;
    private HidRemoteClient hid;
    private BluetoothDevice station;
    private SharedPreferences prefs;
    private LinearLayout content,tabs,scheduleBanner,connectionBar;
    private ScrollView scroll;
    private TextView connectionTitle,connectionDetail,screenTitle,screenStatus,feedback,volumeText;
    private TextView homeName,homeConnection,homeTrack,homeArtist,homeTimers;
    private RemoteIconView homePlay,homeRetry;
    private SeekBar volume;
    private MusicPanel musicPanel;
    private SchedulePanel schedulePanel;
    private DisplayPanel displayPanel;
    private JSONObject lastStatus;
    private String hidState="Ожидание подключения", page="Главная";
    private boolean foreground,connecting,statusPending,agentReady,dragging,muted;
    private int volumePercent=-1,pending;
    private JSONArray apps=new JSONArray();
    private Runnable repeat;
    private final Runnable poll=this::pollStep;
    private final Runnable musicTick=this::musicTickStep;
    private void pollStep(){if(foreground){refresh();handler.postDelayed(poll,("Музыка".equals(page)||"Часы".equals(page))?1500:3500);}}
    private void musicTickStep(){if(foreground&&(musicPanel!=null||schedulePanel!=null||displayPanel!=null)){if(musicPanel!=null)musicPanel.tick();if(schedulePanel!=null)schedulePanel.tick();if(displayPanel!=null)displayPanel.tick();handler.postDelayed(musicTick,250);}}

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);prefs=getSharedPreferences("station_remote",MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root=column();root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(20)+insets.getSystemWindowInsetLeft(),dp(5)+insets.getSystemWindowInsetTop(),dp(20)+insets.getSystemWindowInsetRight(),dp(3)+insets.getSystemWindowInsetBottom());return insets.consumeSystemWindowInsets();});
        setContentView(root);
        LinearLayout header=row();header.setPadding(dp(2),dp(3),0,dp(5));
        LinearLayout heading=column();screenTitle=text("Станция",27,INK,true);heading.addView(screenTitle);
        screenStatus=text("Bluetooth",11,SUB,false);screenStatus.setMaxLines(2);screenStatus.setPadding(0,dp(5),0,0);heading.addView(screenStatus);header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(icon("link","Выбрать Станцию",PURPLE,0xffffffff,this::chooseStation),box(46,46));root.addView(header,new LinearLayout.LayoutParams(-1,dp(66)));
        connectionBar=row();connectionBar.setVisibility(View.GONE);connectionTitle=text("",14,INK,true);connectionDetail=text("",11,SUB,false);
        scheduleBanner=row();scheduleBanner.setPadding(dp(12),dp(5),dp(5),dp(5));scheduleBanner.setBackground(shape(LILAC,18));
        scheduleBanner.addView(text("Сработал сигнал",13,PURPLE,true),new LinearLayout.LayoutParams(0,-2,1));
        scheduleBanner.addView(pill("Открыть часы",()->showPage("Часы")));scheduleBanner.setVisibility(View.GONE);root.addView(scheduleBanner,new LinearLayout.LayoutParams(-1,-2));
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);content=column();content.setPadding(0,dp(10),0,dp(20));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        tabs=row();tabs.setPadding(0,dp(7),0,dp(3));tabs.setBackground(shape(0xffffffff,22));root.addView(tabs,new LinearLayout.LayoutParams(-1,dp(68)));showPage("Главная");
    }
    private void showPage(String next){View focus=getCurrentFocus();if(focus!=null)((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(),0);stopRepeat();handler.removeCallbacks(musicTick);if(musicPanel!=null)musicPanel.dispose();musicPanel=null;if(schedulePanel!=null)schedulePanel.dispose();schedulePanel=null;if(displayPanel!=null)displayPanel.dispose();displayPanel=null;page=next;connectionBar.setVisibility(View.GONE);screenTitle.setText("Главная".equals(page)?"Моя Станция":"Экран колонки".equals(page)?"Экран":"Приложения".equals(page)?"ТВ-приложения":"Подсветка".equals(page)?"Кольцо":page);content.removeAllViews();tabs.removeAllViews();volume=null;volumeText=null;feedback=null;homeName=null;homeConnection=null;homeTrack=null;homeArtist=null;homeTimers=null;homePlay=null;homeRetry=null;dragging=false;
        if("Главная".equals(page))homePage();else if("Пульт".equals(page))remotePage();else if("Музыка".equals(page))musicPage();else if("Часы".equals(page))schedulePage();else if("Экран колонки".equals(page))displayPage();else if("Подсветка".equals(page))lightingPage();else if("Приложения".equals(page))appsPage();else if("Подключение".equals(page))connectionPage();else if("О приложении".equals(page))aboutPage();else settingsPage();tab("Главная","home");tab("Музыка","music");tab("Пульт","remote");tab("Часы","clock");tab("Настройки","settings");scroll.scrollTo(0,0);if(foreground&&(musicPanel!=null||schedulePanel!=null||displayPanel!=null))handler.post(musicTick);}
    private void homePage(){
        LinearLayout hero=row();hero.setGravity(Gravity.CENTER_VERTICAL);hero.setPadding(dp(20),dp(16),dp(9),dp(16));
        GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xffe8e0ff,0xfff1ecff});gradient.setCornerRadius(dp(28));hero.setBackground(gradient);
        LinearLayout words=column();homeName=text(stationName(),25,INK,true);homeName.setMaxLines(2);words.addView(homeName);
        homeConnection=text("Нет связи",12,SUB,false);homeConnection.setPadding(0,dp(10),0,dp(21));words.addView(homeConnection);
        LinearLayout actions=row();TextView settings=pill("Настроить",()->showPage("Настройки"));settings.setBackground(ripple(0xcfffffff,22));actions.addView(settings,new LinearLayout.LayoutParams(-2,-2));homeRetry=icon("refresh","Повторить подключение",PURPLE,0x99ffffff,this::retry);LinearLayout.LayoutParams retryParams=box(46,46);retryParams.leftMargin=dp(6);actions.addView(homeRetry,retryParams);words.addView(actions);hero.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        ImageView stationImage=new ImageView(this);stationImage.setScaleType(ImageView.ScaleType.FIT_CENTER);stationImage.setAdjustViewBounds(true);stationImage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int artwork=getResources().getIdentifier("station_max","drawable",getPackageName());if(artwork!=0)stationImage.setImageResource(artwork);
        hero.addView(stationImage,box(Math.min(128,Math.max(88,getResources().getConfiguration().screenWidthDp-254)),172));content.addView(hero,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout music=card();LinearLayout track=row();LinearLayout art=column();art.setGravity(Gravity.CENTER);art.setBackground(shape(LILAC,16));RemoteIconView note=new RemoteIconView(this,"music",PURPLE);art.addView(note,box(25,25));track.addView(art,box(50,50));
        LinearLayout names=column();names.setPadding(dp(12),0,dp(8),0);homeTrack=text("Ничего не играет",16,INK,true);homeTrack.setMaxLines(2);homeTrack.setEllipsize(TextUtils.TruncateAt.END);names.addView(homeTrack);homeArtist=text("",12,SUB,false);homeArtist.setPadding(0,dp(5),0,0);homeArtist.setMaxLines(1);homeArtist.setEllipsize(TextUtils.TruncateAt.END);names.addView(homeArtist);track.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        homePlay=icon("play","Воспроизвести",PURPLE,LILAC,()->send(lastStatus!=null&&lastStatus.optBoolean("playing")?"MEDIA PAUSE":"MEDIA RESUME",""));track.addView(homePlay,box(48,48));music.addView(track);content.addView(music);
        volumeCard();
        LinearLayout first=row();shortcut(first,"screen","Экран","",()->showPage("Экран колонки"),true);shortcut(first,"ring","Кольцо","",()->showPage("Подсветка"),false);content.addView(first);
        LinearLayout second=row();shortcut(second,"clock","Таймеры","",()->showPage("Часы"),true);shortcut(second,"apps","Приложения","",()->showPage("Приложения"),false);content.addView(second);
        homeTimers=text("",12,PURPLE,true);homeTimers.setPadding(dp(3),dp(4),dp(3),dp(6));homeTimers.setVisibility(View.GONE);content.addView(homeTimers);
        feedback=text("",12,SUB,false);feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(feedback);updateHome();updateAvailability();
    }
    private void updateHome(){
        if(homeName==null)return;homeName.setText(stationName());boolean keys=hid!=null&&hid.isConnected();
        homeConnection.setText(keys&&agentReady?"Подключена":agentReady?"Данные готовы":keys?"Пульт готов":connecting?"Подключение…":"Нет связи");
        homeConnection.setTextColor(keys&&agentReady?0xff287e63:SUB);homeRetry.setVisibility(keys&&agentReady?View.GONE:View.VISIBLE);
        boolean fresh=agentReady&&lastStatus!=null,prepared=fresh&&lastStatus.optBoolean("prepared"),playing=fresh&&lastStatus.optBoolean("playing");
        String title=fresh?lastStatus.optString("title","").trim():"",artist=fresh?lastStatus.optString("artist","").trim():"";
        homeTrack.setText(!fresh?"Подключи Станцию":title.isEmpty()?"Ничего не играет":title);
        String playbackState=playing?"Играет на Станции":prepared?"На паузе":"Загрузка";
        homeArtist.setText(!fresh?"Нет связи":artist.isEmpty()?title.isEmpty()?"":playbackState:artist+(playing?"":prepared?" · На паузе":" · Загрузка"));
        homePlay.setSymbol(playing?"pause":"play");homePlay.setContentDescription(playing?"Пауза":"Воспроизвести");homePlay.setEnabled(prepared);homePlay.setAlpha(prepared?1:.35f);
        JSONObject clocks=fresh?lastStatus.optJSONObject("scheduleRuntime"):null;int ringing=clocks==null?0:clocks.optInt("ringing",0);
        homeTimers.setText("Звучит таймер");homeTimers.setVisibility(ringing>0?View.VISIBLE:View.GONE);
    }
    private void shortcut(LinearLayout parent,String symbol,String title,String subtitle,Runnable action,boolean left){
        LinearLayout tile=row();tile.setPadding(dp(14),dp(16),dp(12),dp(16));tile.setBackground(ripple(0xffffffff,21));
        RemoteIconView glyph=new RemoteIconView(this,symbol,PURPLE);glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);tile.addView(glyph,box(25,25));
        TextView name=text(title,14,INK,true);name.setPadding(dp(11),0,0,0);name.setMaxLines(2);tile.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        tile.setMinimumHeight(dp(72));tile.setContentDescription(title);interactive(tile,action);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(0,dp(4),left?dp(10):0,dp(6));parent.addView(tile,p);
    }
    private void volumeCard(){
        LinearLayout audio=card();LinearLayout heading=row();heading.addView(text("Громкость",16,INK,true),new LinearLayout.LayoutParams(0,-2,1));volumeText=text(volumePercent<0?"—":volumePercent+"%",13,PURPLE,true);heading.addView(volumeText);audio.addView(heading);
        LinearLayout controls=row();controls.setPadding(0,dp(8),0,0);controls.addView(icon("minus","Тише",INK,BG,()->volumeStep(false)),box(48,48));volume=new SeekBar(this);volume.setMax(100);if(volumePercent>=0)volume.setProgress(volumePercent);volume.setContentDescription("Громкость Станции");volume.setProgressTintList(ColorStateList.valueOf(PURPLE));volume.setProgressBackgroundTintList(ColorStateList.valueOf(0xffeae8f2));volume.setThumbTintList(ColorStateList.valueOf(PURPLE));volume.setOnSeekBarChangeListener(new VolumeListener(this));controls.addView(volume,new LinearLayout.LayoutParams(0,dp(48),1));controls.addView(icon("plus","Громче",INK,BG,()->volumeStep(true)),box(48,48));audio.addView(controls);content.addView(audio);
    }
    private TextView sectionTitle(String title){TextView value=text(title,19,INK,true);value.setPadding(dp(2),dp(12),0,dp(15));return value;}
    private void displayPage(){displayPanel=new DisplayPanel(this,this,()->showPage("Настройки"));content.addView(displayPanel,new LinearLayout.LayoutParams(-1,-2));displayPanel.connection(agentReady);}
    @Override public boolean displayCanPoll(){return foreground&&agentReady&&pending==0&&!statusPending;}
    private void schedulePage(){
        schedulePanel=new SchedulePanel(this,this);content.addView(schedulePanel,new LinearLayout.LayoutParams(-1,-2));
        schedulePanel.connection(agentReady);if(agentReady&&lastStatus!=null)schedulePanel.status(lastStatus);refresh();
    }
    private void musicPage(){
        musicPanel=new MusicPanel(this,this);
        content.addView(musicPanel,new LinearLayout.LayoutParams(-1,-2));musicPanel.connection(agentReady);
        if(agentReady&&lastStatus!=null)musicPanel.status(lastStatus);refresh();
    }
    @Override public void request(String command,BluetoothRemoteClient.Callback callback){
        if(!foreground||agent==null||!agentReady){callback.done(null,"Нет связи со Станцией по Bluetooth");return;}
        if(pending>=2){callback.done(null,"Дождись предыдущего действия");return;}
        pending++;agent.request(command,(json,error)->{
            pending=Math.max(0,pending-1);
            if(error!=null){agentReady=agent!=null&&agent.isConnected();updateConnection();updateAvailability();}
            callback.done(json,error);
        });
    }
    private void remotePage(){
        LinearLayout title=row();title.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));TextView clocks=pill("Приложения",()->showPage("Приложения"));LinearLayout.LayoutParams clockParams=new LinearLayout.LayoutParams(-2,-2);clockParams.setMargins(0,0,dp(8),0);title.addView(clocks,clockParams);title.addView(pill("Алиса",this::openAlice));content.addView(title,new LinearLayout.LayoutParams(-1,dp(48)));
        FrameLayout pad=new FrameLayout(this);pad.addView(new RemotePadView(this),new FrameLayout.LayoutParams(-1,-1));
        pad.addView(direction("up","Вверх"),padPosition(80,70,Gravity.TOP|Gravity.CENTER_HORIZONTAL,0,3));pad.addView(direction("down","Вниз"),padPosition(80,70,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL,0,3));pad.addView(direction("left","Влево"),padPosition(70,80,Gravity.LEFT|Gravity.CENTER_VERTICAL,3,0));pad.addView(direction("right","Вправо"),padPosition(70,80,Gravity.RIGHT|Gravity.CENTER_VERTICAL,3,0));
        TextView ok=text("OK",22,0xffffffff,true);ok.setGravity(Gravity.CENTER);ok.setContentDescription("Выбрать");ok.setBackground(ripple(PURPLE,100));ok.setElevation(dp(4));interactive(ok,()->key("ok"));pad.addView(ok,padPosition(88,88,Gravity.CENTER,0,0));
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(dp(248),dp(248));pp.gravity=Gravity.CENTER_HORIZONTAL;pp.topMargin=dp(8);pp.bottomMargin=dp(8);content.addView(pad,pp);
        LinearLayout nav=row();labelled(nav,"back","Назад",()->key("back"));labelled(nav,"home","Домой",()->key("home"));labelled(nav,"menu","Меню",()->key("menu"));content.addView(nav);
        LinearLayout audio=card();LinearLayout audioHead=row();audioHead.addView(text("Громкость",14,INK,true),new LinearLayout.LayoutParams(0,-2,1));volumeText=text(volumePercent<0?"—":volumePercent+"%",13,PURPLE,true);audioHead.addView(volumeText);audio.addView(audioHead);
        LinearLayout volumeRow=row();volumeRow.addView(icon("minus","Тише",INK,BG,()->volumeStep(false)),box(42,42));volume=new SeekBar(this);volume.setMax(100);if(volumePercent>=0)volume.setProgress(volumePercent);volume.setContentDescription("Громкость Станции");volume.setProgressTintList(ColorStateList.valueOf(PURPLE));volume.setProgressBackgroundTintList(ColorStateList.valueOf(0xffeae8f2));volume.setThumbTintList(ColorStateList.valueOf(PURPLE));volume.setOnSeekBarChangeListener(new VolumeListener(this));volumeRow.addView(volume,new LinearLayout.LayoutParams(0,dp(44),1));volumeRow.addView(icon("plus","Громче",INK,BG,()->volumeStep(true)),box(42,42));audio.addView(volumeRow);content.addView(audio);
        LinearLayout media=row();media.addView(icon("mute","Включить или выключить звук",INK,0xffffffff,this::toggleMute),box(48,48));LinearLayout transport=row();transport.setGravity(Gravity.CENTER);transport.addView(icon("previous","Предыдущий трек",INK,0,()->key("previous")),box(48,48));transport.addView(icon("playpause","Пауза или воспроизведение",PURPLE,LILAC,()->key("play_pause")),box(56,52));transport.addView(icon("next","Следующий трек",INK,0,()->key("next")),box(48,48));media.addView(transport,new LinearLayout.LayoutParams(0,-2,1));media.addView(icon("apps","Открыть приложения",INK,0xffffffff,()->showPage("Приложения")),box(48,48));content.addView(media);
        feedback=text("",11,SUB,false);feedback.setGravity(Gravity.CENTER);feedback.setPadding(0,dp(10),0,0);feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(feedback);updateAvailability();
    }
    private void appsPage(){backHeader("ТВ-приложения","");
        if(apps.length()==0){LinearLayout empty=card();empty.addView(text(agentReady?"Загружаю приложения…":"Подключи Станцию",17,INK,true));empty.addView(pill("Подключиться",this::connect));content.addView(empty);loadApps();return;}
        LinearLayout line=null;for(int i=0;i<apps.length();i++){JSONObject app=apps.optJSONObject(i);if(app==null)continue;if(i%2==0){line=row();line.setGravity(Gravity.TOP);content.addView(line);}LinearLayout tile=column();tile.setPadding(dp(16),dp(18),dp(12),dp(16));tile.setBackground(ripple(0xffffffff,22));String pkg=app.optString("package"),label=app.optString("label",pkg);RemoteIconView symbol=new RemoteIconView(this,appIcon(pkg),PURPLE);tile.addView(symbol,box(38,38));TextView name=text(label,15,INK,true);name.setPadding(0,dp(14),0,0);name.setMaxLines(2);tile.addView(name);tile.setContentDescription("Открыть "+label);interactive(tile,()->launch(app));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(112),1);p.setMargins(0,0,dp(i%2==0?10:0),dp(10));line.addView(tile,p);}if(apps.length()%2==1&&line!=null)line.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));}
    private void settingsPage(){
        EditText search=new EditText(this);search.setSingleLine(true);search.setTextSize(14);search.setTextColor(INK);search.setHintTextColor(SUB);search.setHint("Найти настройку");search.setContentDescription("Поиск настроек Станции");search.setPadding(dp(16),dp(12),dp(16),dp(12));search.setBackground(shape(0xffeae8f1,17));content.addView(search,new LinearLayout.LayoutParams(-1,dp(50)));
        ArrayList<LinearLayout> groups=new ArrayList<>();
        LinearLayout device=settingsGroup("Устройство",groups);
        settingsRow(device,"screen","Экран колонки","Размер часов, яркость и анимации","экран дисплей часы яркость анимации музыка",()->showPage("Экран колонки"));
        settingsRow(device,"ring","Подсветка","Кольцо и штатная индикация","подсветка кольцо цвет свет",()->showPage("Подсветка"));
        settingsRow(device,"clock","Таймеры и будильники","Хранятся на самой Станции","часы таймер будильник сигнал",()->showPage("Часы"));
        settingsRow(device,"apps","ТВ-приложения","Список приложений на Станции","приложения телевизор тв youtube",()->showPage("Приложения"));
        LinearLayout connection=settingsGroup("Подключение",groups);
        settingsRow(connection,"bluetooth","Bluetooth",agentReady?"Данные подключены":"Настройка и восстановление связи","bluetooth подключение связь устройство сопряжение",()->showPage("Подключение"));
        settingsRow(connection,"link","Выбрать Станцию",stationName(),"выбрать устройство станция",this::chooseStation);
        LinearLayout info=settingsGroup("О приложении",groups);
        settingsRow(info,"info","О приложении","","версия информация приложение сервер пк помощь",()->showPage("О приложении"));
        TextView empty=text("Ничего не найдено",14,SUB,false);empty.setPadding(dp(4),dp(22),dp(4),dp(12));empty.setVisibility(View.GONE);content.addView(empty);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){String query=s.toString().toLowerCase(Locale.ROOT).trim();boolean any=false;for(LinearLayout group:groups){boolean visible=false;for(int i=1;i<group.getChildCount();i++){View item=group.getChildAt(i);boolean match=query.isEmpty()||String.valueOf(item.getTag()).contains(query);item.setVisibility(match?View.VISIBLE:View.GONE);visible|=match;}group.setVisibility(visible?View.VISIBLE:View.GONE);any|=visible;}empty.setVisibility(any?View.GONE:View.VISIBLE);}public void afterTextChanged(Editable s){}});
        TextView version=text("Пульт Станции · 0.8.0",11,SUB,false);version.setGravity(Gravity.CENTER);version.setPadding(0,dp(20),0,dp(8));content.addView(version);
    }
    private LinearLayout settingsGroup(String heading,List<LinearLayout> groups){LinearLayout group=card();group.setPadding(dp(14),dp(15),dp(8),dp(6));TextView title=text(heading.toUpperCase(Locale.ROOT),10,SUB,true);title.setLetterSpacing(.06f);title.setPadding(dp(3),0,0,dp(9));group.addView(title);content.addView(group);groups.add(group);return group;}
    private void settingsRow(LinearLayout group,String symbol,String title,String detail,String keywords,Runnable action){
        LinearLayout line=row();line.setMinimumHeight(dp(57));line.setPadding(dp(3),dp(11),dp(6),dp(11));line.setBackground(ripple(0,15));RemoteIconView glyph=new RemoteIconView(this,symbol,PURPLE);glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);line.addView(glyph,box(24,24));
        TextView name=text(title,14,INK,true);name.setPadding(dp(13),0,dp(8),0);name.setMaxLines(2);line.addView(name,new LinearLayout.LayoutParams(0,-2,1));RemoteIconView arrow=new RemoteIconView(this,"right",0xffb9b3c8);arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);line.addView(arrow,box(16,20));line.setTag((title+" "+detail+" "+keywords).toLowerCase(Locale.ROOT));line.setContentDescription(title);interactive(line,action);group.addView(line,new LinearLayout.LayoutParams(-1,-2));
    }
    private void backHeader(String title,String description){content.addView(pill("‹ Настройки",()->showPage("Настройки")),new LinearLayout.LayoutParams(-2,-2));}
    private void connectionPage(){
        backHeader("Подключение","");LinearLayout state=card();state.addView(text(stationName(),20,INK,true));
        statusRow(state,"Пульт",hid!=null&&hid.isConnected()?"Подключён":"Нет связи");statusRow(state,"Данные",agentReady?"Подключены":"Нет связи");
        TextView retry=pill("Переподключить",this::retry);LinearLayout.LayoutParams retryParams=new LinearLayout.LayoutParams(-1,-2);retryParams.topMargin=dp(12);state.addView(retry,retryParams);content.addView(state);
        LinearLayout choices=card();settingsRow(choices,"link","Выбрать Станцию","","",this::chooseStation);settingsRow(choices,"bluetooth","Bluetooth телефона","","",()->startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));content.addView(choices);
        TextView rescue=text("Восстановить связь",13,PURPLE,true);rescue.setPadding(dp(4),dp(18),dp(4),dp(18));interactive(rescue,()->{AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Восстановление связи").setMessage("Остановить VPN Станции?").setPositiveButton("Остановить",(d,w)->send("STOP_VPN","Запрос отправлен")).setNegativeButton("Отмена",null).create();dialog.show();PhoneUi.styleDialog(dialog);});content.addView(rescue);
    }
    private void statusRow(LinearLayout parent,String title,String value){LinearLayout line=row();line.setPadding(0,dp(13),0,dp(7));line.addView(text(title,14,SUB,false),new LinearLayout.LayoutParams(0,-2,1));line.addView(text(value,14,INK,true));parent.addView(line);}
    private void aboutPage(){
        backHeader("О приложении","");LinearLayout app=card();RemoteIconView mark=new RemoteIconView(this,"brand",PURPLE);LinearLayout.LayoutParams logo=box(54,54);logo.gravity=Gravity.CENTER_HORIZONTAL;app.addView(mark,logo);TextView name=text("Пульт Станции",20,INK,true);name.setGravity(Gravity.CENTER);name.setPadding(0,dp(12),0,dp(7));app.addView(name);TextView version=text("0.8.0",13,SUB,false);version.setGravity(Gravity.CENTER);app.addView(version);content.addView(app);
        LinearLayout info=card();statusRow(info,"Управление","Bluetooth");statusRow(info,"Обработка","На Станции");statusRow(info,"Ответы и голос","Облачные API");statusRow(info,"Сетевой туннель","ПК");content.addView(info);
    }
    private void lightingPage(){
        backHeader("Кольцо","");LinearLayout hero=column();hero.setPadding(dp(18),dp(3),dp(18),dp(18));GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xffeae3ff,0xfff5f1ff});gradient.setCornerRadius(dp(25));hero.setBackground(gradient);hero.addView(new LightRingView(this),new LinearLayout.LayoutParams(-1,dp(110)));
        TextView automatic=text("Автоматическая индикация",17,INK,true);automatic.setGravity(Gravity.CENTER);hero.addView(automatic);TextView limitation=text("Ручное управление недоступно",12,SUB,false);limitation.setGravity(Gravity.CENTER);limitation.setPadding(0,dp(8),0,0);hero.addView(limitation);LinearLayout.LayoutParams heroParams=new LinearLayout.LayoutParams(-1,-2);heroParams.topMargin=dp(13);content.addView(hero,heroParams);
        LinearLayout legend=card();lightLegend(legend,0xff9470f0,"Алиса","Слушает и отвечает");lightLegend(legend,0xff4b7ee9,"Bluetooth","Воспроизведение музыки");lightLegend(legend,0xff56a97a,"Таймер","Сигнал и уведомления");lightLegend(legend,0xffdc627b,"Ошибка","Сеть и загрузка");content.addView(legend);
        TextView screen=pill("Настроить LED-экран",()->showPage("Экран колонки"));content.addView(screen,new LinearLayout.LayoutParams(-1,-2));
    }
    private void lightLegend(LinearLayout parent,int color,String title,String description){LinearLayout line=row();line.setPadding(dp(2),dp(11),dp(2),dp(11));View dot=new View(this);dot.setBackground(shape(color,20));dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);line.addView(dot,box(12,12));TextView name=text(title,14,INK,true);name.setPadding(dp(12),0,dp(7),0);line.addView(name,new LinearLayout.LayoutParams(0,-2,1));line.addView(text(description,11,SUB,false));parent.addView(line);}
    private void tab(String label,String symbol){String selectedPage="Главная".equals(page)||"Музыка".equals(page)||"Пульт".equals(page)||"Часы".equals(page)?page:"Настройки";boolean selected=label.equals(selectedPage);LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setBackground(ripple(selected?LILAC:0,16));RemoteIconView image=new RemoteIconView(this,symbol,selected?PURPLE:SUB);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);LinearLayout.LayoutParams iconParams=box(24,24);iconParams.gravity=Gravity.CENTER_HORIZONTAL;item.addView(image,iconParams);TextView tabLabel=text(label,9,selected?PURPLE:SUB,selected);tabLabel.setGravity(Gravity.CENTER);tabLabel.setSingleLine(true);tabLabel.setPadding(0,dp(4),0,0);item.addView(tabLabel,new LinearLayout.LayoutParams(-1,-2));item.setContentDescription(label);item.setSelected(selected);interactive(item,()->showPage(label));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(dp(1),0,dp(1),0);tabs.addView(item,p);}

    private void connectionPrompt(String value){connectionTitle.setText(value);screenStatus.setText(value);screenStatus.setTextColor(SUB);}
    private boolean ensureBluetooth(){BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();if(adapter==null){connectionPrompt("Bluetooth недоступен");return false;}if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(PERMISSION)!=PackageManager.PERMISSION_GRANTED){connectionPrompt("Разреши подключение Bluetooth");requestPermissions(new String[]{PERMISSION},41);return false;}if(!adapter.isEnabled()){connectionPrompt("Включи Bluetooth");startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),42);return false;}return true;}
    private void chooseStation(){if(!ensureBluetooth())return;List<BluetoothDevice> devices=new ArrayList<>();List<String> labels=new ArrayList<>();try{for(BluetoothDevice device:BluetoothAdapter.getDefaultAdapter().getBondedDevices()){devices.add(device);String name=device.getName();labels.add(name==null||name.isEmpty()?"Bluetooth-устройство":name);}}catch(SecurityException error){return;}AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("Станция рядом");if(devices.isEmpty())dialog.setMessage("Нет сопряжённых устройств");else dialog.setItems(labels.toArray(new String[0]),(d,w)->selectStation(devices.get(w)));dialog.setNeutralButton("Сопрячь устройство",(d,w)->startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));dialog.setNegativeButton("Отмена",null);AlertDialog picker=dialog.create();picker.show();PhoneUi.styleDialog(picker);}
    private void selectStation(BluetoothDevice selected){
        boolean changed=station==null||!selected.getAddress().equals(station.getAddress());
        station=selected;prefs.edit().putString("bluetooth_station",station.getAddress()).apply();
        if(changed){apps=new JSONArray();volumePercent=-1;muted=false;}
        retry();if(changed)showPage(page);
    }
    private void retry(){disconnect();handler.postDelayed(()->{if(foreground)connect();},300);}
    private void connect(){if(!foreground||connecting||!ensureBluetooth())return;
        if(station==null){String saved=prefs.getString("bluetooth_station","");try{for(BluetoothDevice device:BluetoothAdapter.getDefaultAdapter().getBondedDevices())if(device.getAddress().equals(saved)){station=device;break;}if(station==null){BluetoothDevice candidate=null;int count=0;for(BluetoothDevice device:BluetoothAdapter.getDefaultAdapter().getBondedDevices()){String name=device.getName(),lower=name==null?"":name.toLowerCase(Locale.ROOT);if(lower.contains("yandex")||lower.contains("станци")||lower.contains("station")){candidate=device;count++;}}if(count==1){station=candidate;prefs.edit().putString("bluetooth_station",station.getAddress()).apply();}}}catch(SecurityException ignored){}}
        if(station==null){connectionPrompt("Выбери Станцию");connectionDetail.setText("Нажми значок подключения справа вверху");return;}
        connectionTitle.setText(stationName());connectionDetail.setText("Подключаю Bluetooth…");connecting=true;updateConnection();
        if(hid==null){hid=new HidRemoteClient(this,(state,connected)->{hidState=state;updateConnection();updateAvailability();});hid.start(station);}
        if(agent==null)agent=new BluetoothRemoteClient(this);agent.selectStation(station.getAddress());agent.connect((value,error)->{connecting=false;agentReady=error==null;updateConnection();updateAvailability();if(agentReady){refresh();loadApps();}});
    }
    @Override public void refresh(){if(!foreground||agent==null||statusPending||pending>0)return;statusPending=true;agent.request("STATUS",(json,error)->{statusPending=false;agentReady=error==null;if(json!=null&&error==null){lastStatus=json;if(json.has("volume")){volumePercent=json.optInt("volume",-1);muted=json.optBoolean("muted",volumePercent==0);if(!dragging&&volume!=null&&volumePercent>=0)volume.setProgress(volumePercent);if(!dragging&&volumeText!=null)volumeText.setText(volumePercent<0?"—":volumePercent+"%");}}updateConnection();updateAvailability();if(musicPanel!=null&&agentReady)musicPanel.status(json);if(schedulePanel!=null&&agentReady)schedulePanel.status(json);});}
    private void updateConnection(){if(!foreground)return;boolean keys=hid!=null&&hid.isConnected();connectionTitle.setText(stationName());connectionTitle.setTextColor(keys&&agentReady?0xff287e63:INK);if(keys&&agentReady)connectionDetail.setText("Bluetooth · подключена");else if(keys)connectionDetail.setText("Пульт готов · данные ожидают");else if(agentReady)connectionDetail.setText("Данные готовы · пульт ожидает");else connectionDetail.setText(hidState);screenStatus.setText(keys&&agentReady?"Bluetooth · подключена":connecting?"Подключение…":keys||agentReady?"Частичное подключение":"Нет связи");screenStatus.setTextColor(keys&&agentReady?0xff287e63:SUB);updateHome();}
    private void updateAvailability(){if(volume!=null)volume.setEnabled(agentReady&&volumePercent>=0);if(musicPanel!=null)musicPanel.connection(agentReady);if(schedulePanel!=null)schedulePanel.connection(agentReady);if(displayPanel!=null)displayPanel.connection(agentReady);if(scheduleBanner!=null){JSONObject runtime=lastStatus==null?null:lastStatus.optJSONObject("scheduleRuntime");scheduleBanner.setVisibility(agentReady&&runtime!=null&&runtime.optInt("ringing",0)>0?View.VISIBLE:View.GONE);}if(feedback!=null&&"Пульт".equals(page)){if(hid==null||!hid.isConnected())feedback.setText("Подключи Bluetooth для стрелок и кнопок");else if("Подключи Bluetooth для стрелок и кнопок".contentEquals(feedback.getText()))feedback.setText("");}updateHome();}
    private void loadApps(){if(agent==null||!agentReady)return;agent.request("APPS",(json,error)->{if(error!=null||json==null)return;JSONArray list=json.optJSONArray("apps");if(list!=null){apps=list;if("Приложения".equals(page)&&apps.length()>0)showPage("Приложения");}});}
    private void key(String code){if(hid!=null&&hid.isConnected()&&hid.press(code)){message("");return;}if("home".equals(code)&&agentReady){send("HOME","Главный экран");return;}message(hid!=null&&hid.isConnected()?"Дождись предыдущего нажатия":"Подключи Станцию по Bluetooth");}
    private void send(String command,String success){if(agent==null||!agentReady){message("Нет связи со Station Agent по Bluetooth");return;}if(pending>=2)return;pending++;agent.request(command,(json,error)->{pending=Math.max(0,pending-1);if(error!=null){message(error);agentReady=agent!=null&&agent.isConnected();updateConnection();}else{message(success);handler.postDelayed(this::refresh,350);}});}
    private void openAlice(){if(apps.length()==0){loadApps();message("Для запуска Алисы нужен Agent по Bluetooth");return;}for(int i=0;i<apps.length();i++){JSONObject app=apps.optJSONObject(i);if(app!=null&&"ru.yandex.quasar.app".equals(app.optString("package"))){launch(app);return;}}message("Алиса не найдена в списке приложений");}
    private void launch(JSONObject app){send("LAUNCH "+app.optString("package")+" "+app.optString("activity"),"Открыто: "+app.optString("label"));}
    private void toggleMute(){if(agentReady)send("KEY MUTE","");else message("Подключение громкости пока недоступно");}
    private void volumeStep(boolean up){if(agentReady)send("KEY "+(up?"volume_up":"volume_down"),"");else message("Подключение громкости пока недоступно");}
    void volumeDragging(boolean value){dragging=value;}void volumePreview(int value){if(volumeText!=null)volumeText.setText(value+"%");}void volumeReleased(int value){dragging=false;send("VOLUME "+value,"");}
    private void message(String value){if(feedback!=null)feedback.setText(value==null?"":value);else if(value!=null&&!value.isEmpty())Toast.makeText(this,value,Toast.LENGTH_SHORT).show();}
    private View direction(String code,String description){RemoteIconView button=icon(code,description,INK,0,()->key(code));button.setOnTouchListener((v,event)->{int action=event.getActionMasked();if(action==MotionEvent.ACTION_DOWN){stopRepeat();v.setPressed(true);v.animate().scaleX(.92f).scaleY(.92f).setDuration(90).start();v.performClick();repeat=()->{if(repeat==null)return;key(code);handler.postDelayed(repeat,210);};handler.postDelayed(repeat,420);v.getParent().requestDisallowInterceptTouchEvent(true);return true;}if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){stopRepeat();v.setPressed(false);v.animate().scaleX(1).scaleY(1).setDuration(130).start();v.getParent().requestDisallowInterceptTouchEvent(false);return true;}return true;});return button;}
    private void stopRepeat(){if(repeat!=null)handler.removeCallbacks(repeat);repeat=null;}
    private void disconnect(){connecting=false;statusPending=false;agentReady=false;pending=0;lastStatus=null;volumePercent=-1;muted=false;if(musicPanel!=null)musicPanel.connection(false);if(schedulePanel!=null)schedulePanel.connection(false);if(displayPanel!=null)displayPanel.connection(false);if(scheduleBanner!=null)scheduleBanner.setVisibility(View.GONE);if(hid!=null){hid.close();hid=null;}if(agent!=null){agent.close();agent=null;}if(volume!=null)volume.setEnabled(false);if(volumeText!=null)volumeText.setText("—");updateHome();}
    @Override protected void onResume(){super.onResume();foreground=true;connect();handler.removeCallbacks(poll);handler.postDelayed(poll,3500);handler.removeCallbacks(musicTick);if(musicPanel!=null||schedulePanel!=null||displayPanel!=null)handler.post(musicTick);}
    @Override protected void onPause(){foreground=false;stopRepeat();handler.removeCallbacks(poll);handler.removeCallbacks(musicTick);disconnect();super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);disconnect();super.onDestroy();}
    @Override public void onBackPressed(){if("Экран колонки".equals(page)||"Подсветка".equals(page)||"Приложения".equals(page)||"Подключение".equals(page)||"О приложении".equals(page)){showPage("Настройки");return;}if(!"Главная".equals(page)){showPage("Главная");return;}super.onBackPressed();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==41&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)connect();else{connectionPrompt("Нужен доступ к Bluetooth");connectionDetail.setText("Выдать доступ можно в настройках приложения");}}
    @Override public boolean onKeyDown(int code,KeyEvent event){if(code==KeyEvent.KEYCODE_VOLUME_UP||code==KeyEvent.KEYCODE_VOLUME_DOWN){if(event.getRepeatCount()==0||pending==0)volumeStep(code==KeyEvent.KEYCODE_VOLUME_UP);return true;}return super.onKeyDown(code,event);}
    @Override public boolean onKeyUp(int code,KeyEvent event){return code==KeyEvent.KEYCODE_VOLUME_UP||code==KeyEvent.KEYCODE_VOLUME_DOWN||super.onKeyUp(code,event);}
    private String stationName(){try{String name=station==null?null:station.getName();if(name!=null&&name.toLowerCase(Locale.ROOT).startsWith("yandex.max"))return "Станция Макс";return name==null||name.isEmpty()?"Твоя Станция":name;}catch(SecurityException ignored){return "Твоя Станция";}}
    private String appIcon(String pkg){if(pkg.contains("youtube"))return "youtube";if(pkg.contains("quasar"))return "brand";if(pkg.contains("settings"))return "settings";if(pkg.contains("video"))return "play";if(pkg.contains("media"))return "music";if(pkg.contains("home")||pkg.contains("launcher"))return "home";return "apps";}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private LinearLayout card(){LinearLayout v=column();v.setPadding(dp(16),dp(13),dp(16),dp(12));v.setBackground(shape(0xffffffff,20));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(12);p.bottomMargin=dp(10);v.setLayoutParams(p);return v;}
    private TextView text(String label,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(label);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);v.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));return v;}
    private RemoteIconView icon(String symbol,String label,int color,int background,Runnable action){RemoteIconView v=new RemoteIconView(this,symbol,color);v.setContentDescription(label);v.setBackground(ripple(background,16));interactive(v,action);return v;}
    private void interactive(View v,Runnable action){v.setFocusable(true);v.setClickable(true);v.setOnClickListener(view->{view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);action.run();});v.setOnTouchListener((view,event)->{if(event.getActionMasked()==MotionEvent.ACTION_DOWN)view.animate().scaleX(.94f).scaleY(.94f).setDuration(90).start();if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)view.animate().scaleX(1).scaleY(1).setDuration(150).start();return false;});}
    private TextView pill(String label,Runnable action){TextView v=text(label,12,PURPLE,true);v.setGravity(Gravity.CENTER);v.setPadding(dp(14),dp(10),dp(14),dp(10));v.setMinHeight(dp(48));v.setBackground(ripple(LILAC,24));interactive(v,action);return v;}
    private void labelled(LinearLayout parent,String symbol,String label,Runnable action){LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setPadding(0,dp(7),0,dp(5));item.setBackground(ripple(0,15));RemoteIconView image=new RemoteIconView(this,symbol,INK);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);LinearLayout.LayoutParams iconParams=box(27,27);iconParams.gravity=Gravity.CENTER_HORIZONTAL;item.addView(image,iconParams);TextView caption=text(label,10,SUB,false);caption.setGravity(Gravity.CENTER);caption.setPadding(0,dp(6),0,0);item.addView(caption,new LinearLayout.LayoutParams(-1,-2));item.setContentDescription(label);interactive(item,action);parent.addView(item,new LinearLayout.LayoutParams(0,dp(60),1));}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private RippleDrawable ripple(int color,int radius){return new RippleDrawable(ColorStateList.valueOf(0x227560ea),shape(color,radius),shape(0xffffffff,radius));}
    private LinearLayout.LayoutParams box(int w,int h){return new LinearLayout.LayoutParams(dp(w),dp(h));}
    private FrameLayout.LayoutParams padPosition(int w,int h,int gravity,int x,int y){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(w),dp(h),gravity);p.setMargins(dp(x),dp(y),dp(x),dp(y));return p;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
