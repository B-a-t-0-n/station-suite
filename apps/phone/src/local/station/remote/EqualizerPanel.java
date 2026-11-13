package local.station.remote;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Ready player presets. Only a user choice writes; selection always follows Station ACK. */
final class EqualizerPanel extends LinearLayout {
    interface Host { void request(String command,BluetoothRemoteClient.Callback callback); }
    private static final int INK=0xff202032,SUB=0xff777384,PURPLE=0xff7560ea,LILAC=0xffeeebff;
    private final Host host;
    private final TextView badge,current,attachment,notice,reload;
    private final LinearLayout choices;
    private final List<View> buttons=new ArrayList<>();
    private EqualizerModel.Snapshot snapshot;
    private boolean connected,disposed,pending,fresh;
    private int epoch;

    EqualizerPanel(Context context,Host host,Runnable back){
        super(context);this.host=host;setOrientation(VERTICAL);
        LinearLayout navigation=row();navigation.addView(pill("‹ Назад",back));
        navigation.addView(new View(context),new LayoutParams(0,1,1));
        reload=pill("Обновить",this::load);navigation.addView(reload);addView(navigation);
        TextView title=label("Эквалайзер",27,INK,true);title.setPadding(0,dp(18),0,dp(4));addView(title);
        addView(label("Выбери настроение для музыки",13,SUB,false));

        LinearLayout hero=column();hero.setPadding(dp(18),dp(18),dp(18),dp(18));
        GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xffeee8ff,0xffe3dafa,0xfff3ecff});
        gradient.setCornerRadius(dp(23));hero.setBackground(gradient);
        LinearLayout heading=row();TextView overline=label("ТВОЙ ЗВУК",10,PURPLE,true);
        heading.addView(overline,new LayoutParams(0,-2,1));
        badge=label("Подключаемся…",10,PURPLE,true);badge.setPadding(dp(9),dp(6),dp(9),dp(6));
        badge.setBackground(shape(0x99ffffff,12));heading.addView(badge);hero.addView(heading);
        current=label("Узнаём настройку",23,INK,true);current.setPadding(0,dp(14),0,dp(7));hero.addView(current);
        attachment=label("Выбор сохранится на Станции",12,SUB,false);attachment.setLineSpacing(dp(3),1);hero.addView(attachment);
        LayoutParams heroParams=new LayoutParams(-1,-2);heroParams.topMargin=dp(17);heroParams.bottomMargin=dp(17);addView(hero,heroParams);
        TextView chooseLabel=label("Готовые режимы",17,INK,true);chooseLabel.setPadding(dp(2),0,0,dp(7));addView(chooseLabel);
        choices=column();addView(choices);
        notice=label("Подключись к Станции по Bluetooth",12,SUB,false);notice.setPadding(dp(3),dp(10),dp(3),dp(17));
        notice.setLineSpacing(dp(3),1);notice.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);addView(notice);
        TextView scope=label("Для песен нашего плеера · настройки хранятся на Станции",11,SUB,false);
        scope.setGravity(Gravity.CENTER);scope.setPadding(dp(3),dp(4),dp(3),dp(10));addView(scope);
        updateControls();
    }
    void connection(boolean value){
        if(disposed||connected==value)return;
        connected=value;epoch++;pending=false;fresh=false;
        if(value)load();else{notice.setText("Нет связи со Станцией по Bluetooth");badge.setText("Нет связи");updateControls();}
    }
    void dispose(){disposed=true;epoch++;}
    private void load(){if(!disposed&&connected&&!pending)send(EqualizerModel.get(),null);}
    private void select(String id){
        if(disposed||!connected||pending||!fresh||snapshot==null||!snapshot.canChoose())return;
        send(EqualizerModel.choose(snapshot,id),id);
    }
    private void send(String command,String chosen){
        pending=true;final int requestEpoch=epoch;
        notice.setText(chosen==null?"Получаю режимы Станции…":"Применяю режим…");updateControls();
        host.request(command,(result,error)->{
            if(disposed||requestEpoch!=epoch)return;pending=false;
            if(error!=null||result==null){fresh=false;notice.setText("Нет подтверждения от Станции. Нажми «Обновить»");updateControls();return;}
            try{
                EqualizerModel.Snapshot received=new EqualizerModel.Snapshot(result);
                if(!received.acknowledges(chosen)){
                    fresh=false;notice.setText("Станция не подтвердила выбранный режим. Нажми «Обновить»");updateControls();return;
                }
                snapshot=received;fresh=received.canChoose();render();
                notice.setText(received.notice());
            }catch(IllegalArgumentException invalid){fresh=false;notice.setText("Станция передала неполные настройки. Нажми «Обновить»");}
            updateControls();
        });
    }
    private void render(){
        buttons.clear();choices.removeAllViews();if(snapshot==null)return;
        badge.setText(snapshot.state());
        current.setText(snapshot.nativePlayerUnsupported()?"Штатный плеер":!snapshot.available?"Эквалайзер недоступен":"custom".equals(snapshot.selectedPreset)?"Своя настройка":snapshot.preset(snapshot.selectedPreset).name);
        attachment.setText(snapshot.detail());
        if(snapshot.nativePlayerUnsupported()){
            String saved="custom".equals(snapshot.selectedPreset)?"Своя настройка":snapshot.preset(snapshot.selectedPreset).name;
            TextView retained=label("Сохранено: "+saved+". Пресеты ниже доступны только нашему прежнему плееру.",12,SUB,false);
            retained.setPadding(dp(3),0,dp(3),dp(10));choices.addView(retained);
        }
        if(snapshot.available&&"custom".equals(snapshot.selectedPreset)){
            TextView retained=label("Твоя прежняя настройка сохранена. Она изменится, когда выберешь режим ниже.",12,SUB,false);
            retained.setPadding(dp(3),0,dp(3),dp(10));choices.addView(retained);
        }
        for(EqualizerModel.Preset preset:snapshot.presets)addChoice(preset);
    }
    private void addChoice(EqualizerModel.Preset preset){
        boolean selected=preset.id.equals(snapshot.selectedPreset);
        LinearLayout card=row();card.setPadding(dp(15),dp(15),dp(15),dp(15));card.setMinimumHeight(dp(82));
        GradientDrawable background=shape(selected?LILAC:0xffffffff,20);
        if(selected)background.setStroke(dp(1),0xffc7b8ff);
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(0x227560ea),background,shape(0xffffffff,20)));
        RemoteIconView icon=new RemoteIconView(getContext(),"normal".equals(preset.id)?"music":
                "vocal".equals(preset.id)?"brand":"cinema".equals(preset.id)?"play":"music",PURPLE);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout symbol=column();symbol.setGravity(Gravity.CENTER);symbol.setBackground(shape(selected?0xffffffff:0xfff4f1ff,14));
        symbol.addView(icon,new LayoutParams(dp(24),dp(24)));card.addView(symbol,new LayoutParams(dp(43),dp(43)));
        LinearLayout words=column();words.setPadding(dp(13),0,dp(10),0);
        words.addView(label(preset.name,15,selected?PURPLE:INK,true));
        TextView description=label(preset.description,12,SUB,false);description.setPadding(0,dp(5),0,0);description.setLineSpacing(dp(2),1);
        words.addView(description);card.addView(words,new LayoutParams(0,-2,1));
        TextView marker=label(selected?"✓":"",18,PURPLE,true);marker.setGravity(Gravity.CENTER);card.addView(marker,new LayoutParams(dp(24),dp(28)));
        card.setSelected(selected);card.setContentDescription(preset.name+". "+preset.description+(selected?". Сохранённый режим":""));
        card.setFocusable(true);card.setOnClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);select(preset.id);});
        card.setOnTouchListener((v,event)->{int action=event.getActionMasked();if(action==MotionEvent.ACTION_DOWN)v.animate().scaleX(.985f).scaleY(.985f).setDuration(90).start();
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)v.animate().scaleX(1).scaleY(1).setDuration(140).start();return false;});
        LayoutParams params=new LayoutParams(-1,-2);params.topMargin=dp(7);choices.addView(card,params);buttons.add(card);
    }
    private void updateControls(){
        boolean can=connected&&!disposed&&!pending&&fresh&&snapshot!=null&&snapshot.canChoose();
        enabled(reload,connected&&!disposed&&!pending);for(View card:buttons)enabled(card,can);
    }
    private void enabled(View view,boolean value){view.setEnabled(value);view.setAlpha(value?1f:.45f);}
    private TextView label(String text,int size,int color,boolean bold){TextView value=new TextView(getContext());value.setText(text);value.setTextSize(size);value.setTextColor(color);value.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));return value;}
    private LinearLayout column(){LinearLayout value=new LinearLayout(getContext());value.setOrientation(VERTICAL);return value;}
    private LinearLayout row(){LinearLayout value=new LinearLayout(getContext());value.setOrientation(HORIZONTAL);value.setGravity(Gravity.CENTER_VERTICAL);return value;}
    private TextView pill(String text,Runnable action){TextView value=label(text,12,PURPLE,true);value.setGravity(Gravity.CENTER);value.setPadding(dp(14),dp(10),dp(14),dp(10));value.setMinHeight(dp(44));value.setBackground(new RippleDrawable(ColorStateList.valueOf(0x227560ea),shape(LILAC,22),shape(0xffffffff,22)));value.setFocusable(true);value.setOnClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);action.run();});return value;}
    private GradientDrawable shape(int color,int radius){GradientDrawable value=new GradientDrawable();value.setColor(color);value.setCornerRadius(dp(radius));return value;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
