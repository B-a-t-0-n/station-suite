package android.media;
public class MediaPlayer {
    public static int nextId=91, creations, releases, starts;
    public static MediaPlayer last;
    public boolean released;
    private final int id;
    public MediaPlayer(){id=nextId;creations++;last=this;}
    public int getAudioSessionId(){return id;}
    public void release(){if(released)throw new AssertionError("double player release");released=true;releases++;}
    public void start(){starts++;throw new AssertionError("must never start probe");}
}
