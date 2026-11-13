package local.station.agent;

import android.media.MediaPlayer;
import android.media.audiofx.Equalizer;

/** Actual Android adapter with instrumented framework fakes; never loads Android or plays sound. */
public final class AndroidAgentEqualizerHostTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    public static void main(String[] args){
        AndroidAgentEqualizer adapter=new AndroidAgentEqualizer();
        AgentEqualizer.Engine probe=adapter.probe();
        check(MediaPlayer.creations==1&&Equalizer.creations==1&&Equalizer.lastSession==91,"capabilities use disposable owned positive session");
        check(probe.bandCount()==3&&probe.minLevelMb()==-900&&probe.maxLevelMb()==900&&probe.centerMilliHz(2)==8000000,"actual engine values forwarded without assumed bands");
        check(Equalizer.gainSets==0&&Equalizer.enableSets==0&&MediaPlayer.starts==0,"probe does not change curve/enable or play audio");
        probe.release();
        check(MediaPlayer.last.released&&Equalizer.last.released&&MediaPlayer.releases==1&&Equalizer.releases==1,"probe releases effect before owned player");
        int players=MediaPlayer.creations, releases=MediaPlayer.releases;
        AgentEqualizer.Engine track=adapter.attach(117);
        check(Equalizer.lastSession==117&&MediaPlayer.creations==players,"live effect uses supplied owned track session, no replacement player");
        track.setLevelMb(1,425);track.setEnabled(true);
        check(track.levelMb(1)==425&&track.enabled(),"milliBel gain/enabled forward and readback");
        track.release();check(MediaPlayer.releases==releases&&Equalizer.last.released,"detaching track does not release independently owned player");
        for(int invalid:new int[]{0,-1}){
            int created=Equalizer.creations;
            try{adapter.attach(invalid);throw new AssertionError("invalid accepted");}catch(IllegalArgumentException expected){}
            check(Equalizer.creations==created,"invalid/global supplied session never constructs Android effect");
        }
        MediaPlayer.nextId=0;int created=Equalizer.creations;
        try{adapter.probe();throw new AssertionError("global probe accepted");}catch(IllegalArgumentException expected){}
        check(MediaPlayer.last.released&&Equalizer.creations==created,"zero-session probe closes own player and never falls back global");
        MediaPlayer.nextId=122;Equalizer.fail=true;
        try{adapter.probe();throw new AssertionError("unsupported accepted");}catch(UnsupportedOperationException expected){}
        check(MediaPlayer.last.released&&MediaPlayer.starts==0,"unsupported engine closes disposable player without playback");
        System.out.println(checks+" adapter ownership assertions passed; framework fakes only");
    }
}
