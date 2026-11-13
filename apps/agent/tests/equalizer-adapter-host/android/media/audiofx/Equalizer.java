package android.media.audiofx;
public class Equalizer {
    public static int creations, releases, lastSession=-1, gainSets, enableSets;
    public static boolean fail;
    public static Equalizer last;
    public boolean released, enabled;
    private final short[] gains=new short[3];
    public Equalizer(int priority,int session){
        if(session<=0)throw new AssertionError("global or invalid session");
        if(fail)throw new UnsupportedOperationException();
        creations++;lastSession=session;last=this;
    }
    public short getNumberOfBands(){return 3;}
    public short[] getBandLevelRange(){return new short[]{-900,900};}
    public int getCenterFreq(short band){return new int[]{125000,1000000,8000000}[band];}
    public void setBandLevel(short band,short gain){gains[band]=gain;gainSets++;}
    public short getBandLevel(short band){return gains[band];}
    public int setEnabled(boolean on){enabled=on;enableSets++;return 0;}
    public boolean getEnabled(){return enabled;}
    public boolean hasControl(){return true;}
    public void release(){if(released)throw new AssertionError("double effect release");released=true;releases++;}
}
